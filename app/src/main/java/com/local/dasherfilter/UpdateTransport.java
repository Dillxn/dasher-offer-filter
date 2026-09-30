package com.local.dasherfilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * The real updater HTTP path; also exercised by the release probe. No Android dependencies. A repository request
 * carries the user's GitHub token and asks for the file's raw bytes; the token is only ever sent to the GitHub API
 * under this repository's release folder, because every hop is checked against the channel's origin first.
 */
final class UpdateTransport {
    private static final int MAX_REDIRECTS = 5;
    private static final long TOTAL_TIMEOUT_NANOS = 60_000_000_000L;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    /** @throws IOException unless the address is a plain HTTPS URL on the Render host with a canonical path */
    static void validateAddress(String address) throws IOException {
        validateAddress(UpdatePolicy.Channel.RENDER, address);
    }

    /** @throws IOException unless the address is a plain HTTPS URL on {@code channel}'s origin with a canonical path */
    static void validateAddress(UpdatePolicy.Channel channel, String address) throws IOException {
        try {
            URI uri = URI.create(address);
            String path = uri.getRawPath();
            if (!"https".equals(uri.getScheme()) || !UpdatePolicy.trustedAddress(channel, uri)
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null
                    || path == null || !uri.normalize().getRawPath().equals(path)
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
        download(UpdatePolicy.Channel.RENDER, address, null, output, limit);
    }

    /**
     * Like {@link #download(String, OutputStream, long)} for {@code channel}; {@code token} (the user's GitHub
     * connection) is required for the repository and never sent anywhere else.
     */
    static void download(UpdatePolicy.Channel channel, String address, String token, OutputStream output, long limit)
            throws IOException {
        if (channel == UpdatePolicy.Channel.REPO && (token == null || token.isEmpty())) {
            throw new IOException("Not connected to GitHub");
        }
        if (limit <= 0 || limit > UpdatePolicy.MAX_APK_BYTES * 2L) throw new IOException("Invalid download limit");
        long deadline = System.nanoTime() + TOTAL_TIMEOUT_NANOS;
        URL url = new URL(address);
        for (int redirect = 0; redirect < MAX_REDIRECTS; redirect++) {
            validateAddress(channel, url.toExternalForm());
            checkDeadline(deadline);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "OfferFilter-Updater");
            connection.setRequestProperty("Cache-Control", "no-cache, no-store");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (channel == UpdatePolicy.Channel.REPO) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Accept", "application/vnd.github.raw");
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            }
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
