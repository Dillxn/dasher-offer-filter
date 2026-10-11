package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/** Prospective, observational study only. Never returns an action or changes a driving rule. */
final class DispatchStudy {
    static final int MAX_ROWS = 32;
    static final long RETAIN_MS = QualifyingWait.RETAIN_MS;
    static final long MAX_TRIAL_MS = 30 * 60_000L;
    enum End { QUALIFYING, NONQUALIFYING, UNKNOWN_OFFER, CENSORED }
    enum Reason { NONE, COVERAGE_LOST, PAUSED, POLICY_CHANGED, SESSION_CHANGED, GAP, CLOCK_CHANGED,
        UNREADABLE, ROUTE_OR_ADD_ON, RESTART, LIMIT, QUEUE_OVERFLOW, PREVIEW_CUTOFF }
    enum Observed { UNKNOWN, ACCEPTED, DECLINED }

    /** Only numeric facts; none of OfferSnapshot's label, location or ceiling fields are retained. */
    static OfferSnapshot numeric(OfferSnapshot f) {
        if (f == null) return OfferSnapshot.UNKNOWN;
        return new OfferSnapshot(f.payCents, f.miles, f.minutes, f.stops, null, null, f.items,
                f.itemCountApplicable);
    }

    static final class Row {
        long keptAt, arrivalAt;
        int trial, policy, hour, bar, omittedRows, score = -1;
        long predicted = -1, baseline = -1, trainingMs, observedMs, deltaMs;
        int readable, qualifying, unknown;
        OfferSnapshot facts = OfferSnapshot.UNKNOWN;
        OfferRule.Result decision = OfferRule.Result.REVIEW;
        End end = End.CENSORED;
        Reason reason = Reason.NONE;
        DecisionLog.Action requested = DecisionLog.Action.PAUSED;
        Observed observed = Observed.UNKNOWN;
        boolean actionKnown, touch, pairingAmbiguous, autoAcceptRequested, userAcceptTapped, userDeclineTapped;
        long decisionAt = -1; // Memory-only association. Never restored or exported.

        JSONObject json(long epoch) throws JSONException {
            JSONObject j = new JSONObject().put("schema", 1).put("method", "study_qualifying_mean_v1")
                    .put("baselineMethod", "last_complete_wait_v1").put("uncertainty", "not_computed")
                    .put("consentEpoch", epoch).put("trial", trial).put("policyEpoch", policy).put("utcHour", hour)
                    .put("predictionMs", predicted).put("baselineMs", baseline).put("trainingMs", trainingMs)
                    .put("readable", readable).put("qualifying", qualifying).put("unknown", unknown)
                    .put("observedMs", observedMs).put("deltaMs", deltaMs).put("bar", bar).put("score", score)
                    .put("end", end.name()).put("reason", reason.name()).put("decision", decision.name())
                    .put("actionKnown", actionKnown).put("requested", requested.name())
                    .put("observed", observed.name()).put("touch", touch).put("pairingAmbiguous", pairingAmbiguous)
                    .put("autoAcceptRequested", autoAcceptRequested).put("userAcceptTapped", userAcceptTapped)
                    .put("userDeclineTapped", userDeclineTapped).put("omittedRows", omittedRows);
            j.put("payCents", facts.payCents == null ? JSONObject.NULL : facts.payCents);
            j.put("miles", facts.miles == null ? JSONObject.NULL : facts.miles);
            j.put("minutes", facts.minutes == null ? JSONObject.NULL : facts.minutes);
            j.put("stops", facts.stops == null ? JSONObject.NULL : facts.stops);
            j.put("items", facts.items == null ? JSONObject.NULL : facts.items);
            return j;
        }

        /** Canonical allowlist: malformed/extra fields or strings in numeric positions never leave storage. */
        static Row parse(JSONObject j) throws JSONException {
            if (j.length() != 35 || j.getInt("schema") != 1
                    || !"study_qualifying_mean_v1".equals(j.getString("method"))
                    || !"last_complete_wait_v1".equals(j.getString("baselineMethod"))
                    || !"not_computed".equals(j.getString("uncertainty"))) throw new JSONException("study schema");
            number(j, "consentEpoch", 0, Long.MAX_VALUE);
            Row r = new Row();
            r.trial = (int) number(j, "trial", 1, Integer.MAX_VALUE);
            r.policy = (int) number(j, "policyEpoch", 1, Integer.MAX_VALUE);
            r.hour = (int) number(j, "utcHour", 0, 23);
            r.omittedRows = (int) number(j, "omittedRows", 0, Integer.MAX_VALUE);
            r.predicted = number(j, "predictionMs", -1, RETAIN_MS);
            r.baseline = number(j, "baselineMs", -1, MAX_TRIAL_MS);
            r.trainingMs = number(j, "trainingMs", 0, RETAIN_MS);
            r.readable = (int) number(j, "readable", 0, QualifyingWait.MAX_SAMPLES);
            r.qualifying = (int) number(j, "qualifying", 0, r.readable);
            r.unknown = (int) number(j, "unknown", 0, QualifyingWait.MAX_SAMPLES);
            r.observedMs = number(j, "observedMs", 0, MAX_TRIAL_MS);
            r.deltaMs = number(j, "deltaMs", 0, r.observedMs);
            r.bar = (int) number(j, "bar", 1, 200);
            r.score = (int) number(j, "score", -1, Integer.MAX_VALUE);
            try {
                r.end = End.valueOf(j.getString("end")); r.reason = Reason.valueOf(j.getString("reason"));
                r.decision = OfferRule.Result.valueOf(j.getString("decision"));
                r.requested = DecisionLog.Action.valueOf(j.getString("requested"));
                r.observed = Observed.valueOf(j.getString("observed"));
            } catch (IllegalArgumentException badEnum) { throw new JSONException("study enum"); }
            r.actionKnown = bool(j, "actionKnown"); r.touch = bool(j, "touch");
            r.pairingAmbiguous = bool(j, "pairingAmbiguous");
            r.autoAcceptRequested = bool(j, "autoAcceptRequested");
            r.userAcceptTapped = bool(j, "userAcceptTapped"); r.userDeclineTapped = bool(j, "userDeclineTapped");
            Double miles = null;
            if (!j.has("miles")) throw new JSONException("missing study miles");
            if (!j.isNull("miles")) {
                Object v = j.get("miles");
                if (!(v instanceof Number)) throw new JSONException("study miles");
                miles = ((Number) v).doubleValue();
                if (!Double.isFinite(miles) || miles < 0 || miles > 100_000) throw new JSONException("study miles");
            }
            r.facts = new OfferSnapshot(nullable(j, "payCents"), miles, nullable(j, "minutes"),
                    nullable(j, "stops"), null, null, nullable(j, "items"), false);
            return r;
        }

        private static Integer nullable(JSONObject j, String name) throws JSONException {
            if (!j.has(name)) throw new JSONException("missing study number");
            return j.isNull(name) ? null : (int) number(j, name, 0, Integer.MAX_VALUE);
        }
        private static boolean bool(JSONObject j, String name) throws JSONException {
            Object n = j.get(name);
            if (!(n instanceof Boolean)) throw new JSONException("study boolean");
            return (Boolean) n;
        }
        private static long number(JSONObject j, String name, long low, long high) throws JSONException {
            Object n = j.get(name);
            if (!(n instanceof Integer) && !(n instanceof Long)) throw new JSONException("study integer");
            long value = ((Number) n).longValue();
            if (value < low || value > high) throw new JSONException("study range");
            return value;
        }
    }

    /** Existing outcome evidence reduced to fixed categories before it reaches the study worker. */
    static final class Action {
        final long at;
        final OfferSnapshot facts;
        final DecisionLog.Action requested;
        final Observed observed;
        final boolean touch, autoAcceptRequested, userAcceptTapped, userDeclineTapped;
        Action(DecisionLog.Entry e) {
            at = e.at; facts = numeric(e.facts); requested = e.action;
            Observed result = Observed.UNKNOWN;
            boolean auto = false, accept = false, decline = false;
            for (DecisionLog.Step s : e.steps) {
                auto |= s.kind == DecisionLog.StepKind.AUTO_ACCEPT_REQUESTED;
                accept |= s.kind == DecisionLog.StepKind.ACCEPT_TAPPED;
                decline |= s.kind == DecisionLog.StepKind.DECLINE_TAPPED;
                if (s.kind == DecisionLog.StepKind.DECLINE_COUNTED) result = Observed.DECLINED;
                if (s.kind == DecisionLog.StepKind.ACCEPTED || s.kind == DecisionLog.StepKind.ACCEPTED_AUTOMATIC)
                    result = Observed.ACCEPTED;
            }
            observed = result; touch = e.action == DecisionLog.Action.USER_TOOK_OVER;
            autoAcceptRequested = auto; userAcceptTapped = accept; userDeclineTapped = decline;
        }
    }

    final List<Row> rows = new ArrayList<>();
    private final List<Action> actions = new ArrayList<>();
    private QualifyingWait training = new QualifyingWait(); // Only new, separately consented observations.
    private FilterSettings rules;
    private boolean autoAcceptPolicy;
    private long session, last = -1, lastWall, exposure, delta, handling, previousComplete = -1;
    private boolean waiting, offerUp;
    private Row forecast, lastOffer;
    private int trial, policy;
    int dropped;

    void screen(long now, long wall, long dash, boolean eligible, DasherScene scene, OfferSnapshot f,
                boolean excluded, boolean fresh, FilterSettings settings, boolean autoAccept) {
        prune(wall);
        if (!eligible || !settings.enabled) { stop(settings.enabled ? Reason.COVERAGE_LOST : Reason.PAUSED); return; }
        if (excluded) {
            // A partly drawn arrival later proved to be an add-on is not a standalone forecast success.
            // A subsequent delivery screen, in contrast, does not invalidate the standalone arrival before it.
            if (scene == DasherScene.OFFER && offerUp && lastOffer != null) {
                lastOffer.end = End.CENSORED; lastOffer.reason = Reason.ROUTE_OR_ADD_ON;
                training.excludePendingOffer(); previousComplete = -1;
            }
            stop(Reason.ROUTE_OR_ADD_ON); return;
        }
        if (rules == null || !samePolicy(rules, settings) || autoAcceptPolicy != autoAccept || session != dash) {
            stop(rules != null && session != dash ? Reason.SESSION_CHANGED : Reason.POLICY_CHANGED);
            rules = settings; autoAcceptPolicy = autoAccept; session = dash; policy++; previousComplete = -1;
            training = new QualifyingWait();
        }
        if (!advance(now, wall)) stop(Reason.GAP);
        if (scene == DasherScene.WAITING) {
            if (forecast == null) begin(now, wall);
            else if (offerUp) { exposure += handling; delta += handling; }
            handling = 0; waiting = true; offerUp = false; lastOffer = null;
            training.waiting(now, wall);
        } else if (scene == DasherScene.OFFER) {
            OfferSnapshot facts = numeric(f);
            boolean different = offerUp && lastOffer != null && lastOffer.facts.contradicts(facts);
            if (offerUp && !fresh && !different) {
                // Never reclassify a partly read arrival into a successful forecast retrospectively.
                training.offer(now, wall, facts, false);
            } else {
                if (forecast != null && offerUp) { exposure += handling; delta += handling; }
                handling = 0;
                if (forecast != null) {
                    Row r = snapshot(wall, Reason.NONE);
                    r.facts = facts; r.arrivalAt = wall;
                    OfferRule.Decision decision = OfferRule.evaluate(facts, rules);
                    r.decision = decision.result; r.score = decision.scorePercent;
                    boolean unknown = facts.payCents == null || decision.result == OfferRule.Result.REVIEW;
                    r.end = unknown ? End.UNKNOWN_OFFER : decision.result == OfferRule.Result.KEEP
                            ? End.QUALIFYING : End.NONQUALIFYING;
                    r.reason = unknown ? Reason.UNREADABLE : Reason.NONE;
                    add(r); lastOffer = r; delta = 0;
                    if (r.end != End.NONQUALIFYING) {
                        if (r.end == End.QUALIFYING) previousComplete = exposure;
                        forecast = null;
                    }
                    pair();
                }
                training.offer(now, wall, facts, fresh || different);
            }
            waiting = false; offerUp = true;
        } else stop(Reason.UNREADABLE);
        last = now; lastWall = wall;
    }

    void heartbeat(long now, long wall, boolean eligible) {
        prune(wall);
        if (!eligible) { stop(Reason.COVERAGE_LOST); return; }
        if (!advance(now, wall)) { stop(Reason.GAP); return; }
        training.heartbeat(now, wall);
    }

    private boolean advance(long now, long wall) {
        if (last < 0) return true;
        long step = now - last;
        if (step < 0 || step > QualifyingWait.MAX_COVERAGE_GAP_MS) return false;
        if (Math.abs((wall - lastWall) - step) > QualifyingWait.MAX_CLOCK_DRIFT_MS) {
            stop(Reason.CLOCK_CHANGED); return false;
        }
        if (forecast != null) {
            if (waiting) { exposure += step; delta += step; }
            else if (offerUp) handling += step;
            if (exposure + handling > MAX_TRIAL_MS) { stop(Reason.LIMIT); return false; }
        }
        last = now; lastWall = wall;
        return true;
    }

    private void begin(long now, long wall) {
        QualifyingWait.Estimate estimate = training.estimate(rules, wall);
        forecast = new Row(); forecast.trial = ++trial; forecast.policy = policy;
        forecast.hour = (int) Math.floorMod(wall / 3_600_000, 24L);
        forecast.bar = rules.minimumScalePercent; forecast.predicted = estimate.typicalMs;
        forecast.baseline = previousComplete; forecast.trainingMs = estimate.observedMs;
        forecast.readable = estimate.readable; forecast.qualifying = estimate.qualifying;
        forecast.unknown = estimate.unreadable;
        exposure = delta = handling = 0; last = now; lastWall = wall;
    }

    Row snapshot(long wall, Reason reason) {
        Row r = new Row();
        r.keptAt = wall; r.trial = forecast.trial; r.policy = forecast.policy; r.hour = forecast.hour;
        r.bar = forecast.bar; r.predicted = forecast.predicted; r.baseline = forecast.baseline;
        r.trainingMs = forecast.trainingMs; r.readable = forecast.readable;
        r.qualifying = forecast.qualifying; r.unknown = forecast.unknown;
        r.observedMs = Math.min(MAX_TRIAL_MS, exposure); r.deltaMs = Math.min(r.observedMs, delta);
        r.reason = reason;
        return r;
    }

    void stop(Reason reason) {
        if (forecast != null) add(snapshot(lastWall, reason));
        forecast = null; waiting = offerUp = false; last = -1; handling = 0; lastOffer = null;
        training.stop();
    }
    void policyChanged() {
        stop(Reason.POLICY_CHANGED); rules = null; training = new QualifyingWait(); previousComplete = -1;
    }

    List<Row> snapshotRows(long wall, Reason unfinished) {
        prune(wall);
        List<Row> result = new ArrayList<>(rows);
        if (forecast != null) result.add(snapshot(wall, unfinished));
        return result;
    }

    void actions(List<Action> seen) {
        for (Action a : seen) {
            actions.removeIf(old -> old.at == a.at);
            actions.add(a);
        }
        while (actions.size() > 16) actions.remove(0);
        pair();
    }

    private void pair() {
        for (Row r : rows) {
            if (r.arrivalAt == 0 || r.pairingAmbiguous) continue;
            Action match = null;
            for (Action a : actions) {
                if (!sameFacts(a.facts, r.facts) || Math.abs(a.at - r.arrivalAt) > 2_000) continue;
                if (match != null && match.at != a.at) { r.pairingAmbiguous = true; break; }
                match = a;
            }
            if (match != null) {
                for (Row other : rows) if (other != r && other.arrivalAt != 0
                        && sameFacts(other.facts, r.facts) && Math.abs(match.at - other.arrivalAt) <= 2_000)
                    r.pairingAmbiguous = true;
                if (!r.pairingAmbiguous) {
                    r.decisionAt = match.at; r.requested = match.requested; r.observed = match.observed;
                    r.touch = match.touch; r.actionKnown = true;
                    r.autoAcceptRequested = match.autoAcceptRequested; r.userAcceptTapped = match.userAcceptTapped;
                    r.userDeclineTapped = match.userDeclineTapped;
                }
            }
            if (r.pairingAmbiguous) { r.actionKnown = false; r.observed = Observed.UNKNOWN;
                r.requested = DecisionLog.Action.PAUSED; r.touch = false;
                r.autoAcceptRequested = r.userAcceptTapped = r.userDeclineTapped = false; }
        }
    }

    private void add(Row r) {
        rows.add(r);
        while (rows.size() > MAX_ROWS) { rows.remove(0); dropped++; }
    }
    void restore(Row r) {
        add(r);
        trial = Math.max(trial, r.trial); policy = Math.max(policy, r.policy);
        // Only closed/censored rows survive. No forecast, live timer, training or action association is restored.
    }
    void prune(long wall) {
        rows.removeIf(r -> wall < r.keptAt || wall - r.keptAt > RETAIN_MS);
    }
    private static boolean sameFacts(OfferSnapshot a, OfferSnapshot b) {
        return Objects.equals(a.payCents, b.payCents) && Objects.equals(a.miles, b.miles)
                && Objects.equals(a.minutes, b.minutes) && Objects.equals(a.stops, b.stops)
                && Objects.equals(a.items, b.items);
    }
    private static boolean samePolicy(FilterSettings a, FilterSettings b) {
        return a.enabled == b.enabled && a.flatCents == b.flatCents && a.perMileCents == b.perMileCents
                && a.perMinuteCents == b.perMinuteCents && a.maxStops == b.maxStops
                && a.minimumScalePercent == b.minimumScalePercent && a.autopilot == b.autopilot;
    }
}
