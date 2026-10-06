package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Conservative replay of local standalone arrivals, not realized earnings or a dispatch model. A result is only
 * a suggestion: this class has no screen, action, timer, FilterStore, or network authority.
 */
final class EarningsModel {
    static final long AR_RETAIN_MS = 30 * 24 * 60 * 60_000L;
    static final int MAX_AR_SNAPSHOTS = 200;
    static final double MAX_COST_CENTS_PER_MILE = 1000;
    static final long COOLDOWN_MS = 15 * 60_000L;
    static final int MAX_STEP_PERCENT = 5;
    static final int MIN_READABLE = 20;
    static final int MIN_MATCHING = 5;
    static final long MIN_OBSERVED_MS = 30 * 60_000L;
    static final int MIN_NEW_ARRIVALS = 5;
    static final long MIN_NEW_OBSERVED_MS = 10 * 60_000L;
    static final double MIN_GAIN_FRACTION = .05;
    static final double MIN_GAIN_CENTS_PER_HOUR = 100;

    static final class Config {
        final boolean enabled;
        /** Null is not entered; zero is an explicitly entered zero operating cost. */
        final Double vehicleCostCentsPerMile;
        final int floorPercent;
        final int ceilingPercent;

        Config(boolean enabled, Double vehicleCostCentsPerMile, int floorPercent, int ceilingPercent) {
            if (vehicleCostCentsPerMile != null && (!Double.isFinite(vehicleCostCentsPerMile)
                    || vehicleCostCentsPerMile < 0 || vehicleCostCentsPerMile > MAX_COST_CENTS_PER_MILE)) {
                throw new IllegalArgumentException("Vehicle cost must be between 0 and 1000 cents per mile");
            }
            if (floorPercent < 1 || ceilingPercent > 200 || floorPercent > ceilingPercent) {
                throw new IllegalArgumentException("Minimums range must be within 1 to 200 percent");
            }
            this.enabled = enabled;
            this.vehicleCostCentsPerMile = vehicleCostCentsPerMile;
            this.floorPercent = floorPercent;
            this.ceilingPercent = ceilingPercent;
        }

        static Config defaults() { return new Config(false, null, 90, 110); }
        String key() { return enabled + ":" + vehicleCostCentsPerMile + ":" + floorPercent + ":" + ceilingPercent; }
    }

    /** A user-reported percentage at a user-supplied date; never inferred from observed local outcomes. */
    static final class Snapshot {
        final int percent;
        final long at;
        Snapshot(int percent, long at) {
            if (percent < 0 || percent > 100 || at < 0) throw new IllegalArgumentException("Acceptance rate snapshot");
            this.percent = percent;
            this.at = at;
        }
    }

    static final class Adjustment {
        static final Adjustment NONE = new Adjustment(0, 0, "", "");
        final long at;
        final long latestArrivalAt;
        final String settingsKey;
        final String configKey;
        final long elapsedAt;
        final int boot;
        Adjustment(long at, long latestArrivalAt, String settingsKey, String configKey) {
            this(at, latestArrivalAt, settingsKey, configKey, -1, -1);
        }
        Adjustment(long at, long latestArrivalAt, String settingsKey, String configKey, long elapsedAt, int boot) {
            this.at = at;
            this.latestArrivalAt = latestArrivalAt;
            this.settingsKey = settingsKey;
            this.configKey = configKey;
            this.elapsedAt = elapsedAt;
            this.boot = boot;
        }
    }

    enum Status { OFF, NEEDS_COST, NO_RULES, OUTSIDE_RANGE, LEARNING, UNREADABLE, COOLDOWN,
        NEEDS_FRESH_EVIDENCE, STEADY, READY }

    static final class Recommendation {
        final Status status;
        final int currentPercent;
        final int suggestedPercent;
        /** Null until there is enough evidence; these are modeled offer rates, never actual take-home pay. */
        final Double currentNetCentsPerHour;
        final Double suggestedNetCentsPerHour;
        final int readable;
        final int matching;
        final int unreadable;
        final long observedMs;
        final double matchingPercent;
        final double arrivalsPerObservedHour;
        final Snapshot latestAr;
        final int arSnapshotCount;
        final boolean usingSinceArReport;
        final String settingsKey;
        final String suggestedSettingsKey;
        final String configKey;
        final long evaluatedAt;
        final long latestArrivalAt;
        final long generation;
        final boolean areaMode;

        private Recommendation(Status status, FilterSettings rules, Config config, Stats current, Stats suggested,
                               List<Snapshot> ar, boolean usingSinceArReport, long now, long generation) {
            this.status = status;
            this.currentPercent = rules.minimumScalePercent;
            this.suggestedPercent = status == Status.READY ? suggested.scale : currentPercent;
            this.currentNetCentsPerHour = current.supported() ? current.netCentsPerHour : null;
            this.suggestedNetCentsPerHour = status == Status.READY ? Double.valueOf(suggested.netCentsPerHour)
                    : currentNetCentsPerHour;
            this.readable = current.readable;
            this.matching = current.matching;
            this.unreadable = current.unreadable;
            this.observedMs = current.observedMs;
            this.matchingPercent = readable == 0 ? 0 : 100.0 * matching / readable;
            this.arrivalsPerObservedHour = observedMs == 0 ? 0 : readable * 3_600_000.0 / observedMs;
            this.latestAr = ar.isEmpty() ? null : ar.get(ar.size() - 1);
            this.arSnapshotCount = ar.size();
            this.usingSinceArReport = usingSinceArReport;
            this.settingsKey = settingsKey(rules);
            this.suggestedSettingsKey = settingsKey(rules.withMinimumScalePercent(suggestedPercent));
            this.configKey = config.key();
            this.evaluatedAt = now;
            this.latestArrivalAt = current.latestArrivalAt;
            this.generation = generation;
            this.areaMode = rules.scoreByArea;
        }

        boolean canAdjust() { return status == Status.READY && suggestedPercent != currentPercent; }

        String summary() {
            switch (status) {
                case OFF: return "Auto-manage minimums is off";
                case NEEDS_COST: return "Enter your vehicle cost per mile";
                case NO_RULES: return "Enable filtering and set minimums first";
                case OUTSIDE_RANGE: return "Current minimums are outside your auto-manage range";
                case UNREADABLE: return "Auto-manage needs fully readable offers";
                case LEARNING: return "Learning local offer earnings and frequency";
                case COOLDOWN: return "Waiting between minimums adjustments";
                case NEEDS_FRESH_EVIDENCE: return "Waiting for fresh offer evidence";
                case READY: return "Local evidence suggests " + suggestedPercent + "% minimums";
                default: return "Keeping minimums at " + currentPercent + "%";
            }
        }

        String detail() {
            String text = String.format(Locale.US,
                    "%d of %d readable arrivals match current %s rules (%.0f%%) over %.0f observed waiting minutes. ",
                    matching, readable, areaMode ? "area" : "strict", matchingPercent, observedMs / 60_000.0);
            if (currentNetCentsPerHour != null) {
                text += String.format(Locale.US, "Modeled net offer rate: $%.2f/hour. ", currentNetCentsPerHour / 100);
            }
            if (unreadable > 0) text += unreadable + " incomplete arrivals prevent an adjustment. ";
            text += "The model subtracts your entered vehicle cost on displayed route miles, then divides by "
                    + "displayed route minutes plus observed waiting per match. Extra mileage, unobserved waiting, "
                    + "route overruns, taxes and other costs are not known. This is not realized earnings or a guarantee. ";
            if (latestAr == null) text += "No acceptance-rate snapshot has been entered. ";
            else text += "Latest manually reported acceptance rate: " + latestAr.percent + "%. "
                    + (usingSinceArReport ? "This comparison uses observations since that report. "
                    : "There is not enough evidence since that report to narrow the 24-hour sample. ");
            text += "Reported AR is context, not proof that AR caused any arrival-rate change. "
                    + "Automatic changes require 20 readable arrivals, 5 matches, 30 observed minutes, "
                    + "a gain of at least 5% and $1/hour, and improvement in both time halves. "
                    + "Changes stay within your range, move at most 5 percentage points, and wait at least "
                    + "15 minutes plus fresh evidence. Higher minimums are more selective; the match share "
                    + "is a local replay, not your platform acceptance rate.";
            return text;
        }
    }

    private static final class Stats {
        final int scale;
        long observedMs;
        long latestArrivalAt;
        int readable;
        int matching;
        int unreadable;
        double netCents;
        double routeMinutes;
        double netCentsPerHour;
        boolean costKnown;
        Stats(int scale) { this.scale = scale; }
        boolean supported() {
            return costKnown && unreadable == 0 && readable >= MIN_READABLE && matching >= MIN_MATCHING
                    && observedMs >= MIN_OBSERVED_MS && observedMs <= QualifyingWait.RETAIN_MS
                    && Double.isFinite(netCentsPerHour);
        }
        boolean halfSupported() {
            return costKnown && unreadable == 0 && readable >= MIN_READABLE / 2 && matching >= 3
                    && observedMs >= MIN_NEW_OBSERVED_MS && Double.isFinite(netCentsPerHour);
        }
    }

    static Recommendation recommend(List<QualifyingWait.Sample> samples, FilterSettings rules, Config config,
                                    List<Snapshot> snapshots, Adjustment adjustment, long now) {
        return recommend(samples, rules, config, snapshots, adjustment, now, 0);
    }

    static Recommendation recommend(List<QualifyingWait.Sample> samples, FilterSettings rules, Config config,
                                    List<Snapshot> snapshots, Adjustment adjustment, long now, long generation) {
        List<QualifyingWait.Sample> history = retained(samples, now);
        List<Snapshot> ar = retainedAr(snapshots, now);
        Stats current = replay(history, rules, config);
        boolean sinceAr = false;
        if (!ar.isEmpty()) {
            long reportAt = ar.get(ar.size() - 1).at;
            List<QualifyingWait.Sample> afterReport = after(history, reportAt);
            Stats recent = replay(afterReport, rules, config);
            // A dated report may define a transparent observation window. Its numerical percentage never boosts
            // or suppresses dispatch estimates, and sparse cohorts never manufacture a relationship.
            if (recent.supported() && halvesSupported(afterReport, rules, config)) {
                history = afterReport;
                current = recent;
                sinceAr = true;
            }
        }
        Status status;
        Stats best = current;
        if (!config.enabled) status = Status.OFF;
        else if (config.vehicleCostCentsPerMile == null) status = Status.NEEDS_COST;
        else if (!rules.enabled || !rules.hasAnyRule()) status = Status.NO_RULES;
        else if (rules.minimumScalePercent < config.floorPercent || rules.minimumScalePercent > config.ceilingPercent) {
            status = Status.OUTSIDE_RANGE;
        } else if (current.unreadable > 0 || current.observedMs > QualifyingWait.RETAIN_MS) status = Status.UNREADABLE;
        else if (!current.supported() || !halvesSupported(history, rules, config)) status = Status.LEARNING;
        else if (adjustment.at > 0 && (now < adjustment.at || now - adjustment.at < COOLDOWN_MS)) status = Status.COOLDOWN;
        else if (adjustment.at > 0 && !freshEnough(history, adjustment.at)) status = Status.NEEDS_FRESH_EVIDENCE;
        else {
            // Favor the unchanged scale on ties. Replaying all bounds never authorizes a jump across them.
            for (int scale = config.floorPercent; scale <= config.ceilingPercent; scale++) {
                Stats candidate = replay(history, rules.withMinimumScalePercent(scale), config);
                if (candidate.supported() && candidate.netCentsPerHour > best.netCentsPerHour + .000001) best = candidate;
            }
            int next = Math.max(current.scale - MAX_STEP_PERCENT, Math.min(current.scale + MAX_STEP_PERCENT, best.scale));
            best = replay(history, rules.withMinimumScalePercent(next), config);
            status = next != current.scale && best.supported() && worthwhile(current, best)
                    && corroborated(history, rules, config, next) ? Status.READY : Status.STEADY;
        }
        return new Recommendation(status, rules, config, current, best, ar, sinceAr, now, generation);
    }

    private static Stats replay(List<QualifyingWait.Sample> history, FilterSettings rules, Config config) {
        Stats result = new Stats(rules.minimumScalePercent);
        result.costKnown = config.vehicleCostCentsPerMile != null;
        for (QualifyingWait.Sample sample : history) {
            result.observedMs += sample.observedMs;
            OfferSnapshot offer = sample.arrival;
            if (offer == null) continue;
            result.latestArrivalAt = Math.max(result.latestArrivalAt, sample.at);
            // QualifyingWait's producer excludes add-ons and active routes. Require plausible standalone route
            // facts even when a particular rule does not need them: costs/cycle time still need those facts.
            if (!readable(offer)) { result.unreadable++; continue; }
            OfferRule.Result decision = OfferRule.evaluate(offer.withoutPayBound(), rules).result;
            if (decision == OfferRule.Result.REVIEW) { result.unreadable++; continue; }
            result.readable++;
            if (decision != OfferRule.Result.KEEP) continue;
            result.matching++;
            result.routeMinutes += offer.minutes;
            if (result.costKnown) result.netCents += offer.payCents - config.vehicleCostCentsPerMile * offer.miles;
        }
        double cycleMinutes = result.routeMinutes + result.observedMs / 60_000.0;
        result.netCentsPerHour = cycleMinutes > 0 ? result.netCents * 60 / cycleMinutes : 0;
        return result;
    }

    private static boolean readable(OfferSnapshot offer) {
        return offer.payCents != null && offer.payCents > 0 && offer.payCents <= 1_000_000
                && offer.miles != null && Double.isFinite(offer.miles) && offer.miles >= AcceptedBest.PLAUSIBLE_MILES
                && offer.miles <= 1000 && offer.minutes != null && offer.minutes >= AcceptedBest.PLAUSIBLE_MINUTES
                && offer.minutes <= 24 * 60 && offer.stops != null && offer.stops >= AcceptedBest.PLAUSIBLE_STOPS
                && (!offer.itemCountApplicable || offer.items != null);
    }

    private static boolean worthwhile(Stats current, Stats proposed) {
        double gain = proposed.netCentsPerHour - current.netCentsPerHour;
        return gain >= Math.max(MIN_GAIN_CENTS_PER_HOUR, Math.abs(current.netCentsPerHour) * MIN_GAIN_FRACTION);
    }

    private static long midpoint(List<QualifyingWait.Sample> history) {
        long first = history.get(0).at - history.get(0).observedMs;
        long last = history.get(history.size() - 1).at;
        return first + (last - first) / 2;
    }

    private static boolean halvesSupported(List<QualifyingWait.Sample> history, FilterSettings rules, Config config) {
        if (history.isEmpty()) return false;
        long middle = midpoint(history);
        return replay(before(history, middle), rules, config).halfSupported()
                && replay(after(history, middle), rules, config).halfSupported();
    }

    private static boolean corroborated(List<QualifyingWait.Sample> history, FilterSettings rules, Config config, int scale) {
        long middle = midpoint(history);
        for (List<QualifyingWait.Sample> half : java.util.Arrays.asList(before(history, middle), after(history, middle))) {
            Stats current = replay(half, rules, config);
            Stats proposed = replay(half, rules.withMinimumScalePercent(scale), config);
            if (!current.halfSupported() || !proposed.halfSupported() || !worthwhile(current, proposed)) return false;
        }
        return true;
    }

    private static boolean freshEnough(List<QualifyingWait.Sample> history, long since) {
        int count = 0;
        long exposure = 0;
        for (QualifyingWait.Sample sample : after(history, since)) {
            exposure += sample.observedMs;
            if (sample.arrival != null && readable(sample.arrival)) count++;
        }
        return count >= MIN_NEW_ARRIVALS && exposure >= MIN_NEW_OBSERVED_MS;
    }

    /** Split exposure at the boundary; an arrival at the boundary belongs to the preceding interval only. */
    private static List<QualifyingWait.Sample> before(List<QualifyingWait.Sample> rows, long boundary) {
        List<QualifyingWait.Sample> out = new ArrayList<>();
        for (QualifyingWait.Sample row : rows) {
            if (row.at <= boundary) out.add(row);
            else if (row.at - row.observedMs < boundary) {
                out.add(new QualifyingWait.Sample(boundary, boundary - (row.at - row.observedMs), null));
            }
        }
        return out;
    }

    private static List<QualifyingWait.Sample> after(List<QualifyingWait.Sample> rows, long boundary) {
        List<QualifyingWait.Sample> out = new ArrayList<>();
        for (QualifyingWait.Sample row : rows) {
            if (row.at > boundary) out.add(new QualifyingWait.Sample(row.at,
                    Math.min(row.observedMs, row.at - boundary), row.arrival));
        }
        return out;
    }

    static List<QualifyingWait.Sample> retained(List<QualifyingWait.Sample> rows, long now) {
        List<QualifyingWait.Sample> out = new ArrayList<>();
        if (rows != null) for (QualifyingWait.Sample row : rows) {
            if (row != null && row.at <= now && now - row.at < QualifyingWait.RETAIN_MS) {
                out.add(new QualifyingWait.Sample(row.at, Math.min(row.observedMs,
                        QualifyingWait.RETAIN_MS - (now - row.at)), row.arrival));
            }
        }
        out.sort(Comparator.comparingLong(row -> row.at));
        if (out.size() > QualifyingWait.MAX_SAMPLES) out = new ArrayList<>(out.subList(out.size() - QualifyingWait.MAX_SAMPLES, out.size()));
        return out;
    }

    static List<Snapshot> retainedAr(List<Snapshot> rows, long now) {
        List<Snapshot> out = new ArrayList<>();
        if (rows != null) for (Snapshot row : rows) {
            if (row != null && row.at <= now && now - row.at < AR_RETAIN_MS) out.add(row);
        }
        out.sort(Comparator.comparingLong(row -> row.at));
        if (out.size() > MAX_AR_SNAPSHOTS) out = new ArrayList<>(out.subList(out.size() - MAX_AR_SNAPSHOTS, out.size()));
        return Collections.unmodifiableList(out);
    }

    /** Exact numeric settings identity, including inactive retained learning and per-item learning. No labels. */
    static String settingsKey(FilterSettings rules) {
        return rules.enabled + ":" + rules.flatCents + ":" + rules.perMileCents + ":" + rules.perMinuteCents
                + ":" + rules.perStopCents + ":" + rules.perItemCents + ":" + rules.maxStops + ":" + rules.risingOffers
                + ":" + rules.lastAcceptedCents + ":" + bestKey(rules.best) + ":" + rules.declined.payCents
                + ":" + bestKey(rules.declined.rates) + ":" + rules.scoreByArea + ":"
                + rules.hotspotProximityHundredths + ":" + rules.minimumScalePercent;
    }

    private static String bestKey(AcceptedBest best) {
        return best.minutePay + ":" + best.minutes + ":" + best.milePay + ":" + best.miles + ":"
                + best.stopPay + ":" + best.stops + ":" + best.itemPay + ":" + best.items;
    }

    private EarningsModel() {}
}
