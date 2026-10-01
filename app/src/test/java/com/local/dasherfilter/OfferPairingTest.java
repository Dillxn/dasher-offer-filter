package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** Which notification is the same offer as a screen reading: the windows and the countdown they come from. */
public final class OfferPairingTest {
    private static DecisionLog.Entry notice(long at, DecisionLog.Action action) {
        return new DecisionLog.Entry(at, DecisionLog.Source.NOTIFICATION, false, OfferSnapshot.UNKNOWN, 0,
                OfferRule.Result.REVIEW, "pay not found", action, true, Collections.emptyList());
    }

    private static DecisionLog.Entry onScreen(long at, int pay) {
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, new OfferSnapshot(pay, 4.0, 21, 2), 1000,
                OfferRule.Result.DECLINE, "flat minimum", DecisionLog.Action.DECLINE_TAPPED, true,
                Collections.emptyList());
    }

    @Test
    public void windowFollowsTheCountdown() {
        assertEquals("0:47 left: the offer is at most 15 s old", 15_000, OfferPairing.window(47));
        assertEquals(42_000, OfferPairing.window(20));
        assertEquals("no countdown read", 30_000, OfferPairing.window(-1));
        assertEquals(60_000, OfferPairing.window(0));
        assertEquals(30_000, OfferPairing.window(61));
    }

    @Test
    public void secondsLeftReadsOnlyACountdown() {
        assertEquals(35, OfferEvidence.secondsLeft(Arrays.asList("Accept", "0:35")));
        assertEquals("a clock time is not a countdown", -1, OfferEvidence.secondsLeft(Arrays.asList("9:45 PM")));
        assertEquals("over a minute is skipped", 40, OfferEvidence.secondsLeft(Arrays.asList("5:30", "0:40")));
        assertEquals(60, OfferEvidence.secondsLeft(Arrays.asList("1:00")));
        assertEquals(-1, OfferEvidence.secondsLeft(Collections.emptyList()));
    }

    @Test
    public void aScreenOfferAfterwardsTakesOnlyTheNotificationJustBeforeIt() {
        List<DecisionLog.Entry> history = new ArrayList<>(Arrays.asList(
                notice(0, DecisionLog.Action.CHECK_BELL), notice(20_000, DecisionLog.Action.CHECK_BELL)));
        assertEquals("the newest unmatched notification is the offer on screen", 1,
                OfferPairing.notificationFor(history, onScreen(30_000, 900), -1));
        assertEquals("with 0:47 left, one 30 s old is an earlier offer", -1,
                OfferPairing.notificationFor(history, onScreen(50_000, 900), 47));
        assertEquals("a known failure on the notification is its own line", -1, OfferPairing.notificationFor(
                Collections.singletonList(new DecisionLog.Entry(0, DecisionLog.Source.NOTIFICATION, false,
                        new OfferSnapshot(700, null, null, null), 1000, OfferRule.Result.DECLINE, "flat minimum",
                        DecisionLog.Action.NOTIFICATION_DECLINE_SENT, true, Collections.emptyList())),
                onScreen(5_000, 700), -1));
    }

    @Test
    public void aNotificationAfterwardsJoinsOnlyTheNewestScreenOfferMomentsBefore() {
        List<DecisionLog.Entry> history = new ArrayList<>(Arrays.asList(onScreen(0, 800), onScreen(10_000, 900)));
        assertEquals(1, OfferPairing.screenFor(history, notice(20_000, DecisionLog.Action.SEEN_ON_SCREEN)));
        assertEquals("13 s later is past the lag a notification was seen to have", -1,
                OfferPairing.screenFor(history, notice(23_000, DecisionLog.Action.SEEN_ON_SCREEN)));
        history.set(1, history.get(1).withNotification(notice(9_000, DecisionLog.Action.CHECK_BELL)));
        assertEquals("a screen offer takes one notification", -1,
                OfferPairing.screenFor(history, notice(12_000, DecisionLog.Action.SEEN_ON_SCREEN)));
    }

    @Test
    public void foldingKeepsTheNotificationWithoutItsLines() {
        DecisionLog.Entry n = new DecisionLog.Entry(1_000, DecisionLog.Source.NOTIFICATION, false,
                OfferSnapshot.UNKNOWN, 0, OfferRule.Result.REVIEW, "pay not found", DecisionLog.Action.CHECK_BELL, true,
                Collections.singletonList("Go to Store A")).withAlertTag("offer-1", false);
        DecisionLog.Entry folded = onScreen(15_000, 900).withNotification(n);
        assertEquals(0, folded.notification.evidence.size());
        assertNull(folded.notification.alertTag);
        assertNull(folded.notification.notification);
        assertEquals("14 s earlier", DecisionLog.noticeWhen(folded));
        assertEquals("1 s later", DecisionLog.noticeWhen(onScreen(15_000, 900).withNotification(
                notice(16_000, DecisionLog.Action.SEEN_ON_SCREEN))));
        assertSame(DecisionLog.Action.CHECK_BELL, folded.notification.action);
    }
}
