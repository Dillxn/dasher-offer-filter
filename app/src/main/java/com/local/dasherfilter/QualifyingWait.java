package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A local arrival-rate estimate, never a decision or countdown. No historic offer timestamp is treated as waiting
 * time: callers must positively observe waiting, renew visible/unlocked coverage, and end it on any uncertainty.
 * Retains numeric arrivals and right-censored exposure, so long unfinished waits are not silently discarded.
 */
final class QualifyingWait {
    static final long RETAIN_MS = 24 * 60 * 60_000L;
    static final int MAX_SAMPLES = 200;
    static final long MAX_COVERAGE_GAP_MS = 10_000L;
    static final long MAX_CLOCK_DRIFT_MS = 2_000L;
    static final int MIN_QUALIFYING = 3;
    static final int MIN_READABLE = 5;
    static final long MIN_EXPOSURE_MS = 5 * 60_000L;

    static final class Sample {
        final long at;
        final long observedMs;
        /** Null is an unfinished/censored waiting interval, not an unread offer. */
        final OfferSnapshot arrival;
        Sample(long at, long observedMs, OfferSnapshot arrival) {
            if (at < 0 || observedMs < 0 || observedMs > RETAIN_MS) throw new IllegalArgumentException("wait sample");
            this.at = at;
            this.observedMs = observedMs;
            this.arrival = arrival == null ? null : arrival.withoutPayBound();
        }
    }

    enum Status { LEARNING, READY, UNREADABLE, NO_RULES }

    static final class Estimate {
        final Status status;
        final long observedMs;
        final int qualifying;
        final int readable;
        final int unreadable;
        /** Rough fitted mean, not time remaining. -1 until the available observations support an estimate. */
        final long typicalMs;
        Estimate(Status status, long observedMs, int qualifying, int readable, int unreadable) {
            this.status = status;
            this.observedMs = observedMs;
            this.qualifying = qualifying;
            this.readable = readable;
            this.unreadable = unreadable;
            this.typicalMs = status == Status.READY ? Math.round((double) observedMs / qualifying) : -1;
        }

        String label() {
            if (status == Status.UNREADABLE) return "Wait estimate needs readable offers";
            if (status == Status.NO_RULES) return "Set minimums to estimate your wait";
            if (status != Status.READY) return "Learning your wait";
            return "Next match: about " + minutes(typicalMs);
        }

        String detail() {
            String evidence = qualifying + " qualifying offers among " + readable + " readable arrivals over "
                    + minutes(observedMs) + " of observed waiting in the last 24 hours.";
            if (status == Status.NO_RULES) return "Set at least one minimum to define a qualifying offer.";
            if (status == Status.UNREADABLE) return evidence + " " + unreadable
                    + " arrivals lack facts needed by your current minimums, so no wait is estimated.";
            if (status != Status.READY) return evidence + " Needs at least " + MIN_QUALIFYING
                    + " matches, " + MIN_READABLE + " readable arrivals and 5 monitored minutes.";
            return evidence + " Current strict/area rules, learned floors and minimums percentage are used. "
                    + "The estimate assumes the observed arrival rate continues. Conditions can change; "
                    + "this rounded historical average is not a countdown or promise. "
                    + "Only visible, unlocked waiting is measured. Rejected-offer handling counts when waiting resumes; "
                    + "delivery, pause, hidden-screen and unobserved time do not. Unfinished measured waits count too.";
        }

        private static String minutes(long ms) {
            if (ms < 60_000) return "less than 1 min";
            return Math.max(1, Math.round(ms / 60_000.0)) + " min";
        }
    }

    private enum Phase { NONE, WAITING, OFFER }
    private final List<Sample> samples = new ArrayList<>();
    private Phase phase = Phase.NONE;
    private long started;
    private long heartbeat;
    private long wallHeartbeat;
    private Sample pendingArrival;
    private long revision;

    QualifyingWait() {}
    QualifyingWait(List<Sample> restored, long wallNow) {
        if (restored != null) samples.addAll(restored);
        prune(wallNow);
        // Deliberately no restored timer, offer identity, foreground or action authority.
    }

    /** A real waiting screen, no route/pause/offer, under current consent and visible/unlocked coverage. */
    void waiting(long elapsed, long wallNow) {
        if (!continuous(elapsed, wallNow)) stopAtHeartbeat();
        if (phase == Phase.OFFER) {
            // The previous offer was not taken into a delivery. Its visible handling time belongs to the wait.
            add(new Sample(wallNow, elapsed - started, null));
        }
        if (phase != Phase.WAITING) {
            started = elapsed;
            phase = Phase.WAITING;
            pendingArrival = null;
        }
        renew(elapsed, wallNow);
        prune(wallNow);
    }

    /** Cheap foreground/lock observation, not a fresh read or an invented waiting screen. */
    void heartbeat(long elapsed, long wallNow) {
        if (phase == Phase.NONE) return;
        if (!continuous(elapsed, wallNow)) { stopAtHeartbeat(); return; }
        renew(elapsed, wallNow);
        prune(wallNow);
    }

    /**
     * A standalone screen offer reached from observed waiting. Notification callbacks never call this method.
     * Partial/full rereads update the same arrival; only an explicit new instance creates another arrival.
     */
    void offer(long elapsed, long wallNow, OfferSnapshot facts, boolean newInstance) {
        if (!continuous(elapsed, wallNow)) stopAtHeartbeat();
        if (phase == Phase.NONE) return;
        OfferSnapshot observed = facts == null ? OfferSnapshot.UNKNOWN : facts;
        boolean same = phase == Phase.OFFER
                && (!newInstance || pendingArrival != null && !hasIdentity(pendingArrival.arrival))
                && (pendingArrival == null || !pendingArrival.arrival.contradicts(observed));
        if (same) {
            if (pendingArrival != null && !sameFacts(pendingArrival.arrival, observed)) {
                int index = samples.indexOf(pendingArrival);
                // A richer read may fill unknowns; an incomplete follow-up must not erase earlier observed facts.
                OfferSnapshot merged = merge(pendingArrival.arrival, observed);
                if (index >= 0) {
                    pendingArrival = new Sample(pendingArrival.at, pendingArrival.observedMs, merged);
                    samples.set(index, pendingArrival);
                    revision++;
                }
            }
        } else {
            pendingArrival = new Sample(wallNow, elapsed - started, observed);
            add(pendingArrival);
            started = elapsed;
        }
        phase = Phase.OFFER;
        renew(elapsed, wallNow);
        prune(wallNow);
    }

    /** A partially drawn offer later proved to be an add-on/active route: it is not a standalone arrival. */
    void excludePendingOffer() {
        if (phase == Phase.OFFER && pendingArrival != null && samples.remove(pendingArrival)) revision++;
        stopAtHeartbeat();
    }

    /** End coverage at its last verified heartbeat; never count a lock/off-screen/delivery gap. */
    void stop() { stopAtHeartbeat(); }

    List<Sample> snapshot(long wallNow) {
        prune(wallNow);
        List<Sample> result = new ArrayList<>(samples);
        if (phase == Phase.WAITING && heartbeat > started) {
            result.add(new Sample(wallHeartbeat, Math.min(RETAIN_MS - (wallNow - wallHeartbeat),
                    heartbeat - started), null));
        }
        while (result.size() > MAX_SAMPLES) result.remove(0);
        return Collections.unmodifiableList(result);
    }

    Estimate estimate(FilterSettings settings, long wallNow) {
        return estimate(snapshot(wallNow), settings);
    }

    static Estimate estimate(List<Sample> history, FilterSettings settings) {
        long exposure = 0;
        int matches = 0, readable = 0, unreadable = 0;
        for (Sample sample : history) {
            exposure += sample.observedMs;
            if (sample.arrival == null) continue;
            OfferRule.Result result = OfferRule.evaluate(sample.arrival, settings).result;
            if (result == OfferRule.Result.REVIEW
                    || (result == OfferRule.Result.KEEP && sample.arrival.payCents == null)) unreadable++;
            else {
                readable++;
                if (result == OfferRule.Result.KEEP) matches++;
            }
        }
        Status status = !settings.hasAnyRule() ? Status.NO_RULES : unreadable > 0 ? Status.UNREADABLE
                : matches < MIN_QUALIFYING || readable < MIN_READABLE || exposure < MIN_EXPOSURE_MS
                ? Status.LEARNING : Status.READY;
        return new Estimate(status, exposure, matches, readable, unreadable);
    }

    long revision() { return revision; }
    boolean active() { return phase != Phase.NONE; }

    /** Display-only: an offer-handling phase is not positive evidence that the user is still waiting. */
    boolean observingWaiting(long elapsed, long wallNow) {
        return phase == Phase.WAITING && continuous(elapsed, wallNow);
    }

    private boolean continuous(long elapsed, long wallNow) {
        return phase != Phase.NONE && elapsed >= heartbeat && elapsed - heartbeat <= MAX_COVERAGE_GAP_MS
                && elapsed >= started && elapsed - started <= RETAIN_MS
                && wallNow >= wallHeartbeat
                && Math.abs((wallNow - wallHeartbeat) - (elapsed - heartbeat)) <= MAX_CLOCK_DRIFT_MS;
    }

    private void renew(long elapsed, long wallNow) {
        heartbeat = elapsed;
        wallHeartbeat = wallNow;
    }

    private void stopAtHeartbeat() {
        if (phase == Phase.WAITING && heartbeat > started) {
            add(new Sample(wallHeartbeat, Math.min(RETAIN_MS, heartbeat - started), null));
        }
        phase = Phase.NONE;
        pendingArrival = null;
    }

    private void add(Sample sample) {
        if (sample.observedMs == 0 && sample.arrival == null) return;
        samples.add(sample);
        revision++;
    }

    private void prune(long wallNow) {
        if (phase != Phase.NONE && (wallHeartbeat > wallNow || wallNow - wallHeartbeat >= RETAIN_MS)) {
            // A stale/future in-memory lease cannot resurrect records outside the retention window.
            phase = Phase.NONE;
            pendingArrival = null;
        }
        for (int i = samples.size() - 1; i >= 0; i--) {
            Sample sample = samples.get(i);
            if (sample.at > wallNow || wallNow - sample.at >= RETAIN_MS) {
                samples.remove(i);
                revision++;
            } else {
                // End-time retention alone overcounts an interval that began before the moving cutoff.
                long insideWindow = RETAIN_MS - (wallNow - sample.at);
                if (sample.observedMs > insideWindow) {
                    Sample clipped = new Sample(sample.at, insideWindow, sample.arrival);
                    samples.set(i, clipped);
                    if (pendingArrival == sample) pendingArrival = clipped;
                    revision++;
                }
            }
        }
        while (samples.size() > MAX_SAMPLES) { samples.remove(0); revision++; }
    }

    private static boolean hasIdentity(OfferSnapshot offer) {
        return offer.payCents != null || offer.miles != null || offer.minutes != null || offer.stops != null;
    }

    private static boolean sameFacts(OfferSnapshot a, OfferSnapshot b) {
        return a.fingerprint().equals(b.fingerprint())
                && java.util.Objects.equals(a.finalStopHotspotMiles, b.finalStopHotspotMiles);
    }

    private static OfferSnapshot merge(OfferSnapshot previous, OfferSnapshot next) {
        if (previous.contradicts(next)) return OfferSnapshot.UNKNOWN;
        return new OfferSnapshot(next.payCents == null ? previous.payCents : next.payCents,
                next.miles == null ? previous.miles : next.miles,
                next.minutes == null ? previous.minutes : next.minutes,
                next.stops == null ? previous.stops : next.stops, null,
                next.finalStopHotspotMiles == null ? previous.finalStopHotspotMiles : next.finalStopHotspotMiles,
                next.items == null ? previous.items : next.items,
                previous.itemCountApplicable || next.itemCountApplicable);
    }
}
