package com.local.dasherfilter;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import java.time.Duration;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The homepage in the layouts of a dash (the owner: "split screen at 50/50 doesn't leave enough room for seeing gps
 * well inside the dd app"; "the user isn't juggling multiple windows (dasher, filter, gps) and can just flow"): at
 * about a third of a split screen, one strip; beside Dasher, once, how to give its map more room; beside a map during
 * a dash, that background offers need a tap there, with Swap; full screen, Dasher one tap away. Which half Android
 * gives Dasher on a swap, and how the divider feels, are for a handset.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class DrivingLayoutTest extends AndroidAdapterTestBase {
    private static final ComponentName DASHER_HOME =
            new ComponentName("com.doordash.driverapp", "com.doordash.driverapp.Home");

    private ServiceController<OfferFilterService> screen;
    private ServiceController<OfferNotificationService> listener;

    @After
    public void stopServices() {
        if (listener != null && OfferNotificationService.isConnected()) listener.destroy();
        if (screen != null) screen.destroy();
        OfferFilterService.sawDasherBeside(0);
        DasherSplit.forget();
    }

    /** Screen reading and background offers both on, as during a dash. */
    private void servicesUp() {
        screen = Robolectric.buildService(OfferFilterService.class).create();
        screen.get().onServiceConnected();
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        settle();
    }

    private static ActivityController<MainActivity> splitScreen() {
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        return built.setup();
    }

    private static View content(ActivityController<MainActivity> activity) {
        return activity.get().findViewById(android.R.id.content);
    }

    private Intent started() {
        return Shadows.shadowOf(app).getNextStartedActivity();
    }

    private void logSays(String part) {
        String log = DiagnosticLog.read(app);
        assertTrue(part + " in:\n" + log, log.contains(part));
    }

    // ---- About a third of a split screen: the strip ----

    @Test @Config(qualifiers = "w411dp-h300dp-420dpi")
    public void aThirdOfASplitScreenIsOneStripTheMascotTheLatestVerdictAndTheStatus() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DecisionLog.record(app, declinedEntry());
        servicesUp();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            layOut(content);
            DrivingStrip strip = find(content, DrivingStrip.class);
            assertNotNull("a third of the screen: the strip", strip);
            assertTrue(strip.isShown());
            assertFalse("nothing else: not the page", find(content, MinimumsStarView.class).isShown());
            assertEquals("Latest · $7.90 · Declined", strip.verdictText());
            assertEquals("Auto-decline is on", strip.statusText());
            View mascot = find(content, DrivingStrip.MascotButton.class);
            assertEquals("Pause auto-decline", String.valueOf(mascot.getContentDescription()));
            assertTrue("a full touch target", mascot.getHeight() >= new Ui(app).dp(48));

            mascot.performClick();
            settle();
            assertFalse("the mascot pauses, as on the homepage", FilterStore.load(app).enabled);
            assertEquals("Paused · nothing is declined", strip.statusText());
            assertEquals("Resume auto-decline", String.valueOf(mascot.getContentDescription()));
            mascot.performClick();
            settle();
            assertTrue("and resumes", FilterStore.load(app).enabled);
            assertEquals("Auto-decline is on", strip.statusText());
            assertTrue("the divider was dragged already: no hint to come", SplitLines.hintShown(app));
        }
    }

    @Test @Config(qualifiers = "w411dp-h300dp-420dpi")
    public void theStripSaysWhatNeedsTheUserAndItsTapIsTheFix() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        screen = Robolectric.buildService(OfferFilterService.class).create();
        screen.get().onServiceConnected();
        settle();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            DrivingStrip strip = find(content(activity), DrivingStrip.class);
            assertEquals("No offers yet", strip.verdictText());
            // The homepage's own first setup step, in its words (SetupChecklist): notification access is missing.
            assertEquals(SetupChecklist.NOTIFICATIONS, strip.statusText());
            TextView status = shownTextContaining(content(activity), SetupChecklist.NOTIFICATIONS);
            assertTrue("its tap goes to the fix", status.isClickable());
            status.performClick();
            Intent fix = started();
            assertNotNull("the step's own Fix: Android's notification access", fix);
            assertTrue(fix.getAction(), android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS
                    .equals(fix.getAction())
                    || android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS.equals(fix.getAction()));
        }
    }

    @Test @Config(qualifiers = "w411dp-h300dp-420dpi")
    public void aThirdOfASplitScreenBesideAMapDuringADashSaysSoAndItsTapSwapsInDasher() {
        dasherInstalled();
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        servicesUp();
        Dashing.seen(app);
        try (ActivityController<MainActivity> activity = splitScreen()) {
            DrivingStrip strip = find(content(activity), DrivingStrip.class);
            assertEquals(SplitLines.LAYOUT_NOTE_SHORT, strip.statusText());
            shownTextContaining(content(activity), SplitLines.LAYOUT_NOTE_SHORT).performClick();
            Intent swap = started();
            assertNotNull(swap);
            assertEquals(DASHER_HOME, swap.getComponent());
            assertEquals("into this half: no adjacent launch, nothing cleared", Intent.FLAG_ACTIVITY_NEW_TASK,
                    swap.getFlags());
        }
    }

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void halfOfASplitScreenKeepsTheWholePage() {
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            layOut(content);
            assertNull(find(content, DrivingStrip.class));
            assertTrue(find(content, MinimumsStarView.class).isShown());
        }
    }

    @Test @Config(qualifiers = "w700dp-h300dp-420dpi")
    public void aShortFullScreenKeepsTheWholePage() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNull("only one half of a split screen gets the strip", find(content(activity), DrivingStrip.class));
        }
    }

    // ---- Beside Dasher: the divider hint, once ----

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherTheDividerHintShowsOnceOverThePageAndGoesByItself() {
        servicesUp();
        OfferFilterService.sawDasherBeside(SystemClock.uptimeMillis());
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            TextView hint = shownTextContaining(content, SplitLines.HINT);
            assertNotNull("beside Dasher: how to give its map more room", hint);
            assertEquals("Drag the divider toward Offer Filter to give Dasher's map more room",
                    hint.getText().toString());
            assertFalse("words only: a touch goes to the page under them", hint.isClickable());
            assertFalse("over the page, not in it", isDescendant(find(content, ScenePage.class), hint));
            assertTrue(SplitLines.hintShown(app));
            logSays("[split] divider hint shown (once)");

            iconButton(content, "Settings").performClick();
            settle();
            assertNull("only over the homepage", shownTextContaining(content, SplitLines.HINT));
            activity.get().onBackPressed();
            settle();
            assertNotNull(shownTextContaining(content, SplitLines.HINT));

            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SplitLines.HINT_MS));
            assertNull("gone by itself", shownTextContaining(content, SplitLines.HINT));
        }
        OfferFilterService.sawDasherBeside(SystemClock.uptimeMillis());
        try (ActivityController<MainActivity> again = splitScreen()) {
            assertNull("never again", shownTextContaining(content(again), SplitLines.HINT));
        }
    }

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void noDividerHintBesideAnotherApp() {
        servicesUp();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            assertNull(shownTextContaining(content(activity), SplitLines.HINT));
            assertFalse(SplitLines.hintShown(app));
        }
    }

    // ---- Beside a map during a dash: the layout note and Swap ----

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void duringADashBesideAMapTheLayoutNoteOffersToSwapInDasherSoTheMapStays() {
        dasherInstalled();
        servicesUp();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            assertNull("no dash: nothing to say", shownTextContaining(content, "need a tap in this layout"));
            assertNotNull("outside a dash the button puts Dasher beside",
                    shownIcon(content, DasherSplit.BESIDE_LABEL));
        }
        Dashing.seen(app);
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            TextView note = shownTextContaining(content, "need a tap in this layout");
            assertNotNull("during a dash beside another app", note);
            assertEquals("Background offers need a tap in this layout — use Dasher full screen, or Maps with Dasher "
                    + "beside", note.getText().toString());
            assertNotNull("the header's button says what it does now", shownIcon(content, DasherSplit.SWAP_LABEL));
            ((View) note.getParent()).performClick();
            Intent swap = started();
            assertNotNull(swap);
            assertEquals(DASHER_HOME, swap.getComponent());
            assertEquals("Dasher into this half, as its launcher opens it: no adjacent launch, nothing cleared",
                    Intent.FLAG_ACTIVITY_NEW_TASK, swap.getFlags());
            logSays("[split] tap in split screen during a dash: Dasher's launch intent into this half (the other half "
                    + "stays)");

            shownIcon(content, DasherSplit.SWAP_LABEL).performClick();
            Intent again = started();
            assertNotNull(again);
            assertEquals("the header's button does the same", 0,
                    again.getFlags() & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        }
    }

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void noLayoutNoteWithPeekOffOrBesideDasher() {
        dasherInstalled();
        servicesUp();
        Dashing.seen(app);
        FilterStore.setPeek(app, false);
        try (ActivityController<MainActivity> activity = splitScreen()) {
            assertNull("Peek off: a tap is the way in anyway",
                    shownTextContaining(content(activity), "need a tap in this layout"));
        }
        FilterStore.setPeek(app, true);
        OfferFilterService.sawDasherBeside(SystemClock.uptimeMillis());
        try (ActivityController<MainActivity> activity = splitScreen()) {
            assertNull("beside Dasher, offers show there", shownTextContaining(content(activity),
                    "need a tap in this layout"));
        }
    }

    // ---- Full screen: Open Dasher ----

    @Test @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void openDasherIsOneHeaderTapToDasherFullScreen() {
        dasherInstalled();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View open = shownIcon(content(activity), DasherSplit.OPEN_LABEL);
            assertNotNull("in the header, full screen", open);
            assertTrue(open.getWidth() >= new Ui(app).dp(48) || open.getLayoutParams().width >= new Ui(app).dp(48));
            open.performClick();
            Intent opened = started();
            assertNotNull(opened);
            assertEquals(DASHER_HOME, opened.getComponent());
            assertTrue(opened.hasCategory(Intent.CATEGORY_LAUNCHER));
            assertEquals("as its launcher icon opens it: its task as it was, never beside", Intent.FLAG_ACTIVITY_NEW_TASK,
                    opened.getFlags());
            logSays("[split] Open Dasher tapped: Dasher's launch intent");
        }
    }

    @Test @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void noOpenDasherWithoutDasher() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNull(shownIcon(content(activity), DasherSplit.OPEN_LABEL));
        }
    }

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void noOpenDasherInASplitScreen() {
        dasherInstalled();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            assertNull("the split button puts Dasher on screen there", shownIcon(content(activity),
                    DasherSplit.OPEN_LABEL));
        }
    }
}
