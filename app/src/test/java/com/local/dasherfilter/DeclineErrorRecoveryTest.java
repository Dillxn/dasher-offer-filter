package com.local.dasherfilter;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Request-bound timing only; the service must independently establish a safe Dasher surface before Back. */
public final class DeclineErrorRecoveryTest {
    @Test public void anUnknownOrRefusedRequestCannotSupplyErrorAuthority() {
        DeclineErrorRecovery recovery = new DeclineErrorRecovery();
        // A refused click is never reported through requested(). Neither it nor an unknown event can arm Back.
        assertFalse(recovery.error(new Object(), 1_100, 1_100));
        assertFalse(recovery.error(null, 1_100, 1_100));
        assertFalse(recovery.pending());
        assertFalse(recovery.blankReady(1_100));
        assertFalse(recovery.blankReady(2_000));

        recovery.requested(null, 1_000, 1);
        assertFalse(recovery.error(null, 1_100, 1_100));
        assertFalse(recovery.pending());
    }

    @Test public void anErrorBelongsToTheExactRequestIdentity() {
        DeclineErrorRecovery recovery = new DeclineErrorRecovery();
        Object request = new String("request");
        recovery.requested(request, 1_000, 1);
        assertFalse("equal content is not the same request", recovery.error(new String("request"), 1_100, 1_100));
        assertFalse(recovery.pending());
        assertTrue(recovery.error(request, 1_100, 1_100));

        Object retry = new Object();
        recovery.requested(retry, 2_000, 2);
        assertFalse("the next request discards earlier evidence", recovery.pending());
        assertFalse(recovery.error(request, 2_100, 2_100));
        assertTrue(recovery.error(retry, 2_100, 2_100));
    }

    @Test public void eventTimeMustFollowItsRequestAndNeverBeInTheFuture() {
        DeclineErrorRecovery recovery = new DeclineErrorRecovery();
        Object request = new Object();
        recovery.requested(request, 1_000, 1);
        assertFalse(recovery.error(request, 999, 1_100));
        assertFalse(recovery.error(request, 1_101, 1_100));
        assertFalse(recovery.pending());
        assertTrue("an event at the request timestamp is allowed", recovery.error(request, 1_000, 1_100));
    }

    @Test public void aFreshErrorAfterAnEightSecondNetworkHangStillQualifies() {
        DeclineErrorRecovery recovery = new DeclineErrorRecovery();
        Object request = new Object();
        recovery.requested(request, 1_000, 1);
        assertTrue(recovery.error(request, 9_000, 9_000));
        assertFalse(recovery.blankReady(9_000));
        assertTrue(recovery.blankReady(9_450));
    }

    @Test public void deliveryDelayCannotReviveAnOldError() {
        DeclineErrorRecovery recovery = new DeclineErrorRecovery();
        Object request = new Object();
        recovery.requested(request, 1_000, 1);
        assertFalse(recovery.error(request, 1_100, 5_101));
        assertFalse(recovery.pending());
        assertTrue(recovery.error(request, 1_100, 5_100));
        assertFalse(recovery.expired(5_100));
        assertTrue(recovery.expired(5_101));
    }

    @Test public void duplicateErrorsNeverExtendTheOriginalFourSecondWindow() {
        DeclineErrorRecovery recovery = new DeclineErrorRecovery();
        Object request = new Object();
        recovery.requested(request, 1_000, 1);
        assertTrue(recovery.error(request, 1_100, 1_100));
        assertFalse(recovery.blankReady(1_100));
        assertTrue(recovery.error(request, 3_000, 3_000));
        assertFalse(recovery.expired(5_100));
        assertTrue(recovery.blankReady(5_100));
        assertTrue(recovery.expired(5_101));
        assertFalse(recovery.blankReady(5_101));

        assertTrue("a delayed duplicate may be recognized, but adds no time", recovery.error(request, 3_000, 6_000));
        assertTrue(recovery.expired(6_000));
        assertFalse(recovery.blankReady(6_000));
    }

    @Test public void aBlankSurfaceMustRemainStableForFourHundredFiftyMilliseconds() {
        DeclineErrorRecovery recovery = new DeclineErrorRecovery();
        Object request = new Object();
        recovery.requested(request, 1_000, 1);
        assertTrue(recovery.error(request, 1_100, 1_100));
        assertFalse(recovery.blankReady(1_200));
        assertFalse(recovery.blankReady(1_649));
        assertTrue(recovery.blankReady(1_650));
    }

    @Test public void anInterruptedBlankObservationStartsTheStabilityWaitAgain() {
        DeclineErrorRecovery recovery = new DeclineErrorRecovery();
        Object request = new Object();
        recovery.requested(request, 1_000, 1);
        assertTrue(recovery.error(request, 1_100, 1_100));
        assertFalse(recovery.blankReady(1_200));
        recovery.notBlank();
        assertFalse(recovery.blankReady(1_649));
        assertFalse(recovery.blankReady(2_098));
        assertTrue(recovery.blankReady(2_099));
    }

    @Test public void waitingForBlankCannotOutliveTheErrorEvidence() {
        DeclineErrorRecovery recovery = new DeclineErrorRecovery();
        Object request = new Object();
        recovery.requested(request, 1_000, 1);
        assertTrue(recovery.error(request, 1_100, 1_100));
        assertFalse(recovery.blankReady(4_800));
        assertFalse("the stability wait finished after evidence expired", recovery.blankReady(5_250));
    }

    @Test public void oneRequestCanAuthorizeOnlyOneBackEvenIfItKeepsReportingErrors() {
        DeclineErrorRecovery recovery = new DeclineErrorRecovery();
        Object request = new Object();
        ready(recovery, request, 1_000, 1);
        recovery.backRequested(1_550);
        assertTrue(recovery.pending());
        assertTrue(recovery.returning());
        assertFalse(recovery.error(request, 1_600, 1_600));
        assertFalse(recovery.blankReady(2_000));
        recovery.clearSignal();
        assertFalse(recovery.returning());
        assertFalse(recovery.pending());
        assertFalse("clearing a completed return cannot reuse the request", recovery.error(request, 1_700, 1_700));
        assertFalse(recovery.blankReady(2_500));
    }

    @Test public void twoBacksExhaustTheBudgetAcrossRequestsForTheSameOffer() {
        DeclineErrorRecovery recovery = new DeclineErrorRecovery();
        ready(recovery, new Object(), 1_000, 1);
        recovery.backRequested(1_550);
        assertFalse(recovery.exhausted());

        ready(recovery, new Object(), 3_000, 2);
        assertFalse("a new request ends the earlier return wait", recovery.returning());
        recovery.backRequested(3_550);
        assertTrue(recovery.exhausted());

        Object third = new Object();
        recovery.requested(third, 5_000, 3);
        assertTrue(recovery.error(third, 5_100, 5_100));
        assertFalse(recovery.blankReady(5_100));
        assertFalse("a third request cannot authorize a third Back", recovery.blankReady(5_550));
        assertTrue(recovery.exhausted());
    }

    @Test public void aNewOffersFirstRequestStartsWithAFreshBackBudget() {
        DeclineErrorRecovery recovery = new DeclineErrorRecovery();
        ready(recovery, new Object(), 1_000, 1);
        recovery.backRequested(1_550);
        ready(recovery, new Object(), 3_000, 2);
        recovery.backRequested(3_550);
        assertTrue(recovery.exhausted());

        ready(recovery, new Object(), 5_000, 1);
        assertFalse(recovery.exhausted());
        assertFalse(recovery.returning());
    }

    @Test public void theReturnWaitEndsExactlyThreeSecondsAfterBack() {
        DeclineErrorRecovery recovery = new DeclineErrorRecovery();
        ready(recovery, new Object(), 1_000, 1);
        recovery.backRequested(1_550);
        assertFalse(recovery.expired(4_549));
        assertTrue(recovery.expired(4_550));
        assertTrue(recovery.expired(5_551));
        recovery.clearSignal();
        assertFalse(recovery.pending());
        assertFalse(recovery.returning());
        assertFalse(recovery.expired(6_000));
    }

    @Test public void endingRevokesErrorAndReturnEvidenceAndOldRequestIdentity() {
        DeclineErrorRecovery recovery = new DeclineErrorRecovery();
        Object request = new Object();
        ready(recovery, request, 1_000, 1);
        recovery.end();
        assertFalse(recovery.pending());
        assertFalse(recovery.blankReady(1_600));
        assertFalse(recovery.error(request, 1_600, 1_600));

        Object next = new Object();
        ready(recovery, next, 3_000, 1);
        recovery.backRequested(3_550);
        recovery.end();
        assertFalse(recovery.pending());
        assertFalse(recovery.returning());
        assertFalse(recovery.expired(10_000));
        assertFalse(recovery.exhausted());
        assertFalse(recovery.error(next, 3_600, 3_600));
        assertFalse(recovery.blankReady(4_000));
    }

    private static void ready(DeclineErrorRecovery recovery, Object request, long at, int attempt) {
        recovery.requested(request, at, attempt);
        assertTrue(recovery.error(request, at + 100, at + 100));
        assertFalse(recovery.blankReady(at + 100));
        assertTrue(recovery.blankReady(at + 550));
    }
}
