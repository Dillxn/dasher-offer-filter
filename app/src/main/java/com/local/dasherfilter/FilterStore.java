package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.DateFormat;
import java.util.Date;

/** Saved rules, the standalone payout baseline, and the last user-visible status line. */
final class FilterStore {
    private static final String PREFS = "offer_filter";
    private static final String ENABLED = "enabled";
    private static final String FLAT = "flat";
    private static final String PER_MILE = "mile";
    private static final String PER_MINUTE = "minute";
    private static final String EXTRA_STOP = "stop";
    private static final String MAX_STOPS = "max_stops";
    private static final String RISING_OFFERS = "rising_offers";
    private static final String LAST_ACCEPTED = "last_accepted";
    private static final String DOORDASH_OFFER_CHANNEL = "doordash_offer_channel";
    private static final String LAST_STATUS = "last_status";
    private static final String REPORT_EMAIL = "report_email";
    private static final String SILENCE_WHILE_DECLINING = "silence_while_declining";

    static FilterSettings load(Context context) {
        SharedPreferences prefs = prefs(context);
        return new FilterSettings(prefs.getBoolean(ENABLED, false),
                prefs.getInt(FLAT, 0), prefs.getInt(PER_MILE, 0),
                prefs.getInt(PER_MINUTE, 0), prefs.getInt(EXTRA_STOP, 0), prefs.getInt(MAX_STOPS, 0),
                prefs.getBoolean(RISING_OFFERS, false), prefs.getInt(LAST_ACCEPTED, 0));
    }

    /** Saves rules. The accepted-payout baseline is owned by {@link #recordAccepted} and is not overwritten. */
    static void save(Context context, FilterSettings settings) {
        prefs(context).edit()
                .putBoolean(ENABLED, settings.enabled)
                .putInt(FLAT, settings.flatCents)
                .putInt(PER_MILE, settings.perMileCents)
                .putInt(PER_MINUTE, settings.perMinuteCents)
                .putInt(EXTRA_STOP, settings.extraStopCents)
                .putInt(MAX_STOPS, settings.maxStops)
                .putBoolean(RISING_OFFERS, settings.risingOffers)
                .apply();
    }

    static void recordAccepted(Context context, int cents) {
        prefs(context).edit().putInt(LAST_ACCEPTED, cents).apply();
    }

    static void recordDoorDashOfferChannel(Context context, String channelId) {
        if (channelId == null || channelId.trim().isEmpty()) return;
        SharedPreferences prefs = prefs(context);
        if (!channelId.equals(prefs.getString(DOORDASH_OFFER_CHANNEL, ""))) {
            prefs.edit().putString(DOORDASH_OFFER_CHANNEL, channelId).apply();
        }
    }

    static String doorDashOfferChannel(Context context) {
        return prefs(context).getString(DOORDASH_OFFER_CHANNEL, "");
    }

    static void setLastStatus(Context context, String status) {
        String timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date());
        prefs(context).edit().putString(LAST_STATUS, timestamp + "\n" + status).apply();
    }

    static String lastStatus(Context context) {
        return prefs(context).getString(LAST_STATUS, "No offer evaluated yet.");
    }

    /** The user's own address for emailing diagnostics reports; empty until they enter it. */
    static String reportEmail(Context context) {
        return prefs(context).getString(REPORT_EMAIL, "");
    }

    static void setReportEmail(Context context, String address) {
        prefs(context).edit().putString(REPORT_EMAIL, address).apply();
    }

    /** Whether Dasher's own offer ring is turned down while a filtered offer is declined. On unless turned off. */
    static boolean silenceWhileDeclining(Context context) {
        return prefs(context).getBoolean(SILENCE_WHILE_DECLINING, true);
    }

    static void setSilenceWhileDeclining(Context context, boolean on) {
        prefs(context).edit().putBoolean(SILENCE_WHILE_DECLINING, on).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private FilterStore() {}
}
