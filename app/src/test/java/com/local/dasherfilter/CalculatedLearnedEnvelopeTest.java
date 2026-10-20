package com.local.dasherfilter;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.*;

/** The actual green outline covers every calculated learned minimum, including held and animated saved knobs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36}, qualifiers = "w800dp-h650dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class CalculatedLearnedEnvelopeTest extends AndroidAdapterTestBase {
    private static final OfferSnapshot COMPARISON = new OfferSnapshot(null, 5.0, 20, 2);
    private static final FilterSettings SAVED = FilterSettings.of(true, 671, 195, 56, 0);
    private static final AcceptedFloors BESTS = new AcceptedFloors(800, 2, 900, 2.0, 450, 10);
    private static final float[] CENTER = {400, 300};

    private MinimumsStarView radar(FilterSettings rules) {
        MinimumsStarView star = new MinimumsStarView(app, new Ui(app));
        star.layout(0, 0, 800, 650);
        star.show(rules, COMPARISON, Collections.emptyList());
        star.compose(CENTER[0], CENTER[1], 220);
        star.setChanges((axis, value) -> {
            int[] rates = rules.minimums();
            rates[axis] = value;
            star.show(rules.withMinimums(rates[0], rates[1], rates[2]), COMPARISON, Collections.emptyList());
        });
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        draw(star);
        return star;
    }

    private static void draw(MinimumsStarView star) {
        Bitmap bitmap = Bitmap.createBitmap(800, 650, Bitmap.Config.ARGB_8888);
        try { star.draw(new Canvas(bitmap)); }
        finally { bitmap.recycle(); }
    }

    private static void assertEnclosesSaved(MinimumsStarView star) {
        draw(star);
        List<float[]> green = star.drawnLearnedShape();
        assertEquals(3, green.size());
        for (int i = 0; i < MinimumsStarView.SPOKES.length; i++) {
            int axis = MinimumsStarView.SPOKES[i];
            if (Double.isNaN(star.setAsks(axis))) continue;
            assertTrue("green covers the currently drawn saved knob on axis " + axis,
                    along(CENTER, axis, green.get(i)) + 0.001 >= along(CENTER, axis, star.knobAt(axis)));
        }
    }

    @Test public void differentAxesUseTheirHigherSavedOrLearnedMinimum() {
        FilterSettings rules = SAVED.withAcceptanceLearning(true).withAccepted(BESTS);
        MinimumsStarView star = radar(rules);
        assertEquals(1342, star.learnedAsks(AreaScore.PAY), 0);
        assertEquals(2250, star.learnedAsks(AreaScore.MILE), 0);
        assertEquals(1120, star.learnedAsks(AreaScore.MINUTE), 0);
        assertEnclosesSaved(star);
        assertArrayEquals(SAVED.minimums(), rules.minimums());
        assertSame("a display is not fabricated learned evidence", BESTS, rules.accepted);
    }

    @Test public void theOwnersConfirmedShortAcceptanceRaisesThePersistedMileageHighAndTriangle() {
        FilterStore.save(app, SAVED);
        FilterStore.setAcceptanceLearning(app, true);
        OfferSnapshot offer = new OfferSnapshot(360, 0.8, 10, 2);
        assertEquals(FilterStore.AcceptedLesson.RAISED, FilterStore.recordConfirmedManualAcceptance(app,
                FilterStore.captureLearning(app, offer), offer));
        FilterSettings rules = FilterStore.load(app);
        assertEquals(360, rules.accepted.milePay);
        assertEquals(0.8, rules.accepted.miles, 0);
        MinimumsStarView star = radar(rules);
        assertEquals("$4.50/mi over the comparison's 5 miles", 2250, star.learnedAsks(AreaScore.MILE), 0);
        assertEquals(1342, star.learnedAsks(AreaScore.PAY), 0);
        assertEquals(1120, star.learnedAsks(AreaScore.MINUTE), 0);
        assertEnclosesSaved(star);
    }

    @Test public void noLearnedRecordStillCoversSavedAxesAndOffRetainsOnlyObservedBests() {
        MinimumsStarView star = radar(SAVED.withAcceptanceLearning(true));
        for (int axis : MinimumsStarView.SPOKES) assertEquals(star.setAsks(axis), star.learnedAsks(axis), 0);
        assertEnclosesSaved(star);
        star.show(SAVED.withAccepted(BESTS).withAcceptanceLearning(false), COMPARISON, Collections.emptyList());
        assertEquals(801, star.learnedAsks(AreaScore.PAY), 0);
        assertEquals(2250, star.learnedAsks(AreaScore.MILE), 0);
        assertEquals(900, star.learnedAsks(AreaScore.MINUTE), 0);
        assertTrue(star.getContentDescription().toString().contains("stored but off"));
    }

    @Test public void aLearnedOnlyAxisAppearsButAnEntirelyUnsetAxisIsNotInvented() {
        FilterSettings rules = FilterSettings.of(true, 671, 0, 0, 0).withAcceptanceLearning(true)
                .withAccepted(new AcceptedFloors(0, 900, 2.0, 0, 0));
        MinimumsStarView star = radar(rules);
        assertEquals(1342, star.learnedAsks(AreaScore.PAY), 0);
        assertEquals(2250, star.learnedAsks(AreaScore.MILE), 0);
        assertTrue(Double.isNaN(star.learnedAsks(AreaScore.MINUTE)));
        assertTrue(Double.isNaN(star.setAsks(AreaScore.MILE)));
    }

    @Test public void autopilotCanMoveInsideTheLearningEnvelopeWithoutChangingIt() {
        FilterSettings rules = SAVED.withAcceptanceLearning(true).withAccepted(BESTS).withAutopilot(true, 0);
        rules = rules.withAutopilotAxes(rules.autopilotAxes.withOffer(rules,
                new OfferSnapshot(500, 5.0, 20, 2), 1000));
        MinimumsStarView star = radar(rules);
        assertEquals(1342, star.learnedAsks(AreaScore.PAY), 0);
        assertEquals(2250, star.learnedAsks(AreaScore.MILE), 0);
        assertEquals(1120, star.learnedAsks(AreaScore.MINUTE), 0);
        for (int axis : MinimumsStarView.SPOKES) assertEquals(500, star.barAsks(axis), 0.00001);
        assertEnclosesSaved(star);
    }

    @Test public void switchingLearningOnEnclosesBlueOnEveryAnimationFrame() throws Exception {
        ValueAnimator.class.getMethod("setDurationScale", float.class).invoke(null, 1f);
        FilterSettings off = SAVED.withAccepted(BESTS).withAcceptanceLearning(false);
        MinimumsStarView star = radar(off);
        star.show(off.withAcceptanceLearning(true), COMPARISON, Collections.emptyList());
        for (int i = 0; i < 10; i++) {
            assertEnclosesSaved(star);
            ShadowSystemClock.advanceBy(Duration.ofMillis(80));
        }
    }

    @Test public void draggingSavedMinimumsEitherWayPreviewsTheEnvelopeAndReleasesWithoutDroppingInside()
            throws Exception {
        ValueAnimator.class.getMethod("setDurationScale", float.class).invoke(null, 1f);
        for (float movement : new float[] {35, -35}) {
            MinimumsStarView star = radar(SAVED.withAcceptanceLearning(true).withAccepted(BESTS));
            float[] from = star.knobAt(AreaScore.PAY);
            float[] to = alongSpoke(from, AreaScore.PAY, movement, 0);
            double previous = along(CENTER, AreaScore.PAY, star.drawnLearnedShape().get(0));
            dragKnob(star, star, from, to, () -> {
                assertEnclosesSaved(star);
                double preview = along(CENTER, AreaScore.PAY, star.drawnLearnedShape().get(0));
                assertTrue("the envelope follows the preview in either direction", movement > 0
                        ? preview > previous : preview < previous);
            });
            for (int frame = 0; frame < 10; frame++) {
                assertEnclosesSaved(star);
                ShadowSystemClock.advanceBy(Duration.ofMillis(80));
            }
        }
    }

    @Test public void cancellingADragRestoresTheSavedEnvelopeAndTurningAnAxisOffLeavesItsLearnedHigh() {
        MinimumsStarView star = radar(SAVED.withAcceptanceLearning(true).withAccepted(BESTS));
        float[] from = star.knobAt(AreaScore.PAY);
        float[] to = alongSpoke(from, AreaScore.PAY, 35, 0);
        long now = android.os.SystemClock.uptimeMillis();
        star.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, from[0], from[1], 0));
        star.dispatchTouchEvent(MotionEvent.obtain(now, now + 32, MotionEvent.ACTION_MOVE, to[0], to[1], 0));
        assertEnclosesSaved(star);
        star.dispatchTouchEvent(MotionEvent.obtain(now, now + 64, MotionEvent.ACTION_CANCEL, to[0], to[1], 0));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        assertEquals(1342, star.setAsks(AreaScore.PAY), 0);
        assertEquals(1342, star.learnedAsks(AreaScore.PAY), 0);
        assertEnclosesSaved(star);

        from = star.knobAt(AreaScore.PAY);
        dragKnob(star, star, from, CENTER, () -> {
            draw(star);
            assertEquals("an off saved axis still shows its learned high", 801.0 / (star.ringCents() * 3) * 220,
                    along(CENTER, AreaScore.PAY, star.drawnLearnedShape().get(0)), 0.001);
        });
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        assertTrue(Double.isNaN(star.setAsks(AreaScore.PAY)));
        assertEquals(801, star.learnedAsks(AreaScore.PAY), 0);
    }

    @Test public void reducedMotionUsesTheSameEnclosingOutline() throws Exception {
        ValueAnimator.class.getMethod("setDurationScale", float.class).invoke(null, 0f);
        try {
            MinimumsStarView star = radar(SAVED.withAcceptanceLearning(false).withAccepted(BESTS));
            star.show(SAVED.withAcceptanceLearning(true).withAccepted(BESTS), COMPARISON,
                    Collections.emptyList());
            assertEnclosesSaved(star);
        } finally {
            ValueAnimator.class.getMethod("setDurationScale", float.class).invoke(null, 1f);
        }
    }
}
