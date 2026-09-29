package com.local.dasherfilter;

import java.net.URI;

final class UpdatePolicy {
    static final String PACKAGE = "com.local.dasherfilter";
    static final String REPO = "Dillxn/dasher-offer-filter-updates";
    static final long MAX_APK_BYTES = 20L * 1024 * 1024;
    static final String FEED = "https://dash-offer-filter-build.onrender.com/latest.json";
    static boolean isNewer(long advertised, long installed) { return advertised > installed; }
    static boolean trustedDownloadHost(String host) {
        return "github.com".equals(host) || "raw.githubusercontent.com".equals(host) ||
                "release-assets.githubusercontent.com".equals(host) ||
                "dash-offer-filter-build.onrender.com".equals(host);
    }
    static long retryDelayMillis(int failures) {
        return failures >= 5 ? 900_000L : (1L << Math.max(0, failures - 1)) * 60_000L;
    }
    static boolean coolingDown(long now, long next) {
        return next > now && next - now <= 900_000L;
    }
    /** Retry delay while an already verified update waits for Dasher/offers/deliveries to finish (no network, no re-hash). */
    static final long DEFER_RETRY_MS = 300_000L;
    /** While deferred, the feed is polled again at most this often, so a newer release still replaces a waiting one. */
    static final long FEED_REUSE_MS = 900_000L;
    /**
     * An automatic check may skip the feed download and the APK re-hash when the APK it already verified is still the file it
     * verified (same size and modification time in app-private storage), is newer than the installed version, and the feed
     * was read within FEED_REUSE_MS. Any mismatch falls back to the full download-and-verify path.
     */
    static boolean reuseVerified(long readyCode, long installedCode, long fileSize, long fileModified, long verifiedSize, long verifiedModified,
                                 long checkedAt, long now) {
        return readyCode > installedCode && fileSize > 0 && fileSize == verifiedSize && fileModified > 0 && fileModified == verifiedModified &&
                checkedAt > 0 && now >= checkedAt && now - checkedAt < FEED_REUSE_MS;
    }
    /**
     * PackageManager flags for reading a downloaded archive's signers. On API 28 and 29 the archive parser collects (and
     * verifies) certificates only when GET_SIGNATURES is requested, so signingInfo would be null with GET_SIGNING_CERTIFICATES
     * alone and every self-update would fail. The installed-package lookup keeps GET_SIGNING_CERTIFICATES only.
     */
    static int archiveSigningFlags(int sdk) {
        if (sdk >= 30) return GET_SIGNING_CERTIFICATES;
        if (sdk >= 28) return GET_SIGNING_CERTIFICATES | GET_SIGNATURES;
        return GET_SIGNATURES;
    }
    // PackageManager.GET_SIGNATURES / GET_SIGNING_CERTIFICATES (public API constants). Literal values keep this class free of
    // android.* so tools/verify_channel.py can compile it with plain javac; UpdatePolicyTest pins them to the SDK values.
    static final int GET_SIGNATURES = 0x00000040, GET_SIGNING_CERTIFICATES = 0x08000000;
    static void validate(String pkg, long code, String url, String sha, long bytes) {
        validate(pkg, code, url, sha, bytes, "raw");
    }
    static void validate(String pkg, long code, String url, String sha, long bytes, String encoding) {
        if (url == null || sha == null) throw new IllegalArgumentException("Missing update metadata");
        URI uri = URI.create(url);
        String path = uri.getRawPath();
        boolean release = "raw".equals(encoding) && "github.com".equals(uri.getHost()) && path != null &&
                path.matches("/" + REPO + "/releases/download/v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.]+)?/OfferFilter\\.apk");
        boolean render = "raw".equals(encoding) && "dash-offer-filter-build.onrender.com".equals(uri.getHost()) &&
                "/OfferFilter.apk".equals(path);
        boolean base64 = "base64".equals(encoding) && "raw.githubusercontent.com".equals(uri.getHost()) && path != null &&
                path.matches("/" + REPO + "/main/apks/v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.]+)?/OfferFilter\\.apk\\.b64");
        if (!PACKAGE.equals(pkg) || code <= 0 || code > Integer.MAX_VALUE || !"https".equals(uri.getScheme()) ||
                uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null ||
                (!release && !render && !base64) || !sha.matches("[a-f0-9]{64}") || bytes <= 0 || bytes > MAX_APK_BYTES)
            throw new IllegalArgumentException("Invalid update metadata");
    }
    private UpdatePolicy() {}
}
