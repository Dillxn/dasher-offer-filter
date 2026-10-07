package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
 * masked twice, the current rules and Autopilot's state (exactly the spec's keys, numbers and fixed words only), the
 * app's and Android's versions and the minute it was decided; never its outcome steps, the exact time or anything
 * typed. The masking cases come from the retired GitHub problem reports.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class OfferReportTest {
    private static final FilterSettings RULES = FilterSettings.of(true, 1200, 150, 60, 4);
    /** 2026-10-06 21:02:37.123 UTC. */
    private static final long AT = 1_791_320_557_123L;
    private static final long MIN = 60_000L;
    /** The rules object's keys (finalSpec.privacyConsent, REPORTS), and nothing else. */
    private static final List<String> KEYS = Arrays.asList("enabled", "flatCents", "perMileCents", "perMinuteCents",
            "maxStops", "autopilot", "autopilotGoal", "barPercent", "autopilotMode", "recovering", "extra", "arPercent",
            "arSource", "arAgeMinutes", "lastChange", "context");
    /** The worked example's 20 offers, newest first: pay in cents, miles, minutes; two stops each. */
    private static final int[] PAY = {1625, 975, 700, 1350, 575, 800, 2250, 600, 1100, 350, 925, 1490, 400, 1050,
            725, 1700, 875, 500, 1225, 650};
    private static final double[] MILES = {6.2, 11.8, 2.2, 7.0, 6.6, 3.0, 12.6, 7.9, 5.1, 4.8, 6.3, 9.9, 2.6, 4.4,
            5.5, 8.8, 7.4, 3.2, 6.0, 4.1};
    private static final int[] MINUTES = {26, 41, 14, 28, 27, 16, 44, 29, 23, 19, 25, 38, 15, 20, 24, 33, 31, 18, 26,
            22};

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

    // ---- The rules object: the three minimums, max stops and Autopilot's state ----

    private static Set<String> keys(JSONObject json) {
        Set<String> keys = new TreeSet<>();
        for (Iterator<String> names = json.keys(); names.hasNext(); ) keys.add(names.next());
        return keys;
    }

    private static JSONObject rules(AutopilotText.Status autopilot) throws JSONException {
        return OfferReport.json(OfferReport.Problem.WRONG_DECLINE, "0.5.0", 80, "Android 15 (API 35)", autopilot,
                unreadable("Guaranteed pay")).getJSONObject("rules");
    }

    @Test
    public void theRulesAreTheThreeMinimumsMaxStopsAndAutopilotUnderExactlyTheSpecsKeys() throws JSONException {
        assertEquals(KEYS, OfferReport.RULES_KEYS);
        String text = OfferReport.text(OfferReport.Problem.MISREAD, "0.5.0", 80, "Android 15 (API 35)", RULES,
                unreadable("Guaranteed pay"));
        JSONObject rules = new JSONObject(text).getJSONObject("rules");
        assertEquals("exactly these keys", new TreeSet<>(KEYS), keys(rules));
        assertTrue(rules.getBoolean("enabled"));
        assertEquals(1200, rules.getInt("flatCents"));
        assertEquals(150, rules.getInt("perMileCents"));
        assertEquals("cents per minute as stored ($36.00 per hour)", 60, rules.getInt("perMinuteCents"));
        assertEquals(4, rules.getInt("maxStops"));
        // Rules alone: Autopilot as they say (off, the default goal, exactly the minimums), nothing worked out or read.
        assertFalse(rules.getBoolean("autopilot"));
        assertEquals(70, rules.getInt("autopilotGoal"));
        assertEquals(100, rules.getInt("barPercent"));
        assertTrue(rules.isNull("autopilotMode"));
        assertFalse(rules.getBoolean("recovering"));
        assertEquals(0, rules.getInt("extra"));
        assertEquals(-1, rules.getInt("arPercent"));
        assertEquals("UNKNOWN", rules.getString("arSource"));
        assertEquals(-1, rules.getInt("arAgeMinutes"));
        assertTrue(rules.isNull("lastChange"));
        assertEquals("current rules when report was built; historical baselines are not reconstructed",
                rules.getString("context"));
        // No retired rule and no learned minimum, under any name, anywhere in the report.
        for (String retired : new String[] {"perStop", "perItem", "hotspot", "rising", "scoreByArea", "lastAccepted",
                "minimumScale", "declinedByHand", "bestAccepted", "learned", "adaptive"}) {
            assertFalse(retired, text.contains(retired));
        }
    }

    @Test
    public void autopilotsStateGoesAsNumbersAndFixedNamesWithNoTextFingerprintOrTimeOfDay() throws JSONException {
        FilterSettings rules = new FilterSettings(true, 400, 100, 25, 0, true, 50, 82);
        // No plan yet for these rules: recovering and the stall correction as last stored; Dasher's reading as shown.
        AutopilotText.Status status = new AutopilotText.Status(rules, true, null,
                new AutopilotStore.Reading(55, AT - 12 * MIN - 20_000, "4242|9.9|99|2"),
                new AutopilotStore.Change(AT - 4 * MIN - 20_000, 100, 82, "RECOVERY"), true, 2, AT);
        JSONObject json = rules(status);
        assertEquals(new TreeSet<>(KEYS), keys(json));
        assertEquals(400, json.getInt("flatCents"));
        assertEquals(25, json.getInt("perMinuteCents"));
        assertTrue(json.getBoolean("autopilot"));
        assertEquals(50, json.getInt("autopilotGoal"));
        assertEquals(82, json.getInt("barPercent"));
        assertTrue("no plan for these rules yet", json.isNull("autopilotMode"));
        assertTrue(json.getBoolean("recovering"));
        assertEquals(2, json.getInt("extra"));
        assertEquals(55, json.getInt("arPercent"));
        assertEquals("DASHER", json.getString("arSource"));
        assertEquals(12, json.getInt("arAgeMinutes"));
        JSONObject change = json.getJSONObject("lastChange");
        assertEquals(new TreeSet<>(Arrays.asList("from", "to", "reason", "minutesAgo")), keys(change));
        assertEquals(100, change.getInt("from"));
        assertEquals(82, change.getInt("to"));
        assertEquals("a fixed name, never words", "RECOVERY", change.getString("reason"));
        assertEquals(4, change.getInt("minutesAgo"));
        String text = json.toString();
        assertFalse("the reading's offer stays on the phone", text.contains("4242"));
        assertFalse("no clock time", Pattern.compile("\\d{12,}").matcher(text).find());

        // Pay first, and no reading at all: the rate is unknown, with no age.
        JSONObject payFirst = rules(new AutopilotText.Status(new FilterSettings(true, 400, 100, 25, 3, true, 0, 119),
                true, null, null, null, AT));
        assertEquals(0, payFirst.getInt("autopilotGoal"));
        assertEquals(119, payFirst.getInt("barPercent"));
        assertEquals(3, payFirst.getInt("maxStops"));
        assertEquals(-1, payFirst.getInt("arPercent"));
        assertEquals("UNKNOWN", payFirst.getString("arSource"));
        assertEquals(-1, payFirst.getInt("arAgeMinutes"));
    }

    @Test
    public void theAcceptanceRateIsTheOneAutopilotCountsWithToTheHundredth() throws JSONException {
        // The worked example at 400 / 100 / 25 with Autopilot on (goal 70%), and Dasher's 55% twelve minutes ago.
        FilterSettings rules = new FilterSettings(true, 400, 100, 25, 0, true, 70, 100);
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < PAY.length; i++) {
            OfferSnapshot facts = new OfferSnapshot(PAY[i], MILES[i], MINUTES[i], 2);
            boolean declined = OfferRule.evaluate(facts, rules).result == OfferRule.Result.DECLINE;
            lines.add(new Autopilot.OfferRecord(facts, AT - (2 + 3L * i) * MIN, false, false, false, declined, false));
        }
        Autopilot.Plan plan = Autopilot.plan(new Autopilot.Inputs(rules, lines, null,
                new Autopilot.Reading(55, AT - 12 * MIN), null, AT, 0));
        JSONObject json = rules(new AutopilotText.Status(rules, true, plan,
                new AutopilotStore.Reading(55, AT - 12 * MIN, ""), null, AT));
        assertEquals("RECOVERY", json.getString("autopilotMode"));
        assertTrue(json.getBoolean("recovering"));
        assertEquals(0, json.getInt("extra"));
        // Four offers came after Dasher's 55% and none was accepted: 5,500 − 4 × 55 = 5,280 hundredths, exactly.
        assertEquals(52.8, json.getDouble("arPercent"), 0);
        assertEquals("DASHER", json.getString("arSource"));
        assertEquals("when Dasher showed it", 12, json.getInt("arAgeMinutes"));
        assertTrue(json.isNull("lastChange"));
    }

    @Test
    public void anEstimatedRateCarriesNoAgeOfAReadingItDidNotUse() throws JSONException {
        // Dasher's 55% two and a half hours ago, then 120 offers, each accepted or declined: DoorDash's window has
        // turned over since that reading, so Autopilot counts with its own estimate, though the reading is still kept.
        FilterSettings rules = new FilterSettings(true, 400, 100, 25, 0, true, 70, 100);
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < 6 * PAY.length; i++) {
            int k = i % PAY.length;
            OfferSnapshot facts = new OfferSnapshot(PAY[k], MILES[k], MINUTES[k], 2);
            boolean declined = OfferRule.evaluate(facts, rules).result == OfferRule.Result.DECLINE;
            lines.add(new Autopilot.OfferRecord(facts, AT - (1 + i) * MIN, false, false, !declined, declined, false));
        }
        long readAt = AT - 150 * MIN;
        Autopilot.Plan plan = Autopilot.plan(new Autopilot.Inputs(rules, lines, null, new Autopilot.Reading(55, readAt),
                null, AT, 0));
        assertEquals(Autopilot.ArSource.ESTIMATE, plan.arSource);
        JSONObject json = rules(new AutopilotText.Status(rules, true, plan,
                new AutopilotStore.Reading(55, readAt, ""), null, AT));
        assertEquals("ESTIMATE", json.getString("arSource"));
        assertEquals(plan.arHundredths / 100.0, json.getDouble("arPercent"), 0);
        assertEquals("the estimate owes nothing to Dasher's reading, so no age of it", -1,
                json.getInt("arAgeMinutes"));

        // No rate at all, a reading or not: no age either.
        JSONObject unknown = rules(new AutopilotText.Status(rules, true, null, null, null, AT));
        assertEquals("UNKNOWN", unknown.getString("arSource"));
        assertEquals(-1, unknown.getInt("arAgeMinutes"));
    }
}
