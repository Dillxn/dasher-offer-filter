package com.local.dasherfilter;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UpdatePolicyTest {
    private static final String URL = "https://github.com/Dillxn/dasher-offer-filter-updates/releases/download/v0.3.0/OfferFilter.apk";
    private static final String HASH = "a".repeat(64);

    @Test public void acceptsExpectedFeedAndOnlyHigherVersions() {
        UpdatePolicy.validate(UpdatePolicy.PACKAGE, 4, URL, HASH, 25089);
        assertTrue(UpdatePolicy.isNewer(4, 3));
        assertFalse(UpdatePolicy.isNewer(4, 4));
        assertFalse(UpdatePolicy.isNewer(3, 4));
    }

    @Test public void rejectsOtherHostsRepositoriesAndNonHttpsDownloads() {
        for (String url : new String[] {
                URL.replace("https:", "http:"), URL.replace("github.com", "github.com.attacker.test"),
                URL.replace("Dillxn", "SomeoneElse"), URL.replace("github.com", "name:password@github.com"),
                URL + "?redirect=anything", URL + "#fragment", URL.replace("github.com", "github.com:8443"),
                URL.replace("v0.3.0", "v0.3.0/../../other")}) {
            assertThrows(IllegalArgumentException.class,
                    () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 4, url, HASH, 25089));
        }
    }

    @Test public void retryBackoffIsFastAndBounded() {
        assertEquals(60_000L, UpdatePolicy.retryDelayMillis(1));
        assertEquals(120_000L, UpdatePolicy.retryDelayMillis(2));
        assertEquals(240_000L, UpdatePolicy.retryDelayMillis(3));
        assertEquals(480_000L, UpdatePolicy.retryDelayMillis(4));
        assertEquals(480_000L, UpdatePolicy.retryDelayMillis(8));
    }

    @Test public void rejectsWrongPackageMalformedHashesAndUnboundedSize() {
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate("other.app", 4, URL, HASH, 25089));
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 0, URL, HASH, 25089));
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 4, URL, "bad", 25089));
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 4, URL, HASH, 0));
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 4, URL, HASH, UpdatePolicy.MAX_APK_BYTES + 1));
    }
}
