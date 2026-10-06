package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Local-only preferences for the offers-versus-profit optimizer.
 *
 * <p>No offer history lives here. The optimizer reuses the bounded numeric waiting history and decision history that
 * the app already keeps. A zero vehicle cost deliberately means "optimize gross pay" rather than inventing a cost.
 */
final class EarningsPreferences {
    private static final String PREFS = "earnings_optimizer";
    private static final String TRADEOFF = "tradeoff_percent";
    private static final String AUTO_TUNE = "auto_tune";
    private static final String VEHICLE_COST = "vehicle_cost_cents_per_mile";
    private static final String LAST_TUNE = "last_tune_at";

    static int tradeoff(Context context, int currentScale) {
        SharedPreferences prefs = prefs(context);
        return prefs.contains(TRADEOFF) ? clamp(prefs.getInt(TRADEOFF, 50)) : inferredTradeoff(currentScale);
    }

    static boolean chosen(Context context) {
        return prefs(context).contains(TRADEOFF);
    }

    static void setTradeoff(Context context, int percent) {
        prefs(context).edit().putInt(TRADEOFF, clamp(percent)).putLong(LAST_TUNE, 0).apply();
    }

    static void ensureTradeoff(Context context, int currentScale) {
        if (!chosen(context)) setTradeoff(context, inferredTradeoff(currentScale));
    }

    static boolean autoTune(Context context) {
        return prefs(context).getBoolean(AUTO_TUNE, false);
    }

    static void setAutoTune(Context context, boolean on, int currentScale) {
        SharedPreferences.Editor edit = prefs(context).edit().putBoolean(AUTO_TUNE, on).putLong(LAST_TUNE, 0);
        if (on && !chosen(context)) edit.putInt(TRADEOFF, inferredTradeoff(currentScale));
        edit.apply();
    }

    static int vehicleCostCentsPerMile(Context context) {
        return Math.max(0, Math.min(500, prefs(context).getInt(VEHICLE_COST, 0)));
    }

    static void setVehicleCostCentsPerMile(Context context, int cents) {
        prefs(context).edit().putInt(VEHICLE_COST, Math.max(0, Math.min(500, cents)))
                .putLong(LAST_TUNE, 0).apply();
    }

    static long lastTuneAt(Context context) {
        return Math.max(0, prefs(context).getLong(LAST_TUNE, 0));
    }

    static void markTuned(Context context, long at) {
        prefs(context).edit().putLong(LAST_TUNE, Math.max(0, at)).apply();
    }

    /**
     * Sparse-history mapping: center means unchanged 100%; the more-offers end is 50%, and the more-profit end 200%.
     * Once enough local evidence exists this is only the fallback/preview; the optimizer chooses the actual scale.
     */
    static int fallbackScale(int tradeoff) {
        int p = clamp(tradeoff);
        return p <= 50 ? 50 + p : 100 + (p - 50) * 2;
    }

    /** Inverse of {@link #fallbackScale}, used to make an existing install's current scale the initial slider position. */
    static int inferredTradeoff(int scale) {
        int s = Math.max(1, Math.min(200, scale));
        return clamp(s <= 100 ? s - 50 : 50 + Math.round((s - 100) / 2f));
    }

    private static int clamp(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private EarningsPreferences() {}
}
