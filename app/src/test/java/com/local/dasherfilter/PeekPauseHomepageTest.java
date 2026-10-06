package com.local.dasherfilter;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A pause Peek put on itself (withdrawn offers or launches that never came up, in a row, or a runtime failure) shows on
 * the homepage as one line with its reason and Resume; the Settings switch is never turned off for it, and it ends by
 * itself at the next dash or after 15 minutes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class PeekPauseHomepageTest {
    private static final String ROW = "Peek paused: it hit an error. Resume.";
    private Application app;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        DiagnosticLog.clear(app);
        Dashing.forgetCache();
        DecisionLog.forgetCache();
        AreaMap.forgetCache();
        OfferSilencer.forgetCache();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
    }

    @After
    public void tearDown() {
        OfferFilterService.scanLooperForTests = null;
    }

    private static void idle(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    @Test
    public void aPausePeekPutOnItselfShowsOnTheHomepageAndResumeEndsIt() {
        Peek.pause(app, "it hit an error");
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            idle(1_100);
            View content = activity.get().findViewById(android.R.id.content);
            View row = AndroidAdapterTestBase.shownIcon(content, ROW);
            assertNotNull("one line with the reason and Resume", row);
            assertTrue("the Settings switch stays on", FilterStore.peek(app));
            row.performClick();
            idle(100);
            assertNull(Peek.pausedWhy(app));
            assertNull("gone once resumed", AndroidAdapterTestBase.shownIcon(content, ROW));
            assertTrue(DiagnosticLog.read(app).contains("[peek] pause over: you tapped Resume"));
        }
    }

    @Test
    public void withPeekOffInSettingsNoPauseIsShown() {
        FilterStore.setPeek(app, false);
        Peek.pause(app, "it hit an error");
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            idle(1_100);
            View content = activity.get().findViewById(android.R.id.content);
            assertNull("Peek off is the user's own choice, not a pause",
                    AndroidAdapterTestBase.shownIcon(content, ROW));
        }
    }

    @Test
    public void aPauseEndsByItselfAfter15MinutesOrAtTheNextDash() throws Exception {
        Peek.pause(app, "it hit an error");
        assertEquals("it hit an error", Peek.pausedWhy(app));
        ShadowSystemClock.advanceBy(Duration.ofMillis(Peek.PAUSE_MS));
        assertNull(Peek.pausedWhy(app));
        assertTrue(DiagnosticLog.read(app).contains("[peek] pause over: 15 minutes passed; Peek works again"));

        // Paused during one dash: the next dash ends it.
        Dashing.seen(app);
        Peek.pause(app, "it hit an error");
        assertEquals("it hit an error", Peek.pausedWhy(app));
        Dashing.ended(app);
        assertEquals("no dash on is not a new dash", "it hit an error", Peek.pausedWhy(app));
        Thread.sleep(5);
        Dashing.seen(app);
        assertNull(Peek.pausedWhy(app));
        assertTrue(DiagnosticLog.read(app).contains("[peek] pause over: a new dash started; Peek works again"));
    }
}
