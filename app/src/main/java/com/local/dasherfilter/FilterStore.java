package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;

/**
 * Saved rules in private SharedPreferences. 0.4.x per-minute rates migrate exactly to per-hour on first load; "minute"
 * is still written so a downgrade keeps any remaining per-minute rule.
 */
final class FilterStore {
    private static final String PREFS = "offer_filter";

    static FilterSettings load(Context context) {
        SharedPreferences p = prefs(context);
        int minute = p.getInt("minute", 0), hour = p.getInt("hour", 0);
        if (!p.contains("hour") && minute > 0 && (long) minute * 60 <= Integer.MAX_VALUE) { hour = minute * 60; minute = 0; }
        String stores = p.getString("avoid_stores", "");
        return new FilterSettings(p.getBoolean("enabled", false),
                p.getInt("flat", 0), p.getInt("mile", 0), minute, hour,
                p.getInt("stop", 0), p.getInt("max_stops", 0), p.getInt("max_miles", 0),
                stores == null || stores.isEmpty() ? null : Arrays.asList(stores.split("\n")),
                p.getBoolean("rising_offers", false), p.getInt("last_accepted", 0), p.getLong("last_accepted_at", 0L),
                p.getInt("max_declines_hour", 0));
    }

    /** Persists every rule field. The accepted baseline is owned by recordAccepted and is not overwritten here. */
    static void save(Context context, FilterSettings settings) {
        prefs(context).edit()
                .putBoolean("enabled", settings.enabled)
                .putInt("flat", settings.flatCents)
                .putInt("mile", settings.perMileCents)
                .putInt("minute", settings.perMinuteCents)
                .putInt("hour", settings.perHourCents)
                .putInt("stop", settings.extraStopCents)
                .putInt("max_stops", settings.maxStops)
                .putInt("max_miles", settings.maxMilesHundredths)
                .putString("avoid_stores", String.join("\n", settings.avoidStores))
                .putBoolean("rising_offers", settings.risingOffers)
                .putInt("max_declines_hour", settings.maxDeclinesPerHour)
                .apply();
    }

    /** An observed standalone accept (or 0 to reset). The wall-clock timestamp lets the baseline expire after 8 hours. */
    static void recordAccepted(Context context, int cents) {
        prefs(context).edit()
                .putInt("last_accepted", cents)
                .putLong("last_accepted_at", cents > 0 ? System.currentTimeMillis() : 0L).apply();
    }

    static void recordDoorDashOfferChannel(Context context, String channelId) {
        if (channelId == null || channelId.trim().isEmpty()) return;
        prefs(context).edit().putString("doordash_offer_channel", channelId).apply();
    }

    static String doorDashOfferChannel(Context context) {
        return prefs(context).getString("doordash_offer_channel", "");
    }

    static void setLastStatus(Context context, String status) {
        String timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date());
        prefs(context).edit().putString("last_status", timestamp + "\n" + status).apply();
    }

    static String lastStatus(Context context) {
        return prefs(context).getString("last_status", "No offer evaluated yet.");
    }

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private FilterStore() {}
}
