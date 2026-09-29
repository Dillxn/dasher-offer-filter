package com.local.dasherfilter;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Passing offers may ring; unknown offers get a separate, noninterrupting review card. */
final class OfferAlerts {
    static final String CHANNEL_ID = "qualifying_offers";
    static final String REVIEW_CHANNEL_ID = "unclassified_offers_v1";
    static final int NOTIFICATION_ID = 8241;
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    static void ensureChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class); if (manager == null) return;
        NotificationChannel passing = new NotificationChannel(CHANNEL_ID, "Offers that pass", NotificationManager.IMPORTANCE_HIGH);
        passing.setDescription("Only offers proven to meet enabled rules. Android sound and DND settings still apply."); passing.enableVibration(true);
        passing.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        manager.createNotificationChannel(passing);
        NotificationChannel review = new NotificationChannel(REVIEW_CHANNEL_ID, "Unclassified offers (silent)", NotificationManager.IMPORTANCE_LOW);
        review.setDescription("An offer exists, but required pay/distance evidence is missing. Tap to inspect it."); review.enableVibration(false); review.setSound(null, null); manager.createNotificationChannel(review);
    }
    /** The passing-offer channel can post (app notifications on, permission granted, channel not blocked). */
    static boolean canNotify(Context context) { return canNotify(context, CHANNEL_ID); }
    /** The silent review channel can post. */
    static boolean canNotifyReview(Context context) { return canNotify(context, REVIEW_CHANNEL_ID); }
    /** The channel a card with this verdict would actually use can post. DECLINE never posts a card. */
    static boolean canNotify(Context context, OfferRule.Result result) { return result != OfferRule.Result.DECLINE && canNotify(context, channelFor(result)); }
    static String channelFor(OfferRule.Result result) { return result == OfferRule.Result.KEEP ? CHANNEL_ID : REVIEW_CHANNEL_ID; }
    private static boolean canNotify(Context context, String channelId) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationChannel channel = manager.getNotificationChannel(channelId);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    /** Title, text, sub text and lock-screen title of one card. Pure wording: honest, glanceable, never "declined". */
    static final class Content {
        final String title, text, subText, publicTitle;
        Content(String title, String text, String subText, String publicTitle) { this.title = title; this.text = text; this.subText = subText; this.publicTitle = publicTitle; }
    }

    /**
     * KEEP: title "$12.00 · 5.4 mi · 21 min", text "Meets your rules · $2.22/mi · $34/hr · needed $8.10", sub text the merchant.
     * REVIEW: title "Offer at Chick-fil-A — pay not shown" (or "Offer needs review"), text with the missing evidence and
     * "Tap to open in Dasher".
     */
    static Content content(OfferRule.Decision decision, OfferSnapshot offer, String merchant) {
        String store = merchant == null ? "" : merchant.trim();
        if (decision != null && decision.result == OfferRule.Result.KEEP) {
            String facts = facts(offer);
            List<String> parts = new ArrayList<>();
            parts.add("Meets your rules");
            String efficiency = OfferMetrics.efficiency(offer);
            if (!efficiency.isEmpty()) parts.add(efficiency);
            if (decision.requiredCents > 0) parts.add("needed " + OfferMetrics.money(decision.requiredCents));
            return new Content(facts.isEmpty() ? (store.isEmpty() ? "Offer meets your rules" : "Offer at " + store) : facts,
                    String.join(" · ", parts), store.isEmpty() ? null : store, "Offer meets your rules");
        }
        String why = reviewHeadline(decision);
        String title = store.isEmpty() ? (why == null ? "Offer needs review" : "Offer needs review — " + why) : "Offer at " + store + " — " + (why == null ? "needs review" : why);
        StringBuilder text = new StringBuilder();
        String facts = OfferMetrics.summary(offer);
        if (!facts.isEmpty()) text.append(facts).append('\n');
        String reason = decision == null || decision.reason == null || decision.reason.isEmpty() ? "Offer details are not shown" : decision.reason;
        text.append(capitalize(reason)).append(reason.endsWith(".") ? " " : ". ").append("No automatic decline. Tap to open in Dasher.");
        return new Content(title, text.toString(), null, "Offer needs review");
    }

    /** Short reason for a review title: the missing evidence ("pay not shown"), or what stopped automation. */
    private static String reviewHeadline(OfferRule.Decision d) {
        if (d == null) return null;
        if (d.code.missingEvidence() && d.reason != null && !d.reason.isEmpty() && d.reason.length() <= 40) return d.reason;
        switch (d.code) {
            case ADDON_AMBIGUOUS: return "add-on details not shown";
            case HOURLY_MODE: return "Earn by Time";
            case PAUSED: return "auto-decline is off";
            case DECLINE_LIMIT: return "decline limit reached";
            case AVOIDED_STORE: return "avoided store, no details shown";
            case NO_PAY_RULE: return "no pay rule set";
            case UNREADABLE: return "unreadable";
            default: return null;
        }
    }
    private static String facts(OfferSnapshot o) {
        if (o == null) return "";
        List<String> parts = new ArrayList<>();
        if (o.payCents != null) parts.add(OfferMetrics.money(o.payCents));
        if (o.miles != null) parts.add(OfferMetrics.miles(o.miles) + " mi");
        if (o.minutes != null) parts.add(o.minutes + " min");
        if (o.stops != null) parts.add(OfferMetrics.stops(o.stops));
        return String.join(" · ", parts);
    }
    private static String capitalize(String s) { return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.US) + s.substring(1); }

    /** 0.4.x shape: generic titles with the given detail as text. */
    static boolean notifyOffer(Context context, String tag, PendingIntent doorDashIntent, OfferRule.Result result, String detail, boolean ring) {
        if (result == OfferRule.Result.DECLINE) { clear(context, tag); return false; }
        String body = detail == null ? "" : detail;
        return post(context, tag, doorDashIntent, result, new Content(result == OfferRule.Result.KEEP ? "DoorDash offer passes filter" : "DoorDash offer: not yet classified",
                body, null, result == OfferRule.Result.KEEP ? "Offer meets your rules" : "Offer needs review"), ring);
    }
    /** Glanceable card for a background offer. Channel, ring budget, tag lifecycle and group behavior match the 0.4.x shape. */
    static boolean notifyOffer(Context context, String tag, PendingIntent doorDashIntent, OfferRule.Decision decision, OfferSnapshot offer, String merchant, boolean ring) {
        OfferRule.Result result = decision == null ? OfferRule.Result.REVIEW : decision.result;
        if (result == OfferRule.Result.DECLINE) { clear(context, tag); return false; }
        return post(context, tag, doorDashIntent, result, content(decision, offer, merchant), ring);
    }
    private static boolean post(Context context, String tag, PendingIntent doorDashIntent, OfferRule.Result result, Content content, boolean ring) {
        ensureChannel(context);
        String channel = channelFor(result);
        if (!canNotify(context, channel)) { DiagnosticLog.log(context, "alert", "replacement unavailable; original notification retained; channel=" + channel); return false; }
        PendingIntent action = doorDashIntent;
        if (action != null && !DASHER_PACKAGE.equals(action.getCreatorPackage())) action = null;
        if (action == null) {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(DASHER_PACKAGE);
            if (launch != null) { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); action = PendingIntent.getActivity(context, NOTIFICATION_ID, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); }
        }
        String body = clip(content.text, 500);
        boolean audible = result == OfferRule.Result.KEEP && ring;
        int color = context.getColor(R.color.of_brand);
        Notification publicVersion = new Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_stat_offer).setColor(color)
                .setContentTitle(content.publicTitle).setCategory(Notification.CATEGORY_RECOMMENDATION).build();
        Notification.Builder b = new Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_stat_offer).setColor(color)
                .setContentTitle(clip(content.title, 120)).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
                .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion).setAutoCancel(true).setTimeoutAfter(OfferAlertState.LIFETIME_MS)
                .setOnlyAlertOnce(!audible).setCategory(Notification.CATEGORY_RECOMMENDATION);
        if (content.subText != null && !content.subText.isEmpty()) b.setSubText(clip(content.subText, 60));
        if (!audible) b.setGroup("quiet-" + tag).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
        if (action != null) b.setContentIntent(action);
        try {
            context.getSystemService(NotificationManager.class).notify(tag, NOTIFICATION_ID, b.build());
            DiagnosticLog.log(context, "alert", "posted " + result + " audibleRequested=" + audible); return true;
        } catch (RuntimeException error) { DiagnosticLog.log(context, "alert", "post failed; original retained: " + error.getClass().getSimpleName()); return false; }
    }
    private static String clip(String s, int max) { String t = s == null ? "" : s; return t.length() > max ? t.substring(0, max) + "…" : t; }
    static void clear(Context context, String tag) { NotificationManager manager = context.getSystemService(NotificationManager.class); if (manager != null) manager.cancel(tag, NOTIFICATION_ID); }
    /** Cancels every Offer Filter offer card, tagged or not (listener reconnect must not leave orphan duplicates). */
    static void clear(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class); if (manager == null) return;
        try {
            StatusBarNotification[] mine = manager.getActiveNotifications();
            if (mine != null) for (StatusBarNotification n : mine) if (n != null && n.getId() == NOTIFICATION_ID) manager.cancel(n.getTag(), NOTIFICATION_ID);
        } catch (RuntimeException error) { DiagnosticLog.log(context, "alert", "active card listing failed: " + error.getClass().getSimpleName()); }
        manager.cancel(NOTIFICATION_ID);
    }
    private OfferAlerts() {}
}
