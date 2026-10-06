package com.local.dasherfilter;

import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** Deadline, identity and scope regression tests for the final user-approved Peek policy. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public class PeekStateTest {
    private Peek.Request request(String key) {
        return new Peek.Request(key, System.currentTimeMillis(), "Example Store", false, true, key);
    }
    private Peek armed(long at) {
        Peek peek = new Peek();
        peek.arm(request("one"), new Peek.Front(Peek.Back.APP, "com.example.maps",
                new ComponentName("com.example.maps", "Map"), 1),
                new ComponentName("com.doordash.driverapp", "Home"), at);
        return peek;
    }
    @Test public void touchRestartsQuietAndTheWaitHasABound() {
        Peek peek = armed(1_000);
        assertFalse(peek.quiet(1_699));
        peek.touchedWhileArming(1_650);
        assertFalse(peek.quiet(2_000));
        assertTrue(peek.quiet(2_350));
        peek.touchedWhileArming(3_800);
        assertTrue(peek.quietTimedOut(4_000));
        assertFalse(peek.quiet(4_000));
    }
    @Test public void gapStartsWhenThePeekEndsAndNotWhenItOpened() {
        Peek peek = armed(1_000);
        peek.opened(1_700, 1, 0);
        peek.up(2_000);
        peek.offerSign();
        peek.end(Peek.Outcome.DECLINED_BACK, true, 14_000, 0);
        assertNotNull(peek.refusal(request("two"), 18_999));
        assertNull(peek.refusal(request("two"), 19_000));
    }
    @Test public void failedLaunchDoesNotHoldTheNotificationAndTwoFailuresPause() {
        Peek peek = armed(0);
        peek.opened(700, 1, 0);
        assertNull(peek.end(Peek.Outcome.OPEN_FAILED, false, 6_700, 0));
        assertNull(peek.refusal(request("new-post"), 11_700));
        peek.arm(request("new-post"), new Peek.Front(Peek.Back.HOME, "home", null, 1),
                new ComponentName("com.doordash.driverapp", "Home"), 11_700);
        peek.opened(12_400, 2, 0);
        assertNotNull(peek.end(Peek.Outcome.OPEN_FAILED, false, 18_400, 0));
    }
    @Test public void unrecognisedScreensNeverStartTheNoOfferClock() {
        Peek peek = armed(0);
        peek.opened(700, 1, 0);
        peek.up(900);
        peek.screen(false, 1_000);
        assertEquals(Long.MIN_VALUE, peek.recognisedAt());
        peek.screen(true, 6_000);
        assertEquals(6_000, peek.recognisedAt());
        peek.screen(false, 7_000);
        assertFalse(peek.lastRecognised());
    }
    @Test public void unrecognisedScreenRestartsTheNoOfferInterval() {
        Peek peek = armed(0);
        peek.opened(700, 1, 0);
        peek.up(900);
        peek.screen(true, 1_000);
        peek.screen(false, 4_000);
        assertEquals("loading time is not a recognised empty screen", Long.MIN_VALUE, peek.recognisedAt());
        assertFalse(peek.noOfferWaited(6_000));
        peek.screen(true, 6_500);
        assertEquals("the complete empty-screen interval starts afresh", 6_500, peek.recognisedAt());
        peek.screen(true, 7_000);
        assertEquals("consecutive recognised reads keep the same interval", 6_500, peek.recognisedAt());
        assertFalse(peek.noOfferWaited(10_499));
        assertTrue(peek.noOfferWaited(10_500));
        assertEquals("resetting the empty interval does not extend the whole peek", 700, peek.openedAt());
        assertTrue(peek.mayFollow(20_699));
        assertFalse(peek.mayFollow(20_700));
    }
    @Test public void anAddonCanCompleteWithoutAQuestionButAStandaloneCannot() {
        Peek peek = armed(0);
        peek.opened(700, 1, 0);
        peek.up(900);
        OfferSnapshot offer = new OfferSnapshot(790, 7.2, 21, 2);
        peek.declined(offer, false, false);
        assertFalse(peek.declineComplete());
        peek.declined(offer, true, true);
        assertTrue(peek.declineComplete());
        assertTrue(peek.routeAfter());
    }
    @Test public void chainingIsCappedAtSixAndOlderStartsExpire() {
        Peek peek = armed(0);
        peek.opened(700, 1, 0);
        peek.up(900);
        for (int n = 1; n < Peek.CAP; n++) {
            assertTrue(peek.mayFollow(1_000 * n));
            peek.follow(1_000 * n);
        }
        assertFalse(peek.mayFollow(7_000));
        assertFalse("chaining never extends the whole peek deadline", peek.mayFollow(Peek.CAP_WINDOW_MS + 8_000));
    }
    @Test public void clockAdvancesWhileTheDeviceSleeps() {
        long before = Peek.now();
        ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(3));
        assertEquals(3_000, Peek.now() - before);
        assertEquals(SystemClock.elapsedRealtime(), Peek.now());
    }
    @Test public void stalePostsAndReplaysCannotTriggerAnOpening() {
        Context app = RuntimeEnvironment.getApplication();
        FilterSettings settings = new FilterSettings(true, 2_000, 0, 0, 0, 0);
        Peek.Request old = new Peek.Request("old", System.currentTimeMillis()-10_001,
                "Store", false, true, "old");
        assertTrue(Peek.refusal(app, old, settings, false, -1).contains("old"));
        Peek.Request replay = new Peek.Request("r", System.currentTimeMillis(), "Store", true, true, "r");
        assertTrue(Peek.refusal(app, replay, settings, false, -1).contains("replay"));
        assertNotNull(Peek.refusal(app, request("screen"), settings, false, 10));
    }
    @Test public void navigationCardShowsOnlyObservedFiguresAndUnclearIsExplicit() {
        String passing = Peek.cardText(OfferRule.Result.KEEP, new OfferSnapshot(1250, 5.1, 22, 2), "");
        assertTrue(passing.startsWith("Passes: $12.50"));
        assertTrue(passing.contains("5.1"));
        String unclear = Peek.cardText(OfferRule.Result.REVIEW, OfferSnapshot.UNKNOWN, "pay not found");
        assertTrue(unclear.startsWith("Unclear: pay not read"));
        assertFalse(unclear.contains("$0"));
    }
    @Test public void privateFrontPackageIsNotInItsPrintableKindAndCanBeForgotten() {
        Peek peek = armed(0);
        assertEquals("another app", peek.front().kind());
        peek.end(Peek.Outcome.INTERRUPTED, false, 100, 0);
        peek.forgetFront();
        assertNull(peek.front());
    }
    @Test public void unknownScreenAtTheDeadlineDoesNotHoldAReusedNotificationKey() {
        Peek peek = armed(0);
        Peek.Request first = request("one");
        peek.opened(700, 1, 0);
        peek.up(900);
        peek.screen(false, 1_000);
        peek.end(Peek.Outcome.TIMEOUT, false, 20_700, 0);
        Peek.Request next = new Peek.Request("one", first.postTime + 30_000, "Store", false, true, "next");
        assertNull(peek.refusal(next, 26_000));
    }
    /** One more opened peek on {@code peek} at {@code at}: armed, opened, and (when {@code up}) Dasher seen up. */
    private static long peekAt(Peek peek, long at, boolean up) {
        peek.arm(new Peek.Request("k" + at, System.currentTimeMillis(), "Store", false, true, "k" + at),
                new Peek.Front(Peek.Back.HOME, "home", null, 1), new ComponentName("com.doordash.driverapp", "Home"),
                at);
        peek.opened(at + 700, 1, 0);
        if (up) peek.up(at + 900);
        return at + 6_000;
    }

    @Test public void onlyWithdrawnOffersInARowWithinHalfAnHourAndOneDashCountTowardAPause() {
        Peek peek = new Peek();
        long at = 0;
        // Interruptions (a lock, a touch, a split), the deadline and offers Dasher never drew never count.
        for (Peek.Outcome outcome : new Peek.Outcome[] {Peek.Outcome.INTERRUPTED, Peek.Outcome.TIMEOUT,
                Peek.Outcome.UNSHOWN, Peek.Outcome.INTERRUPTED, Peek.Outcome.TIMEOUT, Peek.Outcome.UNSHOWN}) {
            at = peekAt(peek, at, true);
            assertNull(outcome.name(), peek.end(outcome, false, at, 0));
        }
        // ...and do not begin the run afresh either: three withdrawn offers with interruptions between pause Peek.
        at = peekAt(peek, at, true);
        assertNull(peek.end(Peek.Outcome.WITHDRAWN, true, at, 0));
        at = peekAt(peek, at, true);
        assertNull(peek.end(Peek.Outcome.INTERRUPTED, false, at, 0));
        at = peekAt(peek, at, true);
        assertNull(peek.end(Peek.Outcome.WITHDRAWN, true, at, 0));
        at = peekAt(peek, at, true);
        assertEquals("3 offers in a row were gone by the time Dasher showed",
                peek.end(Peek.Outcome.WITHDRAWN, true, at, 0));

        // An offer read in between begins the run afresh.
        for (int i = 0; i < 2; i++) {
            at = peekAt(peek, at, true);
            assertNull(peek.end(Peek.Outcome.WITHDRAWN, true, at, 0));
        }
        at = peekAt(peek, at, true);
        peek.offerSign();
        assertNull(peek.end(Peek.Outcome.DECLINED_BACK, true, at, 0));
        at = peekAt(peek, at, true);
        assertNull("a run of one", peek.end(Peek.Outcome.WITHDRAWN, true, at, 0));

        // Half an hour apart is not in a row.
        at = peekAt(peek, at + Peek.STREAK_MS, true);
        assertNull(peek.end(Peek.Outcome.WITHDRAWN, true, at, 0));
        at = peekAt(peek, at + Peek.STREAK_MS, true);
        assertNull(peek.end(Peek.Outcome.WITHDRAWN, true, at, 0));

        // Another dash begins it afresh.
        at = peekAt(peek, at, true);
        assertNull(peek.end(Peek.Outcome.WITHDRAWN, true, at, 1_000));
        at = peekAt(peek, at, true);
        assertNull(peek.end(Peek.Outcome.WITHDRAWN, true, at, 1_000));
        at = peekAt(peek, at, true);
        assertNull("the first of a new dash", peek.end(Peek.Outcome.WITHDRAWN, true, at, 2_000));
    }

    @Test public void dasherComingUpBeginsTheFailedLaunchRunAfresh() {
        Peek peek = new Peek();
        long at = peekAt(peek, 0, false);
        assertNull(peek.end(Peek.Outcome.OPEN_FAILED, false, at, 0));
        at = peekAt(peek, at, true);
        assertNull(peek.end(Peek.Outcome.INTERRUPTED, false, at, 0));
        at = peekAt(peek, at, false);
        assertNull("Dasher came up in between", peek.end(Peek.Outcome.OPEN_FAILED, false, at, 0));
        at = peekAt(peek, at, false);
        assertEquals("Dasher did not come up for 2 peeks in a row (the phone may block apps opening from the "
                + "background)", peek.end(Peek.Outcome.OPEN_FAILED, false, at, 0));
    }

    @Test public void aLockedPeekResumesWithTheLockedTimeNotCounted() {
        Peek peek = armed(0);
        peek.opened(700, 1, 0);
        peek.up(900);
        peek.screen(true, 1_000);
        peek.suspend(2_000);
        assertTrue(peek.suspended());
        assertEquals(2_000, peek.suspendedAt());
        peek.resume(32_000);
        assertFalse(peek.suspended());
        assertEquals(30_700, peek.openedAt());
        assertEquals(30_900, peek.upAt());
        assertEquals(31_000, peek.recognisedAt());
        assertEquals(30_900 + Peek.PRESENT_MS, peek.presentationDueAt());
        assertFalse("its 20 s count from the first open, the locked time aside", peek.pastDeadline(50_699));
        assertTrue(peek.pastDeadline(50_700));
        assertEquals(32_000, peek.resumedAt());
    }

    @Test public void chainedOfferCannotExtendTheAbsoluteOpeningDeadline() {
        Peek peek = armed(0);
        peek.opened(700, 1, 0);
        peek.up(900);
        peek.follow(19_000);
        assertTrue(peek.mayFollow(20_699));
        assertFalse(peek.mayFollow(20_700));
    }

}
