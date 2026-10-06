package com.local.dasherfilter;

import static org.junit.Assert.*;
import android.content.Context;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
public class QualifyingWaitStoreTest {
    private Context context;
    private FilterSettings rules;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        QualifyingWaitStore.wallClock = () -> 1_800_000_000_000L + android.os.SystemClock.elapsedRealtime();
        QualifyingWaitStore.flush();
        QualifyingWaitStore.clear(context);
        context.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(Consent.ACCEPTED_VERSION, Consent.VERSION).commit();
        rules = new FilterSettings(true, 1000, 0, 0, 0, 0, false, 0);
    }
    @After public void cleanup() { QualifyingWaitStore.flush(); QualifyingWaitStore.clear(context);
        QualifyingWaitStore.wallClock = System::currentTimeMillis; }
    private void tick(long millis) { org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(millis)); }

    @Test public void persistenceRoundTripPreservesUnknownItemsWithoutAnyText() {
        List<QualifyingWait.Sample> rows = new ArrayList<>();
        rows.add(new QualifyingWait.Sample(12345, 1000,
                new OfferSnapshot(1000, 3.0, 15, 2, null, null, null, true)));
        rows.add(new QualifyingWait.Sample(12346, 2000, null));
        String json = QualifyingWaitStore.encode(rows);
        List<QualifyingWait.Sample> decoded = QualifyingWaitStore.decode(json);
        assertEquals(2, decoded.size());
        assertTrue(decoded.get(0).arrival.itemCountApplicable);
        assertNull(decoded.get(0).arrival.items);
        assertNull(decoded.get(0).arrival.finalStopHotspotMiles);
        assertNull(decoded.get(1).arrival);
        assertFalse(json.contains("label"));
        assertFalse(json.contains("identity"));
    }
    @Test public void stoppedHiddenOrLockedObservationIsCensoredAndNeverResumed() {
        QualifyingWaitStore.screen(context, true, DasherScene.WAITING, null, false, false);
        tick(5_000);
        QualifyingWaitStore.heartbeat(context, true);
        QualifyingWaitStore.heartbeat(context, false);
        tick(60_000);
        QualifyingWaitStore.screen(context, true, DasherScene.OFFER,
                new OfferSnapshot(1000, 3.0, 15, 2), false, false);
        QualifyingWait.Estimate estimate = QualifyingWaitStore.estimate(context, rules);
        assertEquals(5_000, estimate.observedMs);
        assertEquals(0, estimate.readable);
    }
    @Test public void addOnCannotEnterStandaloneWaitingHistory() {
        QualifyingWaitStore.screen(context, true, DasherScene.WAITING, null, false, false);
        tick(5_000);
        QualifyingWaitStore.heartbeat(context, true);
        QualifyingWaitStore.screen(context, true, DasherScene.OFFER,
                new OfferSnapshot(1000, 3.0, 15, 2), true, false);
        assertEquals(0, QualifyingWaitStore.estimate(context, rules).readable);
    }
    @Test public void partialOfferLaterRecognizedAsAddOnDoesNotPolluteUnknownArrivalCount() {
        QualifyingWaitStore.screen(context, true, DasherScene.WAITING, null, false, false);
        tick(5_000);
        QualifyingWaitStore.screen(context, true, DasherScene.OFFER, null, false, false);
        tick(1_000);
        QualifyingWaitStore.screen(context, false, DasherScene.OFFER,
                new OfferSnapshot(1000, 3.0, 15, 2), true, false);
        QualifyingWait.Estimate estimate = QualifyingWaitStore.estimate(context, rules);
        assertEquals(0, estimate.readable);
        assertEquals(0, estimate.unreadable);
    }
    @Test public void persistedCensorSurvivesRestartButNotItsTimer() {
        QualifyingWaitStore.screen(context, true, DasherScene.WAITING, null, false, false);
        for (int i = 0; i < 7; i++) { tick(5_000); QualifyingWaitStore.heartbeat(context, true); }
        QualifyingWaitStore.stop(context);
        QualifyingWaitStore.flush();
        QualifyingWaitStore.forgetCache();
        tick(60_000);
        QualifyingWaitStore.screen(context, true, DasherScene.OFFER,
                new OfferSnapshot(1000, 3.0, 15, 2), false, false);
        QualifyingWait.Estimate estimate = QualifyingWaitStore.estimate(context, rules);
        assertEquals(35_000, estimate.observedMs);
        assertEquals(0, estimate.readable);
    }
    @Test public void clearWinsOverQueuedWritesAndClearAlsoStopsCoverage() {
        QualifyingWaitStore.screen(context, true, DasherScene.WAITING, null, false, false);
        tick(5_000);
        QualifyingWaitStore.screen(context, true, DasherScene.OFFER,
                new OfferSnapshot(1000, 3.0, 15, 2), false, false);
        QualifyingWaitStore.clear(context);
        QualifyingWaitStore.flush();
        QualifyingWaitStore.forgetCache();
        assertEquals(0, QualifyingWaitStore.estimate(context, rules).observedMs);
        assertEquals(0, QualifyingWaitStore.estimate(context, rules).readable);
    }
    @Test public void missingCurrentConsentCollectsNothingEvenIfCallerSaysEligible() {
        context.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        QualifyingWaitStore.screen(context, true, DasherScene.WAITING, null, false, false);
        tick(5_000);
        QualifyingWaitStore.heartbeat(context, true);
        QualifyingWaitStore.screen(context, true, DasherScene.OFFER,
                new OfferSnapshot(1000, 3.0, 15, 2), false, false);
        assertEquals(0, QualifyingWaitStore.estimate(context, rules).observedMs);
        assertEquals(0, QualifyingWaitStore.estimate(context, rules).readable);
    }
    @Test public void malformedNegativeOrOversizedHistoryDoesNotRestoreTiming() {
        assertTrue(QualifyingWaitStore.decode("oops").isEmpty());
        assertTrue(QualifyingWaitStore.decode("[{\"at\":1,\"wait\":-1}]").isEmpty());
        assertTrue(QualifyingWaitStore.decode("[{\"at\":1,\"wait\":999999999999}]").isEmpty());
    }
}
