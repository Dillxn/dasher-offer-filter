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
        peek.end(true, false, 14_000);
        assertNotNull(peek.refusal(request("two"), 18_999));
        assertNull(peek.refusal(request("two"), 19_000));
    }
    @Test public void failedLaunchDoesNotHoldTheNotificationAndTwoFailuresPause() {
        Peek peek = armed(0);
        peek.opened(700, 1, 0);
        assertNull(peek.end(false, true, 6_700));
        assertNull(peek.refusal(request("new-post"), 11_700));
        peek.arm(request("new-post"), new Peek.Front(Peek.Back.HOME, "home", null, 1),
                new ComponentName("com.doordash.driverapp", "Home"), 11_700);
        peek.opened(12_400, 2, 0);
        assertNotNull(peek.end(false, true, 18_400));
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
        peek.end(false, false, 100);
        peek.forgetFront();
        assertNull(peek.front());
    }
    @Test public void unknownScreenAtTheDeadlineDoesNotHoldAReusedNotificationKey() {
        Peek peek = armed(0);
        Peek.Request first = request("one");
        peek.opened(700, 1, 0);
        peek.up(900);
        peek.screen(false, 1_000);
        peek.end(false, false, 20_700);
        Peek.Request next = new Peek.Request("one", first.postTime + 30_000, "Store", false, true, "next");
        assertNull(peek.refusal(next, 26_000));
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
