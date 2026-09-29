package com.local.dasherfilter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class UpdatePolicyTest {
    private static final String RENDER = "https://dash-offer-filter-build.onrender.com/OfferFilter.apk";
    private static final String RETIRED_RELEASE =
            "https://github.com/Dillxn/dasher-offer-filter-updates/releases/download/v0.3.0/OfferFilter.apk";
    private static final String RETIRED_BASE64 =
            "https://raw.githubusercontent.com/Dillxn/dasher-offer-filter-updates/main/apks/v0.4.1/OfferFilter.apk.b64";
    private static final String HASH = "a".repeat(64);

    @Test public void acceptsTheRenderFeedAndOnlyHigherVersions() {
        UpdatePolicy.validate(UpdatePolicy.PACKAGE, 7, RENDER, HASH, 50000);
        UpdatePolicy.validate(UpdatePolicy.PACKAGE, 7, RENDER, HASH, 50000, "raw");
        assertTrue(UpdatePolicy.isNewer(4, 3));
        assertFalse(UpdatePolicy.isNewer(4, 4));
        assertFalse(UpdatePolicy.isNewer(3, 4));
    }

    @Test public void rejectsTheRetiredGitHubFeedInEveryEncoding() {
        assertThrows(IllegalArgumentException.class,
                () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 7, RETIRED_RELEASE, HASH, 50000, "raw"));
        assertThrows(IllegalArgumentException.class,
                () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 7, RETIRED_BASE64, HASH, 50000, "base64"));
        assertThrows(IllegalArgumentException.class,
                () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 7, RENDER, HASH, 50000, "base64"));
    }

    @Test public void rejectsOtherHostsCredentialsPortsQueriesAndPaths() {
        for (String url : new String[] {
                RENDER.replace("https:", "http:"),
                RENDER.replace("onrender.com", "onrender.com.attacker.test"),
                RENDER.replace("dash-offer-filter-build", "attacker"),
                RENDER.replace("https://", "https://name:password@"),
                RENDER.replace(".com/", ".com:8443/"),
                RENDER.replace(".com/", ".com:443/"),
                RENDER + "?redirect=anything",
                RENDER + "#fragment",
                RENDER.replace("/OfferFilter.apk", "/other/../OfferFilter.apk"),
                RENDER.replace("OfferFilter.apk", "Other.apk")}) {
            assertThrows(url, IllegalArgumentException.class,
                    () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 7, url, HASH, 50000));
        }
    }

    @Test public void downloaderTrustsOnlyTheExactRenderOrigin() {
        assertTrue(UpdatePolicy.trustedDownloadHost("dash-offer-filter-build.onrender.com"));
        assertFalse(UpdatePolicy.trustedDownloadHost("dash-offer-filter-build.onrender.com.attacker.test"));
        assertFalse(UpdatePolicy.trustedDownloadHost("other.onrender.com"));
        assertFalse(UpdatePolicy.trustedDownloadHost("github.com"));
        assertFalse(UpdatePolicy.trustedDownloadHost("raw.githubusercontent.com"));
        assertFalse(UpdatePolicy.trustedDownloadHost(null));
    }

    @Test public void retryBackoffIsFastAndBounded() {
        assertEquals(60_000L, UpdatePolicy.retryDelayMillis(1));
        assertEquals(120_000L, UpdatePolicy.retryDelayMillis(2));
        assertEquals(240_000L, UpdatePolicy.retryDelayMillis(3));
        assertEquals(480_000L, UpdatePolicy.retryDelayMillis(4));
        assertEquals(900_000L, UpdatePolicy.retryDelayMillis(5));
        assertEquals(900_000L, UpdatePolicy.retryDelayMillis(8));
    }

    @Test public void rejectsWrongPackageMalformedHashesAndUnboundedSize() {
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate("other.app", 4, RENDER, HASH, 25089));
        assertThrows(IllegalArgumentException.class,
                () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 0, RENDER, HASH, 25089));
        assertThrows(IllegalArgumentException.class,
                () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 1L + Integer.MAX_VALUE, RENDER, HASH, 25089));
        assertThrows(IllegalArgumentException.class,
                () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 4, RENDER, "bad", 25089));
        assertThrows(IllegalArgumentException.class,
                () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 4, RENDER, HASH.toUpperCase(), 25089));
        assertThrows(IllegalArgumentException.class,
                () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 4, RENDER, HASH, 0));
        assertThrows(IllegalArgumentException.class,
                () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 4, RENDER, HASH, UpdatePolicy.MAX_APK_BYTES + 1));
        assertThrows(IllegalArgumentException.class,
                () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 4, null, HASH, 25089));
    }

    @Test public void versionNamesAreSemanticVersions() {
        assertTrue(UpdatePolicy.validVersionName("0.4.6"));
        assertTrue(UpdatePolicy.validVersionName("1.0.0-rc.1"));
        assertFalse(UpdatePolicy.validVersionName("0.4"));
        assertFalse(UpdatePolicy.validVersionName("0.4.6 "));
        assertFalse(UpdatePolicy.validVersionName("v0.4.6"));
        assertFalse(UpdatePolicy.validVersionName(null));
    }
}
