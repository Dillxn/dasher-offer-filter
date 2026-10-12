package com.local.dasherfilter;

import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The anonymous daily count's pure parts, with no Android: the service's answers are taken as the protocol says (2xx
 * counted, 400/413/415/422/429 not retried that day, every other answer retried), the day is the UTC day whatever the
 * phone's time zone, and the token is 32 fresh lowercase hex digits. The request itself is Android's org.json, which a
 * plain test cannot run (Gradle's android.jar only stubs it), so DailyCountTest holds it to the protocol's six fields.
 */
public class DailyCountProtocolTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    public void theAnswersAreTakenAsTheProtocolSays() {
        for (int status : new int[] {200, 201, 202, 204, 299}) {
            assertEquals("any 2xx: today is done and the install counted (" + status + ")",
                    DailyCount.Outcome.COUNTED, DailyCount.outcome(status));
        }
        for (int status : new int[] {400, 413, 415, 422, 429}) {
            assertEquals("not retried today (" + status + "); the function from before counts answers 400",
                    DailyCount.Outcome.REFUSED, DailyCount.outcome(status));
        }
        for (int status : new int[] {0, 100, 301, 302, 304, 401, 403, 404, 405, 408, 409, 500, 502, 503, 504}) {
            assertEquals("any other answer is tried again the same day (" + status + ")", DailyCount.Outcome.RETRY,
                    DailyCount.outcome(status));
        }
    }

    @Test
    public void theLogSaysEachOutcomeInFixedWords() {
        assertEquals("daily count sent", DailyCount.said(DailyCount.Outcome.COUNTED, 201));
        assertEquals("daily count not accepted (status 400): not retried today",
                DailyCount.said(DailyCount.Outcome.REFUSED, 400));
        assertEquals("daily count will retry: no connection", DailyCount.said(DailyCount.Outcome.RETRY, 0));
        assertEquals("daily count will retry: status 503", DailyCount.said(DailyCount.Outcome.RETRY, 503));
    }

    @Test
    public void theDayIsTheUtcDayWhateverThePhonesTimeZone() {
        assertEquals(0, DailyCount.utcDay(0));
        assertEquals(0, DailyCount.utcDay(DailyCount.DAY_MS - 1));
        assertEquals(1, DailyCount.utcDay(DailyCount.DAY_MS));
        assertEquals("before 1970 too, never rounded towards it", -1, DailyCount.utcDay(-1));
        // 12 October 2026 23:59:59.999 UTC and the next millisecond.
        long midnight = 20_739L * DailyCount.DAY_MS;
        TimeZone before = TimeZone.getDefault();
        try {
            for (String zone : new String[] {"America/New_York", "Pacific/Kiritimati", "Pacific/Pago_Pago", "UTC"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertEquals(zone, 20_738L, DailyCount.utcDay(midnight - 1));
                assertEquals(zone, 20_739L, DailyCount.utcDay(midnight));
            }
        } finally {
            TimeZone.setDefault(before);
        }
    }

    @Test
    public void theNextDaysCountWaitsForTheNextUtcDay() {
        long noon = 20_739L * DailyCount.DAY_MS + DailyCount.DAY_MS / 2;
        assertEquals(DailyCount.DAY_MS / 2, DailyCount.untilNextDay(noon, 0));
        assertEquals(DailyCount.DAY_MS / 2 + 3_600_000L, DailyCount.untilNextDay(noon, 3_600_000L));
        assertEquals("a spread is never negative", DailyCount.DAY_MS / 2, DailyCount.untilNextDay(noon, -5));
        assertEquals("from the first millisecond of a day, the whole day", DailyCount.DAY_MS,
                DailyCount.untilNextDay(20_739L * DailyCount.DAY_MS, 0));
        assertEquals(1, DailyCount.untilNextDay(20_740L * DailyCount.DAY_MS - 1, 0));
    }

    @Test
    public void theTokenIs32FreshLowercaseHexDigits() {
        Pattern shape = Pattern.compile("[0-9a-f]{32}");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String token = DailyCount.newToken();
            assertTrue(token, shape.matcher(token).matches());
            assertTrue(token, DailyCount.validToken(token));
            assertTrue("fresh every time", seen.add(token));
        }
        assertNotEquals(DailyCount.newToken(), DailyCount.newToken());
        for (String bad : new String[] {null, "", TOKEN.toUpperCase(java.util.Locale.US), TOKEN + "0",
                TOKEN.substring(1), "g123456789abcdef0123456789abcdef"}) {
            assertFalse(String.valueOf(bad), DailyCount.validToken(bad));
        }
    }
}
