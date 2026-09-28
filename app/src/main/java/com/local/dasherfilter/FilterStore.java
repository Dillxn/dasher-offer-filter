package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.DateFormat;
import java.util.Date;

final class FilterStore {
    private static final String PREFS = "offer_filter";

    static FilterSettings load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new FilterSettings(p.getBoolean("enabled", false),
                p.getInt("flat", 0), p.getInt("mile", 0),
                p.getInt("minute", 0), p.getInt("stop", 0), p.getInt("max_stops", 0),
                p.getBoolean("rising_offers", false), p.getInt("last_accepted", 0));
    }

    static void save(Context context, FilterSettings settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", settings.enabled)
                .putInt("flat", settings.flatCents)
                .putInt("mile", settings.perMileCents)
                .putInt("minute", settings.perMinuteCents)
                .putInt("stop", settings.extraStopCents)
                .putInt("max_stops", settings.maxStops)
                .putBoolean("rising_offers", settings.risingOffers)
                .apply();
    }

    static void recordAccepted(Context context, int cents) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("last_accepted", cents).apply();
    }

    static void setLastStatus(Context context, String status) {
        String timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("last_status", timestamp + "\n" + status).apply();
    }

    static String lastStatus(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("last_status", "No offer evaluated yet.");
    }
}
