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

    /** $10.00 and $1.50 a mile, at most 3 stops; Autopilot off, so offers are judged at exactly these. */
    private FilterSettings rules() {
        return FilterSettings.of(true, 1000, 150, 0, 3);
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
            assertNotNull(shownTextContaining(content, SetupChecklist.ACCESSIBILITY));
            assertNull("setup instructions keep their space until filtering can observe offers",
                    shownTextContaining(content, "Learning your wait"));
        }
    }

    @Test public void autopilotsExplanationsChangeNothingUntilTheirExplicitAction() {
        FilterSettings original = rules();
        FilterStore.save(app, original);
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 97));
        // Set up (screen reading and background offers on), so no setup line takes the status line's room.
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        service.get().onServiceConnected();
        listener.get().onListenerConnected();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            // The status line stands where the wait would; a tap explains and changes nothing.
            TextView status = shownTextContaining(content, "Autopilot 97%");
            assertNotNull(status);
            assertTrue(status.getHeight() >= new Ui(app).dp(48));
            assertTrue(status.performClick());
            AlertDialog details = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(AutopilotText.DETAILS_TITLE, Shadows.shadowOf(details).getTitle().toString());
            assertNotNull(findTextContaining(details.getWindow().getDecorView(),
                    "Bar: 97% of your minimums (100% = exactly your minimums)."));
            unchanged(original, 97);
            details.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            unchanged(original, 97);

            // About acceptance rate explains without closing the choice; Not now leaves the goal as it was.
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(act(star, MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_LONG_CLICK));
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            chooser.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog about = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(AutopilotText.ABOUT_ACCEPTANCE_RATE, Shadows.shadowOf(about).getMessage().toString());
            assertTrue("the choice stays open", chooser.isShowing());
            about.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            chooser.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            unchanged(original, 97);

            // Only the details' own Turn off turns Autopilot off: back to exactly the minimums, filtering still on.
            assertTrue(shownTextContaining(content, "Autopilot 97%").performClick());
            details = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(AutopilotText.DETAILS_TURN_OFF,
                    details.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            details.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            FilterSettings after = FilterStore.load(app);
            assertFalse(after.autopilot);
            assertEquals(100, after.minimumScalePercent);
            assertArrayEquals(original.minimums(), after.minimums());
            assertEquals(original.maxStops, after.maxStops);
            assertTrue("turning Autopilot off does not pause filtering", after.enabled);
        } finally {
            listener.destroy();
            service.destroy();
        }
    }

    /**
     * At every window size (half a split screen here, beside another app, then beside Dasher) the status line beside
     * Autopilot's chip is the wait while Autopilot is off and Autopilot's own status while it is on, so Next match never
     * takes a row under the chip; the chip is there either way.
     */
    @Test @Config(qualifiers = "w411dp-h360dp-420dpi")
    public void theStatusLineIsTheWaitWithAutopilotOffAndItsStatusWithItOn() {
        FilterStore.save(app, rules());
        java.util.List<QualifyingWait.Sample> samples = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) samples.add(new QualifyingWait.Sample(
                QualifyingWaitStore.wallClock.getAsLong(), 120_000, new OfferSnapshot(1200, 3.0, 15, 2)));
        app.getSharedPreferences("qualifying-wait", 0).edit()
                .putString("numeric-history-v1", QualifyingWaitStore.encode(samples)).commit();
        QualifyingWaitStore.forgetCache();
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        service.get().onServiceConnected();
        listener.get().onListenerConnected();
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = page(activity);
            QualifyingWaitStore.screen(app, true, DasherScene.WAITING, null, false, false);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertNotNull("Autopilot off: Next match", shownTextContaining(content, "Next match:"));
            assertTrue("in the status line", statusLine(content).getText().toString().startsWith("Next match:"));
            assertTrue(find(content, AutopilotChip.class).isShown());

            AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            layOut(content);
            assertNull("Autopilot on: no Next match under the chip", shownTextContaining(content, "Next match:"));
            assertTrue("its status instead", statusLine(content).getText().toString().startsWith("Autopilot "));
            assertTrue("the chip says it", find(content, AutopilotChip.class).isShown());

            // Beside Dasher: the same page, the same line.
            OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
            layOut(content);
            assertNull(shownTextContaining(content, "Next match:"));
            assertTrue(statusLine(content).getText().toString().startsWith("Autopilot "));
            assertTrue("the chip as well", find(content, AutopilotChip.class).isShown());

            AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
            OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertNotNull("off again: Next match is back", shownTextContaining(content, "Next match:"));
        } finally {
            OfferFilterService.sawDasherBeside(0);
            listener.destroy();
            service.destroy();
        }
    }

    /** The rules as {@code original}, with Autopilot on at {@code bar} and the top-tier goal. */
    private void unchanged(FilterSettings original, int bar) {
        FilterSettings now = FilterStore.load(app);
        assertArrayEquals(original.minimums(), now.minimums());
        assertEquals(original.maxStops, now.maxStops);
        assertEquals(original.enabled, now.enabled);
        assertTrue(now.autopilot);
        assertEquals(bar, now.minimumScalePercent);
        assertEquals(FilterSettings.GOAL_TOP_TIER, now.autopilotGoalPercent);
    }

    @Test public void clearHistoryErasesWaitDataAfterConfirmationAndPreservesRules() {
        // Per item is retired (0.5.0), and a 97% bar is Autopilot's to set: it set it between offers.
        FilterSettings original = rules();
        FilterStore.save(app, original);
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 97));
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
            assertNotNull(findTextContaining(confirm.getWindow().getDecorView(),
                    "Your rules and Autopilot settings stay."));
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
            assertTrue("Autopilot's settings stay", after.autopilot);
            assertEquals("its bar moves only at its next safe point", 97, after.minimumScalePercent);
            assertEquals(original.maxStops, after.maxStops);
            assertEquals(original.enabled, after.enabled);
        }
    }
}
