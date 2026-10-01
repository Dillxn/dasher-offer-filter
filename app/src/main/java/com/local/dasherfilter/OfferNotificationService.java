package com.local.dasherfilter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Classifies DoorDash offer notifications in the background. Never launches an activity itself: notification evidence
 * and screen evidence are separate authorities. An offer that cannot be judged gets a card that rings once while
 * Dasher is in the background, so it is not missed (silently when Android shows Dasher's own alert for it sounded), and
 * is otherwise left to the user; with Peek on ({@link Peek}), the screen reader is then asked to bring Dasher up for a
 * moment to read it. One offer is one history line: a notification of an offer the screen reads is folded into the
 * screen's line, and its card is cleared then; Dasher's own notification is never touched for that.
 */
public final class OfferNotificationService extends NotificationListenerService {
    /** Current eligible notification incarnations, published to the scanner without sharing the mutable tracker. */
    private final java.util.Set<String> livePeekCards = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicLong CARD_SERIAL = new java.util.concurrent.atomic.AtomicLong();

    static boolean peekCurrent(Peek.Request request) {
        OfferNotificationService service = active;
        return service != null && request != null && request.alertTag != null
                && service.livePeekCards.contains(request.alertTag);
    }

    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private static final int MAX_TRACKED_OFFERS = 16;
    private static final int MAX_NOTIFICATION_LABELS = 32;
    private static final int MAX_NOTIFICATION_LABEL_CHARS = 2048;
    private static final int MAX_LOGGED_EXTRA_KEYS = 40;
    /** A store name longer than this is not shown on our card. */
    private static final int MAX_STORE_CHARS = 40;
    private static final String[] TEXT_EXTRAS = {
            Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT};

    private static volatile OfferNotificationService active;
    private static volatile boolean offerOutstanding;
    private static volatile long generation;
    /** When Dasher's Decline action on a notification was last sent (uptime), 0 for never. */
    private static volatile long declineActionAt;

    private static final int MAX_POSTED_KEYS = 32;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Keyed by source notification key; insertion order makes the first entry the oldest. */
    private final LinkedHashMap<String, TrackedOffer> tracked = new LinkedHashMap<>();
    /**
     * Keys of Dasher's notifications Android shows now, as far as this listener has seen: a post on one of them is
     * an update to Android, which an only-alert-once notification makes silently.
     */
    private final LinkedHashSet<String> postedKeys = new LinkedHashSet<>();
    /**
     * What the screen had read, by notification key, when the listener last lost its incarnations (a reconnect):
     * a replay of that same post (same key and post time) is then known read again. Main thread only; process-wide,
     * since Android may rebind a new instance of this service.
     */
    private static final Map<String, ReadMemory> readBeforeReconnect = new LinkedHashMap<>();

    /** The screen's reading of one incarnation, kept across a listener reconnect. */
    private static final class ReadMemory {
        final long readThrough;
        final OfferSnapshot facts;
        final boolean ended;
        /** {@code SystemClock.elapsedRealtime()} when it was kept. */
        final long keptAt;

        ReadMemory(OfferAlertState state, long keptAt) {
            this.readThrough = state.readThrough;
            this.facts = state.readOnScreen;
            this.ended = state.endedOnScreen;
            this.keptAt = keptAt;
        }
    }

    /** Per-source-notification state plus the tag of the Offer Filter card that mirrors it. */
    private static final class TrackedOffer {
        final OfferAlertState state;
        final String alertTag;
        final String merchant;
        /** The store as Dasher's notification names it ("Taco Bell"), for our card; empty when it names none. */
        String store = "";
        Runnable expiry;
        /** How long after the previous post on this key the latest one came (wall clock), for the log. */
        long gapMs;
        /** Whether the latest post began this incarnation, and was not a replay: a fresh post, which Peek may read. */
        boolean freshPost;
        /** Dasher's own tap intent on its latest post (our card's fallback), and whether Android showed it sounded. */
        PendingIntent contentIntent;
        boolean dasherSounded;
        /** Its card was posted silently while a peek is tried: what the card rings with if the peek does not happen. */
        QuietCard quietCard;
        /** A peek went back with this offer passing or unclear: its card carries what was read; updates change nothing. */
        boolean readCard;
        boolean readCardLogged;

        TrackedOffer(long now, StatusBarNotification source, String merchant) {
            this.state = new OfferAlertState(now, source.getPostTime());
            this.alertTag = "offer-" + now + "-" + CARD_SERIAL.incrementAndGet() + "-" + source.getKey();
            this.merchant = merchant;
        }
    }

    /** What a card posted quietly for a peek needs to be announced as usual, ringing once, if the peek does not happen. */
    private static final class QuietCard {
        final OfferSnapshot facts;
        final OfferRule.Decision decision;
        final List<String> labels;
        final boolean addOn;
        final boolean enabled;
        final String signature;

        QuietCard(OfferSnapshot facts, OfferRule.Decision decision, List<String> labels, boolean addOn, boolean enabled,
                  String signature) {
            this.facts = facts;
            this.decision = decision;
            this.labels = labels;
            this.addOn = addOn;
            this.enabled = enabled;
            this.signature = signature;
        }
    }

    static boolean isConnected() {
        return active != null;
    }

    /** True while any DoorDash offer notification is being tracked; used to defer automatic updates. */
    static boolean hasActiveOffer() {
        return offerOutstanding;
    }

    /**
     * Incremented for each newly tracked offer, except the same store's re-post while Dasher is on screen (the offer
     * on screen); revokes older screen decline-confirmation authority.
     */
    static long generation() {
        return generation;
    }

    private long staleNotices;
    private long staleLoggedAt;
    private static volatile long backgroundGeneration;
    private static volatile Notice lastNotice;
    private static final class Notice {
        final List<String> labels;
        final long at;
        final long generation;
        final boolean foreground;
        Notice(List<String> labels, long generation, boolean foreground) {
            this.labels = new ArrayList<>(labels);
            this.at = SystemClock.uptimeMillis();
            this.generation = generation;
            this.foreground = foreground;
        }
    }
    static long freshBackgroundGeneration() { return backgroundGeneration; }
    static long generationForRead(long startedGeneration, OfferSnapshot facts, List<String> labels, long began) {
        Notice notice = lastNotice;
        long current = generation();
        return notice != null && notice.foreground && notice.generation == current && current == startedGeneration + 1
                && notice.at >= began && OfferFilterService.sameForegroundNotice(notice.labels, facts, labels, 0)
                ? current : startedGeneration;
    }

    private static void nextGeneration() {
        generation++;
    }

    /**
     * Whether a Decline from a notification was requested within {@code ms}: Dasher's decline question then is not
     * the user's own.
     */
    static boolean declineActionWithin(long ms) {
        long at = declineActionAt;
        long now = SystemClock.uptimeMillis();
        return at > 0 && now >= at && now - at <= ms;
    }

    /** For tests: no Decline action was sent (the uptime clock starts again with each test). */
    static void forgetDeclineAction() {
        declineActionAt = 0;
    }

    /**
     * Sends Dasher's own Decline action, marked as requested first: the screen reader (on its own thread) may read
     * Dasher reacting to it before {@code send()} even returns, and must never take that for the user's own decline,
     * or the offer for one the user accepted. A send that fails stays marked, which only means nothing is learned for
     * a minute.
     */
    static void sendDecline(PendingIntent decline) throws PendingIntent.CanceledException {
        declineActionAt = SystemClock.uptimeMillis();
        decline.send();
    }

    static boolean hasAccess(Context context) {
        ComponentName component = new ComponentName(context, OfferNotificationService.class);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            return manager != null && manager.isNotificationListenerAccessGranted(component);
        }
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        for (String value : enabled.split(":")) {
            if (component.equals(ComponentName.unflattenFromString(value))) return true;
        }
        return false;
    }

    /** Re-evaluates current offer notifications against newly saved rules, without ringing or acting. */
    static void rulesChanged() {
        OfferNotificationService service = active;
        if (service != null) service.handler.post(service::reconcile);
    }

    /**
     * The screen read the offer this notification incarnation announced: its card is cleared (never Dasher's own
     * notification), and later updates of it are judged knowing the screen has it.
     */
    static void readOnScreen(String alertTag, OfferSnapshot facts) {
        OfferNotificationService service = active;
        if (service == null || alertTag == null) return;
        onMain(service, () -> {
            for (TrackedOffer offer : service.tracked.values()) {
                if (!alertTag.equals(offer.alertTag)) continue;
                offer.state.markRead(facts);
                service.livePeekCards.remove(alertTag);
                OfferAlerts.clear(service, alertTag);
                DiagnosticLog.log(service, "alert", "card cleared: the screen read this offer");
            }
        });
    }

    /**
     * The screen showed Dasher idle, a delivery or the dash over: every offer it had read is gone, so a re-post on
     * one of their keys is a new offer.
     */
    static void screenOfferEnded() {
        OfferNotificationService service = active;
        if (service == null) return;
        onMain(service, () -> {
            for (TrackedOffer offer : service.tracked.values()) {
                if (offer.state.readOnScreen != null) offer.state.endedOnScreen = true;
            }
        });
    }

    private static void onMain(OfferNotificationService service, Runnable work) {
        if (Looper.myLooper() == Looper.getMainLooper()) work.run();
        else service.handler.post(work);
    }

    @Override public void onListenerConnected() {
        active = this;
        OfferAlerts.ensureChannel(this);
        OfferAlerts.clearAll(this);
        Updater.schedule(this);
        DiagnosticLog.log(this, "notification", "listener connected; replay is noninterrupting");
        reconcile();
        Updater.check(this, UpdateCadence.Trigger.CONNECTED, null);
    }

    @Override public void onListenerDisconnected() {
        clearTracked();
        if (active == this) active = null;
        DiagnosticLog.log(this, "notification", "listener disconnected; delivery monitoring unavailable");
        try {
            requestRebind(new ComponentName(this, OfferNotificationService.class));
        } catch (RuntimeException error) {
            DiagnosticLog.log(this, "notification", "rebind request failed");
        }
    }

    @Override public void onDestroy() {
        clearTracked();
        handler.removeCallbacksAndMessages(null);
        if (active == this) active = null;
        super.onDestroy();
    }

    @Override public void onNotificationPosted(StatusBarNotification source, RankingMap ranking) {
        handle(source, ranking, false);
    }

    @Override public void onNotificationPosted(StatusBarNotification source) {
        handle(source, getCurrentRanking(), false);
    }

    @Override public void onNotificationRemoved(StatusBarNotification source) {
        if (!isFromOwnUsersDasher(source)) return;
        postedKeys.remove(source.getKey());
        TrackedOffer offer = tracked.get(source.getKey());
        if (offer != null && offer.state.removalMatches(source.getPostTime())) remove(source.getKey(), offer);
    }

    /** Replays every active notification; replay never rings, declines, or hides anything. */
    private void reconcile() {
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all == null) return;
            for (StatusBarNotification source : all) handle(source, getCurrentRanking(), true);
        } catch (RuntimeException error) {
            DiagnosticLog.log(this, "notification", "reconcile failed: " + error.getClass().getSimpleName());
        }
    }

    /**
     * @param replay true for reconnect and rule-change re-evaluation: nothing rings, and no decline action is sent
     *               or DoorDash notification hidden on a replay
     */
    private void handle(StatusBarNotification source, RankingMap ranking, boolean replay) {
        if (!isFromOwnUsersDasher(source)) return;
        Notification notification = source.getNotification();
        if (notification == null || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            invalidatePeekForKey(source.getKey());
            return;
        }
        // Until the first-run notice is accepted, nothing is read, posted, hidden or declined; accepting replays what
        // is still shown. The one exception: a fresh post on Dasher's offer channel (its channel alone, never its
        // words) posts the reminder that the app is paused, once per notice version.
        if (!Consent.accepted(this)) {
            invalidatePeekForKey(source.getKey());
            if (!replay && ConsentReminder.onOfferChannel(this, notification.getChannelId())) {
                ConsentReminder.postIfPaused(this, "Dasher's notification");
                // Peek waits for the notice too: said from the channel alone, as the reminder is.
                if (FilterStore.peek(this)) Peek.skipped(this, "the notice isn't accepted yet");
            }
            return;
        }
        // To Android, a post on a key it still shows is an update.
        boolean update = !postedKeys.add(source.getKey());
        while (postedKeys.size() > MAX_POSTED_KEYS) postedKeys.remove(postedKeys.iterator().next());
        if (!OfferEvidence.fresh(source.getPostTime(), System.currentTimeMillis(), OfferAlertState.LIFETIME_MS)) {
            invalidatePeekForKey(source.getKey());
            staleNotices++;
            long now = SystemClock.elapsedRealtime();
            if (staleNotices == 1 || now - staleLoggedAt >= 60_000) {
                DiagnosticLog.log(this, "notification", "ignored stale/future notification (" + staleNotices + " since last valid post)");
                staleLoggedAt = now;
            }
            return;
        }
        if (staleNotices > 1) DiagnosticLog.log(this, "notification", "ignored " + staleNotices + " stale/future notifications");
        staleNotices = 0;
        try {
            List<String> labels = labels(notification);
            if (!NotificationOffer.isLikelyOffer(labels)) {
                invalidatePeekForKey(source.getKey());
                return;
            }
            boolean foreground = OfferFilterService.isDasherOnScreenNow();
            TrackedOffer offer = track(source, merchant(labels), labels.toString(), labels, foreground, replay);
            offer.store = store(labels);
            FilterStore.recordDoorDashOfferChannel(this, notification.getChannelId());
            if (offer.readCard && !replay) {
                livePeekCards.remove(offer.alertTag);
                // A peek read this offer and went back with it on its card: Dasher's updates of it change nothing.
                if (!offer.readCardLogged) {
                    offer.readCardLogged = true;
                    DiagnosticLog.log(this, "notification", "update of an offer Peek put on its card; no change");
                }
                return;
            }

            // A notification's text may be cut short: its pay is never bounded, so nothing is declined or hidden on a
            // bound.
            OfferSnapshot facts = OfferParser.parse(metricLabels(labels)).withoutPayBound();
            FilterSettings settings = FilterStore.load(this);
            OfferRule.Decision decision = decide(facts, labels, settings);
            String signature = labels + "|" + decision.summary();
            // A same-key update with sufficient figures is already judged. It revokes the earlier payless
            // request while that request is still waiting for quiet. Launched peeks no longer use this token.
            if (decision.result != OfferRule.Result.REVIEW || !settings.enabled || foreground || replay) {
                invalidatePeekForKey(source.getKey());
            }
            if (offer.state.duplicate(signature, decision.result)) {
                if (!replay && !offer.state.repostLogged) {
                    offer.state.repostLogged = true;
                    DiagnosticLog.log(this, "notification", "re-posted unchanged " + offer.gapMs / 1000
                            + " s after its last post; the same offer, no new card");
                }
                return;
            }
            DiagnosticLog.log(this, "notification", "parsed " + facts.summary() + "; " + decision.summary());
            Ranking rank = ranking(ranking, source.getKey());
            boolean dasherSounded = dasherAlertSounded(rank, source, update);
            logChannel(rank == null ? null : rank.getChannel(), notification, dasherSounded);
            // Settings asks for Dasher's offer channel to be set to Silent while it is seen to alert.
            FilterStore.recordDoorDashChannel(this, rank == null ? null : rank.getChannel());
            boolean addOn = AddOnOffer.isLikely(labels);

            if (offer.state.coveredByScreen(decision.result, decision.basis, foreground, replay,
                    source.getPostTime())) {
                livePeekCards.remove(offer.alertTag);
                offer.state.settle(signature, decision.result, offer.state.readOnScreen);
                OfferAlerts.clear(this, offer.alertTag);
                DiagnosticLog.log(this, "notification", "update of an offer already read on screen; no card");
                Dashing.seen(this);
                if (foreground) OfferFilterService.requestCheckFromNotification();
                return;
            }
            if (!replay && foreground && !offer.state.displayed && decision.result != OfferRule.Result.DECLINE) {
                // Dasher's notification of the offer the screen read moments ago: one offer, so no card or line.
                DecisionLog.Entry screen = DecisionLog.foldIntoScreen(this, DecisionLog.Entry.of(
                        DecisionLog.Source.NOTIFICATION, addOn, decision.basis, decision,
                        DecisionLog.Action.SEEN_ON_SCREEN, settings.enabled, labels));
                if (screen != null) {
                    livePeekCards.remove(offer.alertTag);
                    offer.state.settle(signature, decision.result, screen.facts);
                    OfferAlerts.clear(this, offer.alertTag);
                    DiagnosticLog.log(this, "notification", "same offer the screen read "
                            + Math.round((screen.notification.at - screen.at) / 1000.0) + " s earlier; no card");
                    Dashing.seen(this);
                    OfferFilterService.requestCheckFromNotification();
                    return;
                }
            }

            offer.contentIntent = notification.contentIntent;
            offer.dasherSounded = dasherSounded;
            // Peek is decided before the card: the notification cannot judge this offer (it names the store, not the
            // pay), and when a peek will be tried the card is posted silently (no ring, so no sound given back over
            // the decline to come); it rings once only if the peek does not happen.
            Peek.Request peek = null;
            if (decision.result == OfferRule.Result.REVIEW) {
                Peek.Request request = new Peek.Request(source.getKey(), source.getPostTime(), offer.store, replay,
                        offer.freshPost, offer.alertTag);
                long readAgo = replay || foreground ? -1 : DecisionLog.screenReadAgo(this, DecisionLog.Entry.of(
                        DecisionLog.Source.NOTIFICATION, addOn, decision.basis, decision,
                        DecisionLog.Action.CHECK_BELL, settings.enabled, labels));
                String why = OfferFilterService.peekRefusal(this, request, settings, foreground, readAgo);
                if (why == null) {
                    peek = request;
                } else {
                    invalidatePeekForKey(source.getKey());
                    Peek.skipped(this, why);
                }
            }

            DecisionLog.Action action;
            if (peek != null) {
                offer.quietCard = new QuietCard(facts, decision, labels, addOn, settings.enabled, signature);
                action = announce(notification.contentIntent, offer, facts, decision, signature, replay, foreground,
                        dasherSounded, true);
            } else if (decision.result != OfferRule.Result.DECLINE) {
                action = announce(notification.contentIntent, offer, facts, decision, signature, replay, foreground,
                        dasherSounded, false);
            } else if (OfferFilterService.userHasOffer(facts)) {
                action = leaveToUser(offer, decision, signature);
            } else {
                action = filter(source, notification, offer, decision, signature, replay);
            }
            Dashing.seen(this);
            DashDiagnostics.offerSeen(this);
            DecisionLog.record(this, DecisionLog.Entry.of(DecisionLog.Source.NOTIFICATION,
                    addOn, decision.basis, decision, action, settings.enabled, labels)
                    .withAlertTag(offer.alertTag, replay));
            if (peek != null) {
                livePeekCards.add(peek.alertTag);
                OfferFilterService.peekAt(this, peek);
            }
        } catch (RuntimeException error) {
            invalidatePeekForKey(source.getKey());
            DiagnosticLog.log(this, "notification",
                    "payload/handler rejected; original retained: " + error.getClass().getSimpleName());
            // An oversized notification is refused on purpose; only real failures are worth a report.
            if (!(error instanceof OversizedNotification)) {
                ReportOutbox.fileAutomatic(this, ProblemReport.Kind.NOTIFICATION_ERROR, null, null, error);
            }
        }
    }

    /** Main thread: a replacement no longer eligible for Peek revokes the earlier quiet-wait request. */
    private void invalidatePeekForKey(String key) {
        TrackedOffer offer = tracked.get(key);
        if (offer != null) {
            livePeekCards.remove(offer.alertTag);
            offer.quietCard = null;
        }
    }

    private OfferRule.Decision decide(OfferSnapshot facts, List<String> labels, FilterSettings settings) {
        if (!settings.enabled) {
            return new OfferRule.Decision(OfferRule.Result.REVIEW, 0,
                    "auto-decline is off; inspect this offer manually", facts);
        }
        if (AddOnOffer.isLikely(labels)) {
            return OfferRule.evaluateAddOn(AddOnOffer.parse(ActiveRouteStore.load(this), labels), settings);
        }
        return OfferRule.evaluate(facts, settings);
    }

    /**
     * Returns the state for this notification incarnation, starting a new one when the key is new, its previous
     * incarnation expired, the merchant changed, or (never on a replay) the same store's re-post is a new offer
     * ({@link OfferAlertState#newOfferReason}).
     */
    private TrackedOffer track(StatusBarNotification source, String merchant, String text, List<String> labels, boolean foreground,
                               boolean replay) {
        long now = SystemClock.elapsedRealtime();
        // The Handler expiry runs on uptime, which stops in deep sleep; elapsed time is authoritative.
        removeExpired(now);
        String key = source.getKey();
        TrackedOffer offer = tracked.get(key);
        if (offer != null && !merchant.isEmpty() && !offer.merchant.isEmpty() && !merchant.equals(offer.merchant)) {
            remove(key, offer);
            offer = null;
        }
        // A replay re-evaluates a post already handled: never a new offer.
        String newOffer = offer == null || replay
                ? null : offer.state.newOfferReason(text, source.getPostTime(), foreground);
        // The screen saw the last offer end and Dasher is on screen: this post is usually the notification of the
        // offer on screen now, which comes up to ~10 s after the screen read (and perhaps declined) it. A new
        // incarnation, but it must not revoke the confirmation of a decline under way for that offer.
        boolean sameOfferOnScreen = foreground && OfferFilterService.sameForegroundOfferNotification(labels);
        if (newOffer != null) {
            DiagnosticLog.log(this, "notification", "the same store re-posted: a new offer (" + newOffer + ")");
            remove(key, offer);
            offer = null;
        }
        boolean began = offer == null;
        if (offer == null) {
            if (tracked.size() >= MAX_TRACKED_OFFERS) {
                String oldest = tracked.keySet().iterator().next();
                remove(oldest, tracked.get(oldest));
            }
            TrackedOffer created = new TrackedOffer(now, source, merchant);
            created.expiry = () -> {
                if (tracked.get(key) == created) remove(key, created);
            };
            tracked.put(key, created);
            handler.postDelayed(created.expiry, OfferAlertState.LIFETIME_MS);
            // A next offer: under the silencer's lock, so a decline in progress turns no stream down over it.
            if (!sameOfferOnScreen && !replay) {
                OfferSilencer.nextOffer(this, OfferNotificationService::nextGeneration);
                if (!foreground) backgroundGeneration = generation();
            }
            if (!replay) lastNotice = new Notice(labels, generation(), foreground);
            if (replay) recallRead(key, source.getPostTime(), created.state, now);
            offer = created;
        }
        offer.gapMs = source.getPostTime() - offer.state.postedAt;
        offer.state.postedAt = Math.max(offer.state.postedAt, source.getPostTime());
        offer.state.text = text;
        offer.freshPost = began && !replay;
        offerOutstanding = true;
        return offer;
    }

    /**
     * A known-failing offer: request DoorDash's own safe Decline action, or else only hide the notification.
     *
     * @return what was done, for the decision log
     */
    private DecisionLog.Action filter(StatusBarNotification source, Notification notification, TrackedOffer offer,
                                      OfferRule.Decision decision, String signature, boolean replay) {
        OfferAlerts.clear(this, offer.alertTag);
        DecisionLog.Action action = DecisionLog.Action.NOTIFICATION_DECLINE_SENT;
        if (!offer.state.actionRequested) {
            PendingIntent decline = replay ? null : declineAction(notification);
            if (decline != null) {
                offer.state.actionRequested = true;
                try {
                    sendDecline(decline);
                    DiagnosticLog.log(this, "notification",
                            "notification Decline action REQUESTED; awaiting DoorDash removal, not yet verified");
                } catch (PendingIntent.CanceledException | RuntimeException error) {
                    action = DecisionLog.Action.DECLINE_REFUSED;
                    DiagnosticLog.log(this, "notification", "Decline action failed; original retained");
                }
            } else if (replay) {
                action = DecisionLog.Action.REPLAY;
                DiagnosticLog.log(this, "notification", "known filtered offer on replay; no action taken");
            } else {
                action = DecisionLog.Action.NOTIFICATION_HIDDEN;
                DiagnosticLog.log(this, "notification", "known filtered offer; no safe background decline action. "
                        + "Hidden notification does NOT decline order.");
                cancelNotification(source.getKey());
            }
        }
        offer.state.delivered(signature, decision.result, false);
        FilterStore.setLastStatus(this, "Filtered background offer: " + decision.summary() + "\n"
                + (offer.state.actionRequested
                        ? "Decline requested; completion unverified."
                        : "Notification hidden only; order not automatically declined."));
        return action;
    }

    /** The user touched the screen during this offer's decline: its notification is neither declined nor hidden. */
    private DecisionLog.Action leaveToUser(TrackedOffer offer, OfferRule.Decision decision, String signature) {
        offer.state.delivered(signature, decision.result, false);
        DiagnosticLog.log(this, "notification", "offer taken over by the user; notification left alone");
        return DecisionLog.Action.USER_TOOK_OVER;
    }

    /**
     * A passing or unclassified offer: post the matching Offer Filter card, keeping DoorDash's original. A card for
     * an offer that could not be judged does not ring when Dasher's own offer alert sounds: the user hears that.
     *
     * <p>With a peek about to read it ({@code peeking}), the card is posted silently: no ring, and so no sound given
     * back over the decline that may follow ({@link OfferSilencer#yieldToPassingAlert}). It rings once only if the peek
     * does not happen ({@link #peekNotTaken}).
     *
     * @param dasherSounds whether Android shows Dasher's own alert for this post sounded ({@link #dasherAlertSounded})
     * @return what was done, for the decision log
     */
    private DecisionLog.Action announce(PendingIntent contentIntent, TrackedOffer offer, OfferSnapshot facts,
                                        OfferRule.Decision decision, String signature, boolean replay,
                                        boolean foreground, boolean dasherSounds, boolean peeking) {
        boolean review = decision.result == OfferRule.Result.REVIEW;
        boolean ring = !peeking && offer.state.shouldRing(decision.result, foreground, replay);
        boolean dasherRings = ring && review && dasherSounds;
        if (dasherRings) {
            ring = false;
            DiagnosticLog.log(this, "alert", "no ring: Android shows Dasher's own alert for this offer sounded");
        }
        String detail = review ? reviewText(facts, decision, peeking) : facts.summary() + "; " + decision.summary();
        boolean posted = OfferAlerts.notifyOffer(this, offer.alertTag, contentIntent, decision.result,
                detail, ring, offer.store);
        // Dasher's own alert sounding for it uses up its one ring, as ours would have.
        if (posted) offer.state.delivered(signature, decision.result, ring || dasherRings);
        if (review) {
            FilterStore.setLastStatus(this, "Background offer requires review: " + decision.reason + ".\n" + (peeking
                    ? AppName.NAME + " is opening Dasher to read it (Peek)."
                    : "Open Dasher and " + AppName.NAME + " judges it."));
        }
        if (foreground) OfferFilterService.requestCheckFromNotification();
        if (!posted) return DecisionLog.Action.CARD_BLOCKED;
        if (peeking) return DecisionLog.Action.PEEK_CARD;
        if (dasherRings) return DecisionLog.Action.DASHER_SOUNDS;
        if (review) return ring ? DecisionLog.Action.CHECK_BELL : DecisionLog.Action.SILENT_CARD;
        return ring ? DecisionLog.Action.BELL : DecisionLog.Action.QUIET_PASS_CARD;
    }

    /**
     * What the card of an offer its notification cannot judge says: plainly that the notification shows no pay (or what
     * else it lacks), and that Offer Filter judges it on Dasher's screen, which it is opening when it peeks.
     */
    static String reviewText(OfferSnapshot facts, OfferRule.Decision decision, boolean peeking) {
        String what = facts.payCents == null && facts.payAtMostCents == null
                ? "Dasher's notification shows no pay."
                : "Dasher's notification doesn't show enough to judge it: " + decision.reason + ".";
        return what + (peeking ? " " + AppName.NAME + " is opening Dasher to read it."
                : " Open Dasher and " + AppName.NAME + " judges it.");
    }

    /**
     * A peek of this incarnation's offer did not happen (refused, the phone not quiet, or Dasher never came up): its
     * card, posted silently, is announced as usual and rings once as it would have, unless the screen read the offer
     * meanwhile or the card is gone (the user tapped it). Any thread.
     */
    static void peekNotTaken(String alertTag) {
        OfferNotificationService service = active;
        if (service == null || alertTag == null) return;
        onMain(service, () -> service.cardAfterNoPeek(alertTag));
    }

    private void cardAfterNoPeek(String alertTag) {
        if (!Consent.accepted(this)) return;
        TrackedOffer offer = trackedBy(alertTag);
        if (offer == null || offer.quietCard == null) return;
        QuietCard card = offer.quietCard;
        offer.quietCard = null;
        if (offer.state.readOnScreen != null || !OfferAlerts.showing(this, alertTag)) return;
        DecisionLog.Action action = announce(offer.contentIntent, offer, card.facts, card.decision, card.signature,
                false, false, offer.dasherSounded, false);
        DecisionLog.record(this, DecisionLog.Entry.of(DecisionLog.Source.NOTIFICATION, card.addOn,
                card.decision.basis, card.decision, action, card.enabled, card.labels).withAlertTag(alertTag, false));
    }

    /**
     * A peek read an offer that passes or is unclear while the user navigates, and went back to the map (the user's
     * decision): the offer's card carries what was read and rings once, as an ordinary card would. Its notification's
     * later updates change nothing. Any thread; without the listener, the card is posted on its own.
     */
    static void peekCard(Context context, OfferSnapshot read, OfferRule.Result result, String text) {
        if (!Consent.accepted(context)) return;
        OfferNotificationService service = active;
        if (service == null) {
            OfferAlerts.notifyOffer(context, "peek-" + System.currentTimeMillis(), null, result, text, true, "");
            return;
        }
        onMain(service, () -> service.cardOfPeekedOffer(read, result, text));
    }

    private void cardOfPeekedOffer(OfferSnapshot read, OfferRule.Result result, String text) {
        if (!Consent.accepted(this)) return;
        TrackedOffer found = null;
        for (TrackedOffer offer : tracked.values()) {
            OfferSnapshot onScreen = offer.state.readOnScreen;
            if (onScreen != null && (onScreen.fingerprint().equals(read.fingerprint()) || onScreen.agreesWith(read))) {
                found = offer;
            }
        }
        // A passing card always rings once; an unclear one not when Dasher's own alert for it sounded.
        boolean ring = result == OfferRule.Result.KEEP || found == null || !found.dasherSounded;
        String tag = found != null ? found.alertTag : "peek-" + System.currentTimeMillis();
        boolean posted = OfferAlerts.notifyOffer(this, tag, found == null ? null : found.contentIntent, result, text,
                ring, found == null ? "" : found.store);
        if (found != null && posted) {
            found.readCard = true;
            found.quietCard = null;
            found.state.delivered(found.state.signature, result, true);
        }
    }

    private TrackedOffer trackedBy(String alertTag) {
        for (TrackedOffer offer : tracked.values()) {
            if (alertTag.equals(offer.alertTag)) return offer;
        }
        return null;
    }

    private void removeExpired(long now) {
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, TrackedOffer> entry : tracked.entrySet()) {
            if (entry.getValue().state.expired(now)) expired.add(entry.getKey());
        }
        for (String key : expired) remove(key, tracked.get(key));
    }

    private void remove(String key, TrackedOffer offer) {
        if (offer == null) return;
        tracked.remove(key);
        livePeekCards.remove(offer.alertTag);
        if (offer.expiry != null) handler.removeCallbacks(offer.expiry);
        OfferAlerts.clear(this, offer.alertTag);
        offerOutstanding = !tracked.isEmpty();
    }

    private void clearTracked() {
        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<String, TrackedOffer> entry : tracked.entrySet()) {
            TrackedOffer offer = entry.getValue();
            if (offer.expiry != null) handler.removeCallbacks(offer.expiry);
            OfferAlerts.clear(this, offer.alertTag);
            // Kept for the replay after a reconnect, so an offer the screen read gets no card or line again.
            if (offer.state.readOnScreen != null) {
                readBeforeReconnect.put(entry.getKey(), new ReadMemory(offer.state, now));
            }
        }
        while (readBeforeReconnect.size() > MAX_TRACKED_OFFERS) {
            readBeforeReconnect.remove(readBeforeReconnect.keySet().iterator().next());
        }
        tracked.clear();
        livePeekCards.clear();
        postedKeys.clear();
        offerOutstanding = false;
    }

    /**
     * On a replay after a reconnect: when the screen had read this very post (same key, same post time), the new
     * incarnation knows it again, so the post gets no card or line. A newer post, or one kept too long ago, is unread.
     */
    private static void recallRead(String key, long postTime, OfferAlertState state, long now) {
        ReadMemory memory = readBeforeReconnect.remove(key);
        if (memory == null || memory.readThrough != postTime || now < memory.keptAt
                || now - memory.keptAt >= OfferAlertState.LIFETIME_MS) {
            return;
        }
        state.readOnScreen = memory.facts;
        state.readThrough = memory.readThrough;
        state.endedOnScreen = memory.ended;
        state.cardCleared = true;
    }

    private static boolean isFromOwnUsersDasher(StatusBarNotification source) {
        return source != null && DASHER_PACKAGE.equals(source.getPackageName())
                && Process.myUserHandle().equals(source.getUser());
    }

    /**
     * DoorDash's Decline action, only when it is provably a DoorDash-created broadcast or service intent. Action
     * type inspection is public only from API 31; older devices must not guess and launch an activity.
     */
    private static PendingIntent declineAction(Notification notification) {
        if (Build.VERSION.SDK_INT < 31 || notification.actions == null) return null;
        for (Notification.Action action : notification.actions) {
            if (action == null || action.title == null || action.actionIntent == null) continue;
            PendingIntent intent = action.actionIntent;
            if (OfferControls.isButton(action.title.toString(), "decline")
                    && DASHER_PACKAGE.equals(intent.getCreatorPackage()) && !intent.isActivity()) {
                return intent;
            }
        }
        return null;
    }

    /** Only standard displayed text is evidence; arbitrary extras are never read as offer values. */
    private static List<String> labels(Notification notification) {
        List<String> out = new ArrayList<>();
        Bundle extras = notification.extras;
        if (extras == null) return out;
        for (String key : TEXT_EXTRAS) {
            Object value = extras.get(key);
            if (value instanceof CharSequence) addLabel(out, value.toString());
        }
        Object lines = extras.get(Notification.EXTRA_TEXT_LINES);
        if (lines instanceof CharSequence[]) {
            for (CharSequence line : (CharSequence[]) lines) {
                if (line != null) addLabel(out, line.toString());
            }
        }
        return out;
    }

    private static void addLabel(List<String> labels, String value) {
        if (labels.size() >= MAX_NOTIFICATION_LABELS || value.length() > MAX_NOTIFICATION_LABEL_CHARS) {
            throw new OversizedNotification();
        }
        String clean = OfferEvidence.normalize(value);
        if (!clean.isEmpty() && !labels.contains(clean)) labels.add(clean);
    }

    /** A notification too large to read safely; it is left alone, as intended, not reported as a failure. */
    static final class OversizedNotification extends IllegalArgumentException {
        OversizedNotification() {
            super("oversized notification");
        }
    }

    /** The lower-cased text after "go to ", e.g. "chick-fil-a"; empty when absent. */
    private static String merchant(List<String> labels) {
        for (String label : labels) {
            String lower = label.toLowerCase(Locale.US);
            int index = lower.indexOf("go to ");
            if (index >= 0) return lower.substring(index + "go to ".length()).trim();
        }
        return "";
    }

    /**
     * The store Dasher's "New Order: Go to …" names, as written, for the title of our card ("Taco Bell offer"); empty
     * when none, or when it is too long to be a store's name. It names a new offer, never one accepted.
     */
    static String store(List<String> labels) {
        for (String label : labels) {
            int index = label.toLowerCase(Locale.US).indexOf("go to ");
            if (index < 0) continue;
            String name = label.substring(index + "go to ".length()).trim();
            return name.length() <= MAX_STORE_CHARS ? name : "";
        }
        return "";
    }

    /** Drops the merchant and headline lines so a store name cannot contribute offer metrics. */
    private static List<String> metricLabels(List<String> labels) {
        List<String> out = new ArrayList<>();
        for (String label : labels) {
            String lower = label.toLowerCase(Locale.US);
            if (lower.contains("go to ") || lower.startsWith("new delivery")) continue;
            out.add(label);
        }
        return out;
    }

    /** Android's ranking of the source notification, or null when Android does not say. */
    private static Ranking ranking(RankingMap map, String key) {
        if (map == null) return null;
        try {
            Ranking rank = new Ranking();
            return map.getRanking(key, rank) ? rank : null;
        } catch (RuntimeException unreadable) {
            // Unknown, as when Android does not say: the offer is still announced as before.
            return null;
        }
    }

    /**
     * Whether Android shows that Dasher's own post of this offer made a sound, so our card need not ring too (the
     * user's decision). From Android 10 Android says when it last alerted audibly for the notification: at this post,
     * give or take a second. Before that, every condition Android sounds on must hold: the post matches the
     * interruption filter (Do Not Disturb does not hold it back), Android ranks it at default importance or above,
     * its channel has a sound, and it is neither an only-alert-once update of a notification Android still shows nor
     * a group child that alerts through its summary. False when Android does not say, so ours rings once as before.
     *
     * @param update whether Android still showed a notification with this key when this one came
     */
    static boolean dasherAlertSounded(Ranking rank, StatusBarNotification source, boolean update) {
        if (rank == null || source == null) return false;
        if (Build.VERSION.SDK_INT >= 29) {
            long alerted = rank.getLastAudiblyAlertedMillis();
            return alerted > 0 && alerted >= source.getPostTime() - 1_000;
        }
        Notification notification = source.getNotification();
        NotificationChannel channel = rank.getChannel();
        if (notification == null || channel == null || channel.getSound() == null) return false;
        boolean onlyOnce = update && (notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0;
        boolean quietChild = notification.getGroup() != null
                && notification.getGroupAlertBehavior() == Notification.GROUP_ALERT_SUMMARY;
        return rank.matchesInterruptionFilter() && rank.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT
                && !onlyOnce && !quietChild;
    }

    /**
     * Records the source channel's actual sound/vibration settings and extra key names (never their values): each is
     * a state, logged once when it changes (and at least daily), per channel.
     */
    private void logChannel(NotificationChannel channel, Notification notification, boolean dasherSounded) {
        if (!DiagnosticLog.isEnabled(this)) return;
        String id = String.valueOf(notification.getChannelId());
        DiagnosticLog.logOnChange(this, "notification-channel", id, "id=" + id
                + " actualSound=" + (channel == null ? "unknown" : channel.getSound() != null)
                + " actualVibration=" + (channel == null ? "unknown" : channel.shouldVibrate())
                + " importance=" + (channel == null ? "unknown" : channel.getImportance())
                + " fullScreenIntent=" + (notification.fullScreenIntent != null)
                + " postSounded=" + dasherSounded);
        if (notification.extras != null) {
            List<String> keys = new ArrayList<>(notification.extras.keySet());
            Collections.sort(keys);
            if (keys.size() > MAX_LOGGED_EXTRA_KEYS) keys = keys.subList(0, MAX_LOGGED_EXTRA_KEYS);
            DiagnosticLog.logOnChange(this, "notification-meta", id, "extra keys only=" + keys);
        }
    }
}
