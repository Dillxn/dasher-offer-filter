package com.local.dasherfilter;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Local observational optimizer for the single existing minimums scale.
 *
 * <p>It does not model or claim DoorDash's dispatch algorithm. It combines the app's bounded numeric waiting samples
 * with confirmed accepted/declined outcomes. Where enough history exists, an observed acceptance band may adjust the
 * expected arrival rate; otherwise the overall observed arrival rate is used. The result always changes only
 * {@link FilterSettings#minimumScalePercent}; saved/learned baselines, scoring mode and max stops are untouched.
 */
final class EarningsOptimizer {
    private static final long MIN_EXPOSURE_MS = 5 * 60_000L;
    private static final int MIN_READABLE = 5;
    private static final int MIN_MATCHES = 3;
    private static final int MIN_ACCEPTANCE_OUTCOMES = 5;
    private static final int ACCEPTANCE_WINDOW = 20;
    private static final int MIN_BAND_ARRIVALS = 3;
    private static final long MIN_BAND_EXPOSURE_MS = 5 * 60_000L;
    private static final long RETUNE_EVERY_MS = 5 * 60_000L;
    private static final int RETUNE_STEP = 5;

    enum Status { LEARNING, READY }

    static final class Result {
        final Status status;
        final int preferencePercent;
        final int recommendedScalePercent;
        final int readableArrivals;
        final int qualifyingArrivals;
        final int unreadableArrivals;
        final long observedWaitMs;
        final double matchingOffersPerHour;
        final double hourlyCents;
        final int observedAcceptancePercent;
        final int acceptanceOutcomes;
        final boolean dispatchAdjusted;
        final int vehicleCostPerMileCents;

        Result(Status status, int preferencePercent, int recommendedScalePercent, int readableArrivals,
               int qualifyingArrivals, int unreadableArrivals, long observedWaitMs, double matchingOffersPerHour,
               double hourlyCents, int observedAcceptancePercent, int acceptanceOutcomes, boolean dispatchAdjusted,
               int vehicleCostPerMileCents) {
            this.status = status;
            this.preferencePercent = preferencePercent;
            this.recommendedScalePercent = recommendedScalePercent;
            this.readableArrivals = readableArrivals;
            this.qualifyingArrivals = qualifyingArrivals;
            this.unreadableArrivals = unreadableArrivals;
            this.observedWaitMs = observedWaitMs;
            this.matchingOffersPerHour = matchingOffersPerHour;
            this.hourlyCents = hourlyCents;
            this.observedAcceptancePercent = observedAcceptancePercent;
            this.acceptanceOutcomes = acceptanceOutcomes;
            this.dispatchAdjusted = dispatchAdjusted;
            this.vehicleCostPerMileCents = vehicleCostPerMileCents;
        }

        String shortLabel() {
            if (status != Status.READY) return "Learning · temporary minimums " + recommendedScalePercent + "%";
            String money = String.format(Locale.US, "$%.2f/hr", hourlyCents / 100.0);
            return money + " · " + String.format(Locale.US, "%.1f matches/hr", matchingOffersPerHour)
                    + " · minimums " + recommendedScalePercent + "%";
        }

        String detail() {
            String basis = vehicleCostPerMileCents > 0
                    ? "Estimated net pay subtracts " + DecisionLog.money(vehicleCostPerMileCents) + "/mi of vehicle cost."
                    : "Vehicle cost is off, so the earnings estimate uses gross offer pay.";
            String acceptance = observedAcceptancePercent < 0
                    ? "There are not yet enough confirmed accepted/declined offers for an observed acceptance proxy."
                    : "The app-observed acceptance proxy is " + observedAcceptancePercent + "% across "
                            + acceptanceOutcomes + " recent confirmed accepted/declined offers.";
            if (status != Status.READY) {
                return "Learning the offers/profit tradeoff from " + readableArrivals + " readable arrivals over "
                        + minutes(observedWaitMs) + " of observed waiting. Until there is enough evidence, preference "
                        + preferencePercent + "% uses " + recommendedScalePercent + "% of the existing minimums. "
                        + basis + " " + acceptance;
            }
            String adjustment = dispatchAdjusted
                    ? "The offer-frequency estimate uses a historical correlation for the matching observed acceptance band."
                    : "There is not enough acceptance-band history to adjust offer frequency, so the overall observed arrival rate is used.";
            return "Preference " + preferencePercent + "% currently favors " + recommendedScalePercent
                    + "% of the existing minimums: about "
                    + String.format(Locale.US, "%.1f", matchingOffersPerHour) + " matching offers/hour and "
                    + String.format(Locale.US, "$%.2f", hourlyCents / 100.0) + " per active hour in the retained sample. "
                    + basis + " " + acceptance + " " + adjustment
                    + " This is observational history, not DoorDash's official acceptance rate, dispatch formula, or an earnings promise.";
        }

        private static String minutes(long ms) {
            if (ms < 60_000) return "less than 1 min";
            return Math.max(1, Math.round(ms / 60_000.0)) + " min";
        }
    }

    private static final class Acceptance {
        final int percent;
        final int count;
        Acceptance(int percent, int count) { this.percent = percent; this.count = count; }
    }

    private static final class Band {
        long exposureMs;
        int arrivals;
    }

    private static final class Candidate {
        final int scale;
        final int readable;
        final int matches;
        final int unreadable;
        final double matchesPerHour;
        final double hourlyCents;
        final boolean dispatchAdjusted;

        Candidate(int scale, int readable, int matches, int unreadable, double matchesPerHour,
                  double hourlyCents, boolean dispatchAdjusted) {
            this.scale = scale;
            this.readable = readable;
            this.matches = matches;
            this.unreadable = unreadable;
            this.matchesPerHour = matchesPerHour;
            this.hourlyCents = hourlyCents;
            this.dispatchAdjusted = dispatchAdjusted;
        }
    }

    static Result recommend(Context context, FilterSettings settings) {
        int preference = EarningsPreferences.tradeoff(context, settings.minimumScalePercent);
        return analyze(QualifyingWaitStore.samples(context), DecisionLog.recent(context, 200), settings, preference,
                EarningsPreferences.vehicleCostCentsPerMile(context));
    }

    static Result recommend(Context context, FilterSettings settings, int preference) {
        return analyze(QualifyingWaitStore.samples(context), DecisionLog.recent(context, 200), settings, preference,
                EarningsPreferences.vehicleCostCentsPerMile(context));
    }

    static Result analyze(List<QualifyingWait.Sample> samples, List<DecisionLog.Entry> decisions,
                          FilterSettings settings, int preference, int vehicleCostPerMileCents) {
        if (samples == null) samples = Collections.emptyList();
        if (decisions == null) decisions = Collections.emptyList();
        int p = Math.max(0, Math.min(100, preference));
        int cost = Math.max(0, Math.min(500, vehicleCostPerMileCents));
        long exposure = 0;
        int arrivals = 0;
        for (QualifyingWait.Sample sample : samples) {
            exposure += sample.observedMs;
            if (sample.arrival != null) arrivals++;
        }
        Acceptance acceptance = acceptanceAt(decisions, Long.MAX_VALUE);
        int fallback = EarningsPreferences.fallbackScale(p);
        if (!settings.hasAnyRule() || exposure < MIN_EXPOSURE_MS || arrivals < MIN_READABLE) {
            return new Result(Status.LEARNING, p, fallback, arrivals, 0, 0, exposure, 0, 0,
                    acceptance.percent, acceptance.count, false, cost);
        }

        Band[] bands = acceptanceBands(samples, decisions);
        List<Integer> scales = candidateScales(settings.minimumScalePercent, fallback);
        List<Candidate> candidates = new ArrayList<>();
        for (int scale : scales) {
            Candidate candidate = candidate(samples, settings.withMinimumScalePercent(scale), scale, exposure, arrivals,
                    bands, cost);
            if (candidate != null) candidates.add(candidate);
        }
        if (candidates.isEmpty()) {
            Candidate atFallback = candidate(samples, settings.withMinimumScalePercent(fallback), fallback, exposure,
                    arrivals, bands, cost);
            int readable = atFallback == null ? 0 : atFallback.readable;
            int matches = atFallback == null ? 0 : atFallback.matches;
            int unreadable = atFallback == null ? 0 : atFallback.unreadable;
            return new Result(Status.LEARNING, p, fallback, readable, matches, unreadable, exposure, 0, 0,
                    acceptance.percent, acceptance.count, false, cost);
        }

        double minVolume = Double.POSITIVE_INFINITY, maxVolume = Double.NEGATIVE_INFINITY;
        double minHourly = Double.POSITIVE_INFINITY, maxHourly = Double.NEGATIVE_INFINITY;
        for (Candidate candidate : candidates) {
            minVolume = Math.min(minVolume, candidate.matchesPerHour);
            maxVolume = Math.max(maxVolume, candidate.matchesPerHour);
            minHourly = Math.min(minHourly, candidate.hourlyCents);
            maxHourly = Math.max(maxHourly, candidate.hourlyCents);
        }
        double weight = p / 100.0;
        Candidate best = null;
        double bestUtility = Double.NEGATIVE_INFINITY;
        for (Candidate candidate : candidates) {
            double volume = normalize(candidate.matchesPerHour, minVolume, maxVolume);
            double hourly = normalize(candidate.hourlyCents, minHourly, maxHourly);
            double utility = (1.0 - weight) * volume + weight * hourly;
            if (best == null || utility > bestUtility + 1e-9
                    || Math.abs(utility - bestUtility) <= 1e-9
                    && Math.abs(candidate.scale - fallback) < Math.abs(best.scale - fallback)) {
                best = candidate;
                bestUtility = utility;
            }
        }
        return new Result(Status.READY, p, best.scale, best.readable, best.matches, best.unreadable, exposure,
                best.matchesPerHour, best.hourlyCents, acceptance.percent, acceptance.count,
                best.dispatchAdjusted, cost);
    }

    /**
     * Called only from a positively recognized WAITING screen. It never acts on an offer and never changes a baseline:
     * one safe retune moves the existing scale at most five percentage points.
     */
    static boolean maybeRetune(Context context) {
        if (!EarningsPreferences.autoTune(context) || !EarningsPreferences.chosen(context)) return false;
        long now = System.currentTimeMillis();
        long last = EarningsPreferences.lastTuneAt(context);
        if (last > 0 && now >= last && now - last < RETUNE_EVERY_MS) return false;
        FilterSettings current = FilterStore.load(context);
        if (!current.enabled || !current.hasAnyRule()) return false;
        Result result = recommend(context, current);
        EarningsPreferences.markTuned(context, now);
        if (result.status != Status.READY || result.recommendedScalePercent == current.minimumScalePercent) return false;
        int delta = result.recommendedScalePercent - current.minimumScalePercent;
        int next = current.minimumScalePercent + Math.max(-RETUNE_STEP, Math.min(RETUNE_STEP, delta));
        FilterStore.save(context, current.withMinimumScalePercent(next));
        DiagnosticLog.log(context, "earnings", "safe-wait retune minimums " + current.minimumScalePercent + "% -> "
                + next + "% toward " + result.recommendedScalePercent + "%; preference " + result.preferencePercent
                + "%; observed acceptance " + (result.observedAcceptancePercent < 0 ? "unavailable"
                : result.observedAcceptancePercent + "%") + "; dispatch correlation "
                + (result.dispatchAdjusted ? "used" : "not used"));
        OfferNotificationService.rulesChanged();
        return true;
    }

    /** Strong on-device outcome proxy only; PASSED/REVIEW/YOURS/REQUESTED are deliberately not acceptance evidence. */
    static int observedAcceptancePercent(List<DecisionLog.Entry> decisions) {
        return acceptanceAt(decisions == null ? Collections.emptyList() : decisions, Long.MAX_VALUE).percent;
    }

    private static Candidate candidate(List<QualifyingWait.Sample> samples, FilterSettings rules, int scale,
                                       long exposure, int totalArrivals, Band[] bands, int cost) {
        int readable = 0, matches = 0, unreadable = 0, economic = 0;
        double netTotal = 0, minutesTotal = 0;
        for (QualifyingWait.Sample sample : samples) {
            OfferSnapshot offer = sample.arrival;
            if (offer == null) continue;
            OfferRule.Decision decision = OfferRule.evaluate(offer, rules);
            if (decision.result == OfferRule.Result.REVIEW
                    || (decision.result == OfferRule.Result.KEEP && offer.payCents == null)) {
                unreadable++;
                continue;
            }
            readable++;
            if (decision.result != OfferRule.Result.KEEP) continue;
            matches++;
            if (offer.payCents == null || offer.minutes == null || offer.minutes <= 0) continue;
            if (cost > 0 && offer.miles == null) continue;
            double net = offer.payCents - (cost > 0 ? offer.miles * cost : 0);
            netTotal += net;
            minutesTotal += offer.minutes;
            economic++;
        }
        if (unreadable > 0 || readable < MIN_READABLE || matches < MIN_MATCHES || economic < MIN_MATCHES) return null;
        double passShare = readable == 0 ? 0 : (double) matches / readable;
        double arrivalRate = totalArrivals * 3_600_000.0 / exposure;
        boolean adjusted = false;
        int proxy = (int) Math.round(passShare * 100);
        Band band = bands[band(proxy)];
        if (band.exposureMs >= MIN_BAND_EXPOSURE_MS && band.arrivals >= MIN_BAND_ARRIVALS) {
            arrivalRate = band.arrivals * 3_600_000.0 / band.exposureMs;
            adjusted = true;
        }
        double matchesPerHour = arrivalRate * passShare;
        if (!(matchesPerHour > 0)) return null;
        double avgNet = netTotal / economic;
        double avgMinutes = minutesTotal / economic;
        double waitHours = 1.0 / matchesPerHour;
        double activeHours = avgMinutes / 60.0 + waitHours;
        double hourly = activeHours > 0 ? avgNet / activeHours : 0;
        return new Candidate(scale, readable, matches, unreadable, matchesPerHour, hourly, adjusted);
    }

    private static Band[] acceptanceBands(List<QualifyingWait.Sample> samples, List<DecisionLog.Entry> decisions) {
        Band[] bands = {new Band(), new Band(), new Band()};
        for (QualifyingWait.Sample sample : samples) {
            Acceptance acceptance = acceptanceAt(decisions, sample.at);
            if (acceptance.percent < 0) continue;
            Band band = bands[band(acceptance.percent)];
            band.exposureMs += sample.observedMs;
            if (sample.arrival != null) band.arrivals++;
        }
        return bands;
    }

    private static Acceptance acceptanceAt(List<DecisionLog.Entry> decisions, long at) {
        List<DecisionLog.Entry> ordered = new ArrayList<>(decisions);
        ordered.sort(Comparator.comparingLong((DecisionLog.Entry entry) -> entry.at).reversed());
        int accepted = 0, declined = 0;
        for (DecisionLog.Entry entry : ordered) {
            if (entry.at > at || entry.addOn) continue;
            DecisionLog.Outcome outcome = DecisionLog.outcome(entry);
            if (outcome == DecisionLog.Outcome.ACCEPTED) accepted++;
            else if (outcome == DecisionLog.Outcome.DECLINED) declined++;
            else continue;
            if (accepted + declined >= ACCEPTANCE_WINDOW) break;
        }
        int count = accepted + declined;
        return new Acceptance(count < MIN_ACCEPTANCE_OUTCOMES ? -1 : (int) Math.round(accepted * 100.0 / count),
                count);
    }

    private static int band(int percent) {
        return percent < 40 ? 0 : percent < 70 ? 1 : 2;
    }

    private static List<Integer> candidateScales(int current, int fallback) {
        List<Integer> scales = new ArrayList<>();
        for (int scale = 50; scale <= 200; scale += 5) scales.add(scale);
        addScale(scales, Math.max(1, Math.min(200, current)));
        addScale(scales, Math.max(1, Math.min(200, fallback)));
        Collections.sort(scales);
        return scales;
    }

    private static void addScale(List<Integer> scales, int scale) {
        if (!scales.contains(scale)) scales.add(scale);
    }

    private static double normalize(double value, double low, double high) {
        return high > low ? (value - low) / (high - low) : 1.0;
    }

    private EarningsOptimizer() {}
}
