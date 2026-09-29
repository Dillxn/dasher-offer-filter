package com.local.dasherfilter;

import org.junit.Test;
import java.time.ZoneId;
import java.util.List;
import static org.junit.Assert.*;

/** Pure history model, serialization, sessions, today stats, what-if replay, decline budget (spec D1-D4, B7). */
public final class OfferHistoryTest {
    private static final long NOW = 1_700_000_000_000L;   // 2023-11-14 22:13:20 UTC
    private static final long DAY = 86_400_000L;
    private static OfferRule.Decision decision(OfferRule.Result r, OfferRule.Code c, long required, String reason) { return new OfferRule.Decision(r, required, reason, c, null, null); }
    private static OfferRecord rec(long id, long at, OfferRecord.Source src, Integer pay, Double miles, Integer min, Integer stops, OfferRule.Result r, OfferRule.Code c, int actions) {
        return new OfferRecord(id, at, src, pay, miles, min, stops, false, r, 0, c, "reason", actions, null);
    }

    @Test public void recordRoundTripsWithEscapedText() {
        OfferRecord r = new OfferRecord(42, NOW, OfferRecord.Source.NOTIFICATION, 790, 7.2, 21, 2, true, OfferRule.Result.DECLINE, 800,
                OfferRule.Code.AVOIDED_STORE, "avoided \\ store", OfferRecord.NOTIF_HIDDEN | OfferRecord.PASS_BELL, "Mr. Pollo\\Tab");
        OfferRecord back = OfferRecord.parse(r.serialize());
        assertNotNull(back); assertEquals(r.serialize(), back.serialize());
        assertEquals(Integer.valueOf(790), back.payCents); assertEquals(7.2, back.miles, 0.0); assertTrue(back.addOn); assertEquals("Mr. Pollo\\Tab", back.store);
        assertEquals("a\tb\nc\\d\re", OfferRecord.unescape(OfferRecord.escape("a\tb\nc\\d\re")));
        OfferRecord empty = new OfferRecord(1, NOW, OfferRecord.Source.SCREEN, null, null, null, null, false, OfferRule.Result.REVIEW, 0, OfferRule.Code.PAY_MISSING, null, 0, null);
        assertEquals(empty.serialize(), OfferRecord.parse(empty.serialize()).serialize());
    }
    @Test public void recordClipsAndStripsControlCharacters() {
        StringBuilder longText = new StringBuilder(); for (int i = 0; i < 300; i++) longText.append('x');
        OfferRecord r = new OfferRecord(1, NOW, null, -5, Double.NaN, -1, -1, false, null, -9, null, "line1\nline2\t" + longText, 1 << 20, longText.toString());
        assertTrue(r.reason.length() <= 160); assertFalse(r.reason.contains("\n")); assertTrue(r.store.length() <= 60);
        assertNull(r.payCents); assertNull(r.miles); assertEquals(0, r.actions); assertEquals(0L, r.requiredCents); assertEquals(OfferRule.Result.REVIEW, r.verdict);
    }
    @Test public void tolerantParsingSkipsBadLinesAndLaterLinesWin() {
        OfferRecord a = rec(1, NOW - 1000, OfferRecord.Source.SCREEN, 790, 7.2, 21, 2, OfferRule.Result.DECLINE, OfferRule.Code.FLOOR, 0);
        String text = a.serialize() + "\ngarbage\nv1\tx\n" + "v1\t2\t" + NOW + "\tZ\t\t\t\t\t0\tKEEP\t0\tMEETS\t0\t\t\n" +
                a.withAction(OfferRecord.DECLINE_REQUESTED).serialize() + "\r\n" + "v1\t3\t" + NOW + "\tS\t\t\t\t\t0\tKEEP\t0\tFUTURE_CODE\t0\t\tbad\\q\n" +
                "v1\t4\t" + NOW + "\tS\t\t\t\t\t0\tKEEP\t0\tFUTURE_CODE\t0\t\tok\n";
        OfferHistory h = OfferHistory.parse(text, NOW);
        assertEquals(2, h.size()); assertTrue(h.get(1).has(OfferRecord.DECLINE_REQUESTED)); assertEquals(OfferRule.Code.UNSPECIFIED, h.get(4).reasonCode);
        assertEquals(h.serialize(), OfferHistory.parse(h.serialize(), NOW).serialize());
    }
    @Test public void historyIsBoundedByCountAndAge() {
        OfferHistory h = new OfferHistory();
        for (int i = 0; i < 1100; i++) h.upsert(rec(h.nextId(NOW), NOW - 1100 + i, OfferRecord.Source.SCREEN, 100, null, null, null, OfferRule.Result.KEEP, OfferRule.Code.MEETS, 0), NOW);
        assertEquals(1000, h.size()); assertEquals(NOW - 1000, h.chronological().get(0).at);
        h.upsert(rec(1, NOW - 15 * DAY, OfferRecord.Source.SCREEN, 1, null, null, null, OfferRule.Result.KEEP, OfferRule.Code.MEETS, 0), NOW);
        assertNull(h.get(1));
        h.upsert(rec(2, NOW + 2 * DAY, OfferRecord.Source.SCREEN, 1, null, null, null, OfferRule.Result.KEEP, OfferRule.Code.MEETS, 0), NOW);
        assertNull(h.get(2));
        h.prune(NOW + 15 * DAY); assertEquals(0, h.size());
    }
    @Test public void upsertReplacesAndIdsIncrease() {
        OfferHistory h = new OfferHistory();
        long id = h.nextId(NOW); assertEquals(NOW, id); assertEquals(NOW + 1, h.nextId(NOW)); assertEquals(NOW + 2, h.nextId(NOW - 5000));
        OfferRecord r = OfferRecord.of(id, NOW, OfferRecord.Source.SCREEN, new OfferSnapshot(790, 7.2, 21, 2), false, decision(OfferRule.Result.DECLINE, OfferRule.Code.FLOOR, 2000, "flat minimum"), null);
        h.upsert(r, NOW); h.addActions(id, OfferRecord.DECLINE_REQUESTED, NOW); h.addActions(id, OfferRecord.CONFIRM_REQUESTED, NOW);
        OfferRecord later = h.get(id).withEvaluation(new OfferSnapshot(790, 7.2, 21, 2), false, decision(OfferRule.Result.DECLINE, OfferRule.Code.FLOOR, 2000, "flat minimum"), null);
        h.upsert(later, NOW);
        assertEquals(1, h.size()); assertTrue(h.get(id).has(OfferRecord.CONFIRM_REQUESTED)); assertTrue(h.get(id).has(OfferRecord.DECLINE_REQUESTED));
        assertNull(h.addActions(999, OfferRecord.PASS_BELL, NOW));
    }
    @Test public void outcomeWordingIsHonest() {
        OfferRecord base = rec(1, NOW, OfferRecord.Source.NOTIFICATION, 790, null, null, null, OfferRule.Result.DECLINE, OfferRule.Code.FLOOR, 0);
        assertEquals("Notification hidden — order NOT declined", base.withAction(OfferRecord.NOTIF_HIDDEN).outcome());
        assertEquals("Decline requested", base.withAction(OfferRecord.DECLINE_REQUESTED).outcome());
        assertEquals("Accept observed", base.withAction(OfferRecord.ACCEPT_OBSERVED).outcome());
        assertEquals("Auto-decline off — would have requested decline", base.withAction(OfferRecord.PAUSED_SHADOW).outcome());
        for (int flags = 0; flags <= OfferRecord.KNOWN_ACTIONS; flags++) {
            String o = base.withActions(flags).outcome();
            assertFalse(o, o.matches("(?s).*\\b(?:Declined|Earned|Saved)\\b.*"));
        }
    }
    @Test public void csvQuotesTextAndGuardsFormulas() {
        OfferHistory h = new OfferHistory();
        h.upsert(new OfferRecord(1, NOW, OfferRecord.Source.SCREEN, 790, 7.2, null, null, false, OfferRule.Result.DECLINE, 800, OfferRule.Code.AVOIDED_STORE, "avoided store: x", 1, "=HYPERLINK(\"x\")"), NOW);
        String csv = h.csv(ZoneId.of("UTC"));
        assertTrue(csv.startsWith("time,source,pay")); assertTrue(csv.contains("2023-11-14 22:13:20,screen,$7.90,7.2,,,no,DECLINE,$8.00,AVOIDED_STORE"));
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"x\"\")\"")); assertTrue(csv.contains("\"Decline requested\""));
    }

    // D2: screen sessions.
    @Test public void countdownTicksAndProgressiveRenderingKeepOneRecord() {
        OfferSession s = new OfferSession();
        OfferSession.Step a = s.observe(new OfferSnapshot(790, null, null, null), false, 10);
        OfferSession.Step b = s.observe(new OfferSnapshot(790, 7.2, 21, 2), false, 11);
        OfferSession.Step c = s.observe(new OfferSnapshot(790, 7.2, 21, 2), false, 12);
        OfferSession.Step fading = s.observe(new OfferSnapshot(790, null, null, null), false, 13);
        assertTrue(a.started); assertFalse(b.started); assertFalse(c.started); assertFalse(fading.started);
        assertEquals(10, b.id); assertEquals(10, fading.id); assertTrue(b.upgrade); assertTrue(c.upgrade); assertFalse("a fading partial frame never overwrites the verdict", fading.upgrade);
    }
    @Test public void differentOfferOrEndStartsANewRecord() {
        OfferSession s = new OfferSession();
        s.observe(new OfferSnapshot(790, 7.2, 21, 2), false, 10);
        s.observe(new OfferSnapshot(790, null, null, null), false, 11);
        assertEquals("a partial frame cannot bridge to a different offer", 12, s.observe(new OfferSnapshot(790, 3.1, null, null), false, 12).id);
        assertEquals(13, s.observe(new OfferSnapshot(790, 3.1, null, null), true, 13).id);
        s.end(); assertFalse(s.active());
        assertTrue(s.observe(new OfferSnapshot(790, 3.1, null, null), true, 14).started);
    }

    // D3: today stats with an injectable zone.
    @Test public void todayStatsCountFromLocalMidnight() {
        ZoneId la = ZoneId.of("America/Los_Angeles");
        long midnight = HistoryStats.startOfDay(NOW, la);
        assertEquals(1_699_948_800_000L, midnight);   // 2023-11-14 00:00 PST
        List<OfferRecord> records = java.util.Arrays.asList(
                rec(1, midnight - 1, OfferRecord.Source.SCREEN, 100, 1.0, 10, 2, OfferRule.Result.KEEP, OfferRule.Code.MEETS, 0),
                rec(2, midnight, OfferRecord.Source.SCREEN, 1000, 4.0, 20, 2, OfferRule.Result.KEEP, OfferRule.Code.MEETS, OfferRecord.ACCEPT_OBSERVED),
                rec(3, midnight + 10, OfferRecord.Source.SCREEN, 350, 6.0, null, 3, OfferRule.Result.DECLINE, OfferRule.Code.FLOOR, OfferRecord.DECLINE_REQUESTED | OfferRecord.CONFIRM_REQUESTED),
                rec(4, midnight + 20, OfferRecord.Source.NOTIFICATION, 1000, null, null, null, OfferRule.Result.DECLINE, OfferRule.Code.FLOOR, OfferRecord.NOTIF_HIDDEN),
                rec(5, midnight + 30, OfferRecord.Source.NOTIFICATION, null, null, null, null, OfferRule.Result.REVIEW, OfferRule.Code.PAY_MISSING, OfferRecord.REVIEW_CARD),
                rec(6, NOW + 10, OfferRecord.Source.SCREEN, 100, 1.0, 10, 2, OfferRule.Result.KEEP, OfferRule.Code.MEETS, 0));
        HistoryStats t = HistoryStats.today(records, NOW, la);
        assertEquals(4, t.total); assertEquals(2, t.screenOffers); assertEquals(2, t.notificationOffers); assertEquals(1, t.declineRequests);
        assertEquals(1, t.passed); assertEquals(1, t.needsReview); assertEquals(1, t.hiddenOnly); assertEquals(1, t.acceptObserved); assertEquals(1000, t.acceptObservedPayCents);
        assertEquals(350, t.declineRequestedPayCents); assertEquals(Long.valueOf(135), t.avgCentsPerMile);   // (1000 + 350) / (4 + 6)
        List<String> insights = t.insights(new FilterSettings(true, 700, 0, 0, 0, 0).withPerHourCents(2000));
        assertTrue(insights.toString(), insights.contains("Decline requested on $3.50 of offers"));
        assertTrue(insights.toString(), insights.contains("minutes not shown on 3 of 4 offers"));
        assertTrue(insights.toString(), insights.contains("pay not shown on 1 of 4 offers"));
        assertTrue(insights.toString(), insights.contains("1 notification hidden only — those orders were NOT declined"));
        assertFalse(insights.toString(), insights.toString().contains("stops not shown"));
    }

    // D4: what-if replay.
    @Test public void whatIfReplaysStandaloneFactsWithProductionRules() {
        List<OfferRecord> records = java.util.Arrays.asList(
                rec(1, NOW - 3000, OfferRecord.Source.SCREEN, 900, 4.0, 20, 2, OfferRule.Result.KEEP, OfferRule.Code.MEETS, 0),
                rec(2, NOW - 2000, OfferRecord.Source.SCREEN, 600, 4.0, 20, 2, OfferRule.Result.DECLINE, OfferRule.Code.FLOOR, OfferRecord.DECLINE_REQUESTED),
                rec(3, NOW - 1000, OfferRecord.Source.NOTIFICATION, null, 3.0, null, null, OfferRule.Result.REVIEW, OfferRule.Code.PAY_MISSING, 0),
                new OfferRecord(4, NOW - 900, OfferRecord.Source.SCREEN, 300, 1.0, null, null, true, OfferRule.Result.KEEP, 0, OfferRule.Code.MEETS, "", 0, null),
                rec(5, NOW - 800, OfferRecord.Source.SCREEN, null, 3.0, null, null, OfferRule.Result.REVIEW, OfferRule.Code.HOURLY_MODE, 0),
                new OfferRecord(6, NOW - 700, OfferRecord.Source.NOTIFICATION, 5000, 3.0, null, null, false, OfferRule.Result.KEEP, 0, OfferRule.Code.MEETS, "", 0, "Chick-fil-A"),
                rec(7, NOW - 8 * DAY, OfferRecord.Source.SCREEN, 100, 1.0, 1, 1, OfferRule.Result.KEEP, OfferRule.Code.MEETS, 0));
        FilterSettings edited = new FilterSettings(true, 1000, 0, 0, 0, 0).withAvoidStores(java.util.Arrays.asList("Chick-fil-A")).withMaxDeclinesPerHour(1);
        WhatIfReplay w = WhatIfReplay.run(records, edited, NOW - 7 * DAY, NOW + 1);
        assertEquals(6, w.considered); assertEquals(4, w.replayed); assertEquals(2, w.notReplayable); assertEquals(1, w.addOnsSkipped);
        assertEquals(0, w.wouldPass); assertEquals(3, w.wouldDecline); assertEquals(1, w.wouldReview);
        assertEquals(2, w.recordedPass); assertEquals(1, w.recordedDecline); assertEquals(1, w.recordedReview); assertEquals(2, w.changed);
        assertEquals("Against your last 7 days: 0 would pass, 3 would be declined, 1 need review (recorded 2 · 1 · 1); 1 add-on not replayable; 1 Earn by Time/unreadable not replayable.", w.summary("Against your last 7 days"));
        assertEquals("Today: no offers recorded yet.", WhatIfReplay.run(null, edited, 0, 1).summary("Today"));
    }

    // B7: decline budget.
    @Test public void declineBudgetWindowDedupeAndHistorySeed() {
        DeclineBudget b = new DeclineBudget();
        b.record("a", 0); b.record("a", 1000); b.record(null, 2000); b.record(null, 3000);
        assertEquals(3, b.count(3000)); assertTrue(b.exhausted(3, 3000, null)); assertFalse(b.exhausted(3, 3000, "a")); assertFalse(b.exhausted(0, 3000, null));
        assertEquals(0, b.count(3_600_000 + 3000));
        assertEquals("a backwards clock step keeps recent requests counted", 3, b.count(-1000));
        List<OfferRecord> records = java.util.Arrays.asList(
                rec(1, NOW - 10, OfferRecord.Source.SCREEN, 1, null, null, null, OfferRule.Result.DECLINE, OfferRule.Code.FLOOR, OfferRecord.DECLINE_REQUESTED),
                rec(2, NOW - 20, OfferRecord.Source.NOTIFICATION, 1, null, null, null, OfferRule.Result.DECLINE, OfferRule.Code.FLOOR, OfferRecord.NOTIF_HIDDEN),
                rec(3, NOW - 30, OfferRecord.Source.NOTIFICATION, 1, null, null, null, OfferRule.Result.DECLINE, OfferRule.Code.FLOOR, OfferRecord.NOTIF_DECLINE_SENT),
                rec(4, NOW - 40, OfferRecord.Source.SCREEN, 1, null, null, null, OfferRule.Result.DECLINE, OfferRule.Code.FLOOR, OfferRecord.PAUSED_SHADOW),
                rec(5, NOW - 3_600_001, OfferRecord.Source.SCREEN, 1, null, null, null, OfferRule.Result.DECLINE, OfferRule.Code.FLOOR, OfferRecord.DECLINE_REQUESTED));
        assertEquals(3, DeclineBudget.fromHistory(records, NOW).count(NOW));
    }
}
