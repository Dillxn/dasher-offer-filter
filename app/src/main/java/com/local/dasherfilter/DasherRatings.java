package com.local.dasherfilter;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dasher's Ratings page, as numbers (0.6.60), and the acceptance rate it takes to reach the top tier. Pure: no Android
 * class, no text kept. Autopilot's "Reach Platinum" goal ({@link FilterSettings#GOAL_PLATINUM}) is the result.
 *
 * <p>The overall rating is the sum of the factor points ("Acceptance rate 77% · 26 of 30 points", "On-time rate 90% ·
 * 20 of 30 points", ...), and the top tier is the highest mark on the rating bar (55, 75, 85: Platinum at 85). So the
 * acceptance-rate points the top tier needs are {@code top − (overall − acceptance points)}, and the rate that earns
 * them comes from the acceptance-rate bands Dasher shows ("Very high (75% - 100%) · 26 - 30 pts"), read linearly within
 * a band and rounded up, then corrected by every (rate, points) pair Dasher has shown. The goal is that rate plus
 * {@link #MARGIN} points, at most {@link #GOAL_CAP}.
 *
 * <p>Conservative by construction: a page counts only when its own wording is there (overall rating and an
 * acceptance-rate card with "N of M points"; for the bands, "About acceptance rate" or "Last 100 offers"), and a figure
 * out of range or out of order reads as nothing.
 */
final class DasherRatings {
    /** The top tier's overall rating when the bar's marks were not read: Platinum at 85. */
    static final int TOP_TIER_DEFAULT = 85;
    /** The owner's safety margin above the rate the top tier needs (0.6.60: "2 points"). */
    static final int MARGIN = 2;
    /** A goal never asks more than this; Autopilot's need is capped at 95 anyway. */
    static final int GOAL_CAP = 95;

    /** One acceptance-rate band: rates loPct–hiPct earn loPts–hiPts points. */
    static final class Band {
        final int loPct;
        final int hiPct;
        final int loPts;
        final int hiPts;

        Band(int loPct, int hiPct, int loPts, int hiPts) {
            this.loPct = loPct;
            this.hiPct = hiPct;
            this.loPts = loPts;
            this.hiPts = hiPts;
        }

        /** "75-100:26-30". */
        String encode() {
            return loPct + "-" + hiPct + ":" + loPts + "-" + hiPts;
        }
    }

    /**
     * Dasher's bands as its acceptance-rate page showed them on 20 November 2026, used until the page itself is read:
     * very high 75–100% 26–30 pts, high 50–74% 20–25, moderate 30–49% 15–19, low 16–29% 1–14, very low 0–15% 0.
     */
    static final List<Band> DEFAULT_BANDS = Collections.unmodifiableList(java.util.Arrays.asList(
            new Band(75, 100, 26, 30), new Band(50, 74, 20, 25), new Band(30, 49, 15, 19), new Band(16, 29, 1, 14),
            new Band(0, 15, 0, 0)));

    /** What the Ratings page's overview showed. */
    static final class Overview {
        final int overall;
        /** The bar's highest mark; -1 when not read. */
        final int topTier;
        final int arPercent;
        final int arPoints;
        final int arMaxPoints;

        Overview(int overall, int topTier, int arPercent, int arPoints, int arMaxPoints) {
            this.overall = overall;
            this.topTier = topTier;
            this.arPercent = arPercent;
            this.arPoints = arPoints;
            this.arMaxPoints = arMaxPoints;
        }

        /** The points every other factor gives. */
        int otherPoints() {
            return overall - arPoints;
        }
    }

    /** What the top tier asks of the acceptance rate. */
    static final class Target {
        final int topTier;
        final int otherPoints;
        /** Acceptance-rate points the top tier needs (≤ 0: already there on the other factors alone). */
        final int pointsNeeded;
        /** The lowest acceptance rate that earns them; -1 when even 100% does not. */
        final int arNeeded;
        /** Autopilot's goal: {@link #arNeeded} + {@link #MARGIN}, at most {@link #GOAL_CAP}; the cap when out of reach. */
        final int goal;

        Target(int topTier, int otherPoints, int pointsNeeded, int arNeeded, int goal) {
            this.topTier = topTier;
            this.otherPoints = otherPoints;
            this.pointsNeeded = pointsNeeded;
            this.arNeeded = arNeeded;
            this.goal = goal;
        }

        boolean reachable() {
            return arNeeded >= 0;
        }
    }

    private static final String SEP = "\\s*(?:\\|\\s*)?";
    private static final Pattern OVERALL = Pattern.compile("overall rating" + SEP + "(\\d{1,3})(?!\\d)");
    private static final Pattern AR_CARD = Pattern.compile("acceptance rate" + SEP + "(\\d{1,3}) ?%" + SEP
            + "(\\d{1,3}) of (\\d{1,3}) points");
    private static final Pattern BAND = Pattern.compile("\\((\\d{1,3}) ?% ?- ?(\\d{1,3}) ?%\\)" + SEP
            + "(\\d{1,3})(?: ?- ?(\\d{1,3}))? ?pts?\\b");
    private static final Pattern NUMBER = Pattern.compile("^\\d{1,3}$");

    /** Lower case, one space, dashes as "-". */
    static String normalize(String label) {
        return Normalizer.normalize(label, Normalizer.Form.NFKC)
                .replaceAll("[\\u2010-\\u2015\\u2212]", "-")
                .replaceAll("[\\p{Z}\\s]+", " ")
                .trim()
                .toLowerCase(Locale.US);
    }

    private static List<String> normalized(Collection<String> labels) {
        List<String> out = new ArrayList<>();
        if (labels == null) return out;
        for (String label : labels) {
            if (label == null) continue;
            String text = normalize(label);
            if (!text.isEmpty()) out.add(text);
        }
        return out;
    }

    /**
     * The overview: overall rating N, and an acceptance-rate card "Acceptance rate P% · A of M points" (as one label or
     * several); the top tier from the bare whole numbers right after the overall rating (the bar's marks, strictly
     * rising; the rating's own number first when it is a label of its own), before "rewards" or "rating factors". Null unless both are there and consistent: overall 0–100, P 0–100,
     * A ≤ M ≤ 100, A ≤ overall.
     */
    static Overview overview(Collection<String> labels) {
        List<String> texts = normalized(labels);
        String joined = String.join(" | ", texts);
        Matcher overall = OVERALL.matcher(joined);
        Matcher card = AR_CARD.matcher(joined);
        if (!overall.find() || !card.find()) return null;
        int total = Integer.parseInt(overall.group(1));
        int percent = Integer.parseInt(card.group(1));
        int points = Integer.parseInt(card.group(2));
        int max = Integer.parseInt(card.group(3));
        if (total > 100 || percent > 100 || points > max || max > 100 || points > total) return null;
        return new Overview(total, topTier(texts), percent, points, max);
    }

    private static int topTier(List<String> texts) {
        int start = -1;
        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i).contains("overall rating")) {
                start = i;
                break;
            }
        }
        if (start < 0) return -1;
        // "Overall rating" alone: its number is the next label, not a mark on the bar.
        boolean ownNumber = texts.get(start).matches(".*\\d.*");
        List<Integer> marks = new ArrayList<>();
        for (int i = start + 1; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text.contains("rewards") || text.contains("rating factors") || text.contains("acceptance rate")) break;
            if (!NUMBER.matcher(text).matches()) continue;
            if (!ownNumber) {
                ownNumber = true;
                continue;
            }
            marks.add(Integer.parseInt(text));
        }
        if (marks.size() < 2) return -1;
        for (int i = 1; i < marks.size(); i++) if (marks.get(i) <= marks.get(i - 1)) return -1;
        int top = marks.get(marks.size() - 1);
        return top > 0 && top <= 100 ? top : -1;
    }

    /**
     * The acceptance-rate bands, highest first, from the acceptance-rate page ("About acceptance rate" or "Last 100
     * offers" on it): each "(L% - H%)" followed by "A - B pts" or "A pts". Null unless at least three, each in order
     * (L ≤ H ≤ 100, A ≤ B), none overlapping, and the highest reaching 100%.
     */
    static List<Band> bands(Collection<String> labels) {
        List<String> texts = normalized(labels);
        String joined = String.join(" | ", texts);
        if (!joined.contains("about acceptance rate") && !joined.contains("last 100 offers")) return null;
        Matcher m = BAND.matcher(joined);
        List<Band> found = new ArrayList<>();
        while (m.find()) {
            int lo = Integer.parseInt(m.group(1));
            int hi = Integer.parseInt(m.group(2));
            int a = Integer.parseInt(m.group(3));
            int b = m.group(4) == null ? a : Integer.parseInt(m.group(4));
            if (lo > hi || hi > 100 || a > b || b > 100) return null;
            found.add(new Band(lo, hi, a, b));
        }
        return sane(found) ? sortedHighFirst(found) : null;
    }

    private static List<Band> sortedHighFirst(List<Band> bands) {
        List<Band> sorted = new ArrayList<>(bands);
        Collections.sort(sorted, new Comparator<Band>() {
            @Override public int compare(Band x, Band y) {
                return Integer.compare(y.loPct, x.loPct);
            }
        });
        return Collections.unmodifiableList(sorted);
    }

    private static boolean sane(List<Band> bands) {
        if (bands == null || bands.size() < 3) return false;
        List<Band> sorted = sortedHighFirst(bands);
        if (sorted.get(0).hiPct != 100) return false;
        for (int i = 1; i < sorted.size(); i++) {
            Band above = sorted.get(i - 1);
            Band below = sorted.get(i);
            if (below.hiPct >= above.loPct || below.hiPts > above.loPts) return false;
        }
        return true;
    }

    /** "75-100:26-30;50-74:20-25;..." */
    static String encode(List<Band> bands) {
        StringBuilder out = new StringBuilder();
        for (Band band : bands) {
            if (out.length() > 0) out.append(';');
            out.append(band.encode());
        }
        return out.toString();
    }

    /** {@link #encode}'s bands back; null for anything else. */
    static List<Band> decode(String text) {
        if (text == null || text.isEmpty()) return null;
        List<Band> bands = new ArrayList<>();
        try {
            for (String part : text.split(";")) {
                String[] halves = part.split(":");
                String[] pct = halves[0].split("-");
                String[] pts = halves[1].split("-");
                bands.add(new Band(Integer.parseInt(pct[0]), Integer.parseInt(pct[1]), Integer.parseInt(pts[0]),
                        Integer.parseInt(pts[1])));
            }
        } catch (RuntimeException malformed) {
            return null;
        }
        return sane(bands) ? sortedHighFirst(bands) : null;
    }

    /**
     * The top tier's ask. {@code bands}: Dasher's as read, or {@link #DEFAULT_BANDS}; {@code seen}: (rate, points)
     * pairs Dasher has shown, each {@code {percent, points}}.
     * <ol>
     *   <li>Points needed: top tier (the overview's, else {@link #TOP_TIER_DEFAULT}) − (overall − acceptance points).</li>
     *   <li>None needed: rate 0. More than the highest band gives: out of reach (-1).</li>
     *   <li>Else, in the lowest band reaching them, {@code L + ⌈(needed − A)·(H − L) ÷ (B − A)⌉} (L when A = B).</li>
     *   <li>Then no higher than any rate seen earning at least that many points, and above any rate seen earning
     *   fewer.</li>
     * </ol>
     * Goal: that rate + {@link #MARGIN}, between 1 and {@link #GOAL_CAP}; {@link #GOAL_CAP} out of reach.
     */
    static Target target(Overview overview, List<Band> bands, List<int[]> seen) {
        List<Band> table = bands != null && sane(bands) ? sortedHighFirst(bands) : DEFAULT_BANDS;
        int top = overview.topTier > 0 ? overview.topTier : TOP_TIER_DEFAULT;
        int other = overview.otherPoints();
        int needed = top - other;
        int rate;
        if (needed <= 0) {
            rate = 0;
        } else if (needed > table.get(0).hiPts) {
            rate = -1;
        } else {
            Band band = null;
            for (int i = table.size() - 1; i >= 0; i--) {
                if (table.get(i).hiPts >= needed) {
                    band = table.get(i);
                    break;
                }
            }
            if (band == null) return new Target(top, other, needed, -1, GOAL_CAP);
            if (needed <= band.loPts || band.hiPts == band.loPts) {
                rate = band.loPct;
            } else {
                int span = band.hiPct - band.loPct;
                int steps = band.hiPts - band.loPts;
                rate = band.loPct + ((needed - band.loPts) * span + steps - 1) / steps;
            }
            int lower = 0;
            int upper = 100;
            if (seen != null) {
                for (int[] pair : seen) {
                    if (pair == null || pair.length < 2) continue;
                    if (pair[1] >= needed) upper = Math.min(upper, pair[0]);
                    else lower = Math.max(lower, pair[0] + 1);
                }
            }
            rate = Math.min(rate, upper);
            if (lower <= 100) rate = Math.max(rate, lower);
        }
        int goal = rate < 0 ? GOAL_CAP : Math.max(1, Math.min(GOAL_CAP, rate + MARGIN));
        return new Target(top, other, needed, rate, goal);
    }

    private DasherRatings() {}
}
