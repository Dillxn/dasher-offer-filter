package com.local.dasherfilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

import static org.junit.Assert.assertEquals;

/**
 * The feedback service as tests see it: every request is recorded, checked against the service's own rules
 * (backend/anonymous-feedback/lib.ts validate() and the hardened function's per-submission rules, which the deployed
 * one's are a subset of) and answered from a queue of planned answers (a status, or no connection), else as the
 * service would: 201 with the submission's reference, a part stored before answered 201 again without being stored
 * twice, and a request the service would refuse answered 400 and noted as a violation, which the sending test classes
 * fail on ({@link #assertHonored}). ConsentedTestApp installs one for every test, so no test can ever reach the real
 * service.
 */
final class FakeFeedbackTransport implements Feedback.Transport {
    private static final Set<String> KINDS = new HashSet<>(Arrays.asList("feedback", "problem", "diagnostics"));
    private static final Set<String> CATEGORIES = new HashSet<>(Arrays.asList("general", "bug", "feature", "ux",
            "privacy", "update", "other"));
    private static final Pattern APP_VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,31}");
    private static final Pattern TOKEN = Pattern.compile("[a-f0-9]{32}");
    private static final int MAX_BODY_BYTES = 150_000;
    private static final int MAX_PARTS = 4;

    /** Every request body received, in order. */
    private final List<JSONObject> requests = new ArrayList<>();
    private final Deque<Object> answers = new ArrayDeque<>();
    /** What the service stored, by "token/part": stored once, whatever is retried. */
    private final Map<String, JSONObject> stored = new HashMap<>();
    /** Requests the service would refuse, and why. */
    private final List<String> violations = new ArrayList<>();
    /** Run while a request is "in flight", after it was recorded; tests use it to change state mid-send. */
    volatile Runnable during;
    /** No connection at all while set: every request fails (after being recorded as attempted). */
    volatile boolean down;

    static FakeFeedbackTransport installed() {
        Feedback.Transport transport = Feedback.transport;
        if (!(transport instanceof FakeFeedbackTransport)) throw new AssertionError("no fake transport installed");
        return (FakeFeedbackTransport) transport;
    }

    /** No request of the test broke the service's rules (it would have been refused, and silently dropped). */
    static void assertHonored() {
        assertEquals("requests the feedback service would refuse", new ArrayList<String>(),
                installed().violations());
    }

    @Override public Feedback.Response post(byte[] body) throws IOException {
        JSONObject json;
        try {
            json = new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException malformed) {
            throw new AssertionError("the app sent malformed JSON", malformed);
        }
        Object answer;
        String refused;
        synchronized (this) {
            requests.add(json);
            answer = answers.poll();
            refused = body.length > MAX_BODY_BYTES ? "body over " + MAX_BODY_BYTES + " bytes" : refusal(json);
            if (refused != null) violations.add(refused + ": " + shortly(json));
        }
        Runnable middle = during;
        if (middle != null) middle.run();
        if (down) throw new IOException("no connection in tests");
        if (answer instanceof IOException) throw (IOException) answer;
        if (answer instanceof Feedback.Response) {
            Feedback.Response planned = (Feedback.Response) answer;
            // A planned acceptance is one the service stored.
            if (planned.status >= 200 && planned.status < 300 && refused == null) store(json);
            return planned;
        }
        if (refused != null) {
            return new Feedback.Response(body.length > MAX_BODY_BYTES ? 413 : 400,
                    "{\"ok\":false,\"error\":\"invalid_feedback\"}", 0);
        }
        String token = json.optString("reportToken", "");
        store(json);
        // The real service answers with the first characters of the submission's first row; here, the token's.
        String reference = token.isEmpty() ? "00000000" : token.substring(0, 8);
        return new Feedback.Response(201, "{\"ok\":true,\"reference\":\"" + reference + "\"}", 0);
    }

    /** Stored once: a retry of a stored part changes nothing and gets the same answer. */
    private synchronized void store(JSONObject json) {
        String token = json.optString("reportToken", "");
        if (!token.isEmpty()) stored.putIfAbsent(token + "/" + json.optInt("partIndex", 0), json);
    }

    /** Why the service would refuse this request (lib.ts validate(), and the parts of one token), or null. */
    private String refusal(JSONObject json) {
        try {
            String kind = trimmed(json, "kind", 24);
            if (kind == null) kind = "feedback";
            if (!KINDS.contains(kind)) return "invalid kind";
            String category = trimmed(json, "category", 64);
            if (category == null) category = "general";
            if (!CATEGORIES.contains(category)) return "invalid category";
            String message = trimmed(json, "message", 4_000);
            String diagnostics = untrimmed(json, "diagnostics", 60_000);
            boolean consented = Boolean.TRUE.equals(json.opt("diagnosticsConsented"));
            if (diagnostics != null && !consented) return "diagnostics consent required";
            if (kind.equals("feedback") && message == null) return "feedback message required";
            if (kind.equals("diagnostics") && diagnostics == null) return "diagnostics required";
            if (message == null && diagnostics == null) return "empty";
            String appVersion = trimmed(json, "appVersion", 32);
            if (appVersion != null && !APP_VERSION.matcher(appVersion).matches()) return "invalid app version";
            integer(json, "appVersionCode", 1, 10_000_000, null);
            String token = trimmed(json, "reportToken", 32);
            if (token != null && !TOKEN.matcher(token).matches()) return "invalid report token";
            int index = integer(json, "partIndex", 0, MAX_PARTS - 1, 0);
            int count = integer(json, "partCount", 1, MAX_PARTS, 1);
            if (index >= count) return "invalid part";
            if (token == null && count != 1) return "parts need a report token";
            if (token != null) {
                // The hardened function's rules for the parts of one submission. A part stored before is a duplicate,
                // answered as stored, before anything else is looked at.
                if (stored.containsKey(token + "/" + index)) return null;
                JSONObject first = null;
                for (Map.Entry<String, JSONObject> part : stored.entrySet()) {
                    if (!part.getKey().startsWith(token + "/")) continue;
                    if (part.getValue().optInt("partCount", 1) != count) return "part_mismatch (partCount)";
                    if (!part.getValue().optString("kind", "feedback").equals(kind)) return "part_mismatch (kind)";
                    if (part.getKey().equals(token + "/0")) first = part.getValue();
                }
                if (index > 0 && first == null) return "part_out_of_order";
            }
            return null;
        } catch (IllegalArgumentException invalid) {
            return invalid.getMessage();
        }
    }

    private static String trimmed(JSONObject json, String field, int max) {
        Object value = json.opt(field);
        if (value == null || value == JSONObject.NULL) return null;
        if (!(value instanceof String)) throw new IllegalArgumentException("invalid text: " + field);
        String text = ((String) value).trim();
        if (text.isEmpty()) return null;
        if (text.length() > max) throw new IllegalArgumentException("text too long: " + field);
        return text;
    }

    private static String untrimmed(JSONObject json, String field, int max) {
        Object value = json.opt(field);
        if (value == null || value == JSONObject.NULL) return null;
        if (!(value instanceof String)) throw new IllegalArgumentException("invalid text: " + field);
        String text = (String) value;
        if (text.trim().isEmpty()) return null;
        if (text.length() > max) throw new IllegalArgumentException("text too long: " + field);
        return text;
    }

    private static Integer integer(JSONObject json, String field, int min, int max, Integer otherwise) {
        Object value = json.opt(field);
        if (value == null || value == JSONObject.NULL) return otherwise;
        if (!(value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException("invalid integer: "
                + field);
        long number = ((Number) value).longValue();
        if (number < min || number > max) throw new IllegalArgumentException("invalid integer: " + field);
        return (int) number;
    }

    private static String shortly(JSONObject json) {
        String text = json.toString();
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    /** The next request is answered with {@code status} (and {@code body}). */
    synchronized FakeFeedbackTransport answer(int status, String body) {
        answers.add(new Feedback.Response(status, body, 0));
        return this;
    }

    synchronized FakeFeedbackTransport answer(int status, String body, long retryAfterMs) {
        answers.add(new Feedback.Response(status, body, retryAfterMs));
        return this;
    }

    /** The next request finds no connection. */
    synchronized FakeFeedbackTransport offline() {
        answers.add(new IOException("offline in tests"));
        return this;
    }

    synchronized List<JSONObject> requests() {
        return new ArrayList<>(requests);
    }

    synchronized int count() {
        return requests.size();
    }

    /** The part the service keeps of {@code token}'s part {@code index} (the first one it accepted), or null. */
    synchronized JSONObject stored(String token, int index) {
        return stored.get(token + "/" + index);
    }

    synchronized List<String> violations() {
        return new ArrayList<>(violations);
    }
}
