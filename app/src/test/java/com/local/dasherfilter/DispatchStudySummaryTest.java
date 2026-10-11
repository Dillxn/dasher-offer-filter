package com.local.dasherfilter;

import static org.junit.Assert.*;
import android.app.Application;
import android.os.Looper;
import java.time.Duration;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;

/** Actual summary construction must retain complete allowlisted study lines at its text-size boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, instrumentedPackages = {"com.local.dasherfilter"})
@LooperMode(LooperMode.Mode.PAUSED)
public class DispatchStudySummaryTest {
    private Application app;
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        DispatchStudyStore.resetProcessForTest();
        FilterStore.save(app, DispatchStudyTest.rules(1000));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        DispatchStudyStore.confirm(app, true); DispatchStudyStore.flush();
        QualifyingWaitStore.screen(app, true, DasherScene.WAITING, null, false, false);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        QualifyingWaitStore.screen(app, true, DasherScene.OFFER, DispatchStudyTest.offer(1200), false, true);
        DispatchStudyStore.flush();
    }
    @After public void cleanup() {
        DispatchStudyStore.confirm(app, false); DispatchStudyStore.flush(); FeedbackOutbox.flush();
    }
    @Test public void ordinarySummaryPreservesTheCanonicalStudySection() throws Exception {
        assertSection(new JSONObject(), false);
    }
    @Test public void cutToFitKeepsItsFirstStudyRowOnASeparateCompleteLine() throws Exception {
        // Distinct fixed type/frame error categories accumulate in the existing counts map without a key cap.
        // Their individual keys are bounded as production increment() bounds them; their total can fill a report.
        JSONObject errors = new JSONObject();
        for (int i = 0; i < 900; i++) errors.put("SyntheticError at SyntheticFrame.method" + i, 1);
        assertSection(new JSONObject().put("counts", new JSONObject().put("scanErrors", errors)), true);
    }
    private void assertSection(JSONObject model, boolean truncated) throws Exception {
        String section = DispatchStudyStore.section(app);
        assertFalse(section.isEmpty());
        String summary = DashSummary.build(app, 0, Long.MAX_VALUE - 60_000L, DashSummary.End.DASH_OVER, model);
        assertEquals(truncated, summary.contains("[cut to fit]"));
        assertTrue(summary.length() <= Feedback.MAX_SUMMARY_CHARS);
        String masked = FeedbackOutbox.remask(FeedbackOutbox.TEXT, true, summary);
        assertTrue("remasking must not discard the first study row", masked.contains(section));
        int rows = 0;
        for (String line : masked.split("\n")) if (line.startsWith(DispatchStudyPayload.PREFIX)) {
            DispatchStudy.Row row = DispatchStudy.Row.parse(new JSONObject(line.substring(DispatchStudyPayload.PREFIX.length())));
            assertEquals(DispatchStudy.End.QUALIFYING, row.end); assertEquals(5000, row.observedMs); rows++;
        }
        assertEquals(1, rows);
    }
}
