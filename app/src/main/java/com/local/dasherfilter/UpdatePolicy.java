package com.local.dasherfilter;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Pure update-feed policy, shared with the release probe (no Android dependencies). There are two release origins,
 * each with one exact feed and one exact APK address: Render, and the {@code release/} folder of this app's private
 * repository on {@code main}, read through the GitHub API with the user's own GitHub connection. The retired GitHub
 * feed belonged to a different signer and is not trusted. Whatever the origin, an APK is installed only when it is
 * signed like the installed app.
 */
final class UpdatePolicy {
    /** Where a release is read from. */
    enum Channel { RENDER, REPO }

    static final String PACKAGE = "com.local.dasherfilter";
    static final String HOST = "dash-offer-filter-build.onrender.com";
    static final String FEED = "https://" + HOST + "/latest.json";
    static final String APK_PATH = "/OfferFilter.apk";
    static final String REPOSITORY = "Dillxn/dasher-offer-filter";
    static final String GITHUB_API_HOST = "api.github.com";
    /** Every repository address the updater reads starts with this path. */
    static final String REPO_RELEASE_PATH = "/repos/" + REPOSITORY + "/contents/release/";
    static final String REPO_FEED = "https://" + GITHUB_API_HOST + REPO_RELEASE_PATH + "latest.json?ref=main";
    static final String REPO_APK = "https://" + GITHUB_API_HOST + REPO_RELEASE_PATH + "OfferFilter.apk?ref=main";
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

    /**
     * Every request and redirect hop of a channel stays on its origin: the Render host, or the GitHub API under this
     * repository's {@code release/} folder (so the user's token is only ever sent there).
     */
    static boolean trustedAddress(Channel channel, URI uri) {
        if (channel == Channel.RENDER) return trustedDownloadHost(uri.getHost());
        String path = uri.getRawPath();
        return GITHUB_API_HOST.equals(uri.getHost()) && path != null && path.startsWith(REPO_RELEASE_PATH);
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
        validate(Channel.RENDER, pkg, code, url, sha, bytes, encoding);
    }

    /**
     * @throws IllegalArgumentException unless every field describes a raw APK at {@code channel}'s one APK address: a
     *     Render feed cannot point into the repository, nor the repository's feed at Render or anywhere else
     */
    static void validate(Channel channel, String pkg, long code, String url, String sha, long bytes, String encoding) {
        if (url == null || sha == null) throw new IllegalArgumentException("Missing update metadata");
        boolean releaseUrl = channel == Channel.RENDER ? renderApk(url) : REPO_APK.equals(url);
        if (!PACKAGE.equals(pkg) || code <= 0 || code > Integer.MAX_VALUE || !"raw".equals(encoding) || !releaseUrl
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
