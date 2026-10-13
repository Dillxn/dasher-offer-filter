package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The split divider's labels, the choice among them, the safe moment and what a dash allows (the owner, 13 October
 * 2026: "when either app is in a maps mode for nav the other when in split needs to resize to like 20%"). Plain JUnit:
 * {@link SplitDivider} is pure. The adapter is {@link SplitDividerAdapterTest}.
 */
public class SplitDividerTest {
    /** Stock Android up to 16 QPR1, portrait. */
    private static final List<String> QPR1 = Arrays.asList("Top full screen", "Top 70%", "Top 50%", "Top 30%",
            "Bottom full screen", "Swap top app with bottom");
    /** Android 16 QPR2 and later. */
    private static final List<String> QPR2 = Arrays.asList("Make top app full screen", "10% top, 90% bottom",
            "30% top, 70% bottom", "50% top, 50% bottom", "70% top, 30% bottom", "90% top, 10% bottom",
            "Make bottom app full screen", "Swap top app with bottom app, keep current split");

    private static List<SplitDivider.Target> targets(List<String> labels) {
        List<SplitDivider.Target> targets = new ArrayList<>();
        int id = 100;
        for (String label : labels) {
            int share = SplitDivider.share(label);
            if (share > 0) targets.add(new SplitDivider.Target(id, label, share));
            id++;
        }
        return targets;
    }

    // ---- The parser ----

    @Test
    public void theFirstPercentIsTheTopOrLeftAppsShare() {
        assertEquals(30, SplitDivider.share("Top 30%"));
        assertEquals(30, SplitDivider.share("30% top, 70% bottom"));
        assertEquals(70, SplitDivider.share("Left 70%"));
        assertEquals(70, SplitDivider.share("70 %"));
        assertEquals("a no-break space, as some languages write it", 70, SplitDivider.share("Haut 70 %"));
        assertEquals(50, SplitDivider.share("50% top, 50% bottom"));
    }

    @Test
    public void fullScreenAndSwapNameNoShare() {
        assertEquals(-1, SplitDivider.share("Make top app full screen"));
        assertEquals(-1, SplitDivider.share("Swap top app with bottom app, keep current split"));
        assertEquals(-1, SplitDivider.share("Top full screen"));
        assertEquals(-1, SplitDivider.share("Swap top app with bottom"));
        assertEquals(-1, SplitDivider.share(null));
        assertEquals("out of 1 to 99", -1, SplitDivider.share("100% top"));
        assertEquals("not a whole number's %", -1, SplitDivider.share("Top 2.5%"));
    }

    @Test
    public void tenPercentParsesButIsNeverChosenToShrink() {
        assertEquals(10, SplitDivider.share("10% top, 90% bottom"));
        assertEquals(90, SplitDivider.share("90% top, 10% bottom"));
        List<SplitDivider.Target> qpr2 = targets(QPR2);
        assertEquals(30, SplitDivider.forShrink(qpr2, true).share);
        assertEquals(70, SplitDivider.forShrink(qpr2, false).share);
    }

    // ---- The chooser ----

    @Test
    public void shrinkingTheTopHalfTakesTheSmallestShareOfAtLeastTwenty() {
        SplitDivider.Target top = SplitDivider.forShrink(targets(QPR1), true);
        assertEquals("Top 30%", top.label);
        assertEquals(103, top.id);
        assertEquals("30% top, 70% bottom", SplitDivider.forShrink(targets(QPR2), true).label);
        assertEquals("20% is the least", 20,
                SplitDivider.forShrink(targets(Arrays.asList("Top 15%", "Top 20%", "Top 40%")), true).share);
    }

    @Test
    public void shrinkingTheBottomHalfTakesTheLargestShareOfAtMostEighty() {
        assertEquals("Top 70%", SplitDivider.forShrink(targets(QPR1), false).label);
        assertEquals("70% top, 30% bottom", SplitDivider.forShrink(targets(QPR2), false).label);
        assertEquals(80, SplitDivider.forShrink(targets(Arrays.asList("Top 85%", "Top 80%", "Top 60%")), false).share);
    }

    @Test
    public void restoringTakesTheNearestShare() {
        assertEquals("Top 50%", SplitDivider.forRestore(targets(QPR1), 48).label);
        assertEquals("Top 70%", SplitDivider.forRestore(targets(QPR1), 66).label);
        assertEquals("50% top, 50% bottom", SplitDivider.forRestore(targets(QPR2), 55).label);
    }

    @Test
    public void nothingParseableChoosesNothing() {
        List<SplitDivider.Target> none = targets(Arrays.asList("Top full screen", "Bottom full screen",
                "Swap top app with bottom"));
        assertTrue(none.isEmpty());
        assertNull(SplitDivider.forShrink(none, true));
        assertNull(SplitDivider.forShrink(none, false));
        assertNull(SplitDivider.forRestore(none, 50));
        assertNull("none small enough", SplitDivider.forShrink(targets(Collections.singletonList("Top 10%")), true));
    }

    @Test
    public void theHalfThatIsNotNavigatingShrinks() {
        assertEquals(SplitDivider.Pane.OURS, SplitDivider.shrinks(SplitDivider.Pane.OURS, SplitDivider.Pane.DASHER));
        assertEquals(SplitDivider.Pane.OURS, SplitDivider.shrinks(SplitDivider.Pane.DASHER, SplitDivider.Pane.OURS));
        assertEquals(SplitDivider.Pane.OURS, SplitDivider.shrinks(SplitDivider.Pane.MAP, SplitDivider.Pane.OURS));
        assertEquals(SplitDivider.Pane.DASHER, SplitDivider.shrinks(SplitDivider.Pane.DASHER, SplitDivider.Pane.MAP));
        assertNull(SplitDivider.shrinks(SplitDivider.Pane.DASHER, SplitDivider.Pane.OTHER));
        assertNull(SplitDivider.shrinks(SplitDivider.Pane.OURS, SplitDivider.Pane.OTHER));
        assertNull(SplitDivider.shrinks(SplitDivider.Pane.MAP, SplitDivider.Pane.OTHER));
        assertNull(SplitDivider.shrinks(SplitDivider.Pane.OURS, null));
    }

    // ---- The guards, each blocking on its own ----

    private static SplitDivider.Moment safe() {
        SplitDivider.Moment moment = new SplitDivider.Moment();
        moment.noticeAccepted = true;
        moment.unlocked = true;
        return moment;
    }

    @Test
    public void aQuietMomentIsSafe() {
        assertNull(SplitDivider.blocked(safe()));
        SplitDivider.Moment later = safe();
        later.sinceOfferSign = SplitDivider.QUIET_MS;
        later.sinceUserClick = SplitDivider.TOUCH_MS;
        later.sinceOwnTouch = SplitDivider.TOUCH_MS;
        assertNull(SplitDivider.blocked(later));
    }

    @Test
    public void eachGuardBlocksOnItsOwn() {
        SplitDivider.Moment paused = safe();
        paused.paused = true;
        assertEquals("auto-decline is paused", SplitDivider.blocked(paused));
        SplitDivider.Moment locked = safe();
        locked.unlocked = false;
        assertNotNull(SplitDivider.blocked(locked));
        SplitDivider.Moment notice = safe();
        notice.noticeAccepted = false;
        assertNotNull(SplitDivider.blocked(notice));
        SplitDivider.Moment offerUp = safe();
        offerUp.offerUp = true;
        assertNotNull(SplitDivider.blocked(offerUp));
        SplitDivider.Moment offerSign = safe();
        offerSign.sinceOfferSign = 2_000;
        assertEquals("an offer was seen 2000 ms ago", SplitDivider.blocked(offerSign));
        SplitDivider.Moment decline = safe();
        decline.underWay = "a decline";
        assertEquals("a decline is under way", SplitDivider.blocked(decline));
        SplitDivider.Moment peek = safe();
        peek.underWay = "a peek";
        assertEquals("a peek is under way", SplitDivider.blocked(peek));
        SplitDivider.Moment click = safe();
        click.sinceUserClick = 1_000;
        assertNotNull(SplitDivider.blocked(click));
        SplitDivider.Moment touch = safe();
        touch.sinceOwnTouch = 1_000;
        assertNotNull(SplitDivider.blocked(touch));
        SplitDivider.Moment navigation = safe();
        navigation.navigationRead = true;
        assertEquals("Dasher's navigation is on screen", SplitDivider.blocked(navigation));
    }

    // ---- What a dash allows ----

    /** A plan in a dash, a delivery begun. */
    private static SplitDivider delivering() {
        SplitDivider plan = new SplitDivider();
        plan.dash(1);
        plan.delivery(true);
        return plan;
    }

    /** Offer Filter's top half shrunk from 50% to 30% at {@code at}, verified 1.5 s later. */
    private static void shrunk(SplitDivider plan, long at) {
        assertEquals(SplitDivider.Want.SHRINK, plan.want());
        assertNull(plan.capped());
        assertTrue(plan.roomFor(50, 30));
        plan.moved(SplitDivider.Want.SHRINK, SplitDivider.Pane.OURS, 50, 30, at);
        assertEquals("checked first", SplitDivider.Want.NONE, plan.want());
        assertNull("not yet", plan.verify(50, 50, at + 1_000));
        assertNull(plan.verify(30, 30, at + SplitDivider.VERIFY_MS));
    }

    @Test
    public void onceADeliveryShrinksOnceAndRestoresAtItsEnd() {
        SplitDivider plan = delivering();
        shrunk(plan, 1_000);
        assertEquals("once per delivery per split", SplitDivider.Want.NONE, plan.want());
        assertNull(plan.watchUser(31));
        plan.delivery(false);
        assertEquals(SplitDivider.Want.RESTORE, plan.want());
        assertEquals(50, plan.firstBefore());
        assertEquals(SplitDivider.Pane.OURS, plan.shrunkPane());
        plan.moved(SplitDivider.Want.RESTORE, SplitDivider.Pane.OURS, 30, 50, 9_000);
        assertNull(plan.verify(50, 50, 9_000 + SplitDivider.VERIFY_MS));
        assertEquals(SplitDivider.Want.NONE, plan.want());
        assertNull(plan.shrunkPane());
        // The next delivery shrinks again.
        plan.delivery(true);
        assertEquals(SplitDivider.Want.SHRINK, plan.want());
    }

    @Test
    public void aHalfAlreadySmallIsLeftAlone() {
        SplitDivider plan = delivering();
        assertEquals(SplitDivider.Want.SHRINK, plan.want());
        assertTrue(plan.roomFor(39, 30));
        SplitDivider small = delivering();
        assertEquals("within 8 points", false, small.roomFor(38, 30));
        assertEquals("left alone this delivery", SplitDivider.Want.NONE, small.want());
        small.delivery(false);
        assertEquals("nothing to restore", SplitDivider.Want.NONE, small.want());
    }

    @Test
    public void theUsersMoveAfterOffersStopsItForTheDash() {
        SplitDivider plan = delivering();
        shrunk(plan, 0);
        assertEquals("you moved the divider: left alone for the rest of this dash", plan.watchUser(40));
        plan.delivery(false);
        assertEquals("no restore", SplitDivider.Want.NONE, plan.want());
        plan.delivery(true);
        assertEquals("nor another shrink this dash", SplitDivider.Want.NONE, plan.want());
        plan.dash(2);
        plan.delivery(false);
        plan.delivery(true);
        assertEquals("the next dash starts over", SplitDivider.Want.SHRINK, plan.want());
    }

    @Test
    public void aDividerThatDoesNotMoveStopsItForTheDash() {
        SplitDivider plan = delivering();
        plan.moved(SplitDivider.Want.SHRINK, SplitDivider.Pane.OURS, 50, 30, 0);
        String line = plan.verify(50, 50, SplitDivider.VERIFY_MS);
        assertEquals("divider didn't move (top or left half 50%, wanted 30%): left alone for the rest of this dash",
                line);
        plan.delivery(false);
        plan.delivery(true);
        assertEquals(SplitDivider.Want.NONE, plan.want());
    }

    @Test
    public void theSplitEndingForgetsTheShrinkAndAMoveStillToCheckStops() {
        SplitDivider plan = delivering();
        shrunk(plan, 0);
        assertNull(plan.splitEnded());
        assertEquals("entering split again may shrink again", SplitDivider.Want.SHRINK, plan.want());
        plan.moved(SplitDivider.Want.SHRINK, SplitDivider.Pane.OURS, 50, 30, 10_000);
        assertEquals("split ended after the divider moved: left alone for the rest of this dash", plan.splitEnded());
        assertEquals(SplitDivider.Want.NONE, plan.want());
    }

    @Test
    public void theCapsHoldTwoADeliveryAndTwelveADash() {
        SplitDivider plan = delivering();
        long at = 0;
        for (int delivery = 0; delivery < 6; delivery++) {
            plan.delivery(true);
            shrunk(plan, at);
            plan.delivery(false);
            plan.moved(SplitDivider.Want.RESTORE, SplitDivider.Pane.OURS, 30, 50, at + 5_000);
            assertNull(plan.verify(50, 50, at + 5_000 + SplitDivider.VERIFY_MS));
            at += 60_000;
        }
        plan.delivery(true);
        assertEquals(SplitDivider.Want.SHRINK, plan.want());
        assertEquals("divider: 12 moves this dash already; left as it is", plan.capped());
        assertEquals("said once", "", plan.capped());

        SplitDivider twice = delivering();
        shrunk(twice, 0);
        assertNull(twice.splitEnded());
        assertEquals(SplitDivider.Want.SHRINK, twice.want());
        assertNull(twice.capped());
        twice.moved(SplitDivider.Want.SHRINK, SplitDivider.Pane.OURS, 50, 30, 10_000);
        assertNull(twice.verify(30, 30, 10_000 + SplitDivider.VERIFY_MS));
        twice.delivery(false);
        assertEquals(SplitDivider.Want.RESTORE, twice.want());
        assertEquals("divider: 2 moves this delivery already; left as it is", twice.capped());
    }
}
