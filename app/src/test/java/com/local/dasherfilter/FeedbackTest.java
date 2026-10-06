package com.local.dasherfilter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The accountless feedback protocol, as the deployed service takes it: the fields, the parts and their framing, the
 * limits, and an honest reading of every answer. Pure checks; no request leaves.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class FeedbackTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    public void endpointIsTheExactHttpsFunctionAndTestsNeverReachIt() {
        assertEquals("https://zlnfvqyyjsltmkmmpgzp.supabase.co/functions/v1/offer-filter-feedback", Feedback.ENDPOINT);
        assertTrue("ConsentedTestApp installs a fake", Feedback.transport instanceof FakeFeedbackTransport);
    }

    @Test
    public void reportsAreSplitIntoPartsCutAtLineEndsThatReassembleExactly() {
        StringBuilder report = new StringBuilder();
        // About 126,000 characters: three parts of at most 50,000.
        for (int i = 0; i < 2500; i++) report.append("line ").append(i).append(' ').append("x".repeat(40)).append('\n');
        List<String> parts = Feedback.chunks(report.toString(), Feedback.MAX_PART_CHARS, Feedback.MAX_PART_BYTES);
        assertEquals(3, parts.size());
        StringBuilder rebuilt = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            assertTrue(part.length() <= Feedback.MAX_PART_CHARS);
            if (i < parts.size() - 1) assertTrue("cut at a line's end", part.endsWith("\n"));
            String framed = Feedback.framed(Feedback.Kind.FEEDBACK, TOKEN, i, parts.size(), part);
            assertTrue(framed.startsWith("[" + AppName.NAME + " feedback 01234567 part " + (i + 1) + "/3]\n"));
            assertTrue(framed.endsWith("\n[end part " + (i + 1) + "/3]"));
            // The service trims each field: the frame is never whitespace, so nothing of the part is lost.
            rebuilt.append(Feedback.unframed(framed.trim()));
        }
        assertEquals(report.toString(), rebuilt.toString());
        assertEquals(Collections.singletonList("short"), Feedback.chunks("short", 50_000, 120_000));
        assertTrue(Feedback.chunks("", 50_000, 120_000).isEmpty());
    }

    @Test
    public void textThatBeginsOrEndsWithWhitespaceReassemblesExactly() {
        String text = "\n\n    read: [indented]\n\t\nlast line with trailing spaces   \n\n";
        List<String> parts = Feedback.chunks(text, 12, 1_000);
        StringBuilder rebuilt = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            rebuilt.append(Feedback.unframed(Feedback.framed(Feedback.Kind.DIAGNOSTICS, TOKEN, i, parts.size(),
                    parts.get(i)).trim()));
        }
        assertEquals(text, rebuilt.toString());
    }

    @Test
    public void wideCharactersKeepEachPartInsideTheServicesByteLimit() {
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < 90_000; i++) wide.append(i % 50 == 49 ? '\n' : '•');
        wide.append("😀😀😀");
        List<String> parts = Feedback.chunks(wide.toString(), Feedback.MAX_PART_CHARS, Feedback.MAX_PART_BYTES);
        assertTrue(parts.size() > 1);
        StringBuilder rebuilt = new StringBuilder();
        for (String part : parts) {
            assertTrue(part.length() <= Feedback.MAX_PART_CHARS);
            assertFalse("never half a character pair", Character.isHighSurrogate(part.charAt(part.length() - 1)));
            rebuilt.append(part);
        }
        assertEquals(wide.toString(), rebuilt.toString());
        // The heaviest part, with a full message, still fits the service's request limit.
        String message = "\"".repeat(Feedback.MAX_MESSAGE_CHARS);
        for (int i = 0; i < parts.size(); i++) {
            byte[] body = Feedback.bytes(request(Feedback.Kind.FEEDBACK, message, i, parts.size(), parts.get(i)));
            assertTrue(body.length + " bytes", body.length <= Feedback.MAX_BODY_BYTES);
        }
        String quotes = "\"".repeat(60_000);
        for (String part : Feedback.chunks(quotes, Feedback.MAX_PART_CHARS, Feedback.MAX_PART_BYTES)) {
            assertTrue(Feedback.bytes(request(Feedback.Kind.FEEDBACK, message, 0, 2, part)).length
                    <= Feedback.MAX_BODY_BYTES);
        }
    }

    private static JSONObject request(Feedback.Kind kind, String message, int index, int count, String text) {
        try {
            return Feedback.request(kind, "bug", message, true, TOKEN, index, count, text, "0.4.73", 79);
        } catch (org.json.JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Test
    public void eachPartCarriesOnlyTheServicesFieldsAndNoIdentifier() throws Exception {
        JSONObject first = request(Feedback.Kind.FEEDBACK, " It declined a $12 offer. ", 0, 2, "part one\n");
        Set<String> keys = new HashSet<>();
        for (Iterator<String> names = first.keys(); names.hasNext(); ) keys.add(names.next());
        assertEquals(new HashSet<>(Arrays.asList("kind", "category", "appVersion", "appVersionCode",
                "diagnosticsConsented", "reportToken", "partIndex", "partCount", "message", "diagnostics")), keys);
        assertEquals("feedback", first.getString("kind"));
        assertEquals("bug", first.getString("category"));
        assertEquals("It declined a $12 offer.", first.getString("message"));
        assertEquals(TOKEN, first.getString("reportToken"));
        assertEquals(0, first.getInt("partIndex"));
        assertEquals(2, first.getInt("partCount"));
        assertTrue(first.getBoolean("diagnosticsConsented"));
        assertEquals("[" + AppName.NAME + " feedback 01234567 part 1/2]\npart one\n\n[end part 1/2]",
                first.getString("diagnostics"));

        JSONObject second = request(Feedback.Kind.FEEDBACK, "It declined a $12 offer.", 1, 2, "part two");
        assertEquals("the service needs a message on every feedback row", "(continued: part 2 of 2)",
                second.getString("message"));
        JSONObject problem = request(Feedback.Kind.PROBLEM, "", 0, 1, "{}");
        assertFalse("an offer report needs no note", problem.has("message"));
        JSONObject plain = Feedback.request(Feedback.Kind.FEEDBACK, "general", "Hello", true, TOKEN, 0, 1, null,
                "0.4.73", 79);
        assertFalse(plain.has("diagnostics"));
        assertFalse("consent to diagnostics is only ever sent with them", plain.getBoolean("diagnosticsConsented"));
        String body = new String(Feedback.bytes(first), StandardCharsets.UTF_8);
        for (String identifier : new String[] {"android_id", "deviceId", "installId", "email", "login", "token\""}) {
            assertFalse(identifier, body.contains(identifier));
        }
    }

    @Test
    public void everyAnswerIsReadHonestly() {
        assertEquals(Feedback.State.SENT, Feedback.outcome(201));
        assertEquals(Feedback.State.RATE_LIMITED, Feedback.outcome(429));
        assertEquals(Feedback.State.REJECTED, Feedback.outcome(400));
        assertEquals(Feedback.State.REJECTED, Feedback.outcome(413));
        assertEquals(Feedback.State.UNAVAILABLE, Feedback.outcome(500));
        assertEquals(Feedback.State.UNAVAILABLE, Feedback.outcome(502));
        assertEquals(Feedback.State.UNAVAILABLE, Feedback.outcome(503));
        for (int status : new int[] {401, 403, 404, 405, 415, 301}) {
            assertEquals("a misconfigured service never drops what the user typed", Feedback.State.NOT_ANSWERING,
                    Feedback.outcome(status));
            assertTrue(Feedback.outcome(status).waiting());
        }
        assertEquals("Too many sends from this network right now; it will retry", Feedback.State.RATE_LIMITED.said);
        assertEquals("Couldn't be accepted (too long?)", Feedback.State.REJECTED.said);
        assertEquals("Saved; it will send when you're online", Feedback.State.OFFLINE.said);
        assertEquals("Saved; it will send when you're online", Feedback.State.UNAVAILABLE.said);
        assertTrue(Feedback.State.REJECTED.finished);
        assertFalse(Feedback.State.RATE_LIMITED.finished);
    }

    @Test
    public void onlyAWellFormedReferenceIsKept() {
        assertEquals("1a2b3c4d", Feedback.reference(new Feedback.Response(201,
                "{\"ok\":true,\"reference\":\"1a2b3c4d\"}", 0)));
        assertEquals("", Feedback.reference(new Feedback.Response(201, "{\"reference\":\"<b>x</b>\"}", 0)));
        assertEquals("", Feedback.reference(new Feedback.Response(201, "not json", 0)));
        assertEquals("", Feedback.reference(new Feedback.Response(201, "{}", 0)));
    }

    @Test
    public void everySubmissionHasAFreshRandomToken() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String token = Feedback.newToken();
            assertTrue(token, Feedback.validToken(token));
            tokens.add(token);
        }
        assertEquals(50, tokens.size());
        assertNotEquals(Feedback.newToken(), Feedback.newToken());
        assertFalse(Feedback.validToken("0123456789ABCDEF0123456789ABCDEF"));
        assertFalse(Feedback.validToken("0123"));
    }

    @Test
    public void typedWordsLoseControlCharactersAndKeepTheLimit() {
        assertEquals("Line one\nline\ttwo", Feedback.typed("  Line one\nline\ttwo\u0000\u0007  "));
        assertEquals(Feedback.MAX_MESSAGE_CHARS, Feedback.typed("a".repeat(5000)).length());
        assertEquals("", Feedback.typed(null));
        String pairCut = "a".repeat(Feedback.MAX_MESSAGE_CHARS - 1) + "😀";
        String typed = Feedback.typed(pairCut);
        assertFalse(Character.isHighSurrogate(typed.charAt(typed.length() - 1)));
        assertEquals("120 / 4,000", FeedbackDialogs.count(120));
    }

    @Test
    public void aRetryAfterHeaderIsReadInSecondsAndBounded() {
        assertEquals(30_000L, FeedbackHttp.retryAfter("30"));
        assertEquals(3_600_000L, FeedbackHttp.retryAfter("86400"));
        assertEquals(0L, FeedbackHttp.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(0L, FeedbackHttp.retryAfter(null));
        assertEquals(0L, FeedbackHttp.retryAfter("-5"));
    }

    @Test
    public void retriesBackOffAndRespectTheRateLimitWindow() {
        assertEquals(30_000L, FeedbackOutbox.delay(Feedback.State.OFFLINE, 1, 0));
        assertEquals(60_000L, FeedbackOutbox.delay(Feedback.State.OFFLINE, 2, 0));
        assertEquals(FeedbackOutbox.MAX_RETRY_MS, FeedbackOutbox.delay(Feedback.State.OFFLINE, 30, 0));
        assertEquals("at least the service's ten-minute window", FeedbackOutbox.RATE_LIMIT_MS,
                FeedbackOutbox.delay(Feedback.State.RATE_LIMITED, 1, 0));
        assertEquals(120_000L, FeedbackOutbox.delay(Feedback.State.UNAVAILABLE, 1, 120_000L));
    }
}
