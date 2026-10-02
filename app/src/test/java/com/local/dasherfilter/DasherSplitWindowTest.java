package com.local.dasherfilter;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import java.lang.reflect.Proxy;
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
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

/** Public window geometry can exclude floating panes; it is not a universal freeform-mode detector. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class DasherSplitWindowTest extends AndroidAdapterTestBase {
    @After public void clearSplit() {
        DasherSplit.forget();
        OfferFilterService.sawDasherBeside(0);
    }

    @Test public void floatingGeometryExcludesSmallWindowsButPreservesBothSplitDirections() {
        Rect available = new Rect(0, 40, 1080, 1920);
        assertTrue(DasherSplit.floatingBounds(new Rect(100, 200, 900, 1400), available, 8));
        assertFalse(DasherSplit.floatingBounds(new Rect(0, 40, 1080, 950), available, 8));
        assertFalse(DasherSplit.floatingBounds(new Rect(0, 40, 520, 1920), available, 8));
        assertFalse("unavailable bounds do not invent a mode", DasherSplit.floatingBounds(new Rect(), available, 8));
    }

    @Test public void aTapInAPositivelyFloatingWindowDoesNotLaunchDasherAdjacent() {
        dasherInstalled();
        try (ActivityController<WindowActivity> activity = Robolectric.buildActivity(WindowActivity.class).setup()) {
            Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
            assertEquals(DasherSplit.SPLIT_LABEL, DasherSplit.label(activity.get()));
            assertTrue(DasherSplit.start(activity.get(), ignored -> {}).contains("full screen"));
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            assertFalse(DasherSplit.pending());
        }
    }

    @Test public void aPendingTapCannotFollowTheUserIntoAFloatingWindow() {
        dasherInstalled();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        try (ActivityController<WindowActivity> activity = Robolectric.buildActivity(WindowActivity.class).setup()) {
            Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
            ReflectionHelpers.setStaticField(DasherSplit.class, "requestedAt", SystemClock.uptimeMillis());
            DasherSplit.resumed(activity.get());
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            assertFalse(DasherSplit.pending());
        }
    }

    @Test public void aRealSplitPaneKeepsTheExplicitAdjacentLaunch() {
        dasherInstalled();
        try (ActivityController<WindowActivity> activity = Robolectric.buildActivity(WindowActivity.class).setup()) {
            activity.get().bounds = new Rect(0, 40, 1080, 950);
            Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
            assertEquals(DasherSplit.BESIDE_LABEL, DasherSplit.label(activity.get()));
            assertNull(DasherSplit.start(activity.get(), ignored -> {}));
            Intent launch = Shadows.shadowOf(app).getNextStartedActivity();
            assertNotNull(launch);
            assertTrue((launch.getFlags() & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0);
        }
    }

    @Test public void aConfirmedExitDiscardsTheOldTwentySecondBesideSighting() {
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        try (ActivityController<WindowActivity> activity = Robolectric.buildActivity(WindowActivity.class).setup()) {
            service.get().onServiceConnected();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
            activity.get().bounds = new Rect(0, 40, 1080, 950);
            Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
            DasherSplit.windowMode(activity.get());
            OfferFilterService.sawDasherBeside(SystemClock.uptimeMillis());
            assertTrue(OfferFilterService.dasherBeside());
            Shadows.shadowOf(activity.get()).setInMultiWindowMode(false);
            DasherSplit.windowMode(activity.get());
            assertFalse(OfferFilterService.dasherBeside());
            Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
            assertTrue("the next split may be beside Maps", DasherSplit.offered(activity.get(), true));
        } finally {
            service.destroy();
        }
    }

    public static final class WindowActivity extends Activity {
        Rect bounds = new Rect(100, 200, 900, 1400);

        @Override public WindowManager getWindowManager() {
            WindowManager original = super.getWindowManager();
            return (WindowManager) Proxy.newProxyInstance(WindowManager.class.getClassLoader(),
                    new Class<?>[] {WindowManager.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getCurrentWindowMetrics")) return metrics(bounds);
                        if (method.getName().equals("getMaximumWindowMetrics")) {
                            return metrics(new Rect(0, 0, 1080, 2000));
                        }
                        return method.invoke(original, args);
                    });
        }

        private WindowMetrics metrics(Rect area) {
            return new WindowMetrics(new Rect(area), new WindowInsets.Builder()
                    .setInsetsIgnoringVisibility(WindowInsets.Type.systemBars(), Insets.of(0, 40, 0, 80)).build());
        }
    }
}
