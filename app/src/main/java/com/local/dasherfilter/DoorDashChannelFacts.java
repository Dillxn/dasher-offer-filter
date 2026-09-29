package com.local.dasherfilter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;

/**
 * The actual settings of the DoorDash channel that carried an offer notification, as Android reported them when the offer was
 * seen: importance, sound, vibration, DND bypass and whether a full-screen intent was attached. Settings only, never offer
 * content. Persisted every time an offer notification is seen (diagnostics need not be on) so readiness UI can say whether
 * Dasher's own offer alert can still ring. A channel name is not proof of silence; these values are what Android reported.
 */
final class DoorDashChannelFacts {
    private static final String PREFS = "doordash_channel_facts";
    /** Importance was not reported (no ranking, or the listener could not read the channel). */
    static final int UNKNOWN_IMPORTANCE = Integer.MIN_VALUE;
    private static final long REFRESH_MS = 60_000L;
    final String channelId;
    final String channelName;
    final int importance;
    /** null when Android did not report the channel. */
    final Boolean sound;
    final Boolean vibration;
    final Boolean bypassDnd;
    final boolean fullScreenIntent;
    /** Wall-clock ms when an offer notification last showed these facts. */
    final long observedAt;

    DoorDashChannelFacts(String channelId, String channelName, int importance, Boolean sound, Boolean vibration, Boolean bypassDnd, boolean fullScreenIntent, long observedAt) {
        this.channelId = channelId == null ? "" : clip(channelId);
        this.channelName = channelName == null ? "" : clip(channelName);
        this.importance = importance; this.sound = sound; this.vibration = vibration; this.bypassDnd = bypassDnd;
        this.fullScreenIntent = fullScreenIntent; this.observedAt = observedAt;
    }

    /** Android reported the channel's settings (not only its id). */
    boolean known() { return importance != UNKNOWN_IMPORTANCE && sound != null && vibration != null; }
    /** Android reported a channel that can neither make sound nor vibrate, or one that is blocked/min importance. */
    boolean silent() {
        if (importance != UNKNOWN_IMPORTANCE && importance <= NotificationManager.IMPORTANCE_MIN) return true;
        return known() && !sound && !vibration;
    }
    /** "DoorDash offer channel "Orders": importance high, sound on, vibration on" or an honest unknown. */
    String summary() {
        String name = channelName.isEmpty() ? channelId.isEmpty() ? "unknown" : "\"" + channelId + "\"" : "\"" + channelName + "\"";
        if (!known()) return "DoorDash offer channel " + name + ": settings not reported by Android";
        return "DoorDash offer channel " + name + ": importance " + importanceName(importance) + ", sound " + (sound ? "on" : "off") +
                ", vibration " + (vibration ? "on" : "off") + (Boolean.TRUE.equals(bypassDnd) ? ", overrides Do Not Disturb" : "") +
                (fullScreenIntent ? ", full-screen alert attached" : "");
    }
    static String importanceName(int importance) {
        switch (importance) {
            case NotificationManager.IMPORTANCE_NONE: return "blocked";
            case NotificationManager.IMPORTANCE_MIN: return "min";
            case NotificationManager.IMPORTANCE_LOW: return "low (silent)";
            case NotificationManager.IMPORTANCE_DEFAULT: return "default";
            case NotificationManager.IMPORTANCE_HIGH: return "high";
            case NotificationManager.IMPORTANCE_MAX: return "urgent";
            default: return importance == UNKNOWN_IMPORTANCE ? "unknown" : Integer.toString(importance);
        }
    }

    /** Reads the channel from the listener ranking (API 26+) and records it. Never throws. */
    static void observe(Context context, NotificationListenerService.RankingMap ranking, String key, Notification n, long wallNow) {
        NotificationChannel channel = null;
        try {
            NotificationListenerService.Ranking rank = new NotificationListenerService.Ranking();
            if (ranking != null && key != null && ranking.getRanking(key, rank)) channel = rank.getChannel();
        } catch (RuntimeException error) { channel = null; }
        record(context, n == null ? null : n.getChannelId(), channel, n, wallNow);
    }

    /** Persists the facts; skips the write when nothing changed and the last write is under a minute old. Never throws. */
    static void record(Context context, String channelId, NotificationChannel channel, Notification n, long wallNow) {
        try {
            String id = channel != null && channel.getId() != null ? channel.getId() : channelId;
            if (id == null || id.trim().isEmpty()) return;
            DoorDashChannelFacts next = new DoorDashChannelFacts(id, channel == null || channel.getName() == null ? "" : channel.getName().toString(),
                    channel == null ? UNKNOWN_IMPORTANCE : channel.getImportance(), channel == null ? null : channel.getSound() != null,
                    channel == null ? null : channel.shouldVibrate(), channel == null ? null : channel.canBypassDnd(),
                    n != null && n.fullScreenIntent != null, wallNow);
            DoorDashChannelFacts old = load(context);
            // Android did not report the channel this time: keep the settings it last reported for the same channel.
            if (channel == null && old != null && old.known() && old.channelId.equals(next.channelId)) return;
            if (old != null && old.sameSettings(next) && wallNow - old.observedAt >= 0 && wallNow - old.observedAt < REFRESH_MS) return;
            SharedPreferences.Editor e = prefs(context).edit().clear().putString("id", next.channelId).putString("name", next.channelName)
                    .putInt("importance", next.importance).putBoolean("full_screen", next.fullScreenIntent).putLong("at", wallNow);
            if (next.sound != null) e.putBoolean("sound", next.sound);
            if (next.vibration != null) e.putBoolean("vibration", next.vibration);
            if (next.bypassDnd != null) e.putBoolean("bypass_dnd", next.bypassDnd);
            e.apply();
        } catch (RuntimeException error) { DiagnosticLog.log(context, "notification-channel", "channel facts not recorded: " + error.getClass().getSimpleName()); }
    }

    /** The last observed facts, or null when no DoorDash offer notification has been seen yet. */
    static DoorDashChannelFacts load(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains("id")) return null;
        return new DoorDashChannelFacts(p.getString("id", ""), p.getString("name", ""), p.getInt("importance", UNKNOWN_IMPORTANCE),
                p.contains("sound") ? p.getBoolean("sound", false) : null, p.contains("vibration") ? p.getBoolean("vibration", false) : null,
                p.contains("bypass_dnd") ? p.getBoolean("bypass_dnd", false) : null, p.getBoolean("full_screen", false), p.getLong("at", 0));
    }
    static void clear(Context context) { prefs(context).edit().clear().apply(); }

    private boolean sameSettings(DoorDashChannelFacts o) {
        return channelId.equals(o.channelId) && channelName.equals(o.channelName) && importance == o.importance && java.util.Objects.equals(sound, o.sound) &&
                java.util.Objects.equals(vibration, o.vibration) && java.util.Objects.equals(bypassDnd, o.bypassDnd) && fullScreenIntent == o.fullScreenIntent;
    }
    private static String clip(String s) { String t = s.replaceAll("[\\p{Cc}\\p{Cf}]+", " ").trim(); return t.length() > 60 ? t.substring(0, 60).trim() : t; }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
