package com.local.dasherfilter;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Pure update-feed policy, shared with the release probe (no Android dependencies). Render is the only release
 * origin: the retired GitHub feed belonged to a different signer and is not trusted for downloads.
 */
final class UpdatePolicy {
    static final String PACKAGE = "com.local.dasherfilter";
    static final String HOST = "dash-offer-filter-build.onrender.com";
    static final String FEED = "https://" + HOST + "/latest.json";
    static final String APK_PATH = "/OfferFilter.apk";
    static final long MAX_APK_BYTES = 20L * 1024 * 1024;
    private static final long MAX_RETRY_DELAY_MS = 900_000L;
    private static final long MAX_COOLDOWN_MS = 900_000L;
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern VERSION_NAME = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.]+)?");

    static boolean isNewer(long advertised, long installed) {
        return advertised > installed;
    }

    /** Every request and redirect hop must stay on the exact release host. */
    static boolean trustedDownloadHost(String host) {
        return HOST.equals(host);
    }

    /** 1, 2, 4, 8 minutes, then 15 minutes for every later consecutive failure. */
    static long retryDelayMillis(int failures) {
        return failures >= 5 ? MAX_RETRY_DELAY_MS : (1L << Math.max(0, failures - 1)) * 60_000L;
    }

    /** A far-future deadline (e.g. after the clock moved backwards) is ignored rather than honored indefinitely. */
    static boolean coolingDown(long now, long next) {
        return next > now && next - now <= MAX_COOLDOWN_MS;
    }

    static boolean validVersionName(String versionName) {
        return versionName != null && VERSION_NAME.matcher(versionName).matches();
    }

    static void validate(String pkg, long code, String url, String sha, long bytes) {
        validate(pkg, code, url, sha, bytes, "raw");
    }

    /** @throws IllegalArgumentException unless every field describes a raw APK on the Render release path */
    static void validate(String pkg, long code, String url, String sha, long bytes, String encoding) {
        if (url == null || sha == null) throw new IllegalArgumentException("Missing update metadata");
        URI uri = URI.create(url);
        boolean releaseUrl = "https".equals(uri.getScheme()) && HOST.equals(uri.getHost())
                && APK_PATH.equals(uri.getRawPath()) && uri.getRawUserInfo() == null && uri.getPort() == -1
                && uri.getRawQuery() == null && uri.getRawFragment() == null;
        if (!PACKAGE.equals(pkg) || code <= 0 || code > Integer.MAX_VALUE || !"raw".equals(encoding) || !releaseUrl
                || !SHA256.matcher(sha).matches() || bytes <= 0 || bytes > MAX_APK_BYTES) {
            throw new IllegalArgumentException("Invalid update metadata");
        }
    }

    private UpdatePolicy() {}
}
