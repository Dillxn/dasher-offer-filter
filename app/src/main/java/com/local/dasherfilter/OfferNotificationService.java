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
import java.util.LinkedHashMap;
import java.util.List;

/** Never launches an activity. Notification evidence and screen evidence are separate authorities. */
public final class OfferNotificationService extends NotificationListenerService {
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private static volatile OfferNotificationService active;
    private static volatile boolean offerOutstanding;
    private static volatile long generation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private static final class Entry {
        final OfferAlertState state; final String alertTag; final String merchant; Runnable expiry;
        Entry(long now, StatusBarNotification source, String merchant) {
            this.merchant = merchant; state = new OfferAlertState(now, source.getPostTime(), source.getNotification().when); alertTag = "offer-" + now + "-" + source.getKey();
        }
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
    private void handle(StatusBarNotification source, RankingMap ranking, boolean replay) {
        if (source == null || !DASHER_PACKAGE.equals(source.getPackageName()) || !android.os.Process.myUserHandle().equals(source.getUser())) return;
        Notification n = source.getNotification(); if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (!OfferEvidence.fresh(source.getPostTime(), System.currentTimeMillis(), OfferAlertState.LIFETIME_MS)) { DiagnosticLog.log(this, "notification", "ignored stale/future notification"); return; }
        try {
            List<String> labels = labels(n); if (!NotificationOffer.isLikelyOffer(labels)) return;
            long now = SystemClock.elapsedRealtime(); Entry e = entries.get(source.getKey()); String merchant = merchant(labels);
            if (e != null && !merchant.isEmpty() && !e.merchant.isEmpty() && !merchant.equals(e.merchant)) { remove(source.getKey(), e); e = null; }
            if (e != null && e.state.expired(now)) { remove(source.getKey(), e); return; }
            if (e == null) {
                if (entries.size() >= 16) { String first = entries.keySet().iterator().next(); remove(first, entries.get(first)); }
                e = new Entry(now, source, merchant); entries.put(source.getKey(), e); generation++;
                final Entry captured = e; final String sourceKey = source.getKey();
                e.expiry = () -> { if (entries.get(sourceKey) == captured) remove(sourceKey, captured); };
                handler.postDelayed(e.expiry, OfferAlertState.LIFETIME_MS);
            }
            e.state.postedAt = Math.max(e.state.postedAt, source.getPostTime()); offerOutstanding = !entries.isEmpty();
            FilterStore.recordDoorDashOfferChannel(this, n.getChannelId()); FilterSettings settings = FilterStore.load(this);
            OfferSnapshot offer = OfferParser.parse(metricLabels(labels)); boolean addOn = AddOnOffer.isLikely(labels);
            OfferRule.Decision decision = !settings.enabled ? new OfferRule.Decision(OfferRule.Result.REVIEW, 0, "Auto-decline is off; inspect this offer manually.") : addOn ? OfferRule.evaluateAddOn(AddOnOffer.parse(ActiveRouteStore.load(this), offer, labels), settings) : OfferRule.evaluate(offer, settings);
            String signature = labels.toString() + "|" + decision.summary(); if (e.state.duplicate(signature, decision.result)) return;
            DiagnosticLog.log(this, "notification", "parsed " + offer.summary() + "; " + decision.summary()); logChannel(ranking, source.getKey(), n);
            if (decision.result == OfferRule.Result.DECLINE) {
                OfferAlerts.clear(this, e.alertTag);
                if (!e.state.actionRequested) {
                    PendingIntent decline = declineAction(n);
                    if (decline != null && !replay) {
                        e.state.actionRequested = true;
                        try { decline.send(); DiagnosticLog.log(this, "notification", "notification Decline action REQUESTED; awaiting DoorDash removal, not yet verified"); }
                        catch (PendingIntent.CanceledException | RuntimeException error) { DiagnosticLog.log(this, "notification", "Decline action failed; original retained"); }
                    } else {
                        DiagnosticLog.log(this, "notification", "known filtered offer; no safe background decline action. Hidden notification does NOT decline order.");
                        if (!replay) cancelNotification(source.getKey());
                    }
                }
                e.state.delivered(signature, decision.result, false);
                FilterStore.setLastStatus(this, "Filtered background offer: " + decision.summary() + "\n" + (e.state.actionRequested ? "Decline requested; completion unverified." : "Notification hidden only; order not automatically declined.")); return;
            }
            boolean foreground = OfferFilterService.isDasherForeground(); boolean ring = e.state.shouldRing(decision.result, foreground, replay);
            String detail = decision.result == OfferRule.Result.REVIEW ? "Not classified: " + decision.reason + " Tap to open Dasher. No automatic decline or screen takeover." : offer.summary() + "; " + decision.summary();
            if (OfferAlerts.notifyOffer(this, e.alertTag, n.contentIntent, decision.result, detail, ring)) e.state.delivered(signature, decision.result, ring);
            if (decision.result == OfferRule.Result.REVIEW) FilterStore.setLastStatus(this, "Background offer requires review. " + decision.reason + "\nDasher has not been opened. Tap the silent review card to inspect.");
            if (foreground) OfferFilterService.requestCheckFromNotification();
        } catch (RuntimeException error) { DiagnosticLog.log(this, "notification", "payload/handler rejected; original retained: " + error.getClass().getSimpleName()); }
    }
    @Override public void onNotificationRemoved(StatusBarNotification source) {
        if (source == null || !DASHER_PACKAGE.equals(source.getPackageName()) || !android.os.Process.myUserHandle().equals(source.getUser())) return;
        Entry e = entries.get(source.getKey()); if (e != null && e.state.removalMatches(source.getPostTime())) remove(source.getKey(), e);
    }
    private void remove(String key, Entry e) { if (e == null) return; entries.remove(key); if (e.expiry != null) handler.removeCallbacks(e.expiry); OfferAlerts.clear(this, e.alertTag); offerOutstanding = !entries.isEmpty(); }
    private void clearEntries() { for (Entry e : entries.values()) { if (e.expiry != null) handler.removeCallbacks(e.expiry); OfferAlerts.clear(this, e.alertTag); } entries.clear(); offerOutstanding = false; }
    private static PendingIntent declineAction(Notification n) {
        if (n.actions == null) return null;
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
        Object lines = extras.get(Notification.EXTRA_TEXT_LINES); if (lines instanceof CharSequence[]) for (CharSequence line : (CharSequence[]) lines) if (line != null) add(out, line.toString()); return out;
    }
    private static void add(List<String> labels, String value) {
        if (labels.size() >= 32 || value.length() > 2048) throw new IllegalArgumentException("oversized notification"); String clean = OfferEvidence.normalize(value); if (!clean.isEmpty() && !labels.contains(clean)) labels.add(clean);
    }
    private static String merchant(List<String> labels) {
        for (String label : labels) { String s = label.toLowerCase(java.util.Locale.US); int index = s.indexOf("go to "); if (index >= 0) return s.substring(index + 6).trim(); } return "";
    }
    private static List<String> metricLabels(List<String> labels) {
        List<String> out = new ArrayList<>(); for (String label : labels) { String lower = label.toLowerCase(java.util.Locale.US); if (lower.contains("go to ") || lower.startsWith("new delivery")) continue; out.add(label); } return out;
    }
    private void logChannel(RankingMap map, String key, Notification n) {
        if (!DiagnosticLog.isEnabled(this)) return;
        Ranking rank = new Ranking(); NotificationChannel c = map != null && map.getRanking(key, rank) ? rank.getChannel() : null;
        DiagnosticLog.log(this, "notification-channel", "id=" + n.getChannelId() + " actualSound=" + (c == null ? "unknown" : c.getSound() != null) + " actualVibration=" + (c == null ? "unknown" : c.shouldVibrate()) + " importance=" + (c == null ? "unknown" : c.getImportance()) + " fullScreenIntent=" + (n.fullScreenIntent != null));
        if (n.extras != null) { List<String> keys = new ArrayList<>(n.extras.keySet()); Collections.sort(keys); if (keys.size() > 40) keys = keys.subList(0, 40); DiagnosticLog.log(this, "notification-meta", "extra keys only=" + keys); }
    }
}
