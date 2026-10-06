package com.local.dasherfilter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What "Report this offer" sends about one offer: its figures, decision and fixed outcome category, its read lines
 * masked twice, the current rules, the app's and Android's versions and the minute it was decided; never its learning
 * steps, the exact time or anything typed. The masking cases come from the retired GitHub problem reports.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class OfferReportTest {
    private static final FilterSettings RULES = new FilterSettings(true, 1200, 150, 60, 0, 4);
    /** 2026-10-06 21:02:37.123 UTC. */
    private static final long AT = 1_791_320_557_123L;

    private static DecisionLog.Entry unreadable(String pay) {
        return new DecisionLog.Entry(AT, DecisionLog.Source.SCREEN, false, new OfferSnapshot(null, 7.2, 21, 2), 0,
                OfferRule.Result.REVIEW, "pay not found", DecisionLog.Action.NEEDS_REVIEW, true,
                Arrays.asList(pay, "2 stops (7.2 mi) • 21 min"));
    }

    private static JSONObject report(DecisionLog.Entry entry) throws JSONException {
        return new JSONObject(OfferReport.text(OfferReport.Problem.MISREAD, "0.4.73", 79, "Android 15 (API 35)",
                RULES, entry));
    }

    @Test
    public void reportCarriesWhatWasReadAsAFixtureWithTheCurrentRules() throws JSONException {
        JSONObject data = report(unreadable("Guaranteed pay"));

        assertEquals("offer", data.getString("report"));
        assertEquals("misread", data.getString("problem"));
        assertEquals("0.4.73 (79)", data.getString("app"));
        assertEquals("the version, never the phone's maker", "Android 15 (API 35)", data.getString("android"));
        assertEquals(1200, data.getJSONObject("rules").getInt("flatCents"));
        assertEquals(4, data.getJSONObject("rules").getInt("maxStops"));
        assertTrue(data.getJSONObject("rules").getString("context").startsWith("current rules"));
        JSONObject entry = data.getJSONObject("entry");
        assertEquals("pay not found", entry.getString("reason"));
        assertEquals("REVIEW", entry.getString("outcome"));
        assertEquals("Guaranteed pay", entry.getJSONArray("evidence").getString(0));
        assertEquals("2 stops (7.2 mi) • 21 min", entry.getJSONArray("evidence").getString(1));
        assertTrue(data.getString("read"), data.getString("read").startsWith("pay unknown, 7.2 mi · 21 min · 2 stops"));
        assertTrue(data.getString("decision").startsWith("REVIEW — pay not found"));
    }

    @Test
    public void theTimeIsTheMinuteInUtcAndNoExactTimeOrStepLeaves() throws JSONException {
        DecisionLog.Entry passing = new DecisionLog.Entry(AT, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(2345, 15.7, 32, 2), 2161, OfferRule.Result.KEEP,
                "score 109% (needs 100%)", DecisionLog.Action.PASSES, true,
                Collections.<String>emptyList()).withStep(new DecisionLog.Step(
                        DecisionLog.StepKind.AUTO_ACCEPT_NOT_SENT, AT + 100, "content_changed"));
        String text = OfferReport.text(OfferReport.Problem.WRONG_ACCEPT, "0.4.73", 79, "Android 15 (API 35)",
                RULES, passing);
        JSONObject data = new JSONObject(text);
        assertEquals("2026-10-06T21:02Z", data.getString("decided"));
        JSONObject entry = data.getJSONObject("entry");
        assertEquals("YOURS", entry.getString("outcome"));
        assertFalse(entry.has("steps"));
        assertFalse(entry.has("at"));
        assertFalse(text, text.contains("content_changed"));
        assertFalse("no epoch milliseconds anywhere", Pattern.compile("\\d{12,}").matcher(text).find());
        assertEquals("wrong_accept", data.getString("problem"));
        assertEquals("bug", OfferReport.Problem.WRONG_ACCEPT.category());
        assertEquals("other", OfferReport.Problem.OTHER.category());
    }

    @Test
    public void aFoldedNotificationKeepsOnlyItsOffsetFromTheScreensReading() throws JSONException {
        DecisionLog.Entry notice = new DecisionLog.Entry(AT - 14_000, DecisionLog.Source.NOTIFICATION, false,
                new OfferSnapshot(790, null, null, null), 1080, OfferRule.Result.REVIEW, "figures missing",
                DecisionLog.Action.CHECK_BELL, true, Collections.<String>emptyList());
        DecisionLog.Entry screen = new DecisionLog.Entry(AT, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(790, 7.2, 21, 2), 1080, OfferRule.Result.DECLINE, "dollars per mile",
                DecisionLog.Action.DECLINE_TAPPED, true, Arrays.asList("$7.90")).withNotification(notice);
        JSONObject folded = report(screen).getJSONObject("entry").getJSONObject("notification");
        assertFalse(folded.has("at"));
        assertEquals(-14, folded.getInt("secondsAfterScreen"));
        assertEquals("CHECK_BELL", folded.getString("action"));
    }

    @Test
    public void namesAndAddressesAreMaskedButFiguresAndOfferWordsStay() throws JSONException {
        DecisionLog.Entry entry = new DecisionLog.Entry(AT, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(790, 7.2, 21, 2), 1080, OfferRule.Result.DECLINE, "dollars per mile",
                DecisionLog.Action.DECLINE_TAPPED, true, Arrays.asList("Jane's order $7.90"));
        String text = OfferReport.text(OfferReport.Problem.WRONG_DECLINE, "0.4.73", 79, "Android 15 (API 35)",
                RULES, entry);
        assertEquals("[name]'s order $7.90",
                new JSONObject(text).getJSONObject("entry").getJSONArray("evidence").getString(0));
        assertFalse(text.contains("Jane"));

        List<String> screen = Arrays.asList("Order for Jane D.", "Chick-fil-A", "123 Main St",
                "$7.90 Guaranteed (incl. tips)", "2 stops (7.2 mi) • 21 min", "Deliver by 7:45 PM", "Accept", "Decline");
        List<String> labels = OfferReport.redact(screen);
        // Names and addresses go as the phone keeps them (PersonalText), then every other word to its shape.
        assertEquals("Order for [name]", labels.get(0));
        assertEquals("single-letter words like \"A\" are vocabulary", "Xxxxx-xxx-A", labels.get(1));
        assertEquals("[address]", labels.get(2));
        assertEquals("$7.90 Guaranteed (incl. tips)", labels.get(3));
        assertEquals("2 stops (7.2 mi) • 21 min", labels.get(4));
        assertEquals("Deliver by 7:45 XX", labels.get(5));
        assertEquals("Accept", labels.get(6));
        assertEquals(Collections.singletonList("Order for [name] at Xxxxx-xxx-A $7.90"),
                OfferReport.redact(Collections.singletonList("Order for Jane D. at Chick-fil-A $7.90")));
    }

    @Test
    public void namesThatAreAlsoWordsAndLongNumbersAreMaskedToo() {
        assertEquals("Order for Xxxx Xxx", OfferReport.redact("Order for Will May"));
        assertEquals("#### Xxxx Xx Xxx 5, Xxx Xxxxxxxxx, XX #####",
                OfferReport.redact("1234 Main St Apt 5, San Francisco, CA 94110"));
        assertEquals("(415) 555-####", OfferReport.redact("(415) 555-1234"));
        assertEquals("$12.50 · 3.4 mi · 120 min", OfferReport.redact("$12.50 · 3.4 mi · 120 min"));
        assertEquals("what was masked stays legible", "[card] [street] [phone]",
                OfferReport.redact("[card] [street] [phone]"));
    }

    @Test
    public void maskedLinesMaskToThemselvesSoAStoredReportCanBeMaskedAgain() throws JSONException {
        DecisionLog.Entry entry = new DecisionLog.Entry(AT, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(790, 7.2, 21, 2), 1080, OfferRule.Result.DECLINE, "dollars per mile",
                DecisionLog.Action.DECLINE_TAPPED, true, Arrays.asList("Order for [name] at Chick-fil-A $7.90"));
        String text = OfferReport.text(OfferReport.Problem.MISREAD, "0.4.73", 79, "Android 15 (API 35)", RULES, entry);
        assertEquals("masked again, it is the same", text, OfferReport.remask(text));

        // A report written before a masking repair: the repair reaches it before it is sent.
        JSONObject stale = new JSONObject(text);
        stale.getJSONObject("entry").put("evidence", new JSONArray().put("Deliver to Sam P · 100 Example St"));
        String repaired = OfferReport.remask(stale.toString());
        assertFalse(repaired, repaired.contains("Sam"));
        assertFalse(repaired, repaired.contains("Example"));
        assertNull("not a report this app wrote", OfferReport.remask("{\"something\":\"else\"}"));
        assertNull(OfferReport.remask("not json"));
    }

    @Test
    public void longLinesAreClippedSoOneHugeNodeCannotCrowdOutTheRest() throws JSONException {
        StringBuilder huge = new StringBuilder("$");
        for (int i = 0; i < 1000; i++) huge.append('x');
        DecisionLog.Entry entry = new DecisionLog.Entry(AT, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(790, 7.2, 21, 2), 1080, OfferRule.Result.DECLINE, "dollars per mile",
                DecisionLog.Action.DECLINE_TAPPED, true, Collections.singletonList(huge.toString()));
        String clipped = report(entry).getJSONObject("entry").getJSONArray("evidence").getString(0);
        assertEquals(OfferReport.MAX_LABEL_CHARS + 1, clipped.length());
        assertTrue(clipped.endsWith("…"));
    }

    @Test
    public void aPaymentScreenLeavesNoLineAtAll() throws JSONException {
        assertTrue(OfferReport.redact(Arrays.asList("Card details", "CVV", "731", "$123.45")).isEmpty());
    }
}
