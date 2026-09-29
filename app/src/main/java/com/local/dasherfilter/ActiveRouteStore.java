package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;

/** Accepted route context, not a claim to know current GPS or remaining driving time. */
final class ActiveRouteStore {
    private static final String PREFS = "active_route";
    private static final long MAX_AGE_MS = 10_800_000L;
    private static final String SAVED_AT = "at";
    private static final String PAY = "pay";
    private static final String MILES = "miles";
    private static final String MINUTES = "minutes";
    private static final String STOPS = "stops";

    static void save(Context context, OfferSnapshot route) {
        if (route == null) {
            clear(context);
            return;
        }
        SharedPreferences.Editor editor = prefs(context).edit().clear().putLong(SAVED_AT, System.currentTimeMillis());
        if (route.payCents != null) editor.putInt(PAY, route.payCents);
        if (route.miles != null) editor.putLong(MILES, Double.doubleToLongBits(route.miles));
        if (route.minutes != null) editor.putInt(MINUTES, route.minutes);
        if (route.stops != null) editor.putInt(STOPS, route.stops);
        editor.apply();
    }

    /** The saved route, or null when none was saved, it is over three hours old, or the clock moved backwards. */
    static OfferSnapshot load(Context context) {
        SharedPreferences prefs = prefs(context);
        long savedAt = prefs.getLong(SAVED_AT, 0);
        if (savedAt == 0) return null;
        long age = System.currentTimeMillis() - savedAt;
        if (age < 0 || age > MAX_AGE_MS) {
            clear(context);
            return null;
        }
        return new OfferSnapshot(
                prefs.contains(PAY) ? prefs.getInt(PAY, 0) : null,
                prefs.contains(MILES) ? Double.longBitsToDouble(prefs.getLong(MILES, 0)) : null,
                prefs.contains(MINUTES) ? prefs.getInt(MINUTES, 0) : null,
                prefs.contains(STOPS) ? prefs.getInt(STOPS, 0) : null);
    }

    /** Pickup or completion progress makes the stored travel and stop estimates stale; pay is kept. */
    static void invalidateTravel(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.contains(SAVED_AT)) return;
        prefs.edit().remove(MILES).remove(MINUTES).remove(STOPS).apply();
        DiagnosticLog.log(context, "route", "delivery progress observed; old travel/stop estimates invalidated");
    }

    static void clear(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(SAVED_AT)) prefs.edit().clear().apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private ActiveRouteStore() {}
}
