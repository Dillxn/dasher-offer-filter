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

/**
 * Files issues in the app's private repository, and comments on them, and lists its open diagnostics issues to find
 * one already filed: nothing else, and nowhere else (every request goes under {@link #endpoint}, this repository's
 * issues). Plain HTTPS; no Android dependencies beyond org.json.
 */
final class GitHubIssues {
    static final String REPOSITORY = "Dillxn/dasher-offer-filter";
    /** Package-private so tests can point it at a local server; production always uses the GitHub API. */
    static String endpoint = "https://api.github.com/repos/" + REPOSITORY + "/issues";
    private static final int MAX_RESPONSE_BYTES = 262_144;
    /** A page of diagnostics issues can be large: each body holds up to 60,000 characters. */
    private static final int MAX_LIST_BYTES = 4 * 1024 * 1024;
    /** How many of the newest open issues one look for an issue already filed reads. */
    static final int LIST_PAGE = 10;

    /** How a request reaches GitHub; tests put a fake in its place. */
    interface Transport {
        /**
         * Sends one request under {@link #endpoint} and returns GitHub's answer: a POST must be answered 201
         * (created), a GET 200.
         *
         * @param json the request's body, or null for a GET
         * @throws Rejected    when GitHub answers otherwise
         * @throws IOException when GitHub could not be reached, or the answer was cut off
         */
        String send(String method, String url, String token, String json, int maxBytes) throws IOException;
    }

    /** Package-private so tests can fake GitHub; production always uses HTTPS. */
    static Transport transport = GitHubIssues::http;

    /** GitHub answered, but not with a created issue. */
    static final class Rejected extends IOException {
        final int code;
        /** GitHub said to slow down (429, or 403 with a rate-limit signal): the same request can succeed later. */
        final boolean rateLimited;
        /** GitHub's own words, such as "Resource not accessible by integration"; "" when it gave none. */
        final String message;

        Rejected(int code, boolean rateLimited, String message) {
            super("GitHub HTTP " + code);
            this.code = code;
            this.rateLimited = rateLimited;
            this.message = message;
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
        return create(token, title, body, null);
    }

    /**
     * @param labels the issue's labels, or null for none (GitHub drops labels the token may not set)
     * @return the new issue's number
     */
    static int create(String token, String title, String body, java.util.List<String> labels) throws IOException {
        try {
            JSONObject issue = new JSONObject().put("title", title).put("body", body);
            if (labels != null && !labels.isEmpty()) issue.put("labels", new org.json.JSONArray(labels));
            return new JSONObject(post(endpoint, token, issue)).getInt("number");
        } catch (JSONException malformed) {
            throw new IOException("Unreadable GitHub response", malformed);
        }
    }

    /**
     * Adds a comment to one of this repository's issues.
     *
     * @throws Rejected    when GitHub refuses
     * @throws IOException when GitHub could not be reached
     */
    static void comment(String token, int issue, String body) throws IOException {
        if (issue <= 0) throw new IOException("No issue to comment on");
        try {
            post(endpoint + "/" + issue + "/comments", token, new JSONObject().put("body", body));
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
    }

    /** Finds a dash receipt, including closed issues and older pages. A bounded incomplete search fails closed. */
    static int find(String token, String label, String marker) throws IOException {
        try {
            for (int page = 1; page <= 100; page++) {
                // Ignore labels: a user may remove one after creation, and a refused label falls back to no label.
                String url = endpoint + "?state=all&sort=created&direction=desc&per_page=" + LIST_PAGE + "&page=" + page;
                org.json.JSONArray issues = new org.json.JSONArray(transport.send("GET", url, token, null,
                        MAX_LIST_BYTES));
                for (int i = 0; i < issues.length(); i++) {
                    JSONObject issue = issues.optJSONObject(i);
                    if (issue == null || issue.has("pull_request")) continue;
                    if (issue.optString("body", "").contains(marker)) return issue.getInt("number");
                }
                if (issues.length() < LIST_PAGE) return 0;
            }
            throw new IOException("Dash receipt search incomplete; no duplicate issue created");
        } catch (JSONException malformed) {
            throw new IOException("Unreadable GitHub response", malformed);
        }
    }

    /** Finds one opaque part marker across an issue's comments; an incomplete search fails closed. */
    static boolean findComment(String token, int issue, String marker) throws IOException {
        if (issue <= 0) throw new IOException("No issue to inspect");
        try {
            for (int page = 1; page <= 100; page++) {
                String url = endpoint + "/" + issue + "/comments?per_page=" + LIST_PAGE + "&page=" + page;
                org.json.JSONArray comments = new org.json.JSONArray(transport.send("GET", url, token, null,
                        MAX_LIST_BYTES));
                for (int i = 0; i < comments.length(); i++) {
                    JSONObject comment = comments.optJSONObject(i);
                    if (comment != null && comment.optString("body", "").contains(marker)) return true;
                }
                if (comments.length() < LIST_PAGE) return false;
            }
            throw new IOException("Comment receipt search incomplete; no duplicate comment created");
        } catch (JSONException malformed) {
            throw new IOException("Unreadable GitHub response", malformed);
        }
    }

    /** POSTs {@code json} and returns GitHub's answer to a created item (201). */
    private static String post(String url, String token, JSONObject json) throws IOException {
        return transport.send("POST", url, token, json.toString(), MAX_RESPONSE_BYTES);
    }

    /** One request over HTTPS: a POST of {@code json} answered 201, or a GET answered 200. */
    private static String http(String method, String url, String token, String json, int maxBytes)
            throws IOException {
        boolean post = json != null;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "OfferFilter-Reporter");
            if (post) {
                byte[] payload = json.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }
            int status = connection.getResponseCode();
            if (status != (post ? 201 : 200)) {
                String error = errorBody(connection);
                // GitHub's secondary rate limit can answer 403 with no headers, only a message saying so.
                boolean rateLimited = connection.getHeaderField("Retry-After") != null
                        || "0".equals(connection.getHeaderField("X-RateLimit-Remaining"))
                        || error.toLowerCase(java.util.Locale.US).contains("rate limit");
                throw new Rejected(status, rateLimited, messageOf(error));
            }
            try (InputStream in = connection.getInputStream()) {
                return readBounded(in, maxBytes);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String errorBody(HttpURLConnection connection) {
        try (InputStream in = connection.getErrorStream()) {
            return in == null ? "" : readBounded(in, MAX_RESPONSE_BYTES);
        } catch (IOException unreadable) {
            return "";
        }
    }

    /** The "message" of GitHub's error answer, as one short plain line; "" when there is none. */
    static String messageOf(String error) {
        String message;
        try {
            message = new JSONObject(error).optString("message", "");
        } catch (JSONException notJson) {
            return "";
        }
        message = message.replaceAll("[\\p{Cntrl}]+", " ").trim();
        return message.length() > 120 ? message.substring(0, 119).trim() + "…" : message;
    }

    private static String readBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
            if (out.size() > maxBytes) throw new IOException("GitHub response too large");
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private GitHubIssues() {}
}
