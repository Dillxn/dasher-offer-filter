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
 * Classifies DoorDash offer notifications in the background. Never launches an activity: notification evidence and
 * screen evidence are separate authorities. An offer that cannot be judged gets a card that rings once while Dasher
 * is in the background, so it is not missed (silently when Android shows Dasher's own alert for it sounded), and is
 * otherwise left to the user. One offer is one history line: a notification of an offer the screen reads is folded
 * into the screen's line, and its card is cleared then; Dasher's own notification is never touched for that.
 */
public final class OfferNotificationService extends NotificationListenerService {
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private static final int MAX_TRACKED_OFFERS = 16;
    private static final int MAX_NOTIFICATION_LABELS = 32;
    private static final int MAX_NOTIFICATION_LABEL_CHARS = 2048;
    private static final int MAX_LOGGED_EXTRA_KEYS = 40;
    private static final String[] TEXT_EXTRAS = {
            Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT};

    private static volatile OfferNotificationService active;
    private static volatile boolean offerOutstanding;
    private static volatile long generation;

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
        Runnable expiry;
        /** How long after the previous post on this key the latest one came (wall clock), for the log. */
        long gapMs;

        TrackedOffer(long now, StatusBarNotification source, String merchant) {
            this.state = new OfferAlertState(now, source.getPostTime());
            this.alertTag = "offer-" + now + "-" + source.getKey();
            this.merchant = merchant;
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

    private static void nextGeneration() {
        generation++;
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
        Updater.check(this, false, null);
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
        if (notification == null || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        // To Android, a post on a key it still shows is an update.
        boolean update = !postedKeys.add(source.getKey());
        while (postedKeys.size() > MAX_POSTED_KEYS) postedKeys.remove(postedKeys.iterator().next());
        if (!OfferEvidence.fresh(source.getPostTime(), System.currentTimeMillis(), OfferAlertState.LIFETIME_MS)) {
            DiagnosticLog.log(this, "notification", "ignored stale/future notification");
            return;
        }
        try {
            List<String> labels = labels(notification);
            if (!NotificationOffer.isLikelyOffer(labels)) return;
            boolean foreground = OfferFilterService.isDasherOnScreenNow();
            TrackedOffer offer = track(source, merchant(labels), labels.toString(), foreground, replay);
            FilterStore.recordDoorDashOfferChannel(this, notification.getChannelId());

            // A notification's text may be cut short: its pay is never bounded, so nothing is declined or hidden on a
            // bound.
            OfferSnapshot facts = OfferParser.parse(metricLabels(labels)).withoutPayBound();
            FilterSettings settings = FilterStore.load(this);
            OfferRule.Decision decision = decide(facts, labels, settings);
            String signature = labels + "|" + decision.summary();
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
            boolean addOn = AddOnOffer.isLikely(labels);

            if (offer.state.coveredByScreen(decision.result, decision.basis, foreground, replay,
                    source.getPostTime())) {
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
                    offer.state.settle(signature, decision.result, screen.facts);
                    OfferAlerts.clear(this, offer.alertTag);
                    DiagnosticLog.log(this, "notification", "same offer the screen read "
                            + Math.round((screen.notification.at - screen.at) / 1000.0) + " s earlier; no card");
                    Dashing.seen(this);
                    OfferFilterService.requestCheckFromNotification();
                    return;
                }
            }

            DecisionLog.Action action;
            if (decision.result != OfferRule.Result.DECLINE) {
                action = announce(notification, offer, facts, decision, signature, replay, foreground,
                        dasherSounded);
            } else if (OfferFilterService.userHasOffer(facts)) {
                action = leaveToUser(offer, decision, signature);
            } else {
                action = filter(source, notification, offer, decision, signature, replay);
            }
            Dashing.seen(this);
            DecisionLog.record(this, DecisionLog.Entry.of(DecisionLog.Source.NOTIFICATION,
                    addOn, decision.basis, decision, action, settings.enabled, labels)
                    .withAlertTag(offer.alertTag, replay));
        } catch (RuntimeException error) {
            DiagnosticLog.log(this, "notification",
                    "payload/handler rejected; original retained: " + error.getClass().getSimpleName());
            // An oversized notification is refused on purpose; only real failures are worth a report.
            if (!(error instanceof OversizedNotification)) {
                ReportOutbox.fileAutomatic(this, ProblemReport.Kind.NOTIFICATION_ERROR, null, null, error);
            }
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
    private TrackedOffer track(StatusBarNotification source, String merchant, String text, boolean foreground,
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
        boolean sameOfferOnScreen = newOffer != null && foreground;
        if (newOffer != null) {
            DiagnosticLog.log(this, "notification", "the same store re-posted: a new offer (" + newOffer + ")");
            remove(key, offer);
            offer = null;
        }
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
            if (!sameOfferOnScreen) OfferSilencer.nextOffer(this, OfferNotificationService::nextGeneration);
            if (replay) recallRead(key, source.getPostTime(), created.state, now);
            offer = created;
        }
        offer.gapMs = source.getPostTime() - offer.state.postedAt;
        offer.state.postedAt = Math.max(offer.state.postedAt, source.getPostTime());
        offer.state.text = text;
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
                    decline.send();
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
     * @param dasherSounds whether Android shows Dasher's own alert for this post sounded ({@link #dasherAlertSounded})
     * @return what was done, for the decision log
     */
    private DecisionLog.Action announce(Notification notification, TrackedOffer offer, OfferSnapshot facts,
                                        OfferRule.Decision decision, String signature, boolean replay,
                                        boolean foreground, boolean dasherSounds) {
        boolean review = decision.result == OfferRule.Result.REVIEW;
        boolean ring = offer.state.shouldRing(decision.result, foreground, replay);
        boolean dasherRings = ring && review && dasherSounds;
        if (dasherRings) {
            ring = false;
            DiagnosticLog.log(this, "alert", "no ring: Android shows Dasher's own alert for this offer sounded");
        }
        String detail = review
                ? "Not classified: " + decision.reason
                        + ". Tap to open Dasher. No automatic decline or screen takeover."
                : facts.summary() + "; " + decision.summary();
        boolean posted = OfferAlerts.notifyOffer(
                this, offer.alertTag, notification.contentIntent, decision.result, detail, ring);
        if (posted) offer.state.delivered(signature, decision.result, ring);
        if (review) {
            FilterStore.setLastStatus(this, "Background offer requires review: " + decision.reason
                    + ".\nDasher has not been opened. Tap the card to inspect.");
        }
        if (foreground) OfferFilterService.requestCheckFromNotification();
        if (!posted) return DecisionLog.Action.CARD_BLOCKED;
        if (dasherRings) return DecisionLog.Action.DASHER_SOUNDS;
        if (review) return ring ? DecisionLog.Action.CHECK_BELL : DecisionLog.Action.SILENT_CARD;
        return ring ? DecisionLog.Action.BELL : DecisionLog.Action.QUIET_PASS_CARD;
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

    /** Records the source channel's actual sound/vibration settings and extra key names (never their values). */
    private void logChannel(NotificationChannel channel, Notification notification, boolean dasherSounded) {
        if (!DiagnosticLog.isEnabled(this)) return;
        DiagnosticLog.log(this, "notification-channel", "id=" + notification.getChannelId()
                + " actualSound=" + (channel == null ? "unknown" : channel.getSound() != null)
                + " actualVibration=" + (channel == null ? "unknown" : channel.shouldVibrate())
                + " importance=" + (channel == null ? "unknown" : channel.getImportance())
                + " fullScreenIntent=" + (notification.fullScreenIntent != null)
                + " postSounded=" + dasherSounded);
        if (notification.extras != null) {
            List<String> keys = new ArrayList<>(notification.extras.keySet());
            Collections.sort(keys);
            if (keys.size() > MAX_LOGGED_EXTRA_KEYS) keys = keys.subList(0, MAX_LOGGED_EXTRA_KEYS);
            DiagnosticLog.log(this, "notification-meta", "extra keys only=" + keys);
        }
    }
}
