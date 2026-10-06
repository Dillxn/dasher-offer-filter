package com.local.dasherfilter;

import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Seeded properties of the one strict rule over 20,000 random offers and rule sets (pure JUnit):
 * <ul>
 * <li>θ ({@link AreaScore#passThreshold}) is exactly the largest bar at which an offer is not declined, for every bar
 * from 1 to 200;</li>
 * <li>for a complete offer within max stops, KEEP ⟺ score ≥ bar (θ is its score);</li>
 * <li>a decline never shows a score at or above its bar, unless max stops declined it;</li>
 * <li>raising pay, per mile, per minute or the bar, or adding or lowering a max stops limit, never makes a decision
 * less strict (DECLINE stays DECLINE; nothing becomes KEEP that was not);</li>
 * <li>below-minimum passes are exactly the KEEPs that miss 100% of the minimums, and an automatic Accept is only ever
 * eligible at 100% of the minimums or more.</li>
 * </ul>
 */
public final class MonotonicityPropertyTest {
    private static final long SEED = 0x2026_1006_0500L;
    private static final int CASES = 20_000;

    private static FilterSettings randomRules(Random random) {
        int flat = random.nextInt(10) < 3 ? 0 : 1 + random.nextInt(3_000);
        int mile = random.nextInt(10) < 3 ? 0 : 1 + random.nextInt(500);
        int minute = random.nextInt(10) < 3 ? 0 : 1 + random.nextInt(100);
        int maxStops = random.nextInt(10) < 6 ? 0 : 1 + random.nextInt(5);
        int[] goals = {70, 50, 0};
        return new FilterSettings(random.nextInt(20) != 0, flat, mile, minute, maxStops, random.nextBoolean(),
                goals[random.nextInt(3)], 1 + random.nextInt(200));
    }

    private static OfferSnapshot randomOffer(Random random) {
        Integer pay = random.nextInt(100) < 8 ? null : random.nextInt(8_000);
        Integer ceiling = pay == null && random.nextBoolean() ? random.nextInt(8_000) : null;
        Double miles;
        int shape = random.nextInt(100);
        if (shape < 8) miles = null;
        else if (shape < 13) miles = 0.0;
        else if (shape < 60) miles = random.nextInt(400) / 10.0;
        else if (shape < 90) miles = random.nextInt(4_000) / 100.0;
        else miles = new double[] {1.0301, 2.1, 0.3, 4.05, 12.345, 0.01}[random.nextInt(6)];
        Integer minutes = random.nextInt(100) < 8 ? null : random.nextInt(100) < 5 ? 0 : random.nextInt(120);
        Integer stops = random.nextInt(100) < 8 ? null : random.nextInt(6);
        OfferSnapshot offer = new OfferSnapshot(pay, miles, minutes, stops, ceiling);
        if (random.nextInt(10) == 0) offer = offer.withItems(random.nextBoolean() ? null : 1 + random.nextInt(20), true);
        return offer;
    }

    /** One rule made stricter (or a max stops limit added or lowered). */
    private static FilterSettings stricter(FilterSettings rules, Random random) {
        int change = random.nextInt(5);
        int raise = 1 + random.nextInt(random.nextBoolean() ? 5 : 800);
        switch (change) {
            case 0: return rules.withMinimums(rules.flatCents + raise, rules.perMileCents, rules.perMinuteCents);
            case 1: return rules.withMinimums(rules.flatCents, rules.perMileCents + raise, rules.perMinuteCents);
            case 2: return rules.withMinimums(rules.flatCents, rules.perMileCents, rules.perMinuteCents + raise);
            case 3: return rules.withMinimumScalePercent(Math.min(200, rules.minimumScalePercent + raise));
            default:
                if (rules.maxStops == 0) return rules.withMaxStops(1 + random.nextInt(5));
                return rules.withMaxStops(Math.max(1, rules.maxStops - 1 - random.nextInt(2)));
        }
    }

    private static int rank(OfferRule.Result result) {
        return result == OfferRule.Result.DECLINE ? 0 : result == OfferRule.Result.REVIEW ? 1 : 2;
    }

    private static boolean overMaxStops(FilterSettings rules, OfferSnapshot offer) {
        return rules.maxStops > 0 && offer.stops != null && offer.stops > rules.maxStops;
    }

    private static boolean complete(FilterSettings rules, OfferSnapshot offer) {
        return offer.payCents != null && offer.miles != null && offer.minutes != null && offer.stops != null
                && !overMaxStops(rules, offer) && rules.hasMonetaryRule();
    }

    @Test
    public void thetaIsExactlyTheLargestBarThatDoesNotDecline() {
        Random random = new Random(SEED);
        int declines = 0, keeps = 0, reviews = 0, ceilings = 0;
        for (int i = 0; i < CASES; i++) {
            FilterSettings rules = randomRules(random);
            OfferSnapshot offer = randomOffer(random);
            if (offer.payAtMostCents != null) ceilings++;
            int theta = AreaScore.passThreshold(rules, offer);
            int score = AreaScore.scorePercent(rules, offer);
            boolean complete = complete(rules, offer);
            if (complete && AreaScore.required100(rules, offer).signum() > 0 && theta != score) {
                fail("complete offer: θ " + theta + " ≠ score " + score + " for " + offer.summary() + " under "
                        + rules.describe());
            }
            for (int bar = 1; bar <= 200; bar++) {
                OfferRule.Decision decision = OfferRule.evaluate(offer, rules.withMinimumScalePercent(bar));
                boolean declined = decision.result == OfferRule.Result.DECLINE;
                if ((theta >= bar) == declined) {
                    fail("θ " + theta + " at bar " + bar + " but " + decision.summary() + " for " + offer.summary()
                            + " under " + rules.describe());
                }
                if (declined && !overMaxStops(rules, offer) && decision.scorePercent >= bar) {
                    fail("a decline shows score " + decision.scorePercent + " at bar " + bar + ": " + offer.summary());
                }
                if (complete && score >= 0 && (decision.result == OfferRule.Result.KEEP) != (score >= bar)) {
                    fail("KEEP ⟺ score ≥ bar broke at " + bar + " (score " + score + "): " + offer.summary());
                }
                if (decision.belowMinimums != (decision.result == OfferRule.Result.KEEP && bar < 100
                        && decision.scorePercent >= 0 && decision.scorePercent < 100)) {
                    fail("belowMinimums " + decision.belowMinimums + " at bar " + bar + ": " + decision.summary());
                }
                if (decision.belowMinimums && decision.scorePercent < bar) {
                    fail("a below-minimum pass under its own bar: " + decision.summary());
                }
                if (bar == rules.minimumScalePercent) {
                    if (declined) declines++;
                    else if (decision.result == OfferRule.Result.KEEP) keeps++;
                    else reviews++;
                }
            }
        }
        // The generator reaches every outcome often, and the "+$" ceiling too.
        assertTrue("declines " + declines, declines > 2_000);
        assertTrue("keeps " + keeps, keeps > 2_000);
        assertTrue("reviews " + reviews, reviews > 2_000);
        assertTrue("ceilings " + ceilings, ceilings > 500);
    }

    /** Add-ons on random routes with random explicit increments, parsed once and judged under many rule pairs. */
    private static AddOnOffer[] addOnPool(Random random) {
        AddOnOffer[] pool = new AddOnOffer[400];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = AddOnOffer.parse(randomOffer(random), java.util.Arrays.asList("Add to route",
                    "+$" + (random.nextInt(900) / 100.0), "+" + random.nextInt(9) + " mi",
                    "+" + random.nextInt(30) + " min", "+" + random.nextInt(3) + " stops"));
        }
        return pool;
    }

    @Test
    public void strongerRulesNeverMakeADecisionLessStrict() {
        Random random = new Random(SEED + 1);
        AddOnOffer[] addOns = addOnPool(random);
        int stayedDeclined = 0, addOnDeclines = 0;
        for (int i = 0; i < CASES; i++) {
            FilterSettings rules = randomRules(random);
            OfferSnapshot offer = randomOffer(random);
            FilterSettings stronger = stricter(rules, random);
            OfferRule.Result before = OfferRule.evaluate(offer, rules).result;
            OfferRule.Result after = OfferRule.evaluate(offer, stronger).result;
            if (rank(after) > rank(before)) {
                fail(before + " became " + after + " for " + offer.summary() + ": " + rules.describe() + " → "
                        + stronger.describe());
            }
            if (before == OfferRule.Result.DECLINE) {
                assertEquals(offer.summary() + " under " + stronger.describe(), OfferRule.Result.DECLINE, after);
                stayedDeclined++;
            }
            // The same holds for add-ons built on a random route.
            AddOnOffer addOn = addOns[random.nextInt(addOns.length)];
            OfferRule.Result addBefore = OfferRule.evaluateAddOn(addOn, rules).result;
            OfferRule.Result addAfter = OfferRule.evaluateAddOn(addOn, stronger).result;
            if (rank(addAfter) > rank(addBefore)) {
                fail("add-on " + addBefore + " became " + addAfter + ": " + rules.describe() + " → "
                        + stronger.describe());
            }
            if (addBefore == OfferRule.Result.DECLINE) addOnDeclines++;
        }
        assertTrue("declines checked " + stayedDeclined, stayedDeclined > 2_000);
        assertTrue("add-on declines checked " + addOnDeclines, addOnDeclines > 1_000);
    }

    @Test
    public void anAutomaticAcceptNeedsAtLeastOneHundredPercentOfTheMinimums() {
        Random random = new Random(SEED + 2);
        int eligible = 0;
        for (int i = 0; i < CASES; i++) {
            FilterSettings rules = randomRules(random).withEnabled(true);
            OfferSnapshot offer = randomOffer(random);
            if (!AutoAccept.eligible(offer, rules, true, false, false, 30)) continue;
            eligible++;
            OfferRule.Decision atBar = OfferRule.evaluate(offer, rules);
            OfferRule.Decision atMinimums = OfferRule.evaluate(offer, rules.withMinimumScalePercent(100));
            assertEquals(offer.summary(), OfferRule.Result.KEEP, atBar.result);
            assertEquals(offer.summary(), OfferRule.Result.KEEP, atMinimums.result);
            assertTrue(!atBar.belowMinimums);
            assertTrue(rules.hasMonetaryRule());
            int score = AreaScore.scorePercent(rules, offer);
            assertTrue("score " + score, score < 0 || score >= Math.max(100, rules.minimumScalePercent));
        }
        assertTrue("eligible " + eligible, eligible > 500);
    }
}
