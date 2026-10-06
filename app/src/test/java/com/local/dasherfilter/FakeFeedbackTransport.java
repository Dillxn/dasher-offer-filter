package com.local.dasherfilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The feedback service as tests see it: every request is recorded, and answered from a queue of planned answers
 * (a status, or no connection), else with 201 and a reference. ConsentedTestApp installs one for every test, so no
 * test can ever reach the real service.
 */
final class FakeFeedbackTransport implements Feedback.Transport {
    /** Every request body received, in order. */
    private final List<JSONObject> requests = new ArrayList<>();
    private final Deque<Object> answers = new ArrayDeque<>();
    /** Run while a request is "in flight", after it was recorded; tests use it to change state mid-send. */
    volatile Runnable during;
    /** No connection at all while set: every request fails (after being recorded as attempted). */
    volatile boolean down;

    static FakeFeedbackTransport installed() {
        Feedback.Transport transport = Feedback.transport;
        if (!(transport instanceof FakeFeedbackTransport)) throw new AssertionError("no fake transport installed");
        return (FakeFeedbackTransport) transport;
    }

    @Override public Feedback.Response post(byte[] body) throws IOException {
        JSONObject json;
        try {
            json = new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException malformed) {
            throw new AssertionError("the app sent malformed JSON", malformed);
        }
        Object answer;
        synchronized (this) {
            requests.add(json);
            answer = answers.poll();
        }
        Runnable middle = during;
        if (middle != null) middle.run();
        if (down) throw new IOException("no connection in tests");
        if (answer instanceof IOException) throw (IOException) answer;
        if (answer instanceof Feedback.Response) return (Feedback.Response) answer;
        String token = json.optString("reportToken", "00000000");
        // The real service answers with the first characters of the stored row's id; here, the token's.
        return new Feedback.Response(201, "{\"ok\":true,\"reference\":\"" + token.substring(0, 8) + "\"}", 0);
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
}
