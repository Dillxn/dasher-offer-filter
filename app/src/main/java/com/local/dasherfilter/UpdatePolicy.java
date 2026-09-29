package com.local.dasherfilter;

import java.net.URI;

final class UpdatePolicy {
    static final String PACKAGE = "com.local.dasherfilter";
    static final String REPO = "Dillxn/dasher-offer-filter-updates";
    static final long MAX_APK_BYTES = 16 * 1024 * 1024;

    static void validate(String packageName, long code, String url, String sha256, long bytes) {
        URI uri = URI.create(url);
        if (!PACKAGE.equals(packageName) || code <= 0 || code > Integer.MAX_VALUE ||
                !"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) ||
                uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null ||
                uri.getFragment() != null ||
                !uri.getPath().matches("/" + REPO + "/releases/download/v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.]+)?/OfferFilter\\.apk") ||
                !sha256.matches("[a-f0-9]{64}") || bytes <= 0 || bytes > MAX_APK_BYTES) {
            throw new IllegalArgumentException("Invalid update metadata");
        }
    }

    static boolean isNewer(long advertised, long installed) { return advertised > installed; }

    static long retryDelayMillis(int consecutiveFailures) {
        int failures = Math.max(1, consecutiveFailures);
        if (failures >= 5) return 15L * 60_000L;
        return (1L << (failures - 1)) * 60_000L;
    }
}
