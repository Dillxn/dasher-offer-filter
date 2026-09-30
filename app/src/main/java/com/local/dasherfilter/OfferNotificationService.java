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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Classifies DoorDash offer notifications in the background. Never launches an activity: notification evidence and
 * screen evidence are separate authorities. An offer that cannot be judged gets a card that rings once while Dasher
 * is in the background, so it is not missed, and is otherwise left to the user.
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

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Keyed by source notification key; insertion order makes the first entry the oldest. */
    private final LinkedHashMap<String, TrackedOffer> tracked = new LinkedHashMap<>();

    /** Per-source-notification state plus the tag of the Offer Filter card that mirrors it. */
    private static final class TrackedOffer {
        final OfferAlertState state;
        final String alertTag;
        final String merchant;
        Runnable expiry;

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

    /** Incremented for each newly tracked offer; revokes older screen decline-confirmation authority. */
    static long generation() {
        return generation;
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
        if (!OfferEvidence.fresh(source.getPostTime(), System.currentTimeMillis(), OfferAlertState.LIFETIME_MS)) {
            DiagnosticLog.log(this, "notification", "ignored stale/future notification");
            return;
        }
        try {
            List<String> labels = labels(notification);
            if (!NotificationOffer.isLikelyOffer(labels)) return;
            TrackedOffer offer = track(source, merchant(labels));
            FilterStore.recordDoorDashOfferChannel(this, notification.getChannelId());

            OfferSnapshot facts = OfferParser.parse(metricLabels(labels));
            FilterSettings settings = FilterStore.load(this);
            OfferRule.Decision decision = decide(facts, labels, settings);
            String signature = labels + "|" + decision.summary();
            if (offer.state.duplicate(signature, decision.result)) return;
            DiagnosticLog.log(this, "notification", "parsed " + facts.summary() + "; " + decision.summary());
            logChannel(ranking, source.getKey(), notification);

            DecisionLog.Action action;
            if (decision.result != OfferRule.Result.DECLINE) {
                action = announce(notification, offer, facts, decision, signature, replay);
            } else if (OfferFilterService.userHasOffer(facts)) {
                action = leaveToUser(offer, decision, signature);
            } else {
                action = filter(source, notification, offer, decision, signature, replay);
            }
            DecisionLog.record(this, DecisionLog.Entry.of(DecisionLog.Source.NOTIFICATION,
                    AddOnOffer.isLikely(labels), decision.basis, decision, action, settings.enabled, labels));
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
     * incarnation expired, or the merchant changed.
     */
    private TrackedOffer track(StatusBarNotification source, String merchant) {
        long now = SystemClock.elapsedRealtime();
        // The Handler expiry runs on uptime, which stops in deep sleep; elapsed time is authoritative.
        removeExpired(now);
        String key = source.getKey();
        TrackedOffer offer = tracked.get(key);
        if (offer != null && !merchant.isEmpty() && !offer.merchant.isEmpty() && !merchant.equals(offer.merchant)) {
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
            generation++;
            offer = created;
        }
        offer.state.postedAt = Math.max(offer.state.postedAt, source.getPostTime());
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
     * A passing or unclassified offer: post the matching Offer Filter card, keeping DoorDash's original.
     *
     * @return what was done, for the decision log
     */
    private DecisionLog.Action announce(Notification notification, TrackedOffer offer, OfferSnapshot facts,
                                        OfferRule.Decision decision, String signature, boolean replay) {
        boolean review = decision.result == OfferRule.Result.REVIEW;
        boolean foreground = OfferFilterService.isDasherForeground();
        boolean ring = offer.state.shouldRing(decision.result, foreground, replay);
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
        for (TrackedOffer offer : tracked.values()) {
            if (offer.expiry != null) handler.removeCallbacks(offer.expiry);
            OfferAlerts.clear(this, offer.alertTag);
        }
        tracked.clear();
        offerOutstanding = false;
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

    /** Records the source channel's actual sound/vibration settings and extra key names (never their values). */
    private void logChannel(RankingMap map, String key, Notification notification) {
        if (!DiagnosticLog.isEnabled(this)) return;
        Ranking rank = new Ranking();
        NotificationChannel channel = map != null && map.getRanking(key, rank) ? rank.getChannel() : null;
        DiagnosticLog.log(this, "notification-channel", "id=" + notification.getChannelId()
                + " actualSound=" + (channel == null ? "unknown" : channel.getSound() != null)
                + " actualVibration=" + (channel == null ? "unknown" : channel.shouldVibrate())
                + " importance=" + (channel == null ? "unknown" : channel.getImportance())
                + " fullScreenIntent=" + (notification.fullScreenIntent != null));
        if (notification.extras != null) {
            List<String> keys = new ArrayList<>(notification.extras.keySet());
            Collections.sort(keys);
            if (keys.size() > MAX_LOGGED_EXTRA_KEYS) keys = keys.subList(0, MAX_LOGGED_EXTRA_KEYS);
            DiagnosticLog.log(this, "notification-meta", "extra keys only=" + keys);
        }
    }
}
