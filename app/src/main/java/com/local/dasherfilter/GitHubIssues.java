package com.local.dasherfilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Files one issue in the app's private repository. Plain HTTPS; no Android dependencies beyond org.json. */
final class GitHubIssues {
    static final String REPOSITORY = "Dillxn/dasher-offer-filter";
    /** Package-private so tests can point it at a local server; production always uses the GitHub API. */
    static String endpoint = "https://api.github.com/repos/" + REPOSITORY + "/issues";
    private static final int MAX_RESPONSE_BYTES = 262_144;

    /** GitHub answered, but not with a created issue. */
    static final class Rejected extends IOException {
        final int code;
        /** GitHub said to slow down (429, or 403 with a rate-limit signal): the same request can succeed later. */
        final boolean rateLimited;

        Rejected(int code, boolean rateLimited) {
            super("GitHub HTTP " + code);
            this.code = code;
            this.rateLimited = rateLimited;
        }

        /** Worth sending again later: an outage (5xx) or a rate limit. */
        boolean retryable() {
            return rateLimited || code == 408 || code == 429 || code >= 500;
        }

        /** The token is missing, expired, revoked or lacks access to the repository. */
        boolean tokenProblem() {
            return !retryable() && (code == 401 || code == 403 || code == 404);
        }
    }

    /**
     * @return the new issue's number
     * @throws Rejected    when GitHub refuses (token problems, an invalid issue, an outage or a rate limit)
     * @throws IOException when GitHub could not be reached
     */
    static int create(String token, String title, String body) throws IOException {
        byte[] payload;
        try {
            payload = new JSONObject().put("title", title).put("body", body).toString()
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("User-Agent", "OfferFilter-Reporter");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
            int status = connection.getResponseCode();
            if (status != 201) {
                boolean rateLimited = connection.getHeaderField("Retry-After") != null
                        || "0".equals(connection.getHeaderField("X-RateLimit-Remaining"))
                        || mentionsRateLimit(connection);
                throw new Rejected(status, rateLimited);
            }
            try (InputStream in = connection.getInputStream()) {
                return new JSONObject(readBounded(in)).getInt("number");
            } catch (JSONException malformed) {
                throw new IOException("Unreadable GitHub response", malformed);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** GitHub's secondary rate limit can answer 403 with no headers, only a message saying so. */
    private static boolean mentionsRateLimit(HttpURLConnection connection) {
        try (InputStream in = connection.getErrorStream()) {
            return in != null && readBounded(in).toLowerCase(java.util.Locale.US).contains("rate limit");
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static String readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
            if (out.size() > MAX_RESPONSE_BYTES) throw new IOException("GitHub response too large");
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private GitHubIssues() {}
}
