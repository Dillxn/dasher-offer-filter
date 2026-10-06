package com.local.dasherfilter;

import android.content.Context;
import android.os.SystemClock;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Private numeric waiting history. No labels, coordinates, app identities, or network/report path. */
final class QualifyingWaitStore {
    private static final String PREFS = "qualifying-wait";
    private static final String HISTORY = "numeric-history-v1";
    private static final long SAVE_EVERY_MS = 30_000L;
    private static final Object LOCK = new Object();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    private static QualifyingWait history;
    private static long lastSave;
    private static long savedRevision = -1;
    private static long generation;
    private static long observationRevision;

    /** A detached numeric history with process-only waiting identity; never a restored waiting lease. */
    static final class Snapshot {
        final List<QualifyingWait.Sample> samples;
        final boolean observingWaiting;
        final long wallNow;
        private final long generation, observationRevision, historyRevision;
        private Snapshot(List<QualifyingWait.Sample> samples, boolean observingWaiting, long wallNow,
                         long generation, long observationRevision, long historyRevision) {
            this.samples = samples;
            this.observingWaiting = observingWaiting;
            this.wallNow = wallNow;
            this.generation = generation;
            this.observationRevision = observationRevision;
            this.historyRevision = historyRevision;
        }
    }

    static List<QualifyingWait.Sample> snapshot(Context context) { return capture(context).samples; }

    static Snapshot capture(Context context) {
        synchronized (LOCK) {
            long wall = wallClock.getAsLong();
            QualifyingWait model = loaded(context);
            List<QualifyingWait.Sample> samples = model.snapshot(wall);
            return new Snapshot(samples, Consent.accepted(context)
                    && model.observingWaiting(SystemClock.elapsedRealtime(), wall), wall,
                    generation, observationRevision, model.revision());
        }
    }

    /** Clearing/stopping or replacing any observation must win over a recommendation already being evaluated. */
    static boolean withCurrentWaiting(Context context, Snapshot expected, BooleanSupplier action) {
        synchronized (LOCK) { return isCurrentWaiting(context, expected) && action.getAsBoolean(); }
    }

    static boolean isCurrentWaiting(Context context, Snapshot expected) {
        synchronized (LOCK) {
            return expected != null && expected.observingWaiting && Consent.accepted(context) && history != null
                    && generation == expected.generation && observationRevision == expected.observationRevision
                    && history.revision() == expected.historyRevision
                    && history.observingWaiting(SystemClock.elapsedRealtime(), wallClock.getAsLong());
        }
    }
    /** Separate wall-clock seam: Android elapsed time advances independently of System time in simulation. */
    static volatile LongSupplier wallClock = System::currentTimeMillis;

    /** Called after the screen's decision/tap, never before or from notification callbacks. */
    static void screen(Context context, boolean eligible, DasherScene scene, OfferSnapshot offer,
                       boolean addOnOrRoute, boolean newInstance) {
        synchronized (LOCK) {
            observationRevision++;
            if (addOnOrRoute && history != null) history.excludePendingOffer();
            if (!Consent.accepted(context) || !eligible || addOnOrRoute) { stop(context); return; }
            QualifyingWait model = loaded(context);
            long now = SystemClock.elapsedRealtime();
            long wall = wallClock.getAsLong();
            if (scene == DasherScene.WAITING) model.waiting(now, wall);
            else if (scene == DasherScene.OFFER) model.offer(now, wall, offer, newInstance);
            else model.stop();
            save(context, model, now, wall, false);
        }
    }

    /** The existing window observer renews only a still-readable Dasher window, without reading extra screen text. */
    static void heartbeat(Context context, boolean eligible) {
        synchronized (LOCK) {
            if (history == null) return;
            observationRevision++;
            if (!Consent.accepted(context) || !eligible) { stop(context); return; }
            long now = SystemClock.elapsedRealtime();
            long wall = wallClock.getAsLong();
            history.heartbeat(now, wall);
            save(context, history, now, wall, false);
        }
    }

    static void stop(Context context) {
        synchronized (LOCK) {
            observationRevision++;
            if (history == null) return;
            history.stop();
            save(context, history, SystemClock.elapsedRealtime(), wallClock.getAsLong(), true);
        }
    }

    static QualifyingWait.Estimate estimate(Context context, FilterSettings settings) {
        synchronized (LOCK) {
            return loaded(context).estimate(settings, wallClock.getAsLong());
        }
    }

    /** No restored timer or cached estimate can make the homepage claim current waiting. */
    static boolean observingWaiting(Context context) {
        synchronized (LOCK) {
            return Consent.accepted(context) && history != null
                    && history.observingWaiting(SystemClock.elapsedRealtime(), wallClock.getAsLong());
        }
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            history = new QualifyingWait();
            savedRevision = -1;
            lastSave = 0;
            generation++;
            observationRevision++;
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(HISTORY).apply();
        }
    }

    private static QualifyingWait loaded(Context context) {
        if (history == null) {
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(HISTORY, "[]");
            history = new QualifyingWait(decode(raw), wallClock.getAsLong());
            lastSave = 0;
            savedRevision = -1;
        }
        return history;
    }

    private static void save(Context context, QualifyingWait model, long elapsed, long wall, boolean force) {
        long revision = model.revision();
        if (revision == savedRevision && !model.active()) return;
        if (!force && revision == savedRevision && elapsed >= lastSave && elapsed - lastSave < SAVE_EVERY_MS) return;
        List<QualifyingWait.Sample> snapshot = model.snapshot(wall);
        // No record is written merely because a cold estimator was queried.
        if (snapshot.isEmpty() && revision == savedRevision) return;
        long expected = generation;
        Context app = context.getApplicationContext();
        lastSave = elapsed;
        savedRevision = model.revision();
        WRITER.execute(() -> {
            String encoded = encode(snapshot);
            synchronized (LOCK) {
                if (generation != expected) return; // Clearing must win over an already queued old snapshot.
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(HISTORY, encoded).apply();
            }
        });
    }

    static String encode(List<QualifyingWait.Sample> samples) {
        JSONArray out = new JSONArray();
        try {
            for (QualifyingWait.Sample sample : samples) {
                JSONObject row = new JSONObject().put("at", sample.at).put("wait", sample.observedMs);
                OfferSnapshot offer = sample.arrival;
                if (offer != null) {
                    JSONObject facts = new JSONObject().put("pay", offer.payCents).put("miles", offer.miles)
                            .put("minutes", offer.minutes).put("stops", offer.stops).put("items", offer.items)
                            .put("itemApplicable", offer.itemCountApplicable)
                            .put("hotspotMiles", offer.finalStopHotspotMiles);
                    row.put("arrival", facts);
                }
                out.put(row);
            }
        } catch (JSONException impossibleNumbers) { return "[]"; }
        return out.toString();
    }

    static List<QualifyingWait.Sample> decode(String raw) {
        List<QualifyingWait.Sample> out = new ArrayList<>();
        if (raw == null || raw.length() > 100_000) return out;
        try {
            JSONArray rows = new JSONArray(raw);
            for (int i = Math.max(0, rows.length() - QualifyingWait.MAX_SAMPLES); i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                long at = whole(row.get("at"));
                long wait = whole(row.get("wait"));
                OfferSnapshot offer = null;
                if (row.has("arrival")) {
                    try {
                        Object rawFacts = row.get("arrival");
                        if (!(rawFacts instanceof JSONObject)) throw new IllegalArgumentException("arrival object");
                        JSONObject facts = (JSONObject) rawFacts;
                        Object applicable = facts.has("itemApplicable") ? facts.get("itemApplicable") : false;
                        if (!(applicable instanceof Boolean)) throw new IllegalArgumentException("item applicability");
                        offer = new OfferSnapshot(integer(facts, "pay"), decimal(facts, "miles"),
                                integer(facts, "minutes"), integer(facts, "stops"), null,
                                decimal(facts, "hotspotMiles"), integer(facts, "items"), (Boolean) applicable);
                    } catch (JSONException | IllegalArgumentException corruptFacts) {
                        // Unreadable arrivals cannot become censor-only time or disappear from support checks.
                        offer = OfferSnapshot.UNKNOWN;
                    }
                }
                out.add(new QualifyingWait.Sample(at, wait, offer));
            }
        } catch (JSONException | IllegalArgumentException malformed) {
            // Unknown observation times cannot safely be retained/replayed: reject the corrupt history as a unit.
            out.clear();
        }
        return out;
    }

    /** JSON getInt/getLong also coerce strings and truncate/wrap; retained model evidence must never do that. */
    private static long whole(Object raw) {
        if (!(raw instanceof Number)) throw new IllegalArgumentException("numeric integer required");
        try { return new BigDecimal(raw.toString()).longValueExact(); }
        catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("integer range"); }
    }
    private static Integer integer(JSONObject value, String name) throws JSONException {
        if (!value.has(name) || value.isNull(name)) return null;
        long number = whole(value.get(name));
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new IllegalArgumentException("integer range");
        return (int) number;
    }
    private static Double decimal(JSONObject value, String name) throws JSONException {
        if (!value.has(name) || value.isNull(name)) return null;
        Object raw = value.get(name);
        if (!(raw instanceof Number)) throw new IllegalArgumentException("numeric decimal required");
        double number = ((Number) raw).doubleValue();
        if (!Double.isFinite(number)) throw new IllegalArgumentException("finite decimal required");
        return number;
    }

    /** Test/process-restart seam. No in-progress observation survives cache loss. */
    static void forgetCache() {
        synchronized (LOCK) { history = null; lastSave = 0; savedRevision = -1; generation++; observationRevision++; }
    }
    static void flush() {
        try { WRITER.submit(() -> {}).get(2, TimeUnit.SECONDS); }
        catch (Exception stopped) { if (stopped instanceof InterruptedException) Thread.currentThread().interrupt(); }
    }

    private QualifyingWaitStore() {}
}
