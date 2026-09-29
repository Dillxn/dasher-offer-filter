package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;

/** Accepted route context, not a claim to know current GPS or remaining driving time. */
final class ActiveRouteStore {
    private static final String PREFS = "active_route";
    private static final long MAX_AGE_MS = 10_800_000L;
    static void save(Context context, OfferSnapshot route) {
        if (route == null) { clear(context); return; }
        SharedPreferences.Editor e = prefs(context).edit().clear().putLong("at", System.currentTimeMillis());
        if (route.payCents != null) e.putInt("pay", route.payCents);
        if (route.miles != null) e.putLong("miles", Double.doubleToLongBits(route.miles));
        if (route.minutes != null) e.putInt("minutes", route.minutes);
        if (route.stops != null) e.putInt("stops", route.stops);
        e.apply();
    }
    static OfferSnapshot load(Context context) {
        SharedPreferences p = prefs(context);
        long at = p.getLong("at", 0), age = System.currentTimeMillis() - at;
        if (at == 0) return null;
        if (age < 0 || age > MAX_AGE_MS) { clear(context); return null; }
        return new OfferSnapshot(p.contains("pay") ? p.getInt("pay", 0) : null,
                p.contains("miles") ? Double.longBitsToDouble(p.getLong("miles", 0)) : null,
                p.contains("minutes") ? p.getInt("minutes", 0) : null,
                p.contains("stops") ? p.getInt("stops", 0) : null);
    }
    static void invalidateTravel(Context context) {
        SharedPreferences p = prefs(context);
        if (p.contains("at")) { p.edit().remove("miles").remove("minutes").remove("stops").apply(); DiagnosticLog.log(context, "route", "delivery progress observed; old travel/stop estimates invalidated"); }
    }
    static void clear(Context context) { SharedPreferences p = prefs(context); if (p.contains("at")) p.edit().clear().apply(); }
    static boolean isIdleScreen(List<String> labels) { return OfferEvidence.isIdle(labels); }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private ActiveRouteStore() {}
}
