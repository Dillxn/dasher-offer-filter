package com.local.dasherfilter;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Avoid-list normalization and whole-phrase matching (spec B5). */
public final class StoreMatcherTest {
    @Test public void normalizesPunctuationAccentsCaseAndAmpersands() {
        for (String s : new String[]{"McDonald's", "McDonald’s", "McDonald´s", "MCDONALDS", "  mcdonald‘s ", "McDonaldʼs"}) assertEquals(s, "mcdonalds", StoreMatcher.normalizeTerm(s));
        assertEquals("cafe rio", StoreMatcher.normalizeTerm("Café Rio"));
        assertEquals("a and w", StoreMatcher.normalizeTerm("A&W"));
        assertEquals("pf changs", StoreMatcher.normalizeTerm("P.F. Chang's"));
        assertEquals("chick fil a", StoreMatcher.normalizeTerm("Chick-fil-A"));
        assertEquals("kfc", StoreMatcher.normalizeTerm("ＫＦＣ"));
        assertEquals("7 eleven", StoreMatcher.normalizeTerm("7-Eleven®"));
        assertEquals("", StoreMatcher.normalizeTerm(null));
    }
    @Test public void validatesTermLengthAndAlphanumerics() {
        assertNull(StoreMatcher.termError("KFC"));
        assertNull(StoreMatcher.termError("A&W"));
        assertNotNull(StoreMatcher.termError("A"));
        assertNotNull(StoreMatcher.termError("-- ! --"));
        assertNotNull(StoreMatcher.termError(new String(new char[61]).replace('\0', 'x')));
        assertNull(StoreMatcher.termError(new String(new char[60]).replace('\0', 'x')));
    }
    @Test public void sanitizeDropsInvalidDuplicatesAndCapsAtFifty() {
        List<String> raw = new ArrayList<>(Arrays.asList("Chick-fil-A", "chick fil a", "A", null, "Taco Bell"));
        for (int i = 0; i < 80; i++) raw.add("Store " + i);
        List<String> clean = StoreMatcher.sanitize(raw);
        assertEquals(50, clean.size()); assertEquals("chick fil a", clean.get(0)); assertEquals("taco bell", clean.get(1));
        try { clean.add("x"); fail("unmodifiable"); } catch (UnsupportedOperationException expected) { }
        assertEquals(Arrays.asList("chick fil a", "wendys", "a and w"), StoreMatcher.parseTerms("Chick-fil-A, Wendy's\nA&W;; \n"));
    }
    @Test public void matchesWholeLabelOrWholePhraseOnly() {
        assertTrue(StoreMatcher.matches("chick fil a", "Chick-fil-A"));
        assertTrue(StoreMatcher.matches("chick fil a", "Pickup at CHICK-FIL-A (Main St)"));
        assertFalse(StoreMatcher.matches("taco", "Tacos El Gordo"));
        assertFalse(StoreMatcher.matches("kfc", "KFCX Grill"));
        assertTrue(StoreMatcher.matches("taco bell", "Taco Bell #1234"));
        assertFalse(StoreMatcher.matches("bell", "Taco Bellissimo"));
    }
    @Test public void firstMatchUsesMerchantAndNonControlLabels() {
        List<String> terms = StoreMatcher.sanitize(Arrays.asList("Decline", "Mr. Pollo"));
        assertNull(StoreMatcher.firstMatch(terms, null, Arrays.asList("Decline", "Accept", "Add to route 30")));
        assertEquals("mr pollo", StoreMatcher.firstMatch(terms, "Mr. Pollo", Arrays.asList("Decline")));
        assertEquals("mr pollo", StoreMatcher.firstMatch(terms, null, Arrays.asList("3.2mi offer from Mr. Pollo")));
        assertNull(StoreMatcher.firstMatch(terms, "", null));
        assertNull(StoreMatcher.firstMatch(null, "Mr. Pollo", null));
    }
}
