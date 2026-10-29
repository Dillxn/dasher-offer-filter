package com.local.dasherfilter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A bounded comparison with offers normally seen near this local time, derived from retained facts only. */
final class TimeScore {
    static final int ONE = 10_000;
    static final long LOOKBACK_MS = 28L * 24 * 60 * 60_000;
    static final long BUCKET_MS = 15 * 60_000L;
    static final int MIN_NEAR = 12, MIN_ALL = 30, MIN_DAYS = 3;
    static final String EXPLANATION = "Compare offers with what you usually see near this time of day. "
            + "Scores rise during typically weaker hours and fall during stronger hours, by at most 25%. "
            + "Uses recent offers on this phone, including declined offers; needs at least 30 readable offers "
            + "overall and 12 within 90 minutes of this local time across 3 days. Until then, the normal score applies. "
            + "Changes apply between offers. Saved minimums stay as set; add-ons and automatic acceptance keep "
            + "their existing requirements.";

    /** Numeric decision provenance. A factor of ONE leaves every exact cutoff unchanged. */
    static final class Weight {
        static final Weight NONE = new Weight(ONE, 0, 0, 0, 0);
        final int factor, near, all, days, minute;
        Weight(int factor, int near, int all, int days, int minute) {
            this.factor = factor; this.near = near; this.all = all; this.days = days; this.minute = minute;
        }
        boolean ready() { return near >= MIN_NEAR && all >= MIN_ALL && days >= MIN_DAYS; }
        String encode() { return factor + ":" + near + ":" + all + ":" + days + ":" + minute; }
        static Weight decode(String value) {
            if (value == null || value.length() > 100) return null;
            try {
                String[] fields = value.split(":", -1);
                if (fields.length != 5) return null;
                int[] n = new int[5];
                for (int i = 0; i < n.length; i++) n[i] = Integer.parseInt(fields[i]);
                if (n[0] < 7500 || n[0] > 12500 || n[1] < 0 || n[1] > n[2]
                        || n[2] > DecisionLog.MAX_ENTRIES || n[3] < 0 || n[3] > n[1]
                        || n[4] < 0 || n[4] >= 1440) return null;
                Weight weight = new Weight(n[0], n[1], n[2], n[3], n[4]);
                return weight.ready() || weight.factor == ONE ? weight : null;
            } catch (NumberFormatException invalid) { return null; }
        }
        String describe() {
            if (!ready()) return "Time-aware: gathering history (" + near + " nearby offers across " + days
                    + " days; " + all + " overall). Normal score applies.";
            return String.format(Locale.US, "Time-aware: score ×%.3f near %02d:%02d local, from %d offers across %d days (%d overall).",
                    factor / (double) ONE, minute / 60, minute % 60, near, days, all);
        }
    }

    private static final class Sample {
        final int score;
        final double weight;
        Sample(int score, double weight) { this.score = score; this.weight = weight; }
    }

    /** Sample values use the same current saved/learned rules, without either adaptive adjustment or old scores. */
    static String key(FilterSettings rules) {
        return rules.withoutAutopilotAxes().withTimeWeight(Weight.NONE).scoringKey();
    }

    static Weight calculate(FilterSettings rules, List<DecisionLog.Entry> history, long now, ZoneId zone) {
        if (!rules.timeAware || !rules.hasMonetaryRule()) return Weight.NONE;
        long bucket = Math.floorDiv(now, BUCKET_MS) * BUCKET_MS;
        java.time.ZonedDateTime local = Instant.ofEpochMilli(bucket).atZone(zone);
        int minute = local.getHour() * 60 + local.getMinute();
        FilterSettings baseline = rules.withoutAutopilotAxes().withTimeWeight(Weight.NONE);
        List<Sample> all = new ArrayList<>(), near = new ArrayList<>();
        Set<LocalDate> days = new HashSet<>();
        List<DecisionLog.Entry> seen = new ArrayList<>();
        for (DecisionLog.Entry entry : history) {
            if (entry == null || entry.at >= bucket || entry.at < now - LOOKBACK_MS || entry.addOn || entry.replay
                    || !entry.autoDecline || OfferRule.SKIP_LIST.equals(entry.reason)
                    || entry.facts.payCents == null || entry.facts.payCents <= 0
                    || entry.facts.payAtMostCents != null || OfferSanity.looksMisread(entry.facts)) continue;
            boolean duplicate = false;
            for (DecisionLog.Entry previous : seen) {
                if (Math.abs(previous.at - entry.at) <= Autopilot.DEDUP_MS
                        && previous.facts.fingerprint().equals(entry.facts.fingerprint())) { duplicate = true; break; }
            }
            if (duplicate) continue;
            seen.add(entry);
            int score = AreaScore.scorePercent(baseline, entry.facts);
            if (score <= 0) continue;
            double recency = StrictMath.pow(2, -(now - entry.at) / (7.0 * 24 * 60 * 60_000));
            Sample sample = new Sample(score, recency);
            all.add(sample);
            java.time.ZonedDateTime time = Instant.ofEpochMilli(entry.at).atZone(zone);
            int gap = Math.abs(time.getHour() * 60 + time.getMinute() - minute);
            gap = Math.min(gap, 1440 - gap);
            if (gap <= 90) {
                near.add(sample);
                days.add(time.toLocalDate());
            }
        }
        int factor = ONE;
        if (all.size() >= MIN_ALL && near.size() >= MIN_NEAR && days.size() >= MIN_DAYS) {
            double ratio = median(all) / median(near);
            // Shrink sparse estimates toward no adjustment. Medians resist a few exceptionally large payouts.
            double confidence = near.size() / (near.size() + 20.0);
            factor = (int) Math.round(ONE * Math.max(.75, Math.min(1.25, 1 + confidence * (ratio - 1))));
        }
        return new Weight(factor, near.size(), all.size(), days.size(), minute);
    }

    private static double median(List<Sample> samples) {
        samples.sort(Comparator.comparingInt(s -> s.score));
        double total = 0, accumulated = 0;
        for (Sample sample : samples) total += sample.weight;
        for (Sample sample : samples) {
            accumulated += sample.weight;
            if (accumulated >= total / 2) return sample.score;
        }
        return samples.get(samples.size() - 1).score;
    }
    private TimeScore() {}
}
