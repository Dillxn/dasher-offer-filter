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
import org.robolectric.android.controller.ServiceController;
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
        QualifyingWaitStore.wallClock = () -> 1_800_000_000_000L + android.os.SystemClock.elapsedRealtime();
        QualifyingWaitStore.flush();
        QualifyingWaitStore.clear(app);
        Appearance.choose(app, Appearance.Mode.DAY);
    }

    @After public void finishWaiting() {
        QualifyingWaitStore.flush();
        QualifyingWaitStore.clear(app);
        QualifyingWaitStore.wallClock = System::currentTimeMillis;
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

    @Test public void emptyHistoryHasOneShortWaitingStateWithoutAnUnavailableEstimate() {
        FilterStore.save(app, rules());
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        ServiceController<OfferNotificationService> listener = Robolectric.buildService(OfferNotificationService.class).create();
        service.get().onServiceConnected();
        listener.get().onListenerConnected();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            TextView waiting = shownTextContaining(content, "Waiting for offers");
            assertNotNull(waiting);
            assertFalse("there is no offer ticket to open yet", waiting.isClickable());
            assertNull(shownTextContaining(content, "Learning your wait"));
            assertNull(shownTextContaining(content, "Wait estimate needs"));
            assertNull(shownTextContaining(content, "No offers yet"));
            find(content, FilterHeroView.class).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertFalse(FilterStore.load(app).enabled);
            assertNull(shownTextContaining(content, "Waiting for offers"));
            assertNotNull(shownTextContaining(content, "Paused"));
        } finally {
            listener.destroy();
            service.destroy();
        }
    }

    @Test public void usableWaitAppearsOnlyDuringObservedWaitingAndKeepsItsOwnDetails() {
        FilterStore.save(app, rules());
        java.util.List<QualifyingWait.Sample> samples = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) samples.add(new QualifyingWait.Sample(
                QualifyingWaitStore.wallClock.getAsLong(), 120_000, new OfferSnapshot(1200, 3.0, 15, 2)));
        app.getSharedPreferences("qualifying-wait", 0).edit()
                .putString("numeric-history-v1", QualifyingWaitStore.encode(samples)).commit();
        QualifyingWaitStore.forgetCache();
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        ServiceController<OfferNotificationService> listener = Robolectric.buildService(OfferNotificationService.class).create();
        service.get().onServiceConnected();
        listener.get().onListenerConnected();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            assertEquals(QualifyingWait.Status.READY, QualifyingWaitStore.estimate(app, rules()).status);
            assertNull("restored numeric history is not a live waiting state", shownTextContaining(content, "Next match:"));
            QualifyingWaitStore.screen(app, true, DasherScene.WAITING, null, false, false);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            layOut(content);
            TextView wait = shownTextContaining(content, "Next match:");
            assertNotNull(wait);
            assertTrue(wait.getHeight() >= new Ui(app).dp(48));
            assertTrue(wait.performClick());
            AlertDialog explanation = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(findTextContaining(explanation.getWindow().getDecorView(), "not a countdown or promise"));
            assertTrue(FilterStore.load(app).enabled);
            explanation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            QualifyingWaitStore.screen(app, true, DasherScene.OFFER,
                    new OfferSnapshot(1200, 3.0, 15, 2), false, true);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertNull("offer handling is not a positively observed wait", shownTextContaining(content, "Next match:"));
        } finally {
            listener.destroy();
            service.destroy();
        }
    }

    @Test public void setupProblemsTakePriorityOverTheWaitEstimate() {
        FilterStore.save(app, rules());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            assertNotNull(shownTextContaining(content, "Screen reading is off"));
            assertNull("setup instructions keep their space until filtering can observe offers",
                    shownTextContaining(content, "Learning your wait"));
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
        // Per item is retired (0.5.0), and the 97% bar is Autopilot's to set: it set it between offers.
        FilterSettings original = rules();
        FilterStore.save(app, original);
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, original.minimumScalePercent));
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
