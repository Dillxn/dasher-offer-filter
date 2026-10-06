package com.local.dasherfilter;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.SystemClock;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
public final class EarningsAdjustmentStoreTest {
    private Context context;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(Consent.PREFS, 0).edit().putInt(Consent.ACCEPTED_VERSION, Consent.VERSION).commit();
        FilterStore.save(context, new FilterSettings(true, 1000, 0, 0, 0, 0));
        QualifyingWaitStore.wallClock = () -> 1_800_000_000_000L + SystemClock.elapsedRealtime();
        QualifyingWaitStore.flush(); QualifyingWaitStore.clear(context);
    }
    @After public void cleanup() {
        QualifyingWaitStore.flush(); QualifyingWaitStore.clear(context);
        QualifyingWaitStore.wallClock = System::currentTimeMillis;
    }
    @Test public void scaleCasUsesOrdinarySaveAndPreservesEveryOtherRule() {
        FilterStore.RuleSnapshot before = FilterStore.snapshot(context);
        assertTrue(FilterStore.saveScaleIfUnchanged(context, before, 105, () -> true));
        assertEquals(AutoAccept.rulesKey(before.settings.withMinimumScalePercent(105)),
                AutoAccept.rulesKey(FilterStore.load(context)));
        assertFalse(FilterStore.saveScaleIfUnchanged(context, before, 104, () -> true));
    }
    @Test public void manualChangeBackAndOptInChangesInvalidateCas() {
        FilterStore.RuleSnapshot before = FilterStore.snapshot(context);
        FilterStore.save(context, before.settings.withMinimumScalePercent(110));
        FilterStore.save(context, before.settings);
        assertFalse(FilterStore.saveScaleIfUnchanged(context, before, 105, () -> true));
        before = FilterStore.snapshot(context); FilterStore.setAutoAcceptEnabled(context, true);
        assertFalse(FilterStore.saveScaleIfUnchanged(context, before, 105, () -> true));
    }
    @Test public void finalGuardAndStepLimitsFailClosed() {
        FilterStore.RuleSnapshot before = FilterStore.snapshot(context);
        assertFalse(FilterStore.saveScaleIfUnchanged(context, before, 105, () -> false));
        assertFalse(FilterStore.saveScaleIfUnchanged(context, before, 106, () -> true));
        assertFalse(FilterStore.saveScaleIfUnchanged(context, before, 0, () -> true));
        assertEquals(100, FilterStore.load(context).minimumScalePercent);
    }
    @Test public void corruptArrivalsStayUnknownRatherThanCoercingToValidModelEvidence() {
        for (String facts : new String[]{"null", "[]", "5", "\"offer\"",
                "{\"pay\":4294968296,\"miles\":2,\"minutes\":15,\"stops\":2}",
                "{\"pay\":1000.5,\"miles\":2,\"minutes\":15,\"stops\":2}",
                "{\"pay\":\"1000\",\"miles\":2,\"minutes\":15,\"stops\":2}",
                "{\"pay\":1000,\"miles\":\"2\",\"minutes\":15,\"stops\":2}",
                "{\"pay\":1000,\"miles\":2,\"minutes\":15,\"stops\":2,\"itemApplicable\":\"false\"}"}) {
            java.util.List<QualifyingWait.Sample> rows = QualifyingWaitStore.decode(
                    "[{\"at\":1000,\"wait\":100,\"arrival\":" + facts + "}]");
            assertEquals(facts, 1, rows.size());
            assertNotNull(facts, rows.get(0).arrival);
            assertNull(facts, rows.get(0).arrival.payCents);
        }
    }
    @Test public void malformedObservationTimesRejectTheCorruptHistory() {
        for (String at : new String[]{"1.5", "\"1000\"", "9223372036854775808", "true", "null"}) {
            assertTrue(at, QualifyingWaitStore.decode("[{\"at\":" + at + ",\"wait\":100}]").isEmpty());
        }
        assertTrue(QualifyingWaitStore.decode("[{\"at\":1000,\"wait\":\"100\"}]").isEmpty());
    }
    @Test public void coldSnapshotDoesNotRestoreWaitingOrMutatePersistence() {
        assertTrue(QualifyingWaitStore.snapshot(context).isEmpty());
        QualifyingWaitStore.Snapshot snapshot = QualifyingWaitStore.capture(context);
        assertFalse(snapshot.observingWaiting);
        assertFalse(QualifyingWaitStore.withCurrentWaiting(context, snapshot, () -> { fail(); return true; }));
    }
    @Test public void clearAndStopWinOverCapturedWaitingEvenAfterWaitingResumes() {
        QualifyingWaitStore.screen(context, true, DasherScene.WAITING, null, false, false);
        QualifyingWaitStore.Snapshot captured = QualifyingWaitStore.capture(context);
        assertTrue(captured.observingWaiting);
        QualifyingWaitStore.stop(context);
        QualifyingWaitStore.screen(context, true, DasherScene.WAITING, null, false, false);
        assertFalse(QualifyingWaitStore.withCurrentWaiting(context, captured, () -> { fail(); return true; }));
        captured = QualifyingWaitStore.capture(context);
        QualifyingWaitStore.clear(context);
        QualifyingWaitStore.screen(context, true, DasherScene.WAITING, null, false, false);
        assertFalse(QualifyingWaitStore.withCurrentWaiting(context, captured, () -> { fail(); return true; }));
    }
    @Test public void expiredCoverageAndRevokedConsentCannotCommit() {
        QualifyingWaitStore.screen(context, true, DasherScene.WAITING, null, false, false);
        QualifyingWaitStore.Snapshot captured = QualifyingWaitStore.capture(context);
        ShadowSystemClock.advanceBy(Duration.ofMillis(QualifyingWait.MAX_COVERAGE_GAP_MS + 1));
        assertFalse(QualifyingWaitStore.withCurrentWaiting(context, captured, () -> { fail(); return true; }));
        QualifyingWaitStore.screen(context, true, DasherScene.WAITING, null, false, false);
        captured = QualifyingWaitStore.capture(context);
        context.getSharedPreferences(Consent.PREFS, 0).edit().clear().commit();
        assertFalse(QualifyingWaitStore.withCurrentWaiting(context, captured, () -> { fail(); return true; }));
    }
}
