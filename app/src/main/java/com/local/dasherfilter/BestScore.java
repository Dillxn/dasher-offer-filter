package com.local.dasherfilter;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The best score (0.6.68; the owner, 25 November 2026: "we should track highest mins area score of an offer (hi
 * score?) and make a derivative score which is offer's area score as a % of the highest score, and really that
 * derivative score is the score we want to display and make decisions on in the forefront").
 *
 * <p>Derived, never stored: the highest score against your current minimums ({@link AreaScore#scorePercent} under the
 * selected model, without Autopilot's working rates, the time-aware factor or the bar) of an offer you accepted by hand
 * in the retained history (a line with an ACCEPTED step: never an automatic accept, an add-on, a skipped store's
 * offer, a misread offer, one over max stops or one whose pay or a needed figure was not read). Accepts made while
 * auto-decline was paused count, as they teach (0.6.59). It is held to {@value FilterSettings#BEST_LEAST}–{@value
 * FilterSettings#BEST_NONE}: an accept that beat the minimums asks no more than them (the minimums themselves rise
 * for it), and none known is exactly the minimums. Learning is always on from 0.6.68 (its Learn switch is gone); the
 * internal flag stays only so a kept safety test can check that nothing is read with it off and no rule set, and then
 * the best score does not apply. Recomputed whenever the history or the minimums change, so Clear history, a change of
 * minimums or the best accept leaving the 200 kept lines all follow at once.
 *
 * <p>A consequence the owner should know: the scale follows the minimums, so raising one minimum lowers the best
 * score with it, and changes how the measures are weighed more than how much an offer must pay overall.
 *
 * <p>Why accepted offers only: the minimums grow from what you accept, cumulatively per measure, so no one accepted
 * offer is likely to score 100% of them; the best offer you took is the reference "100%" the owner described. An offer
 * you never took (expired, declined) does not move it, so one outlier you passed on cannot raise every requirement.
 *
 * <p>{@link DecisionLog} publishes the accepted lines' facts under its own lock whenever the history changes
 * ({@link #publish}); reading the best score takes no lock, so it can be read under FilterStore's.
 */
final class BestScore {
    /** The hand-accepted standalone facts of the retained history; null until the history is loaded. */
    private static volatile List<OfferSnapshot> accepted;
    private static volatile Cached cached;
    private static final ExecutorService WARM = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "best-score");
        thread.setDaemon(true);
        return thread;
    });

    private static final class Cached {
        final List<OfferSnapshot> source;
        final String key;
        final int percent;
        Cached(List<OfferSnapshot> source, String key, int percent) {
            this.source = source; this.key = key; this.percent = percent;
        }
    }

    /** Under DecisionLog's lock, with every change of the history (and its first load or a clear). */
    static void publish(List<DecisionLog.Entry> all) {
        List<OfferSnapshot> facts = new ArrayList<>();
        if (all != null) {
            for (DecisionLog.Entry entry : all) {
                if (counts(entry)) facts.add(entry.facts);
            }
        }
        accepted = Collections.unmodifiableList(facts);
    }

    /** Whether a history line can set the best score: a readable standalone offer you accepted by hand. */
    static boolean counts(DecisionLog.Entry entry) {
        return entry != null && !entry.addOn && entry.facts != null && entry.facts.payCents != null
                && entry.facts.payCents > 0 && DecisionLog.handAcceptedAt(entry) != Long.MIN_VALUE
                && !OfferRule.SKIP_LIST.equals(entry.reason) && !OfferSanity.looksMisread(entry.facts);
    }

    /** Loads the history on a thread of its own when it is not loaded yet, so the best score is known. */
    static void warm(Context context) {
        if (accepted != null || context == null) return;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        WARM.execute(() -> {
            try {
                DecisionLog.recent(app, 1);
            } catch (RuntimeException unavailable) {
                // Unknown history: the minimums themselves, as with none.
            }
        });
    }

    /**
     * The best score for {@code rules}, {@value FilterSettings#BEST_LEAST}–{@value FilterSettings#BEST_NONE};
     * {@value FilterSettings#BEST_NONE} with no accepted offer known (or the history not loaded yet).
     */
    static int percent(FilterSettings rules) {
        List<OfferSnapshot> source = accepted;
        if (rules == null || !rules.acceptanceLearning || source == null || source.isEmpty() || !rules.hasMonetaryRule()) {
            return FilterSettings.BEST_NONE;
        }
        FilterSettings base = minimumsOf(rules);
        String key = base.scoringKey();
        Cached hit = cached;
        if (hit != null && hit.source == source && hit.key.equals(key)) return hit.percent;
        int percent = highest(base, source);
        cached = new Cached(source, key, percent);
        return percent;
    }

    /**
     * The score {@code offer} has under {@code rules} once it counts among the offers you accepted (an accept's lesson
     * runs before its line is marked accepted): its share of {@code rules}' minimums as a percent of the best share among
     * the kept accepts and it; -1 when a needed figure was not read.
     */
    static int scoreWith(FilterSettings rules, OfferSnapshot offer) {
        if (rules == null || offer == null) return -1;
        List<OfferSnapshot> facts = new ArrayList<>();
        List<OfferSnapshot> source = accepted;
        if (source != null) facts.addAll(source);
        facts.add(offer);
        int best = rules.acceptanceLearning && rules.hasMonetaryRule()
                ? highest(minimumsOf(rules), facts) : FilterSettings.BEST_NONE;
        return AreaScore.scorePercent(rules.withBest(best), offer);
    }

    /** The rules the best score is measured against: the minimums themselves, nothing scaling them. */
    static FilterSettings minimumsOf(FilterSettings rules) {
        return rules.withoutAutopilotAxes().withoutTimeAdjustment().withBest(FilterSettings.BEST_NONE)
                .withMinimumScalePercent(FilterSettings.BAR_AT_MINIMUMS);
    }

    /** The best of {@code facts} against {@code base}, held to the range; the minimums with none scored. */
    static int highest(FilterSettings base, List<OfferSnapshot> facts) {
        int best = -1;
        for (OfferSnapshot offer : facts) {
            if (base.maxStops > 0 && offer.stops != null && offer.stops > base.maxStops) continue;
            best = Math.max(best, AreaScore.scorePercent(base, offer));
        }
        if (best < 0) return FilterSettings.BEST_NONE;
        return Math.max(FilterSettings.BEST_LEAST, Math.min(FilterSettings.BEST_NONE, best));
    }

    private BestScore() {}
}
