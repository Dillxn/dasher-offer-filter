package com.local.dasherfilter;

import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Making the learned (adaptive) minimums the set ones: exact rounding, never lowering, and never looser. */
public final class AdoptAdaptiveTest {
    private static FilterSettings learned(int flat, int mile, int minute, int stop, int lastAccepted, AcceptedBest best,
                                          DeclinedFloor declined) {
        return new FilterSettings(true, flat, mile, minute, stop, 3, true, lastAccepted, best, declined);
    }

    @Test
    public void eachRateRoundsToTheSmallestWholeCentThatIsNeverLooser() {
        // A best accepted rate is matched (rounded up), so its own rate rounded up to the cent is enough.
        FilterSettings best = learned(0, 0, 0, 0, 0, new AcceptedBest(0, 0, 2370, 10.0, 0, 0), DeclinedFloor.NONE);
        assertEquals("$23.70 over 10 mi", 237, best.adoptAdaptive().perMileCents);
        // A declined rate must be beaten, so exactly $2.00/mi is not enough: $2.01.
        FilterSettings declined = learned(0, 0, 0, 0, 0, AcceptedBest.NONE,
                new DeclinedFloor(0, new AcceptedBest(0, 0, 2000, 10.0, 0, 0)));
        assertEquals("$20.00 declined over 10 mi", 201, declined.adoptAdaptive().perMileCents);
        FilterSettings stops = learned(0, 0, 0, 0, 0, new AcceptedBest(0, 0, 0, 0, 1000, 3), DeclinedFloor.NONE);
        assertEquals("$10 over 3 stops is $3.33⅓ a stop, so $3.34", 334, stops.adoptAdaptive().perStopCents);

        // $14.20 over 24 min is $0.591…/min, labelled "$0.59", but only $0.60 is never looser.
        FilterSettings accepted = learned(0, 0, 0, 0, 1420, new AcceptedBest(1420, 24, 1420, 6.0, 1420, 2),
                new DeclinedFloor(2500, new AcceptedBest(900, 12, 1000, 7.3, 0, 0)));
        FilterSettings adopted = accepted.adoptAdaptive();
        assertEquals("more than the highest accepted $14.20 and the declined $25.00", 2501, adopted.flatCents);
        assertEquals("best $2.366…/mi (237) against declined $1.369…/mi (137)", 237, adopted.perMileCents);
        assertEquals("best $0.591…/min (60) against declined $0.75/min, beaten (76)", 76, adopted.perMinuteCents);
        assertEquals(710, adopted.perStopCents);
    }

    @Test
    public void aSetMinimumIsNeverLoweredAndNothingElseChanges() {
        AcceptedBest best = new AcceptedBest(1420, 24, 1420, 6.0, 1420, 2);
        DeclinedFloor declined = new DeclinedFloor(900, new AcceptedBest(0, 0, 1000, 8.0, 0, 0));
        FilterSettings high = new FilterSettings(false, 3000, 400, 100, 900, 4, true, 1420, best, declined);
        FilterSettings adopted = high.adoptAdaptive();
        assertArrayEquals(high.minimums(), adopted.minimums());
        FilterSettings mixed = new FilterSettings(false, 3000, 150, 100, 0, 4, true, 1420, best, declined);
        adopted = mixed.adoptAdaptive();
        assertArrayEquals("only the lower ones rise", new int[] {3000, 237, 100, 710, 0}, adopted.minimums());
        assertEquals("paused stays paused", false, adopted.enabled);
        assertEquals(4, adopted.maxStops);
        assertTrue("the adaptive minimum stays on", adopted.risingOffers);
        assertEquals("and keeps what it learned", 1420, adopted.lastAcceptedCents);
        assertSame(best, adopted.best);
        assertSame(declined, adopted.declined);
    }

    /**
     * The adaptive minimum never judges an add-on, but set minimums do (the flat on the combined route, the rates on the
     * added pay), so adopting can decline an add-on the adaptive minimum let through. That is intended, and said where
     * the user adopts; this pins it so a change to either side is deliberate.
     */
    @Test
    public void adoptingHoldsAddOnsToTheSetMinimumsWhichTheAdaptiveOneNeverJudged() {
        AcceptedBest best = AcceptedBest.NONE.raisedBy(new OfferSnapshot(1420, 6.0, 24, 2));
        FilterSettings learning = new FilterSettings(true, 0, 0, 0, 0, 0, true, 1420, best);
        AddOnOffer addOn = AddOnOffer.parse(new OfferSnapshot(2500, 10.0, null, 2),
                java.util.Arrays.asList("Add to route", "+$6.00", "+3.0 mi"));
        assertEquals("the adaptive minimum never judges an add-on", OfferRule.Result.KEEP,
                OfferRule.evaluateAddOn(addOn, learning).result);

        FilterSettings adopted = learning.adoptAdaptive();
        assertArrayEquals(new int[] {1421, 237, 60, 710, 0}, adopted.minimums());
        OfferRule.Decision decision = OfferRule.evaluateAddOn(addOn, adopted);
        assertEquals("3.0 added miles at the adopted $2.37 ask $7.11 of the added $6.00", OfferRule.Result.DECLINE,
                decision.result);
        assertEquals(711, decision.requiredCents);
        assertEquals("and so with the adaptive minimum turned off", OfferRule.Result.DECLINE,
                OfferRule.evaluateAddOn(addOn, adopted.withoutRisingBaseline()).result);
    }

    @Test
    public void withNothingLearnedNothingChanges() {
        FilterSettings rules = new FilterSettings(true, 700, 150, 30, 100, 3, true, 0);
        assertArrayEquals(rules.minimums(), rules.adoptAdaptive().minimums());
        FilterSettings none = new FilterSettings(true, 0, 0, 0, 0, 0, true, 0);
        assertArrayEquals(new int[5], none.adoptAdaptive().minimums());
        // Only the measures something was learned on change.
        FilterSettings perMileOnly = learned(700, 0, 30, 0, 0, new AcceptedBest(0, 0, 1000, 4.0, 0, 0),
                DeclinedFloor.NONE);
        assertArrayEquals(new int[] {700, 250, 30, 0, 0}, perMileOnly.adoptAdaptive().minimums());
    }

    @Test
    public void huge_or_unusable_learned_values_saturate_and_never_go_negative() {
        FilterSettings huge = learned(0, 0, 0, 0, Integer.MAX_VALUE,
                new AcceptedBest(Integer.MAX_VALUE, 5, Integer.MAX_VALUE, 0.5, Integer.MAX_VALUE, 2),
                new DeclinedFloor(Integer.MAX_VALUE, new AcceptedBest(Integer.MAX_VALUE, 10, Integer.MAX_VALUE, 1e-300,
                        Integer.MAX_VALUE, 2)));
        assertArrayEquals("held to what Settings accepts, $1,000",
                new int[] {FilterSettings.MOST_CENTS, FilterSettings.MOST_CENTS, FilterSettings.MOST_CENTS,
                        FilterSettings.MOST_CENTS, 0}, huge.adoptAdaptive().minimums());
        FilterSettings odd = learned(0, 0, 0, 0, 0, new AcceptedBest(0, 0, 1000, Double.POSITIVE_INFINITY, 0, 0),
                new DeclinedFloor(0, new AcceptedBest(0, 0, 1000, Double.NaN, 0, 0)));
        assertArrayEquals("an unusable distance teaches nothing", new int[5], odd.adoptAdaptive().minimums());
    }

    /**
     * Over many made-up rules and offers: any offer the old rules (with their adaptive minimums) decline, the adopted
     * rules decline too, even with the adaptive minimum then turned off; and for an offer whose amounts are all read,
     * the adopted set minimums alone ask at least as much as everything the old rules asked.
     */
    @Test
    public void theAdoptedMinimumsAloneDeclineEveryOfferTheAdaptiveOnesDeclined() {
        Random random = new Random(20260930L);
        int declinedChecked = 0;
        for (int round = 0; round < 400; round++) {
            FilterSettings old = randomRules(random);
            FilterSettings adopted = old.adoptAdaptive();
            FilterSettings setAlone = adopted.withoutRisingBaseline();
            for (int i = 0; i < old.minimums().length; i++) {
                assertTrue("never lowered", adopted.minimums()[i] >= old.minimums()[i]);
                assertTrue("never negative", adopted.minimums()[i] >= 0);
            }
            for (int offerIndex = 0; offerIndex < 60; offerIndex++) {
                OfferSnapshot offer = randomOffer(random);
                OfferRule.Decision before = OfferRule.evaluate(offer, old);
                if (before.result == OfferRule.Result.DECLINE) {
                    declinedChecked++;
                    assertEquals(offer + " under " + old.describe(), OfferRule.Result.DECLINE,
                            OfferRule.evaluate(offer, adopted).result);
                    assertEquals(offer + " by the set minimums alone, " + setAlone.describe(),
                            OfferRule.Result.DECLINE, OfferRule.evaluate(offer, setAlone).result);
                }
                boolean allRead = offer.payCents != null && offer.miles != null && offer.minutes != null
                        && offer.stops != null && offer.stops >= AcceptedBest.PLAUSIBLE_STOPS;
                if (allRead) {
                    assertTrue(offer + ": " + setAlone.describe() + " against " + old.describe(),
                            OfferRule.evaluate(offer, setAlone).requiredCents >= before.requiredCents);
                }
            }
        }
        assertTrue("the check saw plenty of declines: " + declinedChecked, declinedChecked > 2000);
    }

    private static FilterSettings randomRules(Random random) {
        AcceptedBest best = new AcceptedBest(chance(random) ? 500 + random.nextInt(4000) : 0,
                10 + random.nextInt(80), chance(random) ? 500 + random.nextInt(4000) : 0, miles(random, 2.0),
                chance(random) ? 500 + random.nextInt(4000) : 0, 2 + random.nextInt(4));
        AcceptedBest declinedRates = new AcceptedBest(chance(random) ? 300 + random.nextInt(4000) : 0,
                10 + random.nextInt(80), chance(random) ? 300 + random.nextInt(4000) : 0, miles(random, 2.0),
                chance(random) ? 300 + random.nextInt(4000) : 0, 2 + random.nextInt(4));
        DeclinedFloor declined = new DeclinedFloor(chance(random) ? 300 + random.nextInt(4000) : 0, declinedRates);
        return new FilterSettings(true, chance(random) ? random.nextInt(2000) : 0,
                chance(random) ? random.nextInt(300) : 0, chance(random) ? random.nextInt(80) : 0,
                chance(random) ? random.nextInt(600) : 0, chance(random) ? 2 + random.nextInt(3) : 0, true,
                chance(random) ? 500 + random.nextInt(3000) : 0, best, declined);
    }

    private static OfferSnapshot randomOffer(Random random) {
        return new OfferSnapshot(random.nextInt(12) == 0 ? null : 100 + random.nextInt(5000),
                random.nextInt(10) == 0 ? null : miles(random, 0.1),
                random.nextInt(10) == 0 ? null : 1 + random.nextInt(90),
                random.nextInt(10) == 0 ? null : 1 + random.nextInt(5));
    }

    /** Miles as offers show them (one or two decimals), and now and then an awkward binary fraction. */
    private static double miles(Random random, double least) {
        double miles = least + random.nextDouble() * 25;
        switch (random.nextInt(3)) {
            case 0: return Math.round(miles * 10) / 10.0;
            case 1: return Math.round(miles * 100) / 100.0;
            default: return miles;
        }
    }

    private static boolean chance(Random random) {
        return random.nextInt(3) != 0;
    }
}
