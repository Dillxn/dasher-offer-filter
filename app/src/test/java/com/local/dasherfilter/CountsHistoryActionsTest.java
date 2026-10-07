package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import java.time.Duration;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowToast;

/** Header statistics inspect retained history; only the separate mascot changes filtering. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class CountsHistoryActionsTest extends AndroidAdapterTestBase {
    @Test public void tappingYellowReviewCountOpensItsReasonWithoutPausing() {
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        record(900, OfferRule.Result.REVIEW, "pay not found", 1);
        record(2500, OfferRule.Result.KEEP, "meets enabled rules", 2);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            tapCount(content, 2);
            assertTrue("inspecting the yellow count must never pause auto-decline", FilterStore.load(app).enabled);
            assertEquals("Review", find(content, Decor.Stamp.class).getContentDescription().toString());
            assertNotNull(shownTextContaining(content, "Pay not readable"));
            activity.get().onBackPressed();
            FilterHeroView hero = find(content, FilterHeroView.class);
            tap((ViewGroup) hero.getParent(), hero.getLeft() + hero.mascotX(), hero.getTop() + hero.mascotY());
            assertFalse("the mascot still pauses", FilterStore.load(app).enabled);
            tap((ViewGroup) hero.getParent(), hero.getLeft() + hero.mascotX(), hero.getTop() + hero.mascotY());
            assertTrue("the mascot still resumes", FilterStore.load(app).enabled);
        }
    }

    @Test public void eachCountOpensItsNewestMatchingOutcomeIncludingAnOlderRetainedReview() {
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        record(900, OfferRule.Result.REVIEW, "pay not found", 1);
        for (int i = 2; i <= DecisionChartView.SLOTS + 2; i++) record(2500 + i,
                OfferRule.Result.KEEP, "meets enabled rules", i);
        record(800, OfferRule.Result.DECLINE, "flat minimum", DecisionChartView.SLOTS + 3);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            DecisionChartView chart = find(content, DecisionChartView.class);
            long visibleSelection = chart.selectedEntry().at;
            MinimumsStarView plot = find(content, MinimumsStarView.class);
            String plottedBefore = plot.getContentDescription().toString();
            android.widget.TextView caption = org.robolectric.util.ReflectionHelpers.getField(activity.get(), "offerCaption");
            String captionBefore = caption.getText().toString();
            tapCount(content, 2);
            assertTrue(FilterStore.load(app).enabled);
            assertEquals("Review", find(content, Decor.Stamp.class).getContentDescription().toString());
            assertEquals("older history must not invent a visible skyline selection", visibleSelection,
                    chart.selectedEntry().at);
            assertEquals("an older ticket does not change plotted facts or its item reference", plottedBefore,
                    plot.getContentDescription().toString());
            assertEquals("the main caption continues to identify the plotted offer", captionBefore,
                    caption.getText().toString());
            record(4000, OfferRule.Result.KEEP, "meets enabled rules", DecisionChartView.SLOTS + 4);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertEquals("a new offer must not replace the older review being inspected", "Review",
                    find(content, Decor.Stamp.class).getContentDescription().toString());
            assertLatestOfferAligned(activity.get(), content, 4000);
            assertEquals("the external count ticket must not pin a different plotted offer", -1, plot.openedOffer());
            activity.get().onBackPressed();
            assertLatestOfferAligned(activity.get(), content, 4000);
            tapCount(content, 0);
            assertEquals("Passed", find(content, Decor.Stamp.class).getContentDescription().toString());
            activity.get().onBackPressed();
            tapCount(content, 1);
            assertEquals("Declined", find(content, Decor.Stamp.class).getContentDescription().toString());
            assertTrue(FilterStore.load(app).enabled);
        }
    }

    @Test public void aSelectionRollingOutOfTheSkylineFallsBackToLatestWhileOlderCountTicketStaysOpen() {
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        record(900, OfferRule.Result.REVIEW, "pay not found", 1);
        for (int i = 2; i <= DecisionChartView.SLOTS + 2; i++) record(2500 + i,
                OfferRule.Result.KEEP, "meets enabled rules", i);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            DecisionChartView chart = find(content, DecisionChartView.class);
            chart.select(0);
            long selectedAt = chart.selectedEntry().at;
            tapCount(content, 2);
            assertEquals(selectedAt, chart.selectedEntry().at);
            assertEquals("Review", find(content, Decor.Stamp.class).getContentDescription().toString());

            record(4100, OfferRule.Result.KEEP, "meets enabled rules", DecisionChartView.SLOTS + 3);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertEquals("rolling out the skyline selection must preserve the older inspected ticket", "Review",
                    find(content, Decor.Stamp.class).getContentDescription().toString());
            assertLatestOfferAligned(activity.get(), content, 4100);
            assertEquals(-1, find(content, MinimumsStarView.class).openedOffer());
            activity.get().onBackPressed();
            assertLatestOfferAligned(activity.get(), content, 4100);
        }
    }

    @Test public void emptyOrCanceledCountsNeverPauseOrStartFiltering() {
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            tapCount(content, 2);
            assertTrue(FilterStore.load(app).enabled);
            assertEquals("No recent offers left to review in history.", ShadowToast.getTextOfLatestToast());
            FilterHeroView hero = find(content, FilterHeroView.class);
            RectF counts = new RectF();
            hero.countsAt(counts);
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                    counts.centerX(), counts.centerY(), 0);
            MotionEvent cancel = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_CANCEL,
                    counts.centerX(), counts.centerY(), 0);
            try { hero.dispatchTouchEvent(down); hero.dispatchTouchEvent(cancel); }
            finally { down.recycle(); cancel.recycle(); }
            assertTrue(FilterStore.load(app).enabled);
            hero.performClick();
            assertFalse(FilterStore.load(app).enabled);
            tapCount(content, 2);
            assertFalse("inspection while paused must not resume", FilterStore.load(app).enabled);
        }
    }

    @Test public void nativeAccessibleActionsHaveSeparateBoundsAndExactPurposes() {
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        record(900, OfferRule.Result.REVIEW, "pay not found", 1);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            Button mascot = describedButton(content, "Pause auto-decline");
            Button review = describedButton(content, "Show latest offer left to review");
            assertNotNull(mascot);
            assertNotNull(review);
            assertNotSame(mascot, review);
            Rect first = new Rect(), second = new Rect();
            mascot.getGlobalVisibleRect(first);
            review.getGlobalVisibleRect(second);
            assertFalse("accessible action regions do not overlap", Rect.intersects(first, second));
            int least = new Ui(app).dp(48);
            assertTrue(first.width() >= least && first.height() >= least);
            assertTrue(second.width() >= least && second.height() >= least);
            AccessibilityNodeInfo node = review.createAccessibilityNodeInfo();
            assertEquals(Button.class.getName(), node.getClassName());
            assertTrue(node.isClickable());
            assertTrue(node.isFocusable());
            assertFalse(node.getContentDescription().toString().contains("Pause"));
            assertTrue(review.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
            assertTrue(FilterStore.load(app).enabled);
            assertEquals("Review", find(content, Decor.Stamp.class).getContentDescription().toString());
        }
    }

    private void assertLatestOfferAligned(MainActivity activity, View content, int pay) {
        DecisionLog.Entry selected = find(content, DecisionChartView.class).selectedEntry();
        assertNotNull("a nonempty skyline keeps a selected offer", selected);
        assertEquals(Integer.valueOf(pay), selected.facts.payCents);
        android.widget.TextView caption = org.robolectric.util.ReflectionHelpers.getField(activity, "offerCaption");
        assertEquals("Latest · " + DecisionLog.money(pay) + " · Passed", caption.getText().toString());
        assertEquals("the star and caption describe the same latest offer",
                AreaScore.percent(FilterStore.load(app), selected.facts),
                find(content, MinimumsStarView.class).emphasizedScore());
    }

    private void record(int pay, OfferRule.Result result, String reason, int order) {
        DecisionLog.Action action = result == OfferRule.Result.KEEP ? DecisionLog.Action.PASSES
                : result == OfferRule.Result.DECLINE ? DecisionLog.Action.DECLINE_TAPPED : DecisionLog.Action.NEEDS_REVIEW;
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() - 60_000 + order * 100,
                DecisionLog.Source.SCREEN, false, new OfferSnapshot(result == OfferRule.Result.REVIEW ? null : pay,
                4.0, 20, 2), 2000, result, reason, action, true, Collections.emptyList()));
    }

    private static void tapCount(View content, int index) {
        FilterHeroView hero = find(content, FilterHeroView.class);
        RectF box = new RectF();
        float spacing = hero.countsAt(box);
        tap((ViewGroup) hero.getParent(), hero.getLeft() + box.centerX() + (index - 1) * spacing,
                hero.getTop() + box.centerY());
    }

    private static Button describedButton(View view, String words) {
        if (view instanceof Button && view.getContentDescription() != null
                && view.getContentDescription().toString().contains(words)) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = describedButton(((ViewGroup) view).getChildAt(i), words);
            if (found != null) return found;
        }
        return null;
    }
}
