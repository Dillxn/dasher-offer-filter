package com.local.dasherfilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * The real updater HTTP path; also exercised by the release probe. No Android dependencies. It carries no credential:
 * the feed and the APK are public, and every hop is checked against the Render origin before it is requested.
 */
final class UpdateTransport {
    private static final int MAX_REDIRECTS = 5;
    private static final long TOTAL_TIMEOUT_NANOS = 60_000_000_000L;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    /** @throws IOException unless the address is a plain HTTPS URL on the Render host with a canonical path */
    static void validateAddress(String address) throws IOException {
        if (address == null) throw new IOException("Missing update address");
        try {
            URI uri = URI.create(address);
            String path = uri.getRawPath();
            if (!"https".equals(uri.getScheme()) || !UpdatePolicy.trustedAddress(uri)
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null
                    || path == null || !uri.normalize().getRawPath().equals(path)
                    // URI.normalize() leaves unresolved parents at the root ("/../file") unchanged.
                    || path.contains("/../") || path.endsWith("/..")
                    || path.contains("%") || path.contains("\\")) {
                throw new IOException("Untrusted update address");
            }
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid update address", error);
        }
    }

    /**
     * Streams {@code address} into {@code output}, re-validating every redirect hop and enforcing a byte limit, an
     * exact Content-Length when one is sent, and a one-minute overall deadline.
     */
    static void download(String address, OutputStream output, long limit) throws IOException {
        if (limit <= 0 || limit > UpdatePolicy.MAX_APK_BYTES * 2L) throw new IOException("Invalid download limit");
        long deadline = System.nanoTime() + TOTAL_TIMEOUT_NANOS;
        URL url = new URL(address);
        for (int redirect = 0; redirect < MAX_REDIRECTS; redirect++) {
            validateAddress(url.toExternalForm());
            checkDeadline(deadline);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "OfferFilter-Updater");
            connection.setRequestProperty("Cache-Control", "no-cache, no-store");
            connection.setRequestProperty("Accept-Encoding", "identity");
            try {
                int status = connection.getResponseCode();
                if (isRedirect(status)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) throw new IOException("Redirect without Location");
                    url = new URL(url, location);
                    continue;
                }
                if (status != 200) throw new IOException("Download HTTP " + status);
                long expected = connection.getContentLengthLong();
                if (expected > limit) throw new IOException("Update is too large");
                long received = copy(connection, output, limit, deadline);
                if (expected >= 0 && received != expected) throw new IOException("Truncated update download");
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many download redirects");
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static long copy(HttpURLConnection connection, OutputStream output, long limit, long deadline)
            throws IOException {
        long received = 0;
        try (InputStream input = connection.getInputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkDeadline(deadline);
                received += count;
                if (received > limit) throw new IOException("Update download limit reached");
                output.write(buffer, 0, count);
            }
        }
        return received;
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Update check cancelled");
        if (System.nanoTime() > deadline) throw new IOException("Update download timed out");
    }

    private UpdateTransport() {}
}
