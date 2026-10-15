package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The split divider's labels, the choice among them, the safe moment and what a split and a dash allow (0.6.21, the
 * owner: "whenever one half has nav open make it 70% height / whenever one half has nav open the other shouldn't").
 * Plain JUnit: {@link SplitDivider} is pure. The adapter is {@link SplitDividerAdapterTest}.
 */
public class SplitDividerTest {
    /** Stock Android up to 16 QPR1, portrait. */
    private static final List<String> QPR1 = Arrays.asList("Top full screen", "Top 70%", "Top 50%", "Top 30%",
            "Bottom full screen", "Swap top app with bottom");
    /** Android 16 QPR2 and later. */
    private static final List<String> QPR2 = Arrays.asList("Make top app full screen", "10% top, 90% bottom",
            "30% top, 70% bottom", "50% top, 50% bottom", "70% top, 30% bottom", "90% top, 10% bottom",
            "Make bottom app full screen", "Swap top app with bottom app, keep current split");
    /** The owner's Samsung on Android 16 (his 0.6.11 report, 13 October 2026). */
    private static final List<String> SAMSUNG = Arrays.asList("Make top app full screen", "70% top, 30% bottom",
            "50% top, 50% bottom", "30% top, 70% bottom", "Make bottom app full screen",
            "Swap top app with bottom app, keep current split");

    private static final SplitDivider.Pane MAP = SplitDivider.Pane.MAP;
    private static final SplitDivider.Pane DASHER = SplitDivider.Pane.DASHER;
    private static final SplitDivider.Pane OURS = SplitDivider.Pane.OURS;
    private static final SplitDivider.Pane OTHER = SplitDivider.Pane.OTHER;

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
        assertEquals("a no-break space, as some languages write it", 70, SplitDivider.share("Haut 70 %"));
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

    // ---- The chooser ----

    @Test
    public void theMapsHalfGetsTheStopNearestSeventy() {
        assertEquals("Top 70%", SplitDivider.forMap(targets(QPR1), true).label);
        assertEquals("Top 30%", SplitDivider.forMap(targets(QPR1), false).label);
        assertEquals("70% top, 30% bottom", SplitDivider.forMap(targets(QPR2), true).label);
        assertEquals("30% top, 70% bottom", SplitDivider.forMap(targets(QPR2), false).label);
        assertEquals("70% top, 30% bottom", SplitDivider.forMap(targets(SAMSUNG), true).label);
        assertEquals("30% top, 70% bottom", SplitDivider.forMap(targets(SAMSUNG), false).label);
        assertEquals(103, SplitDivider.forMap(targets(QPR1), false).id);
        // 10% and 90% parse, but 90% is further from 70 than 70% is.
        assertEquals(10, SplitDivider.share("10% top, 90% bottom"));
        assertEquals(70, SplitDivider.forMap(targets(QPR2), true).share);
        assertEquals("of two equally near, the larger", 80,
                SplitDivider.forMap(targets(Arrays.asList("Top 60%", "Top 80%")), true).share);
        assertEquals("never less than half for the map", 50,
                SplitDivider.forMap(targets(Arrays.asList("Top 40%", "Top 50%")), true).share);
        assertNull(SplitDivider.forMap(targets(Collections.singletonList("Top 40%")), true));
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
        assertNull(SplitDivider.forMap(none, true));
        assertNull(SplitDivider.forMap(none, false));
        assertNull(SplitDivider.forRestore(none, 50));
    }

    @Test
    public void theHalfBesideAMapIsMadeSmallAndNothingElse() {
        assertEquals(DASHER, SplitDivider.shrinks(MAP, DASHER));
        assertEquals(DASHER, SplitDivider.shrinks(DASHER, MAP));
        assertEquals(OURS, SplitDivider.shrinks(MAP, OURS));
        assertEquals(OURS, SplitDivider.shrinks(OURS, MAP));
        // 0.6.21: Dasher beside Offer Filter is left as it is (0.6.11 to 0.6.20 shrank Offer Filter's half).
        assertNull(SplitDivider.shrinks(OURS, DASHER));
        assertNull(SplitDivider.shrinks(DASHER, OURS));
        assertNull(SplitDivider.shrinks(DASHER, OTHER));
        assertNull(SplitDivider.shrinks(OURS, OTHER));
        assertNull(SplitDivider.shrinks(MAP, OTHER));
        assertNull(SplitDivider.shrinks(MAP, MAP));
        assertNull(SplitDivider.shrinks(OURS, null));
        assertNull(SplitDivider.shrinks(null, MAP));
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

    // ---- What a split and a dash allow ----

    /** A plan in a dash that has seen {@code first} over {@code second} at {@code share} since 0, settled at 1 s. */
    private static SplitDivider seen(SplitDivider.Pane first, SplitDivider.Pane second, int share) {
        SplitDivider plan = new SplitDivider();
        plan.dash(1);
        assertNull(plan.look(first, second, share, 0));
        assertEquals("a new pair settles first", SplitDivider.Want.NONE, plan.want(0));
        assertNull(plan.look(first, second, share, SplitDivider.SETTLE_MS));
        return plan;
    }

    /** The map's half (top) moved from 50% to 70% at {@code at}, verified 1.5 s later. */
    private static void placed(SplitDivider plan, long at) {
        assertEquals(SplitDivider.Want.SHRINK, plan.want(at));
        assertNull(plan.capped());
        plan.moved(SplitDivider.Want.SHRINK, 50, 70, at);
        assertEquals("checked first", SplitDivider.Want.NONE, plan.want(at));
        assertNull("not yet", plan.verify(50, at + 1_000));
        assertNull(plan.verify(70, at + SplitDivider.VERIFY_MS));
        assertNull(plan.look(MAP, DASHER, 70, at + SplitDivider.VERIFY_MS));
    }

    @Test
    public void aMapBesideDasherWantsItsHalfAtOnceAndTheDividerBackWhenItLeaves() {
        SplitDivider plan = seen(MAP, DASHER, 50);
        long at = SplitDivider.SETTLE_MS;
        placed(plan, at);
        assertEquals("still beside the map: the mover finds no room", SplitDivider.Want.SHRINK, plan.want(at + 2_000));
        assertFalse(SplitDivider.roomFor(30, 30));
        // Offer Filter takes the map's half: a new pair, which settles, then the divider goes back.
        assertNull(plan.look(OURS, DASHER, 70, at + 5_000));
        assertEquals(SplitDivider.Want.NONE, plan.want(at + 5_000));
        assertNull("another app taking a half is not the user's move", plan.look(OURS, DASHER, 70, at + 6_000));
        assertEquals(SplitDivider.Want.RESTORE, plan.want(at + 6_000));
        assertEquals(50, plan.firstBefore());
        plan.moved(SplitDivider.Want.RESTORE, 70, 50, at + 6_000);
        assertNull(plan.verify(50, at + 6_000 + SplitDivider.VERIFY_MS));
        assertEquals(SplitDivider.Want.NONE, plan.want(at + 8_000));
        assertEquals(-1, plan.firstBefore());
        // The map again: again.
        assertNull(plan.look(MAP, DASHER, 50, at + 20_000));
        assertNull(plan.look(MAP, DASHER, 50, at + 21_000));
        assertEquals(SplitDivider.Want.SHRINK, plan.want(at + 21_000));
    }

    @Test
    public void besideOfferFilterOrAnotherAppNothingIsWanted() {
        assertEquals(SplitDivider.Want.NONE, seen(OURS, DASHER, 50).want(SplitDivider.SETTLE_MS));
        assertEquals(SplitDivider.Want.NONE, seen(MAP, OTHER, 50).want(SplitDivider.SETTLE_MS));
        assertEquals(SplitDivider.Want.SHRINK, seen(OURS, MAP, 50).want(SplitDivider.SETTLE_MS));
        SplitDivider unknown = new SplitDivider();
        assertNull(unknown.look(MAP, null, 50, 0));
        assertEquals("a half not identified", SplitDivider.Want.NONE, unknown.want(5_000));
    }

    @Test
    public void aHalfTheUserSizedIsSaidOnceAndLeftAlone() {
        assertTrue("more than 8 points over its target", SplitDivider.roomFor(39, 30));
        assertFalse("within 8 points", SplitDivider.roomFor(38, 30));
        SplitDivider plan = seen(MAP, DASHER, 68);
        assertTrue(plan.sayAlready());
        assertFalse("said once per pair", plan.sayAlready());
        assertEquals("still wanted: the mover finds no room", SplitDivider.Want.SHRINK, plan.want(5_000));
        assertNull(plan.look(OURS, DASHER, 68, 6_000));
        assertNull(plan.look(OURS, DASHER, 68, 7_000));
        assertEquals("nothing to put back", SplitDivider.Want.NONE, plan.want(7_000));
    }

    @Test
    public void theUsersMoveStopsItForTheDash() {
        SplitDivider plan = seen(MAP, DASHER, 50);
        placed(plan, SplitDivider.SETTLE_MS);
        assertNull("3 points is not a move", plan.look(MAP, DASHER, 67, 5_000));
        assertEquals("you moved the divider: left alone for the rest of this dash",
                plan.look(MAP, DASHER, 60, 6_000));
        assertEquals(SplitDivider.Want.NONE, plan.want(7_000));
        assertNull(plan.look(OURS, DASHER, 60, 8_000));
        assertNull(plan.look(OURS, DASHER, 60, 9_000));
        assertEquals("no restore over the user's move", SplitDivider.Want.NONE, plan.want(9_000));
        plan.dash(2);
        assertNull(plan.look(MAP, DASHER, 60, 10_000));
        assertNull(plan.look(MAP, DASHER, 60, 11_000));
        assertEquals("the next dash starts over", SplitDivider.Want.SHRINK, plan.want(11_000));
    }

    @Test
    public void theUsersMoveBeforeOffersFilterMovedStopsItToo() {
        SplitDivider plan = seen(MAP, DASHER, 50);
        assertEquals("you moved the divider: left alone for the rest of this dash",
                plan.look(MAP, DASHER, 40, 3_000));
        assertEquals(SplitDivider.Want.NONE, plan.want(4_000));
    }

    @Test
    public void besideOfferFilterTheUsersMovesAreHisOwnAndStopNothing() {
        SplitDivider plan = seen(OURS, DASHER, 50);
        assertNull(plan.look(OURS, DASHER, 30, 3_000));
        assertNull(plan.look(OURS, DASHER, 60, 4_000));
        assertNull(plan.stoppedFor());
        assertNull(plan.look(MAP, DASHER, 60, 5_000));
        assertNull(plan.look(MAP, DASHER, 60, 6_000));
        assertEquals(SplitDivider.Want.SHRINK, plan.want(6_000));
    }

    @Test
    public void aDividerThatDoesNotMoveStopsItForTheDash() {
        SplitDivider plan = seen(MAP, DASHER, 50);
        plan.moved(SplitDivider.Want.SHRINK, 50, 70, SplitDivider.SETTLE_MS);
        String line = plan.verify(50, SplitDivider.SETTLE_MS + SplitDivider.VERIFY_MS);
        assertEquals("divider didn't move (top or left half 50%, wanted 70%): left alone for the rest of this dash",
                line);
        assertEquals(SplitDivider.Want.NONE, plan.want(10_000));
    }

    @Test
    public void theSplitEndingForgetsTheMoveAndAMoveStillToCheckStops() {
        SplitDivider plan = seen(MAP, DASHER, 50);
        placed(plan, SplitDivider.SETTLE_MS);
        assertNull(plan.splitEnded());
        assertEquals(-1, plan.firstBefore());
        assertNull(plan.look(MAP, DASHER, 50, 10_000));
        assertNull(plan.look(MAP, DASHER, 50, 11_000));
        assertEquals("entering split again may move it again", SplitDivider.Want.SHRINK, plan.want(11_000));
        plan.moved(SplitDivider.Want.SHRINK, 50, 70, 11_000);
        assertEquals("split ended after the divider moved: left alone for the rest of this dash", plan.splitEnded());
        assertEquals(SplitDivider.Want.NONE, plan.want(20_000));
    }

    @Test
    public void theCapHoldsTwelveADash() {
        SplitDivider plan = seen(MAP, DASHER, 50);
        long at = SplitDivider.SETTLE_MS;
        for (int i = 0; i < SplitDivider.MOVES_PER_DASH / 2; i++) {
            placed(plan, at);
            assertNull(plan.look(OURS, DASHER, 70, at + 3_000));
            assertNull(plan.look(OURS, DASHER, 70, at + 4_000));
            assertEquals(SplitDivider.Want.RESTORE, plan.want(at + 4_000));
            plan.moved(SplitDivider.Want.RESTORE, 70, 50, at + 4_000);
            assertNull(plan.verify(50, at + 4_000 + SplitDivider.VERIFY_MS));
            assertNull(plan.look(MAP, DASHER, 50, at + 8_000));
            at += 9_000;
            assertNull(plan.look(MAP, DASHER, 50, at));
        }
        assertEquals(SplitDivider.Want.SHRINK, plan.want(at));
        assertEquals("divider: 12 moves this dash already; left as it is", plan.capped());
        assertEquals("said once", "", plan.capped());
        plan.dash(2);
        assertNull("a new dash", plan.capped());
    }
}
