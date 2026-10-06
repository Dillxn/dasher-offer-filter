package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Local-only numeric configuration and manually entered AR history. No screen reader or report/network path. */
final class EarningsStore {
    static final String SETTINGS_PREFS = "earnings-settings";
    static final String HISTORY_PREFS = "earnings-history";
    static final String CONFIG = "configuration-v1";
    static final String AR_HISTORY = "acceptance-rate-v1";
    static final String ADJUSTMENT = "adjustment-v1";
    private static final String GENERATION = "generation";
    private static final int VERSION = 1;
    static final long RECOMMENDATION_VALID_MS = 10_000;
    private static final Object LOCK = new Object();
    private static String fallbackClockMarker;
    private static long fallbackClockStart;
    private static int fallbackBoot = -1;

    static final class ConfigSnapshot {
        final EarningsModel.Config config;
        final long generation;
        ConfigSnapshot(EarningsModel.Config config, long generation) {
            this.config = config;
            this.generation = generation;
        }
    }

    static ConfigSnapshot configSnapshot(Context context) {
        synchronized (LOCK) { return new ConfigSnapshot(config(context), generation(history(context))); }
    }

    /** The apply callback checks this after any reentrant metadata/platform query and before its rule save. */
    static boolean isApplyingCurrent(Context context, EarningsModel.Recommendation recommendation) {
        synchronized (LOCK) {
            return recommendation != null && config(context).key().equals(recommendation.configKey)
                    && generation(history(context)) == increment(recommendation.generation);
        }
    }

    static EarningsModel.Config config(Context context) {
        synchronized (LOCK) { return decodeConfig(string(settings(context), CONFIG, null)); }
    }

    /** Explicit Settings save. It preserves history and the last cooldown, but invalidates outstanding results. */
    static boolean saveConfig(Context context, EarningsModel.Config config) {
        if (config == null || !Consent.accepted(context)) return false;
        synchronized (LOCK) {
            // Invalidate first: a failed settings write can safely block an old recommendation.
            if (!bumpGeneration(history(context))) return false;
            return settings(context).edit().putString(CONFIG, encodeConfig(config)).commit();
        }
    }

    /** Dialog confirmation may save only the exact configuration/history generation it originally displayed. */
    static boolean saveConfigIfCurrent(Context context, EarningsModel.Config desired, ConfigSnapshot expected) {
        if (desired == null || expected == null || !Consent.accepted(context)) return false;
        synchronized (LOCK) {
            if (!config(context).key().equals(expected.config.key())
                    || generation(history(context)) != expected.generation) return false;
            return saveConfig(context, desired);
        }
    }

    /** A manual minimum edit takes precedence over the optional optimizer, preserving entered preferences. */
    static boolean disableForManualChange(Context context) {
        synchronized (LOCK) {
            EarningsModel.Config current = config(context);
            return saveConfig(context, new EarningsModel.Config(false, current.vehicleCostCentsPerMile,
                    current.floorPercent, current.ceilingPercent));
        }
    }

    static boolean recordAr(Context context, int percent, long at, long now) {
        if (!Consent.accepted(context) || now < 0 || at < 0 || at > now
                || now - at >= EarningsModel.AR_RETAIN_MS || percent < 0 || percent > 100) return false;
        synchronized (LOCK) {
            SharedPreferences prefs = history(context);
            List<EarningsModel.Snapshot> rows = new ArrayList<>(EarningsModel.retainedAr(
                    decodeAr(string(prefs, AR_HISTORY, null)), now));
            // Correcting one dated entry replaces it rather than inflating the history count.
            rows.removeIf(row -> row.at == at);
            rows.add(new EarningsModel.Snapshot(percent, at));
            return prefs.edit().putString(AR_HISTORY, encodeAr(EarningsModel.retainedAr(rows, now)))
                    .putLong(GENERATION, nextGeneration(prefs)).commit();
        }
    }

    static boolean recordAr(Context context, int percent, long now) { return recordAr(context, percent, now, now); }

    /** Pruning happens during use. Merely reading AR never starts an observation or an adjustment. */
    static List<EarningsModel.Snapshot> arHistory(Context context, long now) {
        synchronized (LOCK) {
            SharedPreferences prefs = history(context);
            String raw = string(prefs, AR_HISTORY, null);
            List<EarningsModel.Snapshot> rows = EarningsModel.retainedAr(decodeAr(raw), now);
            String pruned = encodeAr(rows);
            if (raw != null && !raw.equals(pruned)) {
                prefs.edit().putString(AR_HISTORY, pruned).putLong(GENERATION, nextGeneration(prefs)).commit();
            }
            return rows;
        }
    }

    static EarningsModel.Recommendation recommendation(Context context, List<QualifyingWait.Sample> samples,
                                                       FilterSettings rules, long now) {
        synchronized (LOCK) {
            List<EarningsModel.Snapshot> ar = arHistory(context, now);
            SharedPreferences prefs = history(context);
            String rawAdjustment = string(prefs, ADJUSTMENT, null);
            EarningsModel.Adjustment adjustment = decodeAdjustment(rawAdjustment);
            if (monotonicCooldown(context, adjustment, rawAdjustment)) {
                // Preserve the actual marker on disk. This temporary refusal only prevents a candidate; it
                // neither restores a timer nor changes any action authority after process/clock uncertainty.
                adjustment = new EarningsModel.Adjustment(Long.MAX_VALUE, adjustment.latestArrivalAt,
                        adjustment.settingsKey, adjustment.configKey);
            }
            return EarningsModel.recommend(samples, rules, config(context), ar, adjustment, now, generation(prefs));
        }
    }

    /**
     * Only the caller can verify current positively observed waiting, unchanged rules, and absence of all offer
     * authority. Lock order: waiting-store lock, this lock, then the caller's conditional FilterStore save.
     * Persist suppression first: failure/process death may lose an adjustment, never its cooldown.
     */
    static boolean applyIfCurrent(Context context, EarningsModel.Recommendation recommendation, long now,
                                  BooleanSupplier apply) {
        if (recommendation == null || apply == null || !Consent.accepted(context)) return false;
        synchronized (LOCK) {
            SharedPreferences prefs = history(context);
            EarningsModel.Config config = config(context);
            String rawPrevious = string(prefs, ADJUSTMENT, null);
            EarningsModel.Adjustment previous = decodeAdjustment(rawPrevious);
            if (!recommendation.canAdjust() || !config.enabled || config.vehicleCostCentsPerMile == null
                    || !config.key().equals(recommendation.configKey)
                    || generation(prefs) != recommendation.generation
                    || now < recommendation.evaluatedAt || now - recommendation.evaluatedAt > RECOMMENDATION_VALID_MS
                    || recommendation.suggestedPercent < config.floorPercent
                    || recommendation.suggestedPercent > config.ceilingPercent
                    || Math.abs(recommendation.suggestedPercent - recommendation.currentPercent) > EarningsModel.MAX_STEP_PERCENT
                    || monotonicCooldown(context, previous, rawPrevious)
                    || previous.at > 0 && (now < previous.at || now - previous.at < EarningsModel.COOLDOWN_MS)) return false;
            EarningsModel.Adjustment marker = new EarningsModel.Adjustment(now, recommendation.latestArrivalAt,
                    recommendation.suggestedSettingsKey, recommendation.configKey, SystemClock.elapsedRealtime(), boot(context));
            String encoded = encodeAdjustment(marker);
            if (!prefs.edit().putString(ADJUSTMENT, encoded).putLong(GENERATION, nextGeneration(prefs)).commit()) return false;
            fallbackClockMarker = encoded;
            fallbackClockStart = marker.elapsedAt;
            fallbackBoot = marker.boot;
            return apply.getAsBoolean();
        }
    }

    static boolean markAdjusted(Context context, EarningsModel.Recommendation recommendation, long now) {
        return applyIfCurrent(context, recommendation, now, () -> true);
    }

    /** Clear observation/adjustment records only. Saved opt-in, range, and entered costs remain preferences. */
    static void clearHistory(Context context) {
        synchronized (LOCK) {
            SharedPreferences prefs = history(context);
            prefs.edit().remove(AR_HISTORY).remove(ADJUSTMENT).putLong(GENERATION, nextGeneration(prefs)).commit();
            fallbackClockMarker = null;
        }
    }

    static String encodeConfig(EarningsModel.Config config) {
        try {
            return new JSONObject().put("version", VERSION).put("enabled", config.enabled)
                    .put("costCentsPerMile", config.vehicleCostCentsPerMile == null ? JSONObject.NULL : config.vehicleCostCentsPerMile)
                    .put("floorPercent", config.floorPercent).put("ceilingPercent", config.ceilingPercent).toString();
        } catch (JSONException impossibleValidatedNumber) { throw new IllegalArgumentException(impossibleValidatedNumber); }
    }

    static EarningsModel.Config decodeConfig(String raw) {
        if (raw == null || raw.length() > 2048) return EarningsModel.Config.defaults();
        try {
            JSONObject json = new JSONObject(raw);
            if (whole(json, "version") != VERSION || !(json.get("enabled") instanceof Boolean)
                    || !json.has("costCentsPerMile")) return EarningsModel.Config.defaults();
            Double cost = json.isNull("costCentsPerMile") ? null : decimal(json, "costCentsPerMile");
            return new EarningsModel.Config(json.getBoolean("enabled"), cost,
                    integer(json, "floorPercent"), integer(json, "ceilingPercent"));
        } catch (JSONException | IllegalArgumentException malformed) { return EarningsModel.Config.defaults(); }
    }

    static String encodeAr(List<EarningsModel.Snapshot> rows) {
        JSONArray values = new JSONArray();
        try {
            for (EarningsModel.Snapshot row : rows) values.put(new JSONObject().put("at", row.at).put("percent", row.percent));
            return new JSONObject().put("version", VERSION).put("snapshots", values).toString();
        } catch (JSONException impossibleValidatedNumber) { return "{}"; }
    }

    static List<EarningsModel.Snapshot> decodeAr(String raw) {
        List<EarningsModel.Snapshot> rows = new ArrayList<>();
        if (raw == null || raw.length() > 30_000) return rows;
        try {
            JSONObject json = new JSONObject(raw);
            if (whole(json, "version") != VERSION) return rows;
            JSONArray values = json.getJSONArray("snapshots");
            // A corrupt oversized payload is not permission for unbounded retained state.
            for (int i = Math.max(0, values.length() - EarningsModel.MAX_AR_SNAPSHOTS); i < values.length(); i++) {
                try {
                    JSONObject row = values.getJSONObject(i);
                    rows.add(new EarningsModel.Snapshot(integer(row, "percent"), whole(row, "at")));
                } catch (JSONException | IllegalArgumentException malformedRow) { /* Skip this record only. */ }
            }
        } catch (JSONException malformed) { /* No invented manual report. */ }
        return rows;
    }

    private static String encodeAdjustment(EarningsModel.Adjustment adjustment) {
        try {
            return new JSONObject().put("version", VERSION).put("at", adjustment.at)
                    .put("latestArrivalAt", adjustment.latestArrivalAt).put("settingsKey", adjustment.settingsKey)
                    .put("configKey", adjustment.configKey).put("elapsedAt", adjustment.elapsedAt)
                    .put("boot", adjustment.boot).toString();
        } catch (JSONException impossibleValidatedNumber) { return "{}"; }
    }

    static EarningsModel.Adjustment decodeAdjustment(String raw) {
        if (raw == null) return EarningsModel.Adjustment.NONE;
        try {
            if (raw.length() > 4096) throw new JSONException("Oversized adjustment");
            JSONObject json = new JSONObject(raw);
            if (whole(json, "version") != VERSION) throw new JSONException("Unknown adjustment version");
            long at = whole(json, "at");
            long arrival = whole(json, "latestArrivalAt");
            if (at <= 0 || arrival < 0 || arrival > at || !(json.get("settingsKey") instanceof String)
                    || !(json.get("configKey") instanceof String)) throw new JSONException("Invalid adjustment");
            long elapsed = json.has("elapsedAt") ? whole(json, "elapsedAt") : -1;
            int boot = json.has("boot") ? integer(json, "boot") : -1;
            if (elapsed < -1 || boot < -1) throw new JSONException("Invalid monotonic clock");
            return new EarningsModel.Adjustment(at, arrival, json.getString("settingsKey"), json.getString("configKey"), elapsed, boot);
        } catch (JSONException malformed) {
            // An unreadable marker must not reset a cooldown and accidentally authorize another adjustment.
            // Clear history is the explicit recovery path; no live action authority is restored.
            return new EarningsModel.Adjustment(Long.MAX_VALUE, 0, "", "");
        }
    }

    private static int integer(JSONObject json, String key) throws JSONException {
        long value = whole(json, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new JSONException("Out of range");
        return (int) value;
    }

    private static long whole(JSONObject json, String key) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new JSONException("Expected whole number");
        return ((Number) value).longValue();
    }

    private static double decimal(JSONObject json, String key) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue())) throw new JSONException("Expected finite number");
        return ((Number) value).doubleValue();
    }

    private static String string(SharedPreferences prefs, String key, String fallback) {
        try { return prefs.getString(key, fallback); }
        catch (ClassCastException corruptType) { return "invalid"; }
    }

    private static long generation(SharedPreferences prefs) {
        try { return prefs.getLong(GENERATION, 0); }
        catch (ClassCastException corruptType) { return Long.MIN_VALUE; }
    }

    private static long nextGeneration(SharedPreferences prefs) { return increment(generation(prefs)); }
    private static long increment(long current) { return current == Long.MAX_VALUE ? 1 : current + 1; }
    private static boolean bumpGeneration(SharedPreferences prefs) {
        return prefs.edit().putLong(GENERATION, nextGeneration(prefs)).commit();
    }

    private static boolean monotonicCooldown(Context context, EarningsModel.Adjustment marker, String raw) {
        if (marker.at <= 0) return false;
        long now = SystemClock.elapsedRealtime();
        int boot = boot(context);
        if (marker.boot >= 0 && boot == marker.boot && marker.elapsedAt >= 0 && now >= marker.elapsedAt) {
            return now - marker.elapsedAt < EarningsModel.COOLDOWN_MS;
        }
        // Unknown/different boot, legacy marker, or elapsed-clock rollback: require a full real-time cooldown
        // after this process first sees the marker. Forward wall-clock changes cannot shorten this hold.
        if (raw == null || !raw.equals(fallbackClockMarker) || now < fallbackClockStart || fallbackBoot != boot) {
            fallbackClockMarker = raw;
            fallbackClockStart = now;
            fallbackBoot = boot;
        }
        return now - fallbackClockStart < EarningsModel.COOLDOWN_MS;
    }

    private static int boot(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
        catch (RuntimeException unavailable) { return -1; }
    }

    /** Process-restart test seam: forgetting a clock can only add conservative suppression. */
    static void forgetClockCache() {
        synchronized (LOCK) { fallbackClockMarker = null; fallbackClockStart = 0; fallbackBoot = -1; }
    }

    private static SharedPreferences settings(Context context) { return context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE); }
    private static SharedPreferences history(Context context) { return context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE); }
    private EarningsStore() {}
}
