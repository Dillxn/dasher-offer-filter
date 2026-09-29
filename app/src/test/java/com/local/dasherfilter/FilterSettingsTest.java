package com.local.dasherfilter;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

/** Immutable settings: legacy constructors, copy helpers that never drop a field, rule predicates (spec B1, B6). */
public final class FilterSettingsTest {
    private static final FilterSettings FULL = new FilterSettings(true, 700, 150, 5, 2500, 200, 3, 1000, Arrays.asList("Chick-fil-A"), true, 3000, 123L, 4);

    @Test public void legacyConstructorsDefaultNewFieldsToDisabled() {
        FilterSettings six = new FilterSettings(true, 2000, 150, 30, 100, 2), eight = new FilterSettings(false, 1, 2, 3, 4, 5, true, 2500);
        assertEquals(0, six.perHourCents); assertEquals(0, six.maxMilesHundredths); assertTrue(six.avoidStores.isEmpty());
        assertEquals(0L, six.lastAcceptedAt); assertEquals(0, six.maxDeclinesPerHour); assertFalse(six.risingOffers);
        assertEquals(2500, eight.lastAcceptedCents); assertEquals(0L, eight.lastAcceptedAt); assertEquals(3, eight.perMinuteCents);
    }
    @Test public void everyCopyHelperChangesOnlyItsField() {
        assertEquals(FULL, FULL.withEnabled(false).withEnabled(true));
        assertEquals(FULL, FULL.withFlatCents(1).withFlatCents(700));
        assertEquals(FULL, FULL.withPerMileCents(1).withPerMileCents(150));
        assertEquals(FULL, FULL.withPerMinuteCents(1).withPerMinuteCents(5));
        assertEquals(FULL, FULL.withPerHourCents(1).withPerHourCents(2500));
        assertEquals(FULL, FULL.withExtraStopCents(1).withExtraStopCents(200));
        assertEquals(FULL, FULL.withMaxStops(1).withMaxStops(3));
        assertEquals(FULL, FULL.withMaxMilesHundredths(1).withMaxMilesHundredths(1000));
        assertEquals(FULL, FULL.withAvoidStores(null).withAvoidStores(Arrays.asList("chick fil a")));
        assertEquals(FULL, FULL.withRisingOffers(false).withRisingOffers(true));
        assertEquals(FULL, FULL.withLastAccepted(0, 0).withLastAccepted(3000, 123L));
        assertEquals(FULL, FULL.withMaxDeclinesPerHour(0).withMaxDeclinesPerHour(4));
        FilterSettings paused = FULL.withEnabled(false);
        assertFalse(paused.enabled); assertEquals(2500, paused.perHourCents); assertEquals(1000, paused.maxMilesHundredths);
        assertEquals(Arrays.asList("chick fil a"), paused.avoidStores); assertEquals(123L, paused.lastAcceptedAt); assertEquals(4, paused.maxDeclinesPerHour);
    }
    @Test public void rulePredicates() {
        FilterSettings none = new FilterSettings(true, 0, 0, 0, 0, 0);
        assertFalse(none.hasAnyRule()); assertFalse(none.hasPayRule()); assertFalse(none.hasNonStoreRule());
        assertTrue(none.withPerHourCents(1).hasPayRule());
        assertTrue(none.withRisingOffers(true).hasPayRule());
        assertFalse(none.withMaxMilesHundredths(100).hasPayRule()); assertTrue(none.withMaxMilesHundredths(100).hasAnyRule());
        FilterSettings stores = none.withAvoidStores(Arrays.asList("KFC"));
        assertTrue(stores.hasAnyRule()); assertFalse(stores.hasNonStoreRule()); assertFalse(stores.hasPayRule());
        assertFalse(none.withAvoidStores(Arrays.asList("!", "A")).hasAnyRule());
        assertFalse(none.withMaxDeclinesPerHour(3).hasAnyRule());
    }
    @Test public void baselineFreshnessBoundaries() {
        long now = 1_700_000_000_000L, h = 3_600_000L;
        FilterSettings s = new FilterSettings(true, 0, 0, 0, 0, 0).withRisingOffers(true);
        assertTrue(s.withLastAccepted(2500, now - 8 * h).baselineActive(now));
        assertFalse(s.withLastAccepted(2500, now - 8 * h - 1).baselineActive(now));
        assertTrue(s.withLastAccepted(2500, now + 300_000).baselineActive(now));
        assertFalse(s.withLastAccepted(2500, now + 300_001).baselineActive(now));
        assertFalse(s.withLastAccepted(2500, 0).baselineActive(now));
        assertFalse(s.withLastAccepted(0, now).baselineActive(now));
        assertFalse(s.withRisingOffers(false).withLastAccepted(2500, now).baselineActive(now));
        assertTrue(s.withRisingOffers(false).withLastAccepted(2500, now).baselineFresh(now));
    }
    @Test public void avoidStoresAreNormalizedAndUnmodifiable() {
        FilterSettings s = new FilterSettings(true, 0, 0, 0, 0, 0).withAvoidStores(Arrays.asList("Chick-fil-A", "CHICK FIL A", "x"));
        assertEquals(Collections.singletonList("chick fil a"), s.avoidStores);
        try { s.avoidStores.add("y"); fail(); } catch (UnsupportedOperationException expected) { }
    }
}
