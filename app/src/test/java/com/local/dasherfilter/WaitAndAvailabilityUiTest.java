package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowSystemClock;

/** The real page owns disclosure and explicit rule changes; explanations must not toggle filtering. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w420dp-h900dp")
@LooperMode(LooperMode.Mode.PAUSED)
public class WaitAndAvailabilityUiTest extends AndroidAdapterTestBase {
    @Before public void clearWaiting() {
        QualifyingWaitStore.flush();
        QualifyingWaitStore.clear(app);
        Appearance.choose(app, Appearance.Mode.DAY);
    }

    @After public void finishWaiting() {
        QualifyingWaitStore.flush();
        QualifyingWaitStore.clear(app);
    }

    private FilterSettings rules() {
        return new FilterSettings(true, 1000, 150, 0, 0, 3, false, 0).withMinimumScalePercent(97);
    }

    private View page(ActivityController<MainActivity> activity) {
        View content = activity.get().findViewById(android.R.id.content);
        layOut(content);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        return content;
    }

    @Test public void learningWaitIsVisibleOnlyWhenFilteringAndItsDetailsNeverPause() {
        FilterStore.save(app, rules());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            TextView wait = shownTextContaining(content, "Learning your wait");
            assertNotNull("cold history says learning instead of inventing a delay", wait);
            assertTrue(wait.performClick());
            AlertDialog explanation = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Time until a matching offer", Shadows.shadowOf(explanation).getTitle().toString());
            assertNotNull(findTextContaining(explanation.getWindow().getDecorView(), "5 monitored minutes"));
            assertTrue("the estimate has its own tap, not the mascot's pause action", FilterStore.load(app).enabled);
            explanation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            find(content, FilterHeroView.class).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertFalse(FilterStore.load(app).enabled);
            assertFalse("paused filtering must not advertise a wait", wait.isShown());
            assertNotNull(shownTextContaining(content, "Paused"));
        }
    }

    @Test public void hotspotExplanationPreservesRuleUntilItsExplicitOffAction() {
        FilterSettings original = rules().withHotspotProximity(50).withPerItem(125);
        FilterStore.save(app, original);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            AccessibilityNodeInfo hotspot = node(star, AreaScore.HOTSPOT);
            assertNotNull(hotspot);
            assertTrue(hotspot.getContentDescription().toString().toLowerCase(java.util.Locale.US)
                    .contains("unavailable"));
            assertTrue(act(star, AreaScore.HOTSPOT, AccessibilityNodeInfo.ACTION_CLICK));
            AlertDialog explanation = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Hotspot distance is unavailable", Shadows.shadowOf(explanation).getTitle().toString());
            assertNotNull(findTextContaining(explanation.getWindow().getDecorView(), "not Dasher's live hotspots"));
            assertArrayEquals("opening disclosure changes no floor", original.minimums(),
                    FilterStore.load(app).minimums());
            explanation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("acknowledging disclosure is not opting out", 50,
                    FilterStore.load(app).hotspotProximityHundredths);

            assertTrue(act(star, AreaScore.HOTSPOT, AccessibilityNodeInfo.ACTION_CLICK));
            AlertDialog again = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Turn hotspot rule off", again.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
            again.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            FilterSettings after = FilterStore.load(app);
            assertArrayEquals(original.withHotspotProximity(0).minimums(), after.minimums());
            assertEquals(original.minimumScalePercent, after.minimumScalePercent);
            assertEquals(original.maxStops, after.maxStops);
            assertTrue("disabling one unavailable rule does not pause other rules", after.enabled);
        }
    }

    @Test public void clearHistoryErasesWaitDataAfterConfirmationAndPreservesRules() {
        FilterSettings original = rules().withPerItem(125);
        FilterStore.save(app, original);
        QualifyingWaitStore.screen(app, true, DasherScene.WAITING, null, false, false);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        QualifyingWaitStore.screen(app, true, DasherScene.OFFER,
                new OfferSnapshot(1200, 3.0, 15, 2), false, false);
        QualifyingWaitStore.stop(app);
        QualifyingWaitStore.flush();
        assertEquals(1, QualifyingWaitStore.estimate(app, original).readable);

        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            iconDescribed(content, "Settings").performClick();
            assertTrue(settingsShown(content));
            shownButton(content, "Clear history").performClick();
            AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(findTextContaining(confirm.getWindow().getDecorView(), "waiting estimates"));
            assertEquals("opening the confirmation does not clear data", 1,
                    QualifyingWaitStore.estimate(app, original).readable);
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            QualifyingWaitStore.flush();
            QualifyingWaitStore.forgetCache();
            assertEquals("cleared history stays clear after process cache loss", 0,
                    QualifyingWaitStore.estimate(app, original).readable);
            assertEquals(0, QualifyingWaitStore.estimate(app, original).observedMs);
            FilterSettings after = FilterStore.load(app);
            assertArrayEquals(original.minimums(), after.minimums());
            assertEquals(original.minimumScalePercent, after.minimumScalePercent);
            assertEquals(original.maxStops, after.maxStops);
            assertEquals(original.enabled, after.enabled);
        }
    }
}
