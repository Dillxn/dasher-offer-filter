package com.local.dasherfilter;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UpdatePolicyTest {
    private static final String URL = "https://github.com/Dillxn/dasher-offer-filter-updates/releases/download/v0.3.0/OfferFilter.apk";
    private static final String B64 = "https://raw.githubusercontent.com/Dillxn/dasher-offer-filter-updates/main/apks/v0.4.1/OfferFilter.apk.b64";
    private static final String RENDER = "https://dash-offer-filter-build.onrender.com/OfferFilter.apk";
    private static final String HASH = "a".repeat(64);

    @Test public void acceptsExpectedFeedAndOnlyHigherVersions() {
        UpdatePolicy.validate(UpdatePolicy.PACKAGE, 4, URL, HASH, 25089);
        UpdatePolicy.validate(UpdatePolicy.PACKAGE, 7, B64, HASH, 50000, "base64");
        UpdatePolicy.validate(UpdatePolicy.PACKAGE, 7, RENDER, HASH, 50000, "raw");
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

    @Test public void downloaderTrustsExactRenderOrigin() {
        assertTrue(UpdatePolicy.trustedDownloadHost("dash-offer-filter-build.onrender.com"));
        assertTrue(UpdatePolicy.trustedDownloadHost("github.com"));
        assertFalse(UpdatePolicy.trustedDownloadHost("dash-offer-filter-build.onrender.com.attacker.test"));
        assertFalse(UpdatePolicy.trustedDownloadHost("other.onrender.com"));
    }

    @Test public void retryBackoffIsFastAndBounded() {
        assertEquals(60_000L, UpdatePolicy.retryDelayMillis(1));
        assertEquals(120_000L, UpdatePolicy.retryDelayMillis(2));
        assertEquals(240_000L, UpdatePolicy.retryDelayMillis(3));
        assertEquals(480_000L, UpdatePolicy.retryDelayMillis(4));
        assertEquals(900_000L, UpdatePolicy.retryDelayMillis(5));
        assertEquals(900_000L, UpdatePolicy.retryDelayMillis(8));
    }

    @Test public void rejectsMismatchedEncodingAndRawRepoPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 7, B64, HASH, 50000, "raw"));
        assertThrows(IllegalArgumentException.class,
                () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 7, URL, HASH, 50000, "base64"));
        assertThrows(IllegalArgumentException.class,
                () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 7,
                        B64.replace("dasher-offer-filter-updates", "other"), HASH, 50000, "base64"));
        assertThrows(IllegalArgumentException.class,
                () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 7,
                        RENDER.replace("dash-offer-filter-build", "attacker"), HASH, 50000, "raw"));
    }

    /** H2-2: on API 28/29 the archive parser fills signingInfo only when GET_SIGNATURES is also requested. */
    @SuppressWarnings("deprecation")
    @Test public void archiveSignerFlagsIncludeGetSignaturesOnApi28And29() {
        assertEquals(android.content.pm.PackageManager.GET_SIGNATURES, UpdatePolicy.GET_SIGNATURES);
        assertEquals(android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES, UpdatePolicy.GET_SIGNING_CERTIFICATES);
        for (int sdk : new int[] {28, 29}) {
            int flags = UpdatePolicy.archiveSigningFlags(sdk);
            assertTrue("API " + sdk, (flags & UpdatePolicy.GET_SIGNATURES) != 0);
            assertTrue("API " + sdk, (flags & UpdatePolicy.GET_SIGNING_CERTIFICATES) != 0);
        }
        assertEquals(UpdatePolicy.GET_SIGNATURES, UpdatePolicy.archiveSigningFlags(26));
        assertEquals(UpdatePolicy.GET_SIGNATURES, UpdatePolicy.archiveSigningFlags(27));
        assertEquals(UpdatePolicy.GET_SIGNING_CERTIFICATES, UpdatePolicy.archiveSigningFlags(30));
        assertEquals(UpdatePolicy.GET_SIGNING_CERTIFICATES, UpdatePolicy.archiveSigningFlags(35));
    }

    /** H2-9: a verified waiting APK is reused only while it is the same file, newer than installed, and the feed is recent. */
    @Test public void verifiedUpdateIsReusedOnlyWhileUnchangedAndRecent() {
        long now = 10_000_000L;
        assertTrue(UpdatePolicy.reuseVerified(12, 11, 5000, 777, 5000, 777, now - 60_000L, now));
        assertFalse("not newer", UpdatePolicy.reuseVerified(11, 11, 5000, 777, 5000, 777, now - 60_000L, now));
        assertFalse("size changed", UpdatePolicy.reuseVerified(12, 11, 5001, 777, 5000, 777, now - 60_000L, now));
        assertFalse("file rewritten", UpdatePolicy.reuseVerified(12, 11, 5000, 778, 5000, 777, now - 60_000L, now));
        assertFalse("missing file", UpdatePolicy.reuseVerified(12, 11, -1, 0, 5000, 777, now - 60_000L, now));
        assertFalse("feed is stale: poll again", UpdatePolicy.reuseVerified(12, 11, 5000, 777, 5000, 777, now - UpdatePolicy.FEED_REUSE_MS, now));
        assertFalse("clock went backwards", UpdatePolicy.reuseVerified(12, 11, 5000, 777, 5000, 777, now + 1, now));
        assertTrue("deferral retries are slower than the old 60 s poll", UpdatePolicy.DEFER_RETRY_MS >= 300_000L);
    }

    @Test public void rejectsWrongPackageMalformedHashesAndUnboundedSize() {
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate("other.app", 4, URL, HASH, 25089));
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 0, URL, HASH, 25089));
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 4, URL, "bad", 25089));
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 4, URL, HASH, 0));
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 4, URL, HASH, UpdatePolicy.MAX_APK_BYTES + 1));
    }
}
