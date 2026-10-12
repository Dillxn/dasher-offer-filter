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
import android.view.accessibility.AccessibilityWindowInfo;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
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
        OfferFilterService.forgetScreenState();
    }

    /** Dasher's plain launcher, as its home-screen icon starts it: never into the other half. */
    private static void assertPlainLauncher(String why, Intent launch) {
        assertNotNull(why, launch);
        assertEquals(why, "com.doordash.driverapp.Home", launch.getComponent().getClassName());
        assertEquals(why, Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED, launch.getFlags());
        assertEquals(why, 0, launch.getFlags() & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
    }

    private static AccessibilityWindowInfo window(int id, int type, boolean active, Rect bounds) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setId(id);
        shadow.setType(type);
        shadow.setActive(active);
        shadow.setBoundsInScreen(bounds);
        return window;
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

    /**
     * A real split pane (not a floating window): the button swaps Dasher into this half with its plain launcher
     * (0.6.2 sent Dasher into the other half, launch adjacent).
     */
    @Test public void aRealSplitPaneSwapsInDasherWithItsPlainLauncher() {
        dasherInstalled();
        try (ActivityController<WindowActivity> activity = Robolectric.buildActivity(WindowActivity.class).setup()) {
            activity.get().bounds = new Rect(0, 40, 1080, 950);
            Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
            assertEquals(DasherSplit.SWAP_LABEL, DasherSplit.label(activity.get()));
            assertNull(DasherSplit.start(activity.get(), ignored -> {}));
            assertPlainLauncher("Swap in Dasher", Shadows.shadowOf(app).getNextStartedActivity());
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            assertFalse("a swap waits for nothing", DasherSplit.pending());
        }
    }

    /**
     * Split beside another app, the button swaps Dasher in whatever the dash (0.6.2 offered "Put Dasher beside" outside
     * a dash, into the other half); with Dasher already beside by fresh window metadata it starts nothing.
     */
    @Test public void inSplitWithoutDasherTheButtonSwapsWhateverTheDash() {
        dasherInstalled();
        for (boolean dash : new boolean[] {false, true}) {
            DasherSplit.forget();
            DiagnosticLog.clear(app);
            if (dash) Dashing.seen(app);
            ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
            try (ActivityController<WindowActivity> activity = Robolectric.buildActivity(WindowActivity.class).setup()) {
                service.get().onServiceConnected();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                activity.get().bounds = new Rect(0, 40, 1080, 950);
                Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
                Shadows.shadowOf(service.get()).setWindows(Arrays.asList(
                        window(901, AccessibilityWindowInfo.TYPE_APPLICATION, true, new Rect(0, 0, 1080, 1000)),
                        window(903, AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, false,
                                new Rect(0, 1000, 1080, 1040)),
                        window(902, AccessibilityWindowInfo.TYPE_APPLICATION, false, new Rect(0, 1040, 1080, 2040))));
                assertEquals(DasherSplit.SWAP_LABEL, DasherSplit.label(activity.get()));
                assertNull(DasherSplit.start(activity.get(), ignored -> {}));
                assertPlainLauncher(dash ? "during a dash" : "outside a dash",
                        Shadows.shadowOf(app).getNextStartedActivity());
                assertTrue(DiagnosticLog.read(app).contains("[split] tap in split screen: Dasher's launch intent into "
                        + "this half (the other half stays)"));

                // The other half is Dasher's, by fresh window metadata: nothing to swap in.
                Set<Integer> dasher = ReflectionHelpers.getField(service.get(), "dasherWindowIds");
                dasher.add(902);
                assertNull(DasherSplit.start(activity.get(), ignored -> {}));
                assertNull(DasherSplit.swapIn(activity.get()));
                assertNull("Dasher already beside: nothing started", Shadows.shadowOf(app).getNextStartedActivity());
                String log = DiagnosticLog.read(app);
                assertTrue(log, log.contains("[split] tap in split screen: Dasher already visible beside; nothing started"));
                assertTrue(log, log.contains("[split] swap: Dasher already visible beside; nothing started"));
            } finally {
                service.destroy();
            }
        }
    }

    @Test public void aStaleBesideSightingCannotSuppressTheUsersRestorationTap() {
        dasherInstalled();
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        try (ActivityController<WindowActivity> activity = Robolectric.buildActivity(WindowActivity.class).setup()) {
            service.get().onServiceConnected();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            activity.get().bounds = new Rect(0, 40, 1080, 950);
            Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
            OfferFilterService.sawDasherBeside(SystemClock.uptimeMillis());
            Shadows.shadowOf(service.get()).setWindows(java.util.Collections.emptyList());
            assertTrue("the UI grace alone still says beside", OfferFilterService.dasherBeside());
            assertNull(DasherSplit.start(activity.get(), ignored -> {}));
            assertPlainLauncher("only fresh split window metadata suppresses the launch",
                    Shadows.shadowOf(app).getNextStartedActivity());
        } finally {
            service.destroy();
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
