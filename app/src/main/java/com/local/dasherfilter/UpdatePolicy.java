package com.local.dasherfilter;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Pure update-feed policy, shared with the release probe (no Android dependencies). There is one release origin, the
 * public Render server, with one exact feed and one exact APK address, and it needs no account. The retired GitHub
 * feeds (the old signer's, and the private repository's that a GitHub connection once read) are not trusted. An APK is
 * installed only when it is signed like the installed app.
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

    /** Every Render request and redirect hop must stay on the exact release host. */
    static boolean trustedDownloadHost(String host) {
        return HOST.equals(host);
    }

    /** Every request and redirect hop stays on the Render host. */
    static boolean trustedAddress(URI uri) {
        return uri != null && trustedDownloadHost(uri.getHost());
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

    /**
     * @throws IllegalArgumentException unless every field describes a raw APK at the one Render APK address: a feed
     *     cannot point anywhere else, a retired GitHub address included
     */
    static void validate(String pkg, long code, String url, String sha, long bytes, String encoding) {
        if (url == null || sha == null) throw new IllegalArgumentException("Missing update metadata");
        if (!PACKAGE.equals(pkg) || code <= 0 || code > Integer.MAX_VALUE || !"raw".equals(encoding) || !renderApk(url)
                || !SHA256.matcher(sha).matches() || bytes <= 0 || bytes > MAX_APK_BYTES) {
            throw new IllegalArgumentException("Invalid update metadata");
        }
    }

    private static boolean renderApk(String url) {
        URI uri = URI.create(url);
        return "https".equals(uri.getScheme()) && HOST.equals(uri.getHost()) && APK_PATH.equals(uri.getRawPath())
                && uri.getRawUserInfo() == null && uri.getPort() == -1 && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
    }

    private UpdatePolicy() {}
}
