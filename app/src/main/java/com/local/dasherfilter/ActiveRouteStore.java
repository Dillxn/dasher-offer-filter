package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Locale;

/** Persists the currently accepted route so add-on offers can be judged in context. */
final class ActiveRouteStore {
    private static final String PREFS = "active_route";
    private static final long MAX_AGE_MS = 3L * 60 * 60 * 1000;

    static void save(Context context, OfferSnapshot route) {
        if (route == null) {
            clear(context);
            return;
        }
        SharedPreferences.Editor e = prefs(context).edit().clear()
                .putLong("at", System.currentTimeMillis());
        putInt(e, "pay", route.payCents);
        putDouble(e, "miles", route.miles);
        putInt(e, "minutes", route.minutes);
        putInt(e, "stops", route.stops);
        e.apply();
    }

    static OfferSnapshot load(Context context) {
        SharedPreferences p = prefs(context);
        long at = p.getLong("at", 0);
        if (at == 0 || System.currentTimeMillis() - at > MAX_AGE_MS) {
            clear(context);
            return null;
        }
        return new OfferSnapshot(getInt(p, "pay"), getDouble(p, "miles"),
                getInt(p, "minutes"), getInt(p, "stops"));
    }

    static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }

    static boolean isIdleScreen(List<String> labels) {
        for (String label : labels) {
            if (label == null) continue;
            String value = label.trim().toLowerCase(Locale.US);
            if (value.equals("looking for offers") || value.equals("searching for orders") ||
                    value.equals("looking for orders") || value.equals("dash now") ||
                    value.equals("start dashing") || value.equals("dash paused") ||
                    value.equals("resume dash")) return true;
        }
        return false;
    }

    private static void putInt(SharedPreferences.Editor e, String key, Integer value) {
        if (value != null) e.putInt(key, value);
    }

    private static Integer getInt(SharedPreferences p, String key) {
        return p.contains(key) ? p.getInt(key, 0) : null;
    }

    private static void putDouble(SharedPreferences.Editor e, String key, Double value) {
        if (value != null) e.putLong(key, Double.doubleToLongBits(value));
    }

    private static Double getDouble(SharedPreferences p, String key) {
        return p.contains(key) ? Double.longBitsToDouble(p.getLong(key, 0)) : null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private ActiveRouteStore() {}
}
