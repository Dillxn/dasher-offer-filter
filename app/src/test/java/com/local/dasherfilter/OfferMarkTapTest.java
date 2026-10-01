package com.local.dasherfilter;

import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Button;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tapping an offer on the constellation through the real page: a tap on one of its marks, or inside its polygon,
 * opens its ticket exactly as a tap on its building in the skyline does (both show it chosen, and its shape stands out
 * while the ticket is open); the knobs and the buttons keep their touches, a drag opens nothing, and a tap on empty sky
 * still opens the minimums. Screen readers reach each marked offer and open it. The rules: $5 pay, $3.00 a mile,
 * $0.20 a minute, $1.00 a stop, at most 3 stops; the newest offer ($24.00 for 6 mi, 25 min, 2 stops) passes and is the
 * chart's example, the older one ($9.75 for 3.3 mi, 18 min, 2 stops) is declined for its miles.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class OfferMarkTapTest extends AndroidAdapterTestBase {
    private static final FilterSettings RULES = new FilterSettings(true, 500, 300, 20, 100, 3);
    /** The newest offer is mark 0, the older one mark 1. */
    private static final int NEWEST = 0;
    private static final int OLDER = 1;

    @Test
    public void aTapOnAnOffersMarkOpensItsTicketAndBothChartsShowItChosen() {
        seed(RULES);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            DecisionChartView chart = findChart(content);
            assertTrue(star.backdrop());
            assertEquals("the skyline shows the newest first", Integer.valueOf(2400),
                    chart.selectedEntry().facts.payCents);
            assertEquals(-1, star.openedOffer());

            // The older offer's mark clearest of the knobs and the other offer's marks.
            float[] mark = clearestMark(star, OLDER);
            assertTrue("a mark well clear of every knob and other mark", clearance(star, mark, OLDER)
                    > new Ui(app).dp(30));
            touch(content, star, MotionEvent.ACTION_DOWN, mark);
            assertEquals("a finger on it picks it out lightly", OLDER, star.pressedOffer());
            touch(content, star, MotionEvent.ACTION_UP, mark);

            assertEquals("chosen on the skyline, as a tap on its building does", Integer.valueOf(975),
                    chart.selectedEntry().facts.payCents);
            assertNotNull("its ticket is open", shownTextContaining(content, "Read: $9.75"));
            assertNotNull(shownTextContaining(content, "Below your per-mile rate"));
            assertEquals("its shape and marks stand out while the ticket is open", OLDER, star.openedOffer());
            assertEquals(-1, star.pressedOffer());
            assertFalse("no page opens", settingsShown(content));

            // Closed, the skyline keeps it chosen and the constellation lets it go.
            activity.get().onBackPressed();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(shownTextContaining(content, "Read: $9.75"));
            assertEquals(-1, star.openedOffer());
            assertEquals(Integer.valueOf(975), chart.selectedEntry().facts.payCents);

            // The newest offer's mark opens the newest, and the skyline follows.
            touch(content, star, MotionEvent.ACTION_DOWN, clearestMark(star, NEWEST));
            touch(content, star, MotionEvent.ACTION_UP, clearestMark(star, NEWEST));
            assertEquals(Integer.valueOf(2400), chart.selectedEntry().facts.payCents);
            assertNotNull(shownTextContaining(content, "Read: $24.00"));
            assertEquals(NEWEST, star.openedOffer());
        }
    }

    @Test
    public void aTapInsideTwoOverlappingShapesOpensTheNewest() {
        seed(RULES);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            DecisionChartView chart = findChart(content);
            // The older offer chosen on the skyline (its ticket closed): strictly, its shape is not drawn on top.
            chart.select(0);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(Integer.valueOf(975), chart.selectedEntry().facts.payCents);

            float[] inBoth = insideBoth(star);
            assertNotNull("a place inside both shapes, clear of every mark and knob", inBoth);
            tapThrough(content, star, inBoth);

            assertEquals("the newest shape is on top", Integer.valueOf(2400), chart.selectedEntry().facts.payCents);
            assertNotNull(shownTextContaining(content, "Read: $24.00"));
            assertEquals(NEWEST, star.openedOffer());
            assertFalse(settingsShown(content));
        }
    }

    @Test
    public void byAreaTheChosenOffersShapeIsOnTopAndATapInsideItOpensThatOne() {
        seed(RULES.withScoreByArea(true));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            DecisionChartView chart = findChart(content);
            assertTrue(star.byArea());
            // By area the chosen offer's polygon stands out over the rest, so a touch finds it first.
            chart.select(0);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            float[] inBoth = insideBoth(star);
            assertNotNull("a place inside both shapes, clear of every mark and knob", inBoth);
            tapThrough(content, star, inBoth);
            assertEquals(Integer.valueOf(975), chart.selectedEntry().facts.payCents);
            assertNotNull(shownTextContaining(content, "Read: $9.75"));
            assertEquals(OLDER, star.openedOffer());
        }
    }

    @Test
    public void aTapOnEmptySkyOpensNothing() {
        seed(RULES);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            DecisionChartView chart = findChart(content);
            // On the level line, inside the circle but outside every offer's shape, far from the marks.
            float[] empty = {star.skyX() + star.skyRadius() * 0.86f, star.skyY()};
            assertTrue("inside the page", empty[0] < star.getWidth() - new Ui(app).dp(8));
            for (int m = 0; m < 2; m++) assertFalse("outside shape " + m, inside(star.offerShape(m), empty));
            assertTrue(clearance(star, empty, -1) > new Ui(app).dp(30));

            tapThrough(content, star, empty);
            assertFalse("no page opens: the minimums are all here", settingsShown(content));
            assertNull("no ticket", shownTextContaining(content, "Read: $"));
            assertEquals(-1, star.openedOffer());
            assertEquals(Integer.valueOf(2400), chart.selectedEntry().facts.payCents);
        }
    }

    @Test
    public void aKnobKeepsItsTouchesAndADragOpensNothing() {
        seed(RULES);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            DecisionChartView chart = findChart(content);
            Ui ui = new Ui(app);
            // The per-mile knob ($3.00 a mile asks $18.00 of 6 mi) sits on the older offer's per-mile mark ($17.73).
            float[] knob = star.knobAt(1);
            float[] mark = star.markAt(OLDER, 1);
            assertTrue("the mark is within the knob's reach", Math.hypot(knob[0] - mark[0], knob[1] - mark[1])
                    < ui.dp(24));

            // Dragged out along its spoke, it saves a higher minimum and opens nothing.
            float[] to = alongSpoke(knob, 1, ui.dp(50), 0);
            dragKnob(content, star, knob, to, () -> assertEquals("nothing picked out under a knob", -1,
                    star.pressedOffer()));
            assertTrue("saved: " + FilterStore.load(app).perMileCents, FilterStore.load(app).perMileCents > 300);
            assertNull("no ticket", shownTextContaining(content, "Read: $"));
            assertEquals(-1, star.openedOffer());
            assertEquals(Integer.valueOf(2400), chart.selectedEntry().facts.payCents);
            assertFalse(settingsShown(content));

            // A drag that sets out from an offer's mark (on no knob) is not a tap either.
            settleSky(content);
            float[] from = clearestMark(star, OLDER);
            touch(content, star, MotionEvent.ACTION_DOWN, from);
            assertEquals(OLDER, star.pressedOffer());
            float[] across = {from[0] + ui.dp(40), from[1] + ui.dp(30)};
            touch(content, star, MotionEvent.ACTION_MOVE, across);
            assertEquals("a drag lets the offer go", -1, star.pressedOffer());
            touch(content, star, MotionEvent.ACTION_UP, across);
            assertNull(shownTextContaining(content, "Read: $"));
            assertEquals(Integer.valueOf(2400), chart.selectedEntry().facts.payCents);
            assertFalse(settingsShown(content));

            // A tap on the knob, though the mark is within reach, is the knob's: it opens nothing.
            settleSky(content);
            float[] now = star.knobAt(1);
            tapThrough(content, star, now);
            assertFalse(settingsShown(content));
            assertNull(shownTextContaining(content, "Read: $"));
            assertEquals(-1, star.openedOffer());
        }
    }

    @Test
    public void screenReadersReachEachMarkedOfferAfterTheControlsAndOpenItsTicket() throws Exception {
        seed(RULES);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            DecisionChartView chart = findChart(content);
            AccessibilityNodeProvider nodes = star.getAccessibilityNodeProvider();
            assertNotNull(nodes);
            AccessibilityNodeInfo host = nodes.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID);
            assertEquals("the knobs, the max stops badge, the toggles, then the offers newest first",
                    Arrays.asList(0, 1, 2, 3, MinimumsStarView.STOPS_ID, MinimumsStarView.ADAPTIVE_ID,
                            MinimumsStarView.SCORE_ID, MinimumsStarView.OFFER_ID, MinimumsStarView.OFFER_ID + 1),
                    childIds(host));

            AccessibilityNodeInfo older = nodes.createAccessibilityNodeInfo(MinimumsStarView.OFFER_ID + OLDER);
            String said = older.getContentDescription().toString();
            assertTrue(said, said.startsWith("Offer $9.75, 3.3 mi, 18 min, 2 stops, declined"));
            assertEquals(Button.class.getName(), older.getClassName().toString());
            assertTrue(older.isClickable());
            assertFalse(older.isSelected());
            AccessibilityNodeInfo.AccessibilityAction click = null;
            for (AccessibilityNodeInfo.AccessibilityAction action : older.getActionList()) {
                if (action.getId() == AccessibilityNodeInfo.ACTION_CLICK) click = action;
            }
            assertNotNull("double-tap", click);
            assertEquals("show details", String.valueOf(click.getLabel()));
            String newest = nodes.createAccessibilityNodeInfo(MinimumsStarView.OFFER_ID + NEWEST)
                    .getContentDescription().toString();
            assertTrue(newest, newest.startsWith("Offer $24.00, 6 mi, 25 min, 2 stops, passed"));
            assertNull("no more offers than marked",
                    nodes.createAccessibilityNodeInfo(MinimumsStarView.OFFER_ID + 2));

            assertTrue(nodes.performAction(MinimumsStarView.OFFER_ID + OLDER, AccessibilityNodeInfo.ACTION_CLICK,
                    null));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(Integer.valueOf(975), chart.selectedEntry().facts.payCents);
            assertNotNull(shownTextContaining(content, "Read: $9.75"));
            assertEquals(OLDER, star.openedOffer());
            assertTrue("its node says it is the one open",
                    nodes.createAccessibilityNodeInfo(MinimumsStarView.OFFER_ID + OLDER).isSelected());
            assertFalse(settingsShown(content));
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void inAShortSplitWithAnotherAppTheHeaderChartOnlyMovesIntoTheSky() {
        seed(RULES);
        OfferFilterService.sawDasherBeside(0);
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            DecisionChartView chart = findChart(content);
            assertTrue("up in the header", star.beside());
            assertNull("no offers for screen readers there", star.getAccessibilityNodeProvider());
            assertNull(star.markAt(OLDER, 0));
            // Its middle lies inside both offers' shapes; a tap there opens no offer: it spreads the chart across
            // the sky, where its knobs are.
            tap((ViewGroup) star.getParent(), star.getLeft() + star.getWidth() / 2f,
                    star.getTop() + star.getHeight() / 2f);
            assertFalse(star.beside());
            assertFalse(settingsShown(content));
            assertEquals(Integer.valueOf(2400), chart.selectedEntry().facts.payCents);
            assertEquals(-1, star.openedOffer());
            assertNull(shownTextContaining(content, "Read: $"));
        }
    }

    // ---- Helpers ----

    /** The rules, an older declined offer three minutes ago and the newest passing one a minute ago. */
    private void seed(FilterSettings rules) {
        FilterStore.save(app, rules);
        record(rules, 180_000, 975, 3.3, 18, 2);
        record(rules, 60_000, 2400, 6.0, 25, 2);
    }

    private void record(FilterSettings rules, long ago, int pay, double miles, int minutes, int stops) {
        OfferSnapshot facts = new OfferSnapshot(pay, miles, minutes, stops);
        OfferRule.Decision decision = OfferRule.evaluate(facts, rules);
        DecisionLog.record(app, DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, facts, decision,
                decision.result == OfferRule.Result.DECLINE ? DecisionLog.Action.DECLINE_TAPPED
                        : DecisionLog.Action.PASSES, true,
                Arrays.asList(DecisionLog.money(pay), stops + " stops (" + miles + " mi) • " + minutes + " min"))
                .withTime(System.currentTimeMillis() - ago));
    }

    /** Offer {@code m}'s mark farthest from every knob and every other offer's marks. */
    private static float[] clearestMark(MinimumsStarView star, int m) {
        float[] best = null;
        double clearest = -1;
        for (int axis = 0; axis < 4; axis++) {
            float[] at = star.markAt(m, axis);
            if (at == null) continue;
            double clear = clearance(star, at, m);
            if (clear > clearest) {
                clearest = clear;
                best = at;
            }
        }
        assertNotNull("offer " + m + " is marked", best);
        return best;
    }

    /** How far {@code at} is from the nearest knob, button or mark (offer {@code skip}'s own marks aside). */
    private static double clearance(MinimumsStarView star, float[] at, int skip) {
        double clear = Double.MAX_VALUE;
        for (int axis = 0; axis < 4; axis++) {
            float[] knob = star.knobAt(axis);
            if (knob != null) clear = Math.min(clear, Math.hypot(knob[0] - at[0], knob[1] - at[1]));
            for (int m = 0; m < 2; m++) {
                float[] mark = m == skip ? null : star.markAt(m, axis);
                if (mark != null) clear = Math.min(clear, Math.hypot(mark[0] - at[0], mark[1] - at[1]));
            }
        }
        android.graphics.RectF toggle = star.scoreToggleBox();
        if (toggle != null) {
            clear = Math.min(clear, Math.hypot(toggle.centerX() - at[0], toggle.centerY() - at[1]) - toggle.width());
        }
        return clear;
    }

    /**
     * A place inside both offers' shapes, above the middle (clear of the lines along the sky's bottom and of the
     * mascot on its left), at least 30 dp from every mark, knob and button; null where there is none.
     */
    private float[] insideBoth(MinimumsStarView star) {
        Ui ui = new Ui(app);
        List<float[]> older = star.offerShape(OLDER);
        List<float[]> newest = star.offerShape(NEWEST);
        float[] best = null;
        double clearest = ui.dp(30);
        for (float dx = -star.skyRadius() * 0.2f; dx <= star.skyRadius() * 0.6f; dx += ui.dp(4)) {
            for (float dy = -ui.dp(4); dy >= -star.skyRadius() * 0.6f; dy -= ui.dp(4)) {
                float[] at = {star.skyX() + dx, star.skyY() + dy};
                if (!inside(older, at) || !inside(newest, at)) continue;
                double clear = clearance(star, at, -1);
                if (clear > clearest) {
                    clearest = clear;
                    best = at;
                }
            }
        }
        return best;
    }

    private static boolean inside(List<float[]> polygon, float[] at) {
        boolean in = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            float[] a = polygon.get(i);
            float[] b = polygon.get(j);
            if ((a[1] > at[1]) != (b[1] > at[1])
                    && at[0] < (b[0] - a[0]) * (at[1] - a[1]) / (b[1] - a[1]) + a[0]) {
                in = !in;
            }
        }
        return in;
    }

    /** A finger at {@code at} (the star's pixels), sent to the page's root as Android would. */
    private static void touch(View content, MinimumsStarView star, int action, float[] at) {
        int[] starAt = new int[2];
        int[] contentAt = new int[2];
        star.getLocationInWindow(starAt);
        content.getLocationInWindow(contentAt);
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, at[0] + starAt[0] - contentAt[0],
                at[1] + starAt[1] - contentAt[1], 0);
        content.dispatchTouchEvent(event);
        event.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void tapThrough(View content, MinimumsStarView star, float[] at) {
        touch(content, star, MotionEvent.ACTION_DOWN, at);
        touch(content, star, MotionEvent.ACTION_UP, at);
    }

    /** The virtual ids of a node's children, in order. */
    private static List<Integer> childIds(AccessibilityNodeInfo info) throws Exception {
        java.lang.reflect.Method childId = AccessibilityNodeInfo.class.getMethod("getChildId", int.class);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < info.getChildCount(); i++) {
            long id = (Long) childId.invoke(info, i);
            ids.add((int) (id >> 32));
        }
        return ids;
    }
}
