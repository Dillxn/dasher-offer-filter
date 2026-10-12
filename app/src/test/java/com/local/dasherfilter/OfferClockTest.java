package com.local.dasherfilter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The offer clock (0.6.2): each 3-hour slot's middle score, as a percent of the minimums now, from the history kept. */
public final class OfferClockTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    /** $4.00 minimum pay, nothing else. */
    private static final FilterSettings FOUR = FilterSettings.of(true, 400, 0, 0, 0);

    private static long at(int hour, int minute) {
        return LocalDateTime.of(2026, 10, 11, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static DecisionLog.Entry offer(long at, int pay, double miles, int minutes) {
        return line(at, false, new OfferSnapshot(pay, miles, minutes, 1), DecisionLog.Action.PASSES);
    }

    private static DecisionLog.Entry line(long at, boolean addOn, OfferSnapshot facts, DecisionLog.Action action) {
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, addOn, facts, 0, OfferRule.Result.KEEP, "", action,
                true, Collections.<String>emptyList());
    }

    /** Six $4.00 offers from 3 to 6 am and $4, $6, $8 and $10 from 6 to 9 pm, a quarter of an hour apart, newest first. */
    private static List<DecisionLog.Entry> day() {
        List<DecisionLog.Entry> entries = new ArrayList<>();
        int[] evening = {1000, 800, 600, 400};
        for (int i = 0; i < evening.length; i++) entries.add(offer(at(20, 45 - 15 * i), evening[i], 3.0, 15));
        for (int i = 0; i < 6; i++) entries.add(offer(at(5, 50 - 10 * i), 400, 2.0, 12));
        return entries;
    }

    @Test
    public void eachSlotIsTheLowerMiddleScoreOfItsOffersUnderTheMinimumsNow() {
        OfferClock clock = OfferClock.of(day(), FOUR, UTC, at(19, 30));
        assertNotNull(clock);
        assertEquals(10, clock.total);
        assertEquals("3 to 6 am", 100, clock.middle[1]);
        assertEquals("3 to 6 am", 6, clock.offers[1]);
        // 100, 150, 200 and 250%: the lower of the middle two, a score an offer really had.
        assertEquals("6 to 9 pm", 150, clock.middle[6]);
        assertEquals(4, clock.offers[6]);
        assertEquals("no offers at noon", -1, clock.middle[4]);
        assertEquals(0, clock.offers[4]);
        assertEquals("now is 7:30 pm", 6, clock.now);
    }

    @Test
    public void raisingTheMinimumsLowersEveryBarAtOnce() {
        OfferClock clock = OfferClock.of(day(), FilterSettings.of(true, 800, 0, 0, 0), UTC, at(19, 30));
        assertEquals(50, clock.middle[1]);
        assertEquals(75, clock.middle[6]);
    }

    @Test
    public void theRateMinimumsPriceEachOffersMilesAndMinutes() {
        // $1.00 a mile and $15 an hour: each evening offer of 3 mi and 15 min is asked $3.75, so 106, 160, 213, 266%.
        FilterSettings rates = FilterSettings.of(true, 0, 100, 25, 0);
        OfferClock clock = OfferClock.of(day(), rates, UTC, at(19, 30));
        assertEquals(AreaScore.scorePercent(rates, new OfferSnapshot(600, 3.0, 15, 1)), clock.middle[6]);
        assertEquals(160, clock.middle[6]);
    }

    @Test
    public void learnedMinimumsCountAsTheFilterCountsThem() {
        // A $14.40 accept of 8.4 mi and 19 min taught: the learned minimum pay is $14.41.
        AcceptedFloors learned = new AcceptedFloors(1440, 1440, 8.4, 1440, 19);
        FilterSettings rules = new FilterSettings(true, 400, 0, 0, 0, false, 70, 100, false, true, learned);
        List<DecisionLog.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 10; i++) entries.add(offer(at(12, 5 * i), 2000, 6.0, 20));
        OfferClock clock = OfferClock.of(entries, rules, UTC, at(13, 0));
        int score = AreaScore.scorePercent(rules, new OfferSnapshot(2000, 6.0, 20, 1));
        assertEquals(score, clock.middle[4]);
        assertTrue("well under the 500% the saved $4.00 alone gives: " + score, score < 200);
    }

    @Test
    public void noClockWithoutAMoneyMinimumOrBeforeTenOffers() {
        assertNull("max stops alone", OfferClock.of(day(), FilterSettings.of(true, 0, 0, 0, 3), UTC, at(19, 30)));
        assertNull(OfferClock.of(day().subList(0, 9), FOUR, UTC, at(19, 30)));
        assertNull(OfferClock.of(null, FOUR, UTC, at(19, 30)));
    }

    @Test
    public void addOnsReChecksAndUnreadPayAreLeftOut() {
        List<DecisionLog.Entry> entries = new ArrayList<>(day());
        entries.add(line(at(14, 0), true, new OfferSnapshot(900, 2.0, 10, 1), DecisionLog.Action.PASSES));
        entries.add(line(at(14, 10), false, new OfferSnapshot(900, 2.0, 10, 1), DecisionLog.Action.REPLAY));
        entries.add(line(at(14, 20), false, new OfferSnapshot(null, 2.0, 10, 1), DecisionLog.Action.NEEDS_REVIEW));
        entries.add(offer(at(14, 30), 900, 2.0, 10).withAlertTag("tag", true));
        OfferClock clock = OfferClock.of(entries, FOUR, UTC, at(19, 30));
        assertEquals(0, clock.offers[4]);
        assertEquals(10, clock.total);
    }

    @Test
    public void anOfferReadAgainWithinTheMinuteAndAHalfCountsOnce() {
        List<DecisionLog.Entry> entries = new ArrayList<>(day());
        // Read three times in 80 s (once from Dasher's notification, without the miles); counted once.
        entries.add(offer(at(14, 0) + 80_000, 900, 2.0, 10));
        entries.add(line(at(14, 0) + 40_000, false, new OfferSnapshot(900, null, null, null),
                DecisionLog.Action.NATIVE_ALERT));
        entries.add(offer(at(14, 0), 900, 2.0, 10));
        // The same figures two minutes before, and another pay half a minute later: other offers.
        entries.add(offer(at(13, 58), 900, 2.0, 10));
        entries.add(offer(at(13, 58) + 30_000, 950, 2.0, 10));
        OfferClock clock = OfferClock.of(entries, FOUR, UTC, at(19, 30));
        assertEquals(3, clock.offers[4]);
    }

    @Test
    public void theSlotsFollowThePhonesTimeZone() {
        ZoneId cincinnati = ZoneId.of("America/New_York");
        // 23:30 UTC is 7:30 pm in Cincinnati in October.
        assertEquals(6, OfferClock.slot(at(23, 30), cincinnati));
        assertEquals(7, OfferClock.slot(at(23, 30), UTC));
        assertEquals(0, OfferClock.slot(at(2, 59), UTC));
        // The homepage sorts the offers afresh in another zone, even where the slot's number is the same.
        OfferClock clock = OfferClock.of(day(), FOUR, UTC, at(19, 30));
        assertTrue(clock.current(at(20, 59), UTC));
        assertFalse("the next slot", clock.current(at(21, 0), UTC));
        assertFalse("another time zone", clock.current(at(19, 30), ZoneId.of("Europe/London")));
    }

    @Test
    public void screenReadersHearEverySlotAndWhichIsNow() {
        String said = OfferClock.of(day(), FOUR, UTC, at(19, 30)).said();
        assertTrue(said, said.startsWith("Offers by time of day, as a percent of your minimums now. "));
        assertTrue(said, said.contains("3 AM to 6 AM: 100%, 6 offers. "));
        assertTrue(said, said.contains("Noon to 3 PM: no offers. "));
        assertTrue("each sentence starts with a capital", said.contains("Midnight to 3 AM: no offers. "));
        assertTrue(said, said.contains("6 PM to 9 PM: 150%, 4 offers, now. "));
        assertTrue(said, said.contains("9 PM to midnight: no offers. "));
        assertTrue(said, said.endsWith("History, not a promise."));
    }

    @Test
    public void itsDetailsListEverySlotOnALineOfItsOwn() {
        String[] lines = OfferClock.of(day(), FOUR, UTC, at(19, 30)).table().split("\n");
        assertEquals(OfferClock.SLOTS, lines.length);
        assertEquals("12 am–3 am  no offers", lines[0]);
        assertEquals("3 am–6 am  100% · 6 offers", lines[1]);
        assertEquals("6 pm–9 pm  150% · 4 offers (now)", lines[6]);
        assertEquals("9 pm–12 am  no offers", lines[7]);
    }

    @Test
    public void theScaleShowsAtLeast150AndClipsPast300() {
        // A $2.50 minimum: the evening's middle is 240%, so the scale goes to 250%.
        assertEquals(250, OfferClock.Face.scaleTop(OfferClock.of(day(), FilterSettings.of(true, 250, 0, 0, 0), UTC,
                at(19, 30))));
        assertEquals(150, OfferClock.Face.scaleTop(OfferClock.of(day(), FilterSettings.of(true, 800, 0, 0, 0), UTC,
                at(19, 30))));
        assertEquals(300, OfferClock.Face.scaleTop(OfferClock.of(day(), FilterSettings.of(true, 100, 0, 0, 0), UTC,
                at(19, 30))));
    }

    @Test
    public void itsWordsSayItIsHistoryNotAPromise() {
        assertTrue(OfferClock.DETAILS.contains("as a percent of your minimums now"));
        assertTrue(OfferClock.DETAILS.contains("fewer than 5 offers"));
        assertTrue(OfferClock.DETAILS.contains("The hours marked now are the three you are in."));
        assertTrue(OfferClock.DETAILS.endsWith("This is history, not a promise."));
    }
}
