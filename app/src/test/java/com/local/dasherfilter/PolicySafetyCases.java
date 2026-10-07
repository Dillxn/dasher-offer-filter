package com.local.dasherfilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/** Offline checks of existing recovery and updater guard logic. No Android action, request, or install is sent. */
public final class PolicySafetyCases {
    /** The retired repository channel's addresses (the public GitHub repository): never trusted, connected or not. */
    private static final String RETIRED_REPO_FEED =
            "https://api.github.com/repos/Dillxn/dasher-offer-filter/contents/release/latest.json?ref=main";
    private static final String RETIRED_REPO_APK =
            "https://api.github.com/repos/Dillxn/dasher-offer-filter/contents/release/OfferFilter.apk?ref=main";

    public static int declineRecovery() {
        Checks c = new Checks();
        Object offer = new Object();
        DeclineErrorRecovery r = new DeclineErrorRecovery();
        c.eq(false, r.error(offer, 1000, 1000), "no unsolicited recovery");
        r.requested(offer, 1000, 1);
        c.eq(false, r.error(new Object(), 1200, 1200), "wrong offer identity");
        c.eq(false, r.error(offer, 999, 1000), "toast before request");
        c.eq(false, r.error(offer, 1201, 1200), "future toast");
        c.eq(false, r.error(offer, 1000, 5001), "stale toast");
        c.eq(true, r.error(offer, 1200, 1200), "fresh matching toast");
        c.eq(true, r.pending(), "signal retained");
        c.eq(false, r.blankReady(1200), "empty screen must settle");
        c.eq(false, r.blankReady(1649), "settle boundary minus one");
        c.eq(true, r.blankReady(1650), "settle boundary");
        r.notBlank();
        c.eq(false, r.blankReady(1650), "nonblank resets interval");
        c.eq(true, r.blankReady(2100), "new complete settle interval");
        r.backRequested(2100);
        c.eq(true, r.returning(), "bounded return observation");
        c.eq(true, r.hasBackAttempt(), "back attempt recorded");
        c.eq(false, r.error(offer, 2101, 2101), "request cannot spend recovery twice");
        c.eq(false, r.expired(5099), "return window before boundary");
        c.eq(true, r.expired(5100), "return deadline exact boundary");
        r.requested(offer, 5200, 2);
        c.eq(true, r.error(offer, 5300, 5300), "second decline may recover");
        c.eq(false, r.blankReady(5300), "second settle start");
        c.eq(true, r.blankReady(5750), "second settle complete");
        r.backRequested(5750);
        c.eq(true, r.exhausted(), "two-back cap");
        r.requested(offer, 5800, 3);
        c.eq(true, r.error(offer, 5900, 5900), "third toast recorded but not authorized");
        c.eq(false, r.blankReady(6350), "cap cannot be reset by third attempt");
        r.end();
        c.eq(false, r.pending(), "end clears evidence");
        c.eq(false, r.hasBackAttempt(), "end clears attempt state");
        c.eq(false, r.error(offer, 6400, 6400), "ended offer cannot recover");
        r.requested(offer, 7000, 1);
        c.eq(true, r.error(offer, 7100, 7100), "new episode signal");
        c.eq(true, r.error(offer, 11000, 11000), "duplicate toast");
        c.eq(true, r.expired(11101), "duplicates do not extend first signal freshness");
        c.eq(false, r.blankReady(11101), "expired signal cannot act");
        return c.count;
    }

    public static int updateMetadata() {
        Checks c = new Checks();
        String hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String apk = "https://" + UpdatePolicy.HOST + UpdatePolicy.APK_PATH;
        UpdatePolicy.validate(UpdatePolicy.PACKAGE, 78, apk, hash, 12345); c.count++;
        UpdatePolicy.validate(UpdatePolicy.PACKAGE, 78, apk, hash, 12345, "raw"); c.count++;
        for (long code : new long[]{0, -1, (long) Integer.MAX_VALUE + 1}) {
            c.reject(() -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, code, apk, hash, 12345), "invalid version code");
        }
        for (long bytes : new long[]{0, -1, UpdatePolicy.MAX_APK_BYTES + 1}) {
            c.reject(() -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 78, apk, hash, bytes), "invalid byte count");
        }
        for (String invalidHash : Arrays.asList(null, "", "1234", hash.toUpperCase(java.util.Locale.US))) {
            c.reject(() -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 78, apk, invalidHash, 12345), "invalid hash");
        }
        c.reject(() -> UpdatePolicy.validate("other.package", 78, apk, hash, 12345), "wrong package");
        c.reject(() -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 78, apk, hash, 12345, "gzip"), "nonraw encoding");
        for (String address : Arrays.asList(null, apk.replace("https:", "http:"), apk + "?ref=main", apk + "#part",
                "https://" + UpdatePolicy.HOST + ":443" + UpdatePolicy.APK_PATH,
                "https://user@" + UpdatePolicy.HOST + UpdatePolicy.APK_PATH,
                "https://example.invalid/OfferFilter.apk", RETIRED_REPO_APK)) {
            c.reject(() -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 78, address, hash, 12345), "wrong release address");
        }
        c.reject(() -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 78, RETIRED_REPO_APK, hash, 12345, "raw"),
                "retired repository artifact");
        c.eq(false, UpdatePolicy.trustedAddress(java.net.URI.create(RETIRED_REPO_APK)), "retired repository origin");
        c.eq(false, UpdatePolicy.trustedAddress(null), "missing URI");
        c.eq(true, UpdatePolicy.isNewer(78, 74), "new version");
        c.eq(false, UpdatePolicy.isNewer(74, 74), "same version");
        c.eq(false, UpdatePolicy.isNewer(73, 74), "no downgrade");
        c.eq(true, UpdatePolicy.validVersionName("0.4.72"), "version name");
        c.eq(false, UpdatePolicy.validVersionName(null), "missing name");
        for (int failures = 1; failures <= 8; failures++) {
            long expected = failures >= 5 ? 900000 : (1L << (failures - 1)) * 60000;
            c.eq(expected, UpdatePolicy.retryDelayMillis(failures), "bounded retry backoff");
        }
        c.eq(true, UpdatePolicy.coolingDown(1000, 901000), "cooldown upper boundary");
        c.eq(false, UpdatePolicy.coolingDown(1000, 901001), "clock regression cannot suppress updates indefinitely");
        c.eq(false, UpdatePolicy.coolingDown(1000, 1000), "cooldown expired");
        return c.count;
    }

    public static int updateOrigins() {
        Checks c = new Checks();
        try {
            UpdateTransport.validateAddress(UpdatePolicy.FEED); c.count++;
            UpdateTransport.validateAddress("https://" + UpdatePolicy.HOST + UpdatePolicy.APK_PATH); c.count++;
        } catch (IOException error) { throw new AssertionError("canonical release origin refused", error); }
        for (String address : Arrays.asList("http://" + UpdatePolicy.HOST + "/latest.json",
                "https://user@" + UpdatePolicy.HOST + "/latest.json",
                "https://" + UpdatePolicy.HOST + ":443/latest.json",
                "https://" + UpdatePolicy.HOST + "/../OfferFilter.apk",
                "https://" + UpdatePolicy.HOST + "/../../OfferFilter.apk",
                "https://" + UpdatePolicy.HOST + "/..",
                "https://" + UpdatePolicy.HOST + "/%2e%2e/OfferFilter.apk",
                "https://" + UpdatePolicy.HOST + "/OfferFilter.apk#fragment",
                "https://example.invalid/OfferFilter.apk", RETIRED_REPO_FEED, RETIRED_REPO_APK,
                "https://api.github.com/user", "https://github.com/Dillxn/dasher-offer-filter/raw/main/release/latest.json")) {
            c.rejectAddress(address);
        }
        c.rejectAddress(null);
        return c.count;
    }

    private static final class Checks {
        int count;
        void eq(Object expected, Object actual, String context) {
            count++;
            if (!Objects.equals(expected, actual)) throw new AssertionError(context + ": " + expected + " != " + actual);
        }
        void reject(Runnable action, String context) {
            count++;
            try { action.run(); } catch (IllegalArgumentException expected) { return; }
            throw new AssertionError(context + " was accepted");
        }
        void rejectAddress(String address) {
            count++;
            try { UpdateTransport.validateAddress(address); } catch (IOException expected) { return; }
            throw new AssertionError("untrusted address was accepted: " + address);
        }
    }
    public static void main(String[] args) {
        String[] names = {"decline-recovery", "update-metadata", "update-origins"};
        java.util.function.IntSupplier[] groups = {PolicySafetyCases::declineRecovery,
                PolicySafetyCases::updateMetadata, PolicySafetyCases::updateOrigins};
        int checks = 0, failures = 0;
        for (int i = 0; i < groups.length; i++) {
            try {
                int passed = groups[i].getAsInt();
                checks += passed;
                System.out.println("PASS " + names[i] + ": " + passed + " checks");
            } catch (AssertionError | RuntimeException failure) {
                failures++;
                System.out.println("FAIL " + names[i] + ": " + failure);
            }
        }
        System.out.println("RESULT groups=3 failed_groups=" + failures + " passed_checks=" + checks);
        if (failures != 0) System.exit(1);
    }
    private PolicySafetyCases() {}
}
