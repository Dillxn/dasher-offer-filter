package com.local.dasherfilter;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** One notification incarnation: what the screen's reading settles, what a re-post is, and when it rings. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class OfferAlertStateTest {
    private static final OfferSnapshot READ = new OfferSnapshot(790, 7.2, 21, 2);

    private Application app;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        DecisionLog.forgetCache();
    }

    @Test
    public void aScreenReadCoversQuietUpdatesOnScreenAndReplaysOfThePostsItRead() {
        OfferAlertState state = new OfferAlertState(0, 100_000);
        assertFalse("nothing read yet", state.coveredByScreen(OfferRule.Result.REVIEW, OfferSnapshot.UNKNOWN,
                true, false, 100_000));
        state.settle("first", OfferRule.Result.REVIEW, READ);
        assertTrue(state.coveredByScreen(OfferRule.Result.REVIEW, OfferSnapshot.UNKNOWN, true, false, 110_000));
        assertTrue("a replay of the post the screen read", state.coveredByScreen(OfferRule.Result.REVIEW,
                OfferSnapshot.UNKNOWN, false, true, 100_000));
        assertFalse("a known failure still declines", state.coveredByScreen(OfferRule.Result.DECLINE,
                OfferSnapshot.UNKNOWN, true, false, 110_000));
        assertFalse("different facts are a different offer", state.coveredByScreen(OfferRule.Result.REVIEW,
                new OfferSnapshot(990, null, null, null), true, false, 110_000));
        assertFalse("off screen it may be a next offer", state.coveredByScreen(OfferRule.Result.REVIEW,
                OfferSnapshot.UNKNOWN, false, false, 110_000));
        assertFalse("settling never spends the ring", state.rang);
    }

    @Test
    public void aReplayOfAPostAfterTheScreensReadingIsNotCovered() {
        OfferAlertState state = new OfferAlertState(0, 100_000);
        state.markRead(READ);
        // A later post on this incarnation (Dasher off screen) got a card of its own: the screen never read it.
        state.postedAt = 130_000;
        assertFalse("pausing or a rules change must not clear its card", state.coveredByScreen(
                OfferRule.Result.REVIEW, OfferSnapshot.UNKNOWN, false, true, 130_000));
        assertFalse("nor on screen", state.coveredByScreen(OfferRule.Result.REVIEW, OfferSnapshot.UNKNOWN,
                true, true, 130_000));
        assertTrue(state.coveredByScreen(OfferRule.Result.REVIEW, OfferSnapshot.UNKNOWN, false, true, 100_000));
    }

    @Test
    public void aRepostIsANewOfferOnlyAfterTheScreenSawItEndOrAfterAQuietGap() {
        OfferAlertState state = new OfferAlertState(0, 100_000);
        state.text = "[New Delivery!, New Order: Go to Store A]";
        assertNull(state.newOfferReason(state.text, 110_000, false));
        assertNull("a changed text is an update", state.newOfferReason("[New Delivery! 0:30 left]", 130_000, false));
        assertTrue(state.newOfferReason(state.text, 116_000, false).startsWith("unchanged text 16 s"));
        assertNull("on screen the screen tells", state.newOfferReason(state.text, 130_000, true));
        state.readOnScreen = READ;
        state.endedOnScreen = true;
        assertEquals("the screen saw the last offer end", state.newOfferReason(state.text, 101_000, true));
    }

    @Test
    public void aChangedRepostOffScreenAfterTheScreenClearedTheCardIsANewOffer() {
        OfferAlertState state = new OfferAlertState(0, 100_000);
        state.text = "[New Delivery!, New Order: Go to Store A]";
        state.delivered("card", OfferRule.Result.REVIEW, true);
        assertNull("before the screen read it, a changed post is an update",
                state.newOfferReason("[New Delivery! 0:30 left]", 105_000, false));
        state.markRead(READ);
        assertNull("unchanged moments later: the same offer", state.newOfferReason(state.text, 105_000, false));
        assertNull("on screen the screen tells", state.newOfferReason("[New Delivery! 0:30 left]", 105_000, true));
        assertEquals("the screen read the last offer and cleared its card; the text changed",
                state.newOfferReason("[New Delivery! 0:30 left]", 105_000, false));
        // Once a card shows again for this incarnation, a changed post is an update of it.
        state.delivered("card 2", OfferRule.Result.REVIEW, false);
        assertNull(state.newOfferReason("[New Delivery! 0:20 left]", 110_000, false));
    }

    @Test
    public void dashersOwnAlertSoundedOnlyWhenAndroidSaysSo() {
        StatusBarNotification post = new StatusBarNotification("com.doordash.driverapp", "com.doordash.driverapp", 3,
                "NEW_ORDER", 10001, 0, 0, new Notification(), android.os.Process.myUserHandle(), 100_000);
        assertFalse("unknown when Android does not say: ours rings",
                OfferNotificationService.dasherAlertSounded(null, post, false));
        NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
        ReflectionHelpers.setField(ranking, "mChannel",
                new NotificationChannel("a", "Offers", NotificationManager.IMPORTANCE_HIGH));
        ReflectionHelpers.setField(ranking, "mImportance", NotificationManager.IMPORTANCE_HIGH);
        ReflectionHelpers.setField(ranking, "mMatchesInterruptionFilter", true);
        assertFalse("a loud channel alone is not a sound: Android alerted for an older post",
                OfferNotificationService.dasherAlertSounded(ranking, post, false));
        ReflectionHelpers.setField(ranking, "mLastAudiblyAlertedMs", 99_500L);
        assertTrue(OfferNotificationService.dasherAlertSounded(ranking, post, false));
    }

    @Test
    public void twoNotificationIncarnationsAreNeverOneLineButAReplayIs() {
        DecisionLog.record(app, notice(1_000).withAlertTag("offer-1", false));
        DecisionLog.record(app, notice(31_000).withAlertTag("offer-2", false));
        assertEquals(2, DecisionLog.recent(app, 10).size());
        // A replay after a reconnect re-checks a post already recorded, under a new tag.
        DecisionLog.record(app, new DecisionLog.Entry(32_000, DecisionLog.Source.NOTIFICATION, false,
                OfferSnapshot.UNKNOWN, 0, OfferRule.Result.REVIEW, "pay not found", DecisionLog.Action.SILENT_CARD,
                true, Collections.emptyList()).withAlertTag("offer-3", true));
        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals(2, recent.size());
        assertEquals("the replayed line keeps its action and takes the new tag", "offer-3", recent.get(0).alertTag);
        assertEquals(DecisionLog.Action.CHECK_BELL, recent.get(0).action);
    }

    @Test
    public void aSecondReadingTakesEveryNotificationOfItsOfferAndKeepsTheFirst() {
        DecisionLog.Entry read = new DecisionLog.Entry(0, DecisionLog.Source.SCREEN, false, READ, 1000,
                OfferRule.Result.DECLINE, "flat minimum", DecisionLog.Action.DECLINE_TAPPED, true,
                Collections.emptyList());
        DecisionLog.record(app, read);
        // Two incarnations of Dasher's notification while Dasher was away: two lines, two cards.
        DecisionLog.record(app, notice(5_000).withAlertTag("offer-1", false));
        DecisionLog.record(app, notice(25_000).withAlertTag("offer-2", false));
        assertEquals(3, DecisionLog.recent(app, 10).size());

        List<DecisionLog.Entry> taken = DecisionLog.record(app, new DecisionLog.Entry(28_000,
                DecisionLog.Source.SCREEN, false, READ, 1000, OfferRule.Result.DECLINE, "flat minimum",
                DecisionLog.Action.DECLINE_TAPPED, true, Collections.emptyList()), 30);
        assertEquals("both cards are this offer's, so both are cleared", 2, taken.size());
        assertEquals("offer-1", taken.get(0).alertTag);
        assertEquals("offer-2", taken.get(1).alertTag);
        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals(1, recent.size());
        assertEquals(5_000, recent.get(0).notification.at);
        assertArrayEquals(new int[] {0, 1, 0}, DecisionLog.totals(app));

        // A reading with the countdown nearly done cannot take a notification from before its offer came.
        DecisionLog.record(app, notice(40_000).withAlertTag("offer-3", false));
        assertTrue(DecisionLog.record(app, new DecisionLog.Entry(70_000, DecisionLog.Source.SCREEN, false, READ,
                1000, OfferRule.Result.DECLINE, "flat minimum", DecisionLog.Action.DECLINE_TAPPED, true,
                Collections.emptyList()), 40).isEmpty());
        assertEquals(2, DecisionLog.recent(app, 10).size());
    }

    private static DecisionLog.Entry notice(long at) {
        return new DecisionLog.Entry(at, DecisionLog.Source.NOTIFICATION, false, OfferSnapshot.UNKNOWN, 0,
                OfferRule.Result.REVIEW, "pay not found", DecisionLog.Action.CHECK_BELL, true, Collections.emptyList());
    }
}
