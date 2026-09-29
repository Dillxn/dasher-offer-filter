package com.local.dasherfilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/** The real updater HTTP path; also exercised by the release probe. No Android dependencies. */
final class UpdateTransport {
    static void validateAddress(String address) throws IOException {
        try {
            URI uri = URI.create(address);
            if (!"https".equals(uri.getScheme()) || !UpdatePolicy.trustedDownloadHost(uri.getHost()) ||
                    uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null ||
                    uri.getRawPath() == null || !uri.normalize().getRawPath().equals(uri.getRawPath()) ||
                    uri.getRawPath().contains("%") || uri.getRawPath().contains("\\")) {
                throw new IOException("Untrusted update address");
            }
        } catch (IllegalArgumentException error) { throw new IOException("Invalid update address", error); }
    }
    static void download(String address, OutputStream output, long limit) throws IOException {
        if (limit <= 0 || limit > UpdatePolicy.MAX_APK_BYTES * 2L) throw new IOException("Invalid download limit");
        long deadline = System.nanoTime() + 60_000_000_000L;
        URL url = new URL(address);
        for (int redirect = 0; redirect < 5; redirect++) {
            validateAddress(url.toExternalForm()); checkDeadline(deadline);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(15000);
            c.setUseCaches(false); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "OfferFilter-Updater");
            c.setRequestProperty("Cache-Control", "no-cache, no-store");
            c.setRequestProperty("Accept-Encoding", "identity");
            try {
                int status = c.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = c.getHeaderField("Location");
                    if (location == null || location.isEmpty()) throw new IOException("Redirect without Location");
                    url = new URL(url, location); continue;
                }
                if (status != 200) throw new IOException("Download HTTP " + status);
                long expected = c.getContentLengthLong();
                if (expected > limit) throw new IOException("Update is too large");
                long received = 0;
                try (InputStream input = c.getInputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkDeadline(deadline); received += count;
                        if (received > limit) throw new IOException("Update download limit reached");
                        output.write(buffer, 0, count);
                    }
                }
                if (expected >= 0 && received != expected) throw new IOException("Truncated update download");
                return;
            } finally { c.disconnect(); }
        }
        throw new IOException("Too many download redirects");
    }
    private static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Update check cancelled");
        if (System.nanoTime() > deadline) throw new IOException("Update download timed out");
    }
    private UpdateTransport() {}
}
