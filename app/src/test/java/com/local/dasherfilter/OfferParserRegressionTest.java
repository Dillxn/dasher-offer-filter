package com.local.dasherfilter;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Money-losing parser shapes reproduced by the 0.5.0 audit (spec A1, A2, H1.1-3, H1.10-12). */
public final class OfferParserRegressionTest {
    private OfferSnapshot p(String... labels) { return OfferParser.parse(Arrays.asList(labels)); }

    // A1: redesigned card puts the amount BEFORE its label, in separate nodes.
    @Test public void flashOfferSplitNodesUseTheAmountBeforeTheLabelNotTheBoost() {
        assertEquals(Integer.valueOf(2824), p("$28.24", "Guaranteed", "Includes a $15.00 Flash offer boost", "3.2 mi").payCents);
    }
    @Test public void flashOfferMergedNodeIgnoresTheIncludedBoost() {
        assertEquals(Integer.valueOf(2824), p("$28.24 Guaranteed Includes a $15.00 Flash offer boost", "3.2 mi").payCents);
    }
    @Test public void inclTipsSplitAndMergedLabels() {
        assertEquals(Integer.valueOf(875), p("$8.75", "Guaranteed (incl. tips)", "+$2.00 Peak Pay").payCents);
        assertEquals(Integer.valueOf(750), p("$7.50 Guaranteed (incl. tips)", "3 mi").payCents);
        assertEquals(Integer.valueOf(750), p("$7.50", "Guaranteed", "(incl. tips)", "3 mi").payCents);
        assertEquals(Integer.valueOf(790), p("$7.90 incl. tips", "2 stops (7.2 mi) • 21 min").payCents);
    }
    @Test public void originalCardWithIncludesDescription() {
        assertEquals(Integer.valueOf(925), p("$9.25", "Guaranteed", "Includes DoorDash pay and customer tip", "5.1 mi").payCents);
        assertEquals(Integer.valueOf(925), p("$9.25", "Includes DoorDash pay and customer tip", "5.1 mi").payCents);
    }
    // H1.1: component, rate, and breakdown lines after a money-less label are never the payout.
    @Test public void lineAfterPayLabelIsNotPayWhenItIsAComponentOrRate() {
        assertEquals(Integer.valueOf(875), p("$8.75", "Guaranteed", "Includes $2.00 Peak Pay").payCents);
        assertEquals(Integer.valueOf(875), p("$8.75", "Guaranteed", "$1.21/mi").payCents);
        assertEquals(Integer.valueOf(875), p("$8.75", "Guaranteed", "$17.50/hr").payCents);
        assertNull(p("Guaranteed", "Includes $2.00 Peak Pay").payCents);
        assertNull(p("Guaranteed", "$17.50/hr").payCents);
    }
    @Test public void conflictingStandaloneAmountsAroundALabelAreUnknown() { assertNull(p("$8.75", "Guaranteed", "$9.00").payCents); }
    @Test public void componentLinesAreNeverUnlabeledPayCandidates() {
        assertNull(p("Peak Pay $2.00", "4 mi").payCents);
        assertNull(p("Customer tip $4.00", "4 mi").payCents);
        assertNull(p("Peak Pay", "$2.00", "4 mi").payCents);
        // A shown component also blocks the unlabeled fallback: whether $11.00 includes the $4.00 tip is not on screen (0.4.5: null too).
        assertNull(p("$11.00", "Customer tip $4.00", "4 mi").payCents);
        assertEquals(Integer.valueOf(1100), p("$11.00 Guaranteed", "Customer tip $4.00", "4 mi").payCents);
    }
    // A2: Earn by Time rates are never pay.
    @Test public void earnByTimeRatesAreNeverPay() {
        assertNull(p("$15/active hr + tips", "From when you accept to when you complete this offer", "4.1 mi").payCents);
        assertNull(p("$15", "/active hr", "+ tips", "4.1 mi").payCents);
        assertNull(p("$15.00 per active hour", "4.1 mi").payCents);
        assertNull(p("$25 an hour", "4.1 mi").payCents);
        assertNull(p("$18.00 hourly", "4.1 mi").payCents);
        assertNull(p("$18.00 / hr", "4.1 mi").payCents);
        assertNull(p("$18.00", "per hour", "4.1 mi").payCents);
    }
    @Test public void earnByTimeMarkerAnywhereMakesPayUnknown() {
        assertNull(p("$12.00", "Earn by Time", "From when you accept to when you complete this offer").payCents);
    }
    // H1.2: hour/minute variants.
    @Test public void hourAndMinuteVariantsParseToTotalMinutes() {
        for (String s : new String[]{"Estimated 1 h 5 min", "Estimated 1h 5 min", "Estimated 1 hr, 5 min", "Estimated 1 hr and 5 min", "Estimated 1h 5min", "Estimated 1 hr 5 min", "Estimated 1 hour 5 minutes"})
            assertEquals(s, Integer.valueOf(65), p("$20.00", s).minutes);
        assertEquals(Integer.valueOf(60), p("$20.00", "Estimated 1 hr").minutes);
        assertEquals(Integer.valueOf(120), p("$20.00", "3 stops • 2 hrs").minutes);
    }
    @Test public void unconsumedHourTokenMakesMinutesUnknown() {
        assertNull(p("$20.00", "Estimated 1 hr • 5 min").minutes);
        assertNull(p("$20.00", "Estimated 1.5 hr").minutes);
    }
    // H1.3: "+$" amounts are increments, never standalone pay.
    @Test public void plusDollarIsNeverStandalonePay() {
        assertNull(p("+$5.00", "2.1 mi").payCents);
        assertNull(p("$5.00", "+$5.00").payCents);
        // An increment blocks the unlabeled fallback; the new total belongs to the add-on path, not the standalone payout.
        assertNull(p("+$5.00", "New total $30.00").payCents);
        AddOnOffer a = AddOnOffer.parse(new OfferSnapshot(2500, null, null, null), null, Arrays.asList("+$5.00", "New total $30.00"));
        assertEquals(Integer.valueOf(3000), a.combined.payCents); assertEquals(Integer.valueOf(500), a.incremental.payCents); assertTrue(a.explicitIncrementPay);
    }
    // H1.10: trailing '+' is a lower bound.
    @Test public void trailingPlusIsALowerBoundNotAnExactAmount() {
        assertNull(p("$7.50+", "3 mi").payCents);
        assertNull(p("$7.50 + tips", "3 mi").payCents);
        assertEquals(Integer.valueOf(750), p("$7.50", "Total may be higher", "3 mi").payCents);
    }
    // H1.11: a separate "21 min" node next to the mileage node.
    @Test public void bareMinutesSiblingPairsWithAdjacentMileage() {
        assertEquals(Integer.valueOf(21), OfferParser.parse(Arrays.asList("$8.50", "4.2 mi", "21 min"), OfferParser.joinMetricSiblings(Arrays.asList("4.2 mi", "21 min"))).minutes);
        assertEquals(Integer.valueOf(21), OfferParser.parse(Arrays.asList("$8.50", "21 min", "2 stops"), OfferParser.joinMetricSiblings(Arrays.asList("21 min", "2 stops"))).minutes);
        assertEquals(Integer.valueOf(21), OfferParser.parse(Arrays.asList("$8.50", "4.2", "mi", "21 min"), OfferParser.joinMetricSiblings(Arrays.asList("4.2", "mi", "21 min"))).minutes);
        assertNull(p("$8.50", "4.2 mi", "21 min").minutes);
    }
    @Test public void bareMinutesNeverPairWithWaitDeadlineOrRanges() {
        for (String[] group : new String[][]{{"Deliver by 7:45 PM • 4.2 mi", "21 min"}, {"Wait 4.2 mi", "5 min"}, {"4.2 mi", "1-2 min"}, {"4.2 mi remaining", "8 min"}, {"Accept by 4.2 mi", "8 min"}, {"Restaurant", "21 min"},
                {"Ready for pickup in", "35 min", "3.1 mi"}, {"Pickup in", "35 min", "3.1 mi"}, {"ETA", "35 min", "3.1 mi"}, {"4.2 mi", "21 min", "5 min"}}) {
            java.util.List<String> labels = new java.util.ArrayList<>(Arrays.asList(group)); labels.add(0, "$8.50");
            assertNull(Arrays.toString(group), OfferParser.parse(labels, OfferParser.joinMetricSiblings(Arrays.asList(group))).minutes);
        }
    }
    // H1.12: road names and vulgar fractions are not mileage.
    @Test public void roadNamesAndFractionsAreNotMileage() {
        assertNull(p("$8.50", "14 Mile Rd").miles);
        assertNull(p("$8.50", "4½ mi").miles);
        assertNull(p("$8.50", "4 1/2 mi").miles);
        for (String road : new String[]{"Road", "St", "Street", "Ave", "Avenue", "Blvd", "Hwy", "Dr", "Ln", "Way", "Pkwy"}) assertNull(road, p("$8.50", "8 Mile " + road).miles);
        assertEquals(3.1, p("$8.50", "3.1 mi", "14 Mile Rd").miles, 0.0);
    }
    @Test public void deliverByDeadlineIsNeverMinutes() {
        assertNull(p("$8.50", "Deliver by 7:45 PM", "4.2 mi").minutes);
        assertNull(p("$8.50", "4.2 mi • Deliver by 7:45 PM").minutes);
        assertNull(p("$8.50", "5.0 mi • Deliver by 8:10 PM (in 32 min)").minutes);
        assertEquals("the deadline clause is removed, not the whole merged line", Integer.valueOf(45), p("$8.50", "5.0 mi • 45 min • Deliver by 8:10 PM").minutes);
        assertEquals(Integer.valueOf(45), p("$9.00 Guaranteed (incl. tips) 5.0 mi • 45 min Deliver by 8:10 PM Chick-fil-A").minutes);
    }
    // Probe findings: a leading amount described as including components is the total, not a component line.
    @Test public void leadingAmountThatIncludesComponentsIsTheTotal() {
        assertEquals(Integer.valueOf(750), p("$7.50 (incl. $2.00 tip)", "4 mi").payCents);
        assertEquals(Integer.valueOf(750), p("$7.50 incl. $2.00 tip", "4 mi").payCents);
        assertEquals(Integer.valueOf(1125), p("$11.25 · Includes tip", "4 mi").payCents);
        assertNull(p("Includes $2.00 tip", "4 mi").payCents);
        assertNull("two amounts both claiming to be the total stay unknown", p("$7.50 incl. tips", "$9.00 Guaranteed").payCents);
    }
}
