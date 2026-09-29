package com.local.dasherfilter;

import org.junit.Test;
import static org.junit.Assert.*;

/** Display metrics from explicit facts only (spec C). */
public final class OfferMetricsTest {
    @Test public void formatsTheGlanceableSummary() {
        assertEquals("$12.50 · 4.2 mi · 24 min · 2 stops · $2.98/mi · $31/hr", OfferMetrics.summary(new OfferSnapshot(1250, 4.2, 24, 2)));
        assertEquals("$12.50 · 1 stop", OfferMetrics.summary(new OfferSnapshot(1250, null, null, 1)));
        assertEquals("4.0 mi · 30 min", OfferMetrics.summary(new OfferSnapshot(null, 4.0, 30, null)));
        assertEquals("", OfferMetrics.summary(new OfferSnapshot(null, null, null, null)));
    }
    @Test public void ratesAreNeverEstimated() {
        assertNull(OfferMetrics.centsPerMile(1250, 0.0)); assertNull(OfferMetrics.centsPerMile(null, 4.2)); assertNull(OfferMetrics.centsPerMile(1250, null));
        assertNull(OfferMetrics.centsPerHour(1250, 0)); assertNull(OfferMetrics.centsPerHour(null, 24));
        assertEquals(Long.valueOf(298), OfferMetrics.centsPerMile(1250, 4.2));
        assertEquals(Long.valueOf(3125), OfferMetrics.centsPerHour(1250, 24));
        assertEquals("$2.98/mi · $31/hr", OfferMetrics.efficiency(new OfferSnapshot(1250, 4.2, 24, null)));
    }
    @Test public void moneyMilesAndRatesFormatting() {
        assertEquals("$0.07", OfferMetrics.money(7)); assertEquals("$1234.50", OfferMetrics.money(123450)); assertEquals("-$1.00", OfferMetrics.money(-100));
        assertEquals("6.0", OfferMetrics.miles(6)); assertEquals("4.25", OfferMetrics.miles(4.25)); assertEquals("1.7", OfferMetrics.miles(1.7)); assertEquals("6.0", OfferMetrics.milesHundredths(600));
        assertEquals("$34/hr", OfferMetrics.perHourWhole(3350)); assertEquals("$33/hr", OfferMetrics.perHourWhole(3349)); assertEquals("$25.00/hr", OfferMetrics.perHour(2500));
        assertNotNull(OfferMetrics.money(Long.MIN_VALUE));
    }
}
