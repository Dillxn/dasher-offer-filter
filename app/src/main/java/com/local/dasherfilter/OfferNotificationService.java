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
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Never launches an activity. Notification evidence and screen evidence are separate authorities. */
public final class OfferNotificationService extends NotificationListenerService {
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    /** An identical re-post within this window reuses the old incarnation's ring budget and history record. */
    static final long TOMBSTONE_MS = 600_000L;
    private static final int MAX_ENTRIES = 16, MAX_TOMBSTONES = 32;
    private static volatile OfferNotificationService active;
    private static volatile boolean offerOutstanding;
    private static volatile long generation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final LinkedHashMap<String, Tombstone> tombstones = new LinkedHashMap<>();
    private OfferHistoryStore history;
    private static final class Entry {
        final OfferAlertState state; final String alertTag; final String merchant; final List<String> labels; long recordId; Runnable expiry;
        Entry(long now, StatusBarNotification source, String merchant, List<String> labels) {
            this.merchant = merchant; this.labels = labels;
            state = new OfferAlertState(now, source.getPostTime(), source.getNotification().when); alertTag = "offer-" + now + "-" + source.getKey();
        }
    }
    /** What an ended incarnation already did: a re-post of identical content must not ring again or start a new record. */
    private static final class Tombstone {
        final long at, when; final List<String> labels; final boolean rang; final long recordId;
        Tombstone(long at, long when, List<String> labels, boolean rang, long recordId) { this.at = at; this.when = when; this.labels = labels; this.rang = rang; this.recordId = recordId; }
    }
    static boolean isConnected() { return active != null; }
    static boolean hasActiveOffer() { return offerOutstanding; }
    static long generation() { return generation; }
    static boolean hasRecentPendingWake(long now) { return false; }
    static boolean hasAccess(Context context) {
        ComponentName component = new ComponentName(context, OfferNotificationService.class);
        if (Build.VERSION.SDK_INT >= 27) { NotificationManager m = context.getSystemService(NotificationManager.class); return m != null && m.isNotificationListenerAccessGranted(component); }
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners"); if (enabled == null) return false;
        for (String value : enabled.split(":")) if (component.equals(ComponentName.unflattenFromString(value))) return true; return false;
    }
    static void screenResolved(Context context, OfferRule.Result result, String detail) { DiagnosticLog.log(context, "screen-decision", result + ": " + detail); }
    static void cancelPendingFiltered(Context context, String detail) { DiagnosticLog.log(context, "notification", "screen decline requested; unrelated notification keys are not cancelled"); }
    static void rulesChanged() { OfferNotificationService s = active; if (s != null) s.handler.post(s::reconcile); }
    @Override public void onCreate() { super.onCreate(); history = OfferHistoryStore.get(this); }
    @Override public void onListenerConnected() {
        active = this; OfferAlerts.ensureChannel(this); OfferAlerts.clear(this); Updater.schedule(this);
        DiagnosticLog.log(this, "notification", "listener connected; replay is noninterrupting"); reconcile(); Updater.check(this, false, null);
    }
    private void reconcile() {
        try { StatusBarNotification[] all = getActiveNotifications(); if (all != null) for (StatusBarNotification source : all) handle(source, getCurrentRanking(), true); }
        catch (RuntimeException error) { DiagnosticLog.log(this, "notification", "reconcile failed: " + error.getClass().getSimpleName()); }
    }
    @Override public void onListenerDisconnected() {
        clearEntries(); if (active == this) active = null; DiagnosticLog.log(this, "notification", "listener disconnected; delivery monitoring unavailable");
        try { requestRebind(new ComponentName(this, OfferNotificationService.class)); } catch (RuntimeException error) { DiagnosticLog.log(this, "notification", "rebind request failed"); }
    }
    @Override public void onDestroy() { clearEntries(); handler.removeCallbacksAndMessages(null); if (active == this) active = null; super.onDestroy(); }
    @Override public void onNotificationPosted(StatusBarNotification source, RankingMap ranking) { handle(source, ranking, false); }
    @Override public void onNotificationPosted(StatusBarNotification source) { handle(source, getCurrentRanking(), false); }
    private OfferHistoryStore history() { if (history == null) history = OfferHistoryStore.get(this); return history; }
    private void handle(StatusBarNotification source, RankingMap ranking, boolean replay) {
        if (source == null || !DASHER_PACKAGE.equals(source.getPackageName()) || !android.os.Process.myUserHandle().equals(source.getUser())) return;
        Notification n = source.getNotification(); if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (!OfferEvidence.fresh(source.getPostTime(), System.currentTimeMillis(), OfferAlertState.LIFETIME_MS)) { DiagnosticLog.log(this, "notification", "ignored stale/future notification"); return; }
        try {
            List<String> labels = labels(n);
            NotificationOffer.Kind kind = NotificationOffer.classify(labels);
            if (kind == NotificationOffer.Kind.NOT_OFFER) return;
            long now = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
            String key = source.getKey(), merchant = NotificationOffer.merchant(labels);
            // Settings of DoorDash's own offer channel, every time an offer is seen (readiness UI; not offer content).
            DoorDashChannelFacts.observe(this, ranking, key, n, wall); FilterStore.recordDoorDashOfferChannel(this, n.getChannelId());
            OfferSnapshot offer = OfferParser.parse(metricLabels(labels));
            Entry e = entries.get(key);
            if (e != null && !merchant.isEmpty() && !e.merchant.isEmpty() && !merchant.equalsIgnoreCase(e.merchant)) { remove(key, e); e = null; }
            if (e != null && e.state.expired(now)) { remove(key, e); return; }
            if (e == null) {
                if (entries.size() >= MAX_ENTRIES) { String first = entries.keySet().iterator().next(); remove(first, entries.get(first)); }
                e = new Entry(now, source, merchant, labels);
                Tombstone t = tombstone(key, labels, now);
                if (t != null) { e.state.rang = t.rang; e.recordId = t.recordId; } else e.recordId = history().nextId(wall);
                entries.put(key, e); generation++;
                final Entry captured = e; final String sourceKey = key;
                e.expiry = () -> { if (entries.get(sourceKey) == captured) remove(sourceKey, captured); }; handler.postDelayed(e.expiry, OfferAlertState.LIFETIME_MS);
                // A new offer with facts that differ from a screen offer whose decline is pending ends that confirmation authority;
                // the declined offer's own notification (same or no facts) does not.
                OfferFilterService.notificationOfferSeen(offer);
            }
            e.state.postedAt = Math.max(e.state.postedAt, source.getPostTime()); offerOutstanding = !entries.isEmpty();
            FilterSettings settings = FilterStore.load(this);
            boolean addOn = AddOnOffer.isLikely(labels);
            OfferSnapshot route = addOn ? ActiveRouteStore.load(this) : null;
            EvaluationContext ctx = EvaluationContext.at(wall).withMerchant(merchant).withOfferNotification(kind == NotificationOffer.Kind.OFFER && provenOfferMerchant(labels, merchant));
            if (addOn) ctx = ctx.withAddOn(route);
            if (settings.maxDeclinesPerHour > 0) ctx = ctx.withBudget(history().budget(), wall, OfferHistoryStore.budgetKey(e.recordId));
            OfferRule.Decision rule = kind == NotificationOffer.Kind.REVIEW_ONLY
                    ? new OfferRule.Decision(OfferRule.Result.REVIEW, 0, "offer wording mixed with message or promotion text", OfferRule.Code.UNSPECIFIED, null, null)
                    : OfferRule.evaluate(offer, labels, settings, ctx);
            OfferRule.Decision decision = settings.enabled ? rule : OfferRule.Decision.paused();
            OfferSnapshot facts = addOn ? OfferFilterService.shownIncrement(AddOnOffer.parse(route, offer, labels)) : offer;
            String signature = labels.toString() + "|" + decision.summary(); if (e.state.duplicate(signature, decision.result)) return;
            DiagnosticLog.log(this, "notification", "parsed " + offer.summary() + "; " + decision.summary()); logChannel(ranking, key, n);
            int flags = settings.enabled || rule.result != OfferRule.Result.DECLINE ? 0 : OfferRecord.PAUSED_SHADOW;
            if (decision.result == OfferRule.Result.DECLINE) {
                OfferAlerts.clear(this, e.alertTag);
                String outcome;
                if (replay) outcome = "Seen again after reconnect; no new action.";
                else if (e.state.actionRequested) outcome = "Decline already requested; completion unverified.";
                else {
                    PendingIntent decline = declineAction(n);
                    if (decline != null) {
                        e.state.actionRequested = true;
                        try { decline.send(); flags |= OfferRecord.NOTIF_DECLINE_SENT; outcome = "Decline requested from the notification; completion unverified."; DiagnosticLog.log(this, "notification", "notification Decline action REQUESTED; awaiting DoorDash removal, not yet verified"); }
                        catch (PendingIntent.CanceledException | RuntimeException error) { outcome = "Decline action failed; original notification kept."; DiagnosticLog.log(this, "notification", "Decline action failed; original retained"); }
                    } else {
                        DiagnosticLog.log(this, "notification", "known filtered offer; no safe background decline action. Hidden notification does NOT decline order.");
                        try { cancelNotification(key); flags |= OfferRecord.NOTIF_HIDDEN; outcome = "Notification hidden — order NOT declined. It may stay pending in Dasher until it expires."; }
                        catch (RuntimeException error) { outcome = "Could not hide the notification; original kept."; }
                    }
                }
                e.state.delivered(signature, decision.result, false);
                record(e, facts, addOn, rule, merchant, flags, wall);
                FilterStore.setLastStatus(this, "Filtered background offer: " + decision.summary() + "\n" + outcome); return;
            }
            boolean foreground = OfferFilterService.isDasherForeground(); boolean ring = e.state.shouldRing(decision.result, foreground, replay);
            boolean posted = OfferAlerts.notifyOffer(this, e.alertTag, n.contentIntent, decision, facts, merchant, ring);
            if (posted) {
                e.state.delivered(signature, decision.result, ring);
                flags |= decision.result == OfferRule.Result.KEEP ? OfferRecord.PASS_ALERT | (ring ? OfferRecord.PASS_BELL : 0) : OfferRecord.REVIEW_CARD;
            } else flags |= OfferRecord.ALERT_UNAVAILABLE;
            record(e, facts, addOn, rule, merchant, flags, wall);
            if (decision.result == OfferRule.Result.REVIEW) FilterStore.setLastStatus(this, (decision.code == OfferRule.Code.HOURLY_MODE ? "Earn by Time offer — not auto-declined. " : "Background offer requires review. ") + decision.reason + "\nDasher has not been opened. Tap the silent review card to inspect.");
            if (foreground) OfferFilterService.requestCheckFromNotification();
        } catch (RuntimeException error) { DiagnosticLog.log(this, "notification", "payload/handler rejected; original retained: " + error.getClass().getSimpleName()); }
    }
    /** One history record per Entry, updated in place. The verdict is the real rule's, also while paused (PAUSED_SHADOW). */
    private void record(Entry e, OfferSnapshot facts, boolean addOn, OfferRule.Decision rule, String merchant, int flags, long wall) {
        OfferHistoryStore h = history();
        OfferRecord old = h.get(e.recordId);
        OfferRecord next = old == null ? OfferRecord.of(e.recordId, wall, OfferRecord.Source.NOTIFICATION, facts, addOn, rule, merchant.isEmpty() ? null : merchant)
                : old.withEvaluation(facts, addOn, rule, merchant.isEmpty() ? null : merchant);
        h.upsert(flags == 0 ? next : next.withAction(flags));
    }
    /** The merchant came from an explicit offer phrase ("Go to <store>", "New Order: Go to <store>"), not "offer from" text. */
    static boolean provenOfferMerchant(List<String> labels, String merchant) {
        if (merchant == null || merchant.isEmpty() || labels == null) return false;
        List<String> goTo = new ArrayList<>();
        for (String label : labels) if (label != null && label.toLowerCase(Locale.US).contains("go to ")) goTo.add(label);
        String explicit = NotificationOffer.merchant(goTo);
        return !explicit.isEmpty() && explicit.equalsIgnoreCase(merchant);
    }
    @Override public void onNotificationRemoved(StatusBarNotification source) {
        if (source == null || !DASHER_PACKAGE.equals(source.getPackageName()) || !android.os.Process.myUserHandle().equals(source.getUser())) return;
        Entry e = entries.get(source.getKey()); if (e != null && e.state.removalMatches(source.getPostTime())) remove(source.getKey(), e);
    }
    private Tombstone tombstone(String key, List<String> labels, long now) {
        for (Iterator<Map.Entry<String, Tombstone>> it = tombstones.entrySet().iterator(); it.hasNext(); ) {
            Tombstone t = it.next().getValue(); if (now < t.at || now - t.at > TOMBSTONE_MS) it.remove();
        }
        Tombstone t = tombstones.get(key);
        return t != null && t.labels.equals(labels) ? t : null;
    }
    private void bury(String key, Entry e) {
        if (!e.state.displayed && !e.state.rang && !e.state.actionRequested) return;
        tombstones.remove(key); tombstones.put(key, new Tombstone(SystemClock.elapsedRealtime(), e.state.sourceWhen, e.labels, e.state.rang, e.recordId));
        while (tombstones.size() > MAX_TOMBSTONES) { String first = tombstones.keySet().iterator().next(); tombstones.remove(first); }
    }
    private void remove(String key, Entry e) { if (e == null) return; bury(key, e); entries.remove(key); if (e.expiry != null) handler.removeCallbacks(e.expiry); OfferAlerts.clear(this, e.alertTag); offerOutstanding = !entries.isEmpty(); }
    private void clearEntries() { for (Map.Entry<String, Entry> item : entries.entrySet()) { Entry e = item.getValue(); bury(item.getKey(), e); if (e.expiry != null) handler.removeCallbacks(e.expiry); OfferAlerts.clear(this, e.alertTag); } entries.clear(); offerOutstanding = false; }
    private static PendingIntent declineAction(Notification n) {
        // Type inspection is only public from API 31. Older devices must not guess and launch an activity.
        if (Build.VERSION.SDK_INT < 31 || n.actions == null) return null;
        for (Notification.Action action : n.actions) {
            if (action == null || action.title == null || action.actionIntent == null) continue;
            PendingIntent intent = action.actionIntent;
            if (OfferControls.isButton(action.title.toString(), "decline") && DASHER_PACKAGE.equals(intent.getCreatorPackage()) && !intent.isActivity()) return intent;
        }
        return null;
    }
    private static List<String> labels(Notification n) {
        List<String> out = new ArrayList<>(); Bundle extras = n.extras; if (extras == null) return out;
        for (String key : new String[]{Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT}) { Object value = extras.get(key); if (value instanceof CharSequence) add(out, value.toString()); }
        Object lines = extras.get(Notification.EXTRA_TEXT_LINES); if (lines instanceof CharSequence[]) for (CharSequence line : (CharSequence[]) lines) if (line != null) add(out, line.toString()); return Collections.unmodifiableList(out);
    }
    private static void add(List<String> labels, String value) {
        if (labels.size() >= 32 || value.length() > 2048) throw new IllegalArgumentException("oversized notification"); String clean = OfferEvidence.normalize(value); if (!clean.isEmpty() && !labels.contains(clean)) labels.add(clean);
    }
    private static List<String> metricLabels(List<String> labels) {
        List<String> out = new ArrayList<>(); for (String label : labels) { String lower = label.toLowerCase(Locale.US); if (lower.contains("go to ") || lower.startsWith("new delivery")) continue; out.add(label); } return out;
    }
    private void logChannel(RankingMap map, String key, Notification n) {
        if (!DiagnosticLog.isEnabled(this)) return;
        Ranking rank = new Ranking(); NotificationChannel c = map != null && map.getRanking(key, rank) ? rank.getChannel() : null;
        DiagnosticLog.log(this, "notification-channel", "id=" + n.getChannelId() + " actualSound=" + (c == null ? "unknown" : c.getSound() != null) + " actualVibration=" + (c == null ? "unknown" : c.shouldVibrate()) + " importance=" + (c == null ? "unknown" : c.getImportance()) + " fullScreenIntent=" + (n.fullScreenIntent != null));
        if (n.extras != null) { List<String> keys = new ArrayList<>(n.extras.keySet()); Collections.sort(keys); if (keys.size() > 40) keys = keys.subList(0, 40); DiagnosticLog.log(this, "notification-meta", "extra keys only=" + keys); }
    }
}
