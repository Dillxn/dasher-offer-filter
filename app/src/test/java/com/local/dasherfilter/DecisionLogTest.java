package com.local.dasherfilter;

import android.app.Application;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The on-device decision history: merging, bounds, persistence, privacy filtering and the report text. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class DecisionLogTest {
    private Application app;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        DecisionLog.forgetCache();
    }

    private static DecisionLog.Entry entry(long at, int pay, OfferRule.Result result, DecisionLog.Action action) {
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, new OfferSnapshot(pay, 7.2, 21, 2), 1080,
                result, "dollars per mile", action, true, Arrays.asList("$7.90", "2 stops (7.2 mi) • 21 min"));
    }

    @Test
    public void laterActionOnTheSameOfferUpgradesItsEntry() {
        DecisionLog.record(app, entry(1000, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));
        DecisionLog.record(app, entry(1200, 790, OfferRule.Result.DECLINE, DecisionLog.Action.CONFIRMATION_TAPPED));
        // Repeated screen reads of the same offer with a lighter action change nothing.
        DecisionLog.record(app, entry(1400, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));

        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals(1, recent.size());
        assertEquals(DecisionLog.Action.CONFIRMATION_TAPPED, recent.get(0).action);
        assertEquals(1000, recent.get(0).at);
    }

    @Test
    public void differentOffersAndLaterRepeatsGetTheirOwnEntries() {
        DecisionLog.record(app, entry(1000, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));
        DecisionLog.record(app, entry(2000, 2500, OfferRule.Result.KEEP, DecisionLog.Action.PASSES));
        // The same facts again, well after the merge window, are a new offer.
        DecisionLog.record(app, entry(500_000, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));

        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals(3, recent.size());
        assertEquals(500_000, recent.get(0).at);
        assertEquals(OfferRule.Result.KEEP, recent.get(1).result);
    }

    @Test
    public void historyIsBoundedToTheNewestEntries() {
        for (int i = 0; i < DecisionLog.MAX_ENTRIES + 25; i++) {
            DecisionLog.record(app, entry(i * 1_000_000L, 100 + i, OfferRule.Result.KEEP, DecisionLog.Action.PASSES));
        }
        List<DecisionLog.Entry> recent = DecisionLog.recent(app, Integer.MAX_VALUE);
        assertEquals(DecisionLog.MAX_ENTRIES, recent.size());
        assertEquals(Integer.valueOf(100 + DecisionLog.MAX_ENTRIES + 24), recent.get(0).facts.payCents);
    }

    @Test
    public void historySurvivesAProcessRestart() {
        DecisionLog.record(app, entry(1000, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));
        DecisionLog.record(app, new DecisionLog.Entry(900_000, DecisionLog.Source.NOTIFICATION, true,
                new OfferSnapshot(null, null, null, null), 0, OfferRule.Result.REVIEW, "pay not found",
                DecisionLog.Action.SILENT_CARD, false, Collections.emptyList()));
        DecisionLog.flush();
        DecisionLog.forgetCache();

        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals(2, recent.size());
        DecisionLog.Entry review = recent.get(0);
        assertEquals(DecisionLog.Source.NOTIFICATION, review.source);
        assertTrue(review.addOn);
        assertFalse(review.autoDecline);
        assertNull(review.facts.payCents);
        DecisionLog.Entry declined = recent.get(1);
        assertEquals(Integer.valueOf(790), declined.facts.payCents);
        assertEquals(7.2, declined.facts.miles, 0.001);
        assertEquals(1080, declined.requiredCents);
        assertEquals(Arrays.asList("$7.90", "2 stops (7.2 mi) • 21 min"), declined.evidence);
    }

    @Test
    public void clearedHistoryStaysClearedAfterRestart() {
        DecisionLog.record(app, entry(1000, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));
        DecisionLog.flush();
        DecisionLog.clear(app);
        DecisionLog.flush();
        DecisionLog.forgetCache();
        assertTrue(DecisionLog.recent(app, 10).isEmpty());
    }

    @Test
    public void evidenceKeepsFiguresAndPayLabelsButNotNamesOrAddresses() {
        List<String> kept = DecisionLog.evidence(Arrays.asList(
                "$7.90", "Guaranteed (incl. tips)", "2 stops (7.2 mi) • 21 min", "Chick-fil-A",
                "123 Main St, Apt 4", "Jane D.", "Deliver by 9:45 PM", "+$2.00 Peak Pay", "Accept",
                "Call +1 555 123 4567", "Open 24 hrs"));
        assertEquals(Arrays.asList("$7.90", "Guaranteed (incl. tips)", "2 stops (7.2 mi) • 21 min",
                "+$2.00 Peak Pay"), kept);
    }

    @Test
    public void reportListsEachDecisionWithWhatWasRead() {
        DecisionLog.record(app, entry(1000, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));
        String report = DecisionLog.report(app, 10);
        assertTrue(report, report.contains(
                "| screen | DECLINE | pay $7.90 | needed $10.80 | 7.2 mi · 21 min · 2 stops"
                        + " | dollars per mile | Decline tapped"));
        assertTrue(report, report.contains("read: [$7.90, 2 stops (7.2 mi) • 21 min]"));
    }

    @Test
    public void diagnosticsReportStaysWithinIntentLimits() {
        List<String> longLines = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) longLines.add("$" + i + " " + String.join("", Collections.nCopies(120, "x")));
        for (int i = 0; i < DecisionLog.MAX_ENTRIES; i++) {
            DecisionLog.record(app, new DecisionLog.Entry(i * 1_000_000L, DecisionLog.Source.SCREEN, false,
                    new OfferSnapshot(100 + i, 7.2, 21, 2), 1080, OfferRule.Result.DECLINE, "dollars per mile",
                    DecisionLog.Action.DECLINE_TAPPED, true, longLines));
        }
        DiagnosticLog.setEnabled(app, true);
        for (int i = 0; i < 400; i++) DiagnosticLog.log(app, "screen", String.join("", Collections.nCopies(400, "y")));
        String report = DiagnosticLog.report(app);
        assertTrue(String.valueOf(report.length()), report.length() <= 60_000 + 40);
        assertTrue(report.contains("== Decision history"));
    }

    @Test
    public void corruptHistoryFileStartsFresh() throws Exception {
        java.io.File file = new java.io.File(app.getFilesDir(), "decision-log.json");
        try (java.io.FileWriter out = new java.io.FileWriter(file)) {
            out.write("{not json");
        }
        DecisionLog.forgetCache();
        assertTrue(DecisionLog.recent(app, 10).isEmpty());
        DecisionLog.record(app, entry(1000, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));
        assertEquals(1, DecisionLog.recent(app, 10).size());
    }
}
