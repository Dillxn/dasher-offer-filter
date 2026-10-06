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
@Config(sdk = {26, 35})
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
        rules = FilterSettings.of(true, 1000, 0, 0, 0);
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
    @Test public void snapshotIsWhatTheEstimateSeesIncludingTheOpenWait() {
        assertTrue(QualifyingWaitStore.snapshot(context).isEmpty());
        QualifyingWaitStore.screen(context, true, DasherScene.WAITING, null, false, false);
        for (int i = 0; i < 4; i++) { tick(5_000); QualifyingWaitStore.heartbeat(context, true); }
        List<QualifyingWait.Sample> open = QualifyingWaitStore.snapshot(context);
        assertEquals("the wait in progress, right-censored", 1, open.size());
        assertNull(open.get(0).arrival);
        assertEquals(20_000, open.get(0).observedMs);

        QualifyingWaitStore.screen(context, true, DasherScene.OFFER,
                new OfferSnapshot(1200, 3.0, 15, 2), false, false);
        List<QualifyingWait.Sample> rows = QualifyingWaitStore.snapshot(context);
        assertEquals(1, rows.size());
        assertEquals(Integer.valueOf(1200), rows.get(0).arrival.payCents);
        assertEquals(20_000, rows.get(0).observedMs);
        long exposure = 0;
        for (QualifyingWait.Sample row : rows) exposure += row.observedMs;
        assertEquals(QualifyingWaitStore.estimate(context, rules).observedMs, exposure);
        try {
            rows.add(new QualifyingWait.Sample(1, 1, null));
            fail("the snapshot is read-only");
        } catch (UnsupportedOperationException expected) {
            // A copy: nothing a caller does reaches the store.
        }
        // A restart keeps the numbers, never the timer.
        QualifyingWaitStore.stop(context);
        QualifyingWaitStore.flush();
        QualifyingWaitStore.forgetCache();
        assertEquals(1, QualifyingWaitStore.snapshot(context).size());
        assertEquals(Integer.valueOf(1200), QualifyingWaitStore.snapshot(context).get(0).arrival.payCents);
    }

    @Test public void theRetiredHotspotDistanceIsNotWrittenAndOlderRowsStillDecode() {
        @SuppressWarnings("deprecation")
        OfferSnapshot withDistance = new OfferSnapshot(1000, 3.0, 15, 2).withFinalStopHotspotMiles(1.5);
        List<QualifyingWait.Sample> rows = new ArrayList<>();
        rows.add(new QualifyingWait.Sample(12345, 1000, withDistance));
        String json = QualifyingWaitStore.encode(rows);
        assertFalse(json, json.contains("hotspotMiles"));
        assertTrue(json, json.contains("\"pay\":1000"));

        String older = "[{\"at\":12345,\"wait\":1000,\"arrival\":{\"pay\":1000,\"miles\":3,\"minutes\":15,"
                + "\"stops\":2,\"items\":null,\"itemApplicable\":false,\"hotspotMiles\":1.5}},"
                + "{\"at\":12346,\"wait\":2000}]";
        List<QualifyingWait.Sample> decoded = QualifyingWaitStore.decode(older);
        assertEquals(2, decoded.size());
        assertEquals(Integer.valueOf(1000), decoded.get(0).arrival.payCents);
        assertEquals(3.0, decoded.get(0).arrival.miles, 0);
        assertEquals(Integer.valueOf(15), decoded.get(0).arrival.minutes);
        assertEquals(Integer.valueOf(2), decoded.get(0).arrival.stops);
        assertNull(decoded.get(0).arrival.finalStopHotspotMiles);
        assertNull(decoded.get(1).arrival);
        assertFalse(QualifyingWaitStore.encode(decoded).contains("hotspotMiles"));
    }

    @Test public void malformedNegativeOrOversizedHistoryDoesNotRestoreTiming() {
        assertTrue(QualifyingWaitStore.decode("oops").isEmpty());
        assertTrue(QualifyingWaitStore.decode("[{\"at\":1,\"wait\":-1}]").isEmpty());
        assertTrue(QualifyingWaitStore.decode("[{\"at\":1,\"wait\":999999999999}]").isEmpty());
    }
}
