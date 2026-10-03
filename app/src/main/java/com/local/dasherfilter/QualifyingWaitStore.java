package com.local.dasherfilter;

import android.content.Context;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
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
    /** Separate wall-clock seam: Android elapsed time advances independently of System time in simulation. */
    static volatile LongSupplier wallClock = System::currentTimeMillis;

    /** Called after the screen's decision/tap, never before or from notification callbacks. */
    static void screen(Context context, boolean eligible, DasherScene scene, OfferSnapshot offer,
                       boolean addOnOrRoute, boolean newInstance) {
        synchronized (LOCK) {
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
            if (!Consent.accepted(context) || !eligible) { stop(context); return; }
            long now = SystemClock.elapsedRealtime();
            long wall = wallClock.getAsLong();
            history.heartbeat(now, wall);
            save(context, history, now, wall, false);
        }
    }

    static void stop(Context context) {
        synchronized (LOCK) {
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

    static void clear(Context context) {
        synchronized (LOCK) {
            history = new QualifyingWait();
            savedRevision = -1;
            lastSave = 0;
            generation++;
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
                try {
                    JSONObject row = rows.getJSONObject(i);
                    JSONObject facts = row.optJSONObject("arrival");
                    OfferSnapshot offer = facts == null ? null : new OfferSnapshot(integer(facts, "pay"),
                            decimal(facts, "miles"), integer(facts, "minutes"), integer(facts, "stops"), null,
                            decimal(facts, "hotspotMiles"), integer(facts, "items"),
                            facts.optBoolean("itemApplicable", false));
                    out.add(new QualifyingWait.Sample(row.getLong("at"), row.getLong("wait"), offer));
                } catch (JSONException | IllegalArgumentException corruptEntry) { /* Fail closed for this record. */ }
            }
        } catch (JSONException malformed) { /* No recovered timing/authority from malformed storage. */ }
        return out;
    }

    private static Integer integer(JSONObject value, String name) throws JSONException {
        return !value.has(name) || value.isNull(name) ? null : value.getInt(name);
    }
    private static Double decimal(JSONObject value, String name) throws JSONException {
        return !value.has(name) || value.isNull(name) ? null : value.getDouble(name);
    }

    /** Test/process-restart seam. No in-progress observation survives cache loss. */
    static void forgetCache() {
        synchronized (LOCK) { history = null; lastSave = 0; savedRevision = -1; generation++; }
    }
    static void flush() {
        try { WRITER.submit(() -> {}).get(2, TimeUnit.SECONDS); }
        catch (Exception stopped) { if (stopped instanceof InterruptedException) Thread.currentThread().interrupt(); }
    }

    private QualifyingWaitStore() {}
}
