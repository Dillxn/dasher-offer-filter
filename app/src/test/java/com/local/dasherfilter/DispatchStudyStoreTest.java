package com.local.dasherfilter;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.os.Looper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

@RunWith(RobolectricTestRunner.class)
// The store's wall and elapsed clocks must follow the same simulated five-second observation.
@Config(sdk = {26, 35}, instrumentedPackages = {"com.local.dasherfilter"})
@LooperMode(LooperMode.Mode.PAUSED)
public class DispatchStudyStoreTest {
    Application app;
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication(); DispatchStudyStore.resetProcessForTest();
        // Leave the simulated epoch/last-save zero sentinel behind before exercising the 30-second save debounce.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1));
        FilterStore.save(app, DispatchStudyTest.rules(1000));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @After public void cleanup() {
        DispatchStudyStore.confirm(app, false); DispatchStudyStore.flush(); FeedbackOutbox.flush();
    }
    private void enable() { DispatchStudyStore.confirm(app, true); DispatchStudyStore.flush(); }
    private void waitScreen() { QualifyingWaitStore.screen(app, true, DasherScene.WAITING, null, false, false); }
    private void observe() {
        waitScreen(); ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        QualifyingWaitStore.screen(app, true, DasherScene.OFFER, DispatchStudyTest.offer(1200), false, true);
        DispatchStudyStore.flush();
    }
    private File file() { return new File(app.getFilesDir(), DispatchStudyStore.FILE); }
    @Test public void existingNoticeOrReportingConsentNeverCollectsWithoutNewConfirmation() {
        Feedback.setAfterDash(app, true); observe();
        assertFalse(file().exists()); assertEquals("", DispatchStudyStore.section(app));
        assertFalse(DispatchStudyStore.enabled(app));
    }
    @Test public void realObservationHookStartsColdAndDoesNotExportExistingWaitingHistory() throws Exception {
        app.getSharedPreferences("qualifying-wait", Context.MODE_PRIVATE).edit()
                .putString("numeric-history-v1", "[private older history sentinel]").commit();
        enable(); observe(); String section = DispatchStudyStore.section(app);
        assertTrue(section.contains(DispatchStudyPayload.PREFIX)); assertFalse(section.contains("sentinel"));
        assertEquals(-1, first(section).getLong("predictionMs")); assertEquals(0, first(section).getInt("readable"));
        assertEquals("QUALIFYING", first(section).getString("end"));
        assertEquals("NONE", first(section).getString("reason"));
        assertEquals(5000, first(section).getLong("observedMs"));
        assertTrue(file().exists());
    }
    @Test public void withdrawalWinsOverQueuedObservationsAndRemovesStoredRows() {
        enable(); observe();
        for (int i = 0; i < 100; i++) DispatchStudyStore.heartbeat(app, true);
        DispatchStudyStore.confirm(app, false); DispatchStudyStore.flush();
        assertFalse(file().exists()); assertEquals("", DispatchStudyStore.section(app));
        enable(); assertEquals("", DispatchStudyStore.section(app));
    }
    @Test public void restartRestoresOnlyCensoredRowsNeverATimerTrainingOrSuccessfulOutcome() throws Exception {
        enable(); waitScreen(); DispatchStudyStore.flush();
        DispatchStudyStore.resetProcessForTest(); ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
        String section = DispatchStudyStore.section(app);
        assertEquals("RESTART", first(section).getString("reason"));
        assertEquals("CENSORED", first(section).getString("end")); assertEquals(0, first(section).getLong("observedMs"));
        QualifyingWaitStore.screen(app, true, DasherScene.OFFER, DispatchStudyTest.offer(1500), false, true);
        DispatchStudyStore.flush(); assertFalse(DispatchStudyStore.section(app).contains("\"end\":\"QUALIFYING\""));
    }
    @Test public void clearHistoryRemovesStudyAndInvalidatesPreparedSectionWithoutTurningItsChoiceOff() {
        enable(); observe(); String prepared = DispatchStudyStore.section(app);
        QualifyingWaitStore.clear(app); DispatchStudyStore.flush();
        assertTrue(DispatchStudyStore.enabled(app)); assertFalse(file().exists());
        assertEquals("", DispatchStudyStore.section(app));
        assertFalse(DispatchStudyPayload.current(app, prepared).contains(DispatchStudyPayload.PREFIX));
    }
    @Test public void oversizedOrCorruptStorageFailsClosedWithoutReportingItsContents() throws Exception {
        enable();
        for (String invalid : new String[] {"PRIVATE broken JSON", "x".repeat(DispatchStudyStore.MAX_FILE_BYTES + 1)}) {
            Files.write(file().toPath(), invalid.getBytes(StandardCharsets.UTF_8));
            DispatchStudyStore.resetProcessForTest(); assertEquals("", DispatchStudyStore.section(app));
            assertFalse(file().exists());
        }
    }
    @Test public void automaticSectionSelectsOnlyItsDashWindow() {
        enable(); observe();
        assertFalse(DispatchStudyStore.section(app).isEmpty());
        assertEquals("", DispatchStudyStore.section(app,
                org.robolectric.shadows.ShadowSystem.currentTimeMillis() + 1000, Long.MAX_VALUE));
    }
    @Test public void attachedDiagnosticsActuallyIncludesTheSectionOnlyAfterSeparateStudyConsent() {
        assertFalse(String.join("", Feedback.diagnosticParts(app)).contains(DispatchStudyPayload.PREFIX));
        enable(); observe();
        assertTrue(String.join("", Feedback.diagnosticParts(app)).contains(DispatchStudyPayload.PREFIX));
        DispatchStudyStore.confirm(app, false); DispatchStudyStore.flush();
        assertFalse(String.join("", Feedback.diagnosticParts(app)).contains(DispatchStudyPayload.PREFIX));
    }
    @Test public void policyChangeWhileWaitingCensorsBeforeResumingWithNewPolicy() throws Exception {
        enable(); waitScreen(); DispatchStudyStore.flush();
        FilterStore.save(app, DispatchStudyTest.rules(2000)); Shadows.shadowOf(Looper.getMainLooper()).idle();
        DispatchStudyStore.flush();
        assertEquals("POLICY_CHANGED", first(DispatchStudyStore.section(app)).getString("reason"));
    }
    @Test public void realAutoAcceptSetterCensorsEvenWithoutAnotherScreenObservation() throws Exception {
        enable(); waitScreen(); DispatchStudyStore.flush();
        FilterStore.setAutoAcceptEnabled(app, true);
        Shadows.shadowOf(Looper.getMainLooper()).idle(); DispatchStudyStore.flush();
        assertEquals("POLICY_CHANGED", first(DispatchStudyStore.section(app)).getString("reason"));
        assertTrue(FilterStore.autoAcceptEnabled(app));
    }
    @Test public void queuedObservationKeepsItsAutoAcceptPolicyEvenIfItChangesBackBeforeProcessing() throws Exception {
        enable(); waitScreen(); DispatchStudyStore.flush();
        java.lang.reflect.Field field = DispatchStudyStore.class.getDeclaredField("WORK");
        field.setAccessible(true);
        java.util.concurrent.ExecutorService worker = (java.util.concurrent.ExecutorService) field.get(null);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        worker.execute(() -> {
            entered.countDown();
            try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("worker blocked"); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        try {
            // A background setter's main-looper listener has not run yet. Both observations must carry their
            // own policy; reading the latest preference on the study worker would see only the final false.
            Thread toggler = new Thread(() -> FilterStore.setAutoAcceptEnabled(app, true));
            toggler.start(); toggler.join();
            waitScreen();
            toggler = new Thread(() -> FilterStore.setAutoAcceptEnabled(app, false));
            toggler.start(); toggler.join();
            waitScreen();
        } finally { release.countDown(); }
        DispatchStudyStore.flush();
        String section = DispatchStudyStore.section(app);
        java.util.List<JSONObject> rows = new java.util.ArrayList<>();
        for (String line : section.split("\n")) if (line.startsWith(DispatchStudyPayload.PREFIX))
            rows.add(new JSONObject(line.substring(DispatchStudyPayload.PREFIX.length())));
        assertEquals(3, rows.size());
        assertEquals("POLICY_CHANGED", rows.get(0).getString("reason"));
        assertEquals("POLICY_CHANGED", rows.get(1).getString("reason"));
        assertTrue(rows.get(1).getInt("policyEpoch") > rows.get(0).getInt("policyEpoch"));
        assertTrue(rows.get(2).getInt("policyEpoch") > rows.get(1).getInt("policyEpoch"));
        assertEquals(0, rows.get(2).getInt("readable"));
        assertEquals(-1, rows.get(2).getLong("predictionMs"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static JSONObject first(String section) throws Exception {
        for (String line : section.split("\n")) if (line.startsWith(DispatchStudyPayload.PREFIX))
            return new JSONObject(line.substring(DispatchStudyPayload.PREFIX.length()));
        throw new AssertionError("No study row: " + section);
    }
}
