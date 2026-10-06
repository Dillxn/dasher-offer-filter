package com.local.dasherfilter;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityWindowInfo;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowPendingIntent;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A tap on one of our offer cards opens Dasher as its launcher icon does: a screen of ours that never shows starts
 * Dasher's own launch intent (in its own half when it is already beside us in split screen, into the other half
 * when only the tap screen is in one), clears that card and is gone. Dasher's own notification
 * intent opened a screen that could not find the offer on a real phone. Simulated Android only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class CardTapAdapterTest {
    /** Named as text, so these tests compile (and fail) on code from before the tap screen existed. */
    private static final String TAP_SCREEN = "com.local.dasherfilter.OpenDasherActivity";
    private static final ComponentName DASHER_HOME =
            new ComponentName("com.doordash.driverapp", "com.doordash.driverapp.Home");
    private static final int CLEARS_OR_RESETS = Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK
            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;

    private Application app;
    private ServiceController<OfferNotificationService> listener;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Updater.setEnabled(app, false);
        OfferAlerts.ensureChannel(app);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DecisionLog.forgetCache();
        OfferFilterService.scanLooperForTests = android.os.Looper.getMainLooper();
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void stop() {
        listener.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.scanLooperForTests = null;
    }

    /** Dasher installed on the simulated phone, with its launcher activity. */
    private void dasherInstalled() {
        org.robolectric.shadows.ShadowPackageManager packages = Shadows.shadowOf(app.getPackageManager());
        android.content.pm.ActivityInfo entry = packages.addActivityIfNotPresent(DASHER_HOME);
        entry.enabled = true;
        entry.exported = true;
        IntentFilter launcher = new IntentFilter(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        packages.addIntentFilterForActivity(DASHER_HOME, launcher);
    }

    /** Dasher's own tap intent on its offer notification, as Dasher made it. */
    private PendingIntent dashersOwnIntent() {
        PendingIntent own = PendingIntent.getActivity(app, 7, new Intent().setComponent(
                new ComponentName("com.doordash.driverapp", "com.doordash.driverapp.OfferNotificationActivity")),
                PendingIntent.FLAG_IMMUTABLE);
        Shadows.shadowOf(own).setCreatorPackage("com.doordash.driverapp");
        return own;
    }

    /** Dasher's offer notification arrives with Dasher in the background: our card for it. */
    private Notification cardFor(String store, PendingIntent dashersOwn) {
        Notification payload = new Notification.Builder(app, "source")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText("New Order: Go to " + store)
                .setContentIntent(dashersOwn)
                .build();
        listener.get().onNotificationPosted(new StatusBarNotification("com.doordash.driverapp",
                "com.doordash.driverapp", 3, "NEW_ORDER", 10001, 0, 0, payload, android.os.Process.myUserHandle(),
                System.currentTimeMillis()), null);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, notifications().size());
        return notifications().getAllNotifications().get(0);
    }

    private ShadowNotificationManager notifications() {
        return Shadows.shadowOf(app.getSystemService(NotificationManager.class));
    }

    /** What the card's tap starts: our tap screen, as an immutable activity intent (never a service or broadcast). */
    private Intent tapScreenOf(Notification card) {
        assertNotNull("the card can be tapped", card.contentIntent);
        ShadowPendingIntent tap = Shadows.shadowOf(card.contentIntent);
        assertTrue("an activity, which Android lets a notification tap start", tap.isActivityIntent());
        assertTrue(tap.isImmutable());
        Intent screen = tap.getSavedIntent();
        assertEquals("the tap goes to our tap screen, not Dasher's own notification intent", TAP_SCREEN,
                screen.getComponent() == null ? null : screen.getComponent().getClassName());
        return screen;
    }

    /** The user taps: the tap screen is built from the card's intent and runs. */
    private ActivityController<? extends Activity> tap(Intent screen, boolean splitScreen) throws Exception {
        ActivityController<? extends Activity> controller = Robolectric.buildActivity(
                Class.forName(TAP_SCREEN).asSubclass(Activity.class), screen);
        Shadows.shadowOf(controller.get()).setInMultiWindowMode(splitScreen);
        return controller.setup();
    }

    /** Already observed app identities, followed by fresh metadata only at the notification tap. */
    private ServiceController<OfferFilterService> splitWindows(boolean dasher) {
        ServiceController<OfferFilterService> controller = Robolectric.buildService(OfferFilterService.class).create();
        OfferFilterService service = controller.get();
        service.onServiceConnected();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Set<Integer> ours = ReflectionHelpers.getField(service, "ownWindows");
        Set<Integer> sibling = ReflectionHelpers.getField(service, dasher ? "dasherWindowIds" : "otherWindows");
        ours.add(101);
        sibling.add(102);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(101, AccessibilityWindowInfo.TYPE_APPLICATION, true, new Rect(0, 0, 1080, 1000)),
                window(103, AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, false, new Rect(0, 1000, 1080, 1040)),
                window(102, AccessibilityWindowInfo.TYPE_APPLICATION, false, new Rect(0, 1040, 1080, 2040))));
        return controller;
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

    @Test
    public void splitAndCardLaunchUseTheActualLauncherWithoutAPackageBoundTaskIntent() throws Exception {
        dasherInstalled();
        ComponentName info = new ComponentName("com.doordash.driverapp", "com.doordash.driverapp.Info");
        org.robolectric.shadows.ShadowPackageManager packages = Shadows.shadowOf(app.getPackageManager());
        android.content.pm.ActivityInfo details = packages.addActivityIfNotPresent(info);
        details.enabled = true;
        details.exported = true;
        IntentFilter information = new IntentFilter(Intent.ACTION_MAIN);
        information.addCategory(Intent.CATEGORY_INFO);
        packages.addIntentFilterForActivity(info, information);
        Intent launch = DasherSplit.launcher(app);
        assertEquals("the launcher entry, never the package-information front door", DASHER_HOME, launch.getComponent());
        assertNull("a package-bound intent can create another start screen on older Android", launch.getPackage());
        assertTrue(launch.hasCategory(Intent.CATEGORY_LAUNCHER));
        assertEquals(0, launch.getFlags() & CLEARS_OR_RESETS);
        Intent adjacent = DasherSplit.dasher(app);
        assertEquals(DASHER_HOME, adjacent.getComponent());
        assertNull(adjacent.getPackage());
        ActivityController<? extends Activity> tapped = tap(tapScreenOf(cardFor("Store A", null)), false);
        assertEquals(DASHER_HOME, Shadows.shadowOf(app).getNextStartedActivity().getComponent());
        tapped.destroy();
    }

    @Test
    public void tappingACardOpensDasherAsItsLauncherIconDoesAndClearsTheCard() throws Exception {
        dasherInstalled();
        Notification card = cardFor("Store A", dashersOwnIntent());
        Intent screen = tapScreenOf(card);

        ActivityController<? extends Activity> tapped = tap(screen, false);
        Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull("the tap opens Dasher", opened);
        assertEquals(DASHER_HOME, opened.getComponent());
        assertEquals(Intent.ACTION_MAIN, opened.getAction());
        assertTrue(opened.hasCategory(Intent.CATEGORY_LAUNCHER));
        assertEquals("Dasher's own task comes forward as it is: nothing cleared, reset or reordered",
                Intent.FLAG_ACTIVITY_NEW_TASK, opened.getFlags());
        assertEquals("the card is cleared", 0, notifications().size());
        assertTrue("the tap screen is gone at once", tapped.get().isFinishing());
        assertNull("nothing else is opened", Shadows.shadowOf(app).getNextStartedActivity());
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[alert] card tapped → opened Dasher (launcher)"));
        tapped.destroy();
    }

    @Test
    public void inSplitScreenWithoutDasherTheTapOpensDasherInTheOtherHalf() throws Exception {
        dasherInstalled();
        ServiceController<OfferFilterService> reading = splitWindows(false);
        try {
        ActivityController<? extends Activity> tapped = tap(tapScreenOf(cardFor("Store A", dashersOwnIntent())), true);
        Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull(opened);
        assertEquals(DASHER_HOME, opened.getComponent());
        assertEquals("Dasher's launch intent into the other half, as the Split button opens it",
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT, opened.getFlags());
        assertEquals(0, notifications().size());
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[alert] card tapped → opened Dasher (in the other half)"));
        tapped.destroy();
        } finally { reading.destroy(); }
    }

    @Test
    public void withDasherAlreadyBesideTheTapBringsDasherForwardInItsOwnHalf() throws Exception {
        dasherInstalled();
        ServiceController<OfferFilterService> screenReading = splitWindows(true);
        try {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
            // The tap screen opened in one half of the split, beside Dasher: before, it sent Dasher "adjacent" to
            // itself, which could move Dasher's task rather than bring it forward where it is.
            for (boolean tapScreenInAHalf : new boolean[] {true, false}) {
                ActivityController<? extends Activity> tapped =
                        tap(tapScreenOf(cardFor(tapScreenInAHalf ? "Store A" : "Store B", null)), tapScreenInAHalf);
                Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
                assertNotNull(opened);
                assertEquals(DASHER_HOME, opened.getComponent());
                assertEquals("Dasher's plain launch intent: its task comes forward in its own half",
                        Intent.FLAG_ACTIVITY_NEW_TASK, opened.getFlags());
                assertEquals(0, opened.getFlags() & CLEARS_OR_RESETS);
                assertEquals(0, notifications().size());
                String log = DiagnosticLog.read(app);
                assertTrue(log, log.contains("[alert] card tapped → opened Dasher (in its own half)"));
                tapped.destroy();
            }
        } finally {
            screenReading.destroy();
        }
    }

    @Test public void aStaleBesideSightingDoesNotDescribeTheCurrentMapsPair() throws Exception {
        dasherInstalled();
        ServiceController<OfferFilterService> reading = splitWindows(false);
        try {
            OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
            assertTrue(OfferFilterService.dasherBeside());
            try (ActivityController<? extends Activity> tapped = tap(tapScreenOf(cardFor("Store A", null)), true)) {
                Intent launch = Shadows.shadowOf(app).getNextStartedActivity();
                assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT, launch.getFlags());
                assertEquals("only the user-requested launch", 0, launch.getFlags() & CLEARS_OR_RESETS);
            }
        } finally { reading.destroy(); }
    }

    @Test public void missingFreshWindowsNeverGuessesAnAdjacentTask() throws Exception {
        dasherInstalled();
        ServiceController<OfferFilterService> reading = splitWindows(false);
        try {
            reading.get().windowSource = () -> null;
            try (ActivityController<? extends Activity> tapped = tap(tapScreenOf(cardFor("Store A", null)), true)) {
                assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, Shadows.shadowOf(app).getNextStartedActivity().getFlags());
                assertEquals("the card was still handled", 0, notifications().size());
            }
        } finally { reading.destroy(); }
    }

    @Test public void anUnidentifiedPaneNeverGuessesThatDasherIsAbsent() throws Exception {
        dasherInstalled();
        ServiceController<OfferFilterService> reading = splitWindows(false);
        try {
            Set<Integer> known = ReflectionHelpers.getField(reading.get(), "otherWindows");
            known.clear();
            try (ActivityController<? extends Activity> tapped = tap(tapScreenOf(cardFor("Store A", null)), true)) {
                assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, Shadows.shadowOf(app).getNextStartedActivity().getFlags());
            }
        } finally { reading.destroy(); }
    }

    @Test public void obstructedOrIncompleteWindowPairsUseOnlyTheOrdinaryLauncher() throws Exception {
        dasherInstalled();
        for (String state : new String[] {"no divider", "no active pane", "overlap", "empty bounds",
                "keyboard", "shade", "third app", "picture in picture", "metadata exception"}) {
            ServiceController<OfferFilterService> reading = splitWindows(false);
            try {
                List<AccessibilityWindowInfo> windows = new ArrayList<>(reading.get().windowSource.get());
                ShadowAccessibilityWindowInfo first = Shadow.extract(windows.get(0));
                ShadowAccessibilityWindowInfo last = Shadow.extract(windows.get(2));
                switch (state) {
                    case "no divider": windows.remove(1); break;
                    case "no active pane": first.setActive(false); break;
                    case "overlap": last.setBoundsInScreen(new Rect(0, 0, 1080, 2040)); break;
                    case "empty bounds": last.setBoundsInScreen(new Rect()); break;
                    case "keyboard": windows.add(window(104, AccessibilityWindowInfo.TYPE_INPUT_METHOD, false,
                            new Rect(0, 1700, 1080, 2040))); break;
                    case "shade": windows.add(window(104, AccessibilityWindowInfo.TYPE_SYSTEM, true,
                            new Rect(0, 0, 1080, 2040))); break;
                    case "third app": windows.add(window(104, AccessibilityWindowInfo.TYPE_APPLICATION, false,
                            new Rect(100, 200, 900, 1400))); break;
                    case "picture in picture": last.setPictureInPicture(true); break;
                    default: break;
                }
                reading.get().windowSource = () -> {
                    if (state.equals("metadata exception")) throw new IllegalStateException("unavailable");
                    return windows;
                };
                try (ActivityController<? extends Activity> tapped = tap(tapScreenOf(cardFor("Store " + state, null)), true)) {
                    assertEquals(state, Intent.FLAG_ACTIVITY_NEW_TASK,
                            Shadows.shadowOf(app).getNextStartedActivity().getFlags());
                    assertNull(state + " must not cause a retry", Shadows.shadowOf(app).getNextStartedActivity());
                }
            } finally { reading.destroy(); }
        }
    }

    @Test @Config(sdk = {35, 36})
    public void aFloatingCardActivityDoesNotLaunchIntoAnotherSplitPane() throws Exception {
        dasherInstalled();
        ServiceController<OfferFilterService> reading = splitWindows(false);
        try (ActivityController<? extends Activity> tapped = Robolectric.buildActivity(
                Class.forName(TAP_SCREEN).asSubclass(Activity.class), tapScreenOf(cardFor("Store A", null)))) {
            Shadows.shadowOf(tapped.get()).setInMultiWindowMode(true);
            floatingWindow(tapped.get());
            tapped.setup();
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, Shadows.shadowOf(app).getNextStartedActivity().getFlags());
        } finally { reading.destroy(); }
    }

    @Test public void aPictureInPictureCardActivityDoesNotRequestAnotherPane() throws Exception {
        dasherInstalled();
        ServiceController<OfferFilterService> reading = splitWindows(false);
        ActivityController<? extends Activity> tapped = Robolectric.buildActivity(
                Class.forName(TAP_SCREEN).asSubclass(Activity.class), tapScreenOf(cardFor("Store A", null)));
        try {
            Shadows.shadowOf(tapped.get()).setInMultiWindowMode(true);
            tapped.get().enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder().build());
            tapped.setup();
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, Shadows.shadowOf(app).getNextStartedActivity().getFlags());
        } finally { tapped.destroy(); reading.destroy(); }
    }

    private static void floatingWindow(Activity activity) {
            WindowManager original = activity.getWindowManager();
            WindowManager floating = (WindowManager) Proxy.newProxyInstance(WindowManager.class.getClassLoader(),
                    new Class<?>[] {WindowManager.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getCurrentWindowMetrics")) return metrics(new Rect(100, 200, 900, 1400));
                        if (method.getName().equals("getMaximumWindowMetrics")) return metrics(new Rect(0, 0, 1080, 2040));
                        return method.invoke(original, args);
                    });
            ReflectionHelpers.setField(activity, "mWindowManager", floating);
    }
        private static WindowMetrics metrics(Rect bounds) {
            return new WindowMetrics(bounds, new WindowInsets.Builder()
                    .setInsetsIgnoringVisibility(WindowInsets.Type.systemBars(), Insets.NONE).build());
        }

    @Test
    public void whenDasherCannotBeOpenedAtTheTapDashersOwnIntentIsSent() throws Exception {
        dasherInstalled();
        Intent screen = tapScreenOf(cardFor("Store A", dashersOwnIntent()));
        // Dasher's launcher entry is gone by the time of the tap.
        Shadows.shadowOf(app.getPackageManager()).clearIntentFilterForActivity(DASHER_HOME);

        ActivityController<? extends Activity> tapped = tap(screen, false);
        assertTrue("the tap screen is gone at once", tapped.get().isFinishing());
        assertEquals(0, notifications().size());
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[alert] card tapped → opened Dasher (its own notification's screen)"));
        tapped.destroy();
    }

    @Test
    public void withoutDashersLaunchIntentTheCardKeepsDashersOwnTap() {
        PendingIntent own = dashersOwnIntent();
        Notification card = cardFor("Store A", own);
        assertSame("nothing of ours can open Dasher, so Dasher's own intent stays", own, card.contentIntent);
    }
}
