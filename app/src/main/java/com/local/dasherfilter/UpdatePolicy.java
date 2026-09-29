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
