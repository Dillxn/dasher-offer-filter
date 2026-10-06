package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.content.Intent;
import android.os.Looper;
import android.provider.Settings;
import android.text.Spanned;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/**
 * BETA-22 and BETA-16 in Settings, one row each and no paragraph: the version carries a small Beta label, Help opens
 * the website's install help, and a Fix row appears only while Android restricts the app's background work.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class SettingsBetaTest extends AndroidAdapterTestBase {

    private static View settings(ActivityController<MainActivity> activity) {
        View content = activity.get().findViewById(android.R.id.content);
        iconButton(content, "Settings").performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        return content;
    }

    private static Button rowStarting(View content, String title) {
        java.util.List<Button> buttons = new java.util.ArrayList<>();
        collectButtons(content, buttons);
        for (Button button : buttons) {
            if (button.isShown() && button.getText().toString().startsWith(title + "\n")) return button;
        }
        return null;
    }

    @Test public void theVersionCarriesABetaLabelAndHelpOpensTheInstallGuide() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = settings(activity);
            String version = Updater.version(app);
            TextView footer = shownTextContaining(content, "Not a DoorDash app.");
            assertNotNull(footer);
            assertEquals(AppName.NAME + " v" + version + " Beta · Not a DoorDash app.", footer.getText().toString());
            Spanned words = (Spanned) footer.getText();
            BetaProgram.Pill[] pills = words.getSpans(0, words.length(), BetaProgram.Pill.class);
            assertEquals("a small label, next to the version", 1, pills.length);
            assertEquals("Beta", words.subSequence(words.getSpanStart(pills[0]), words.getSpanEnd(pills[0])).toString());
            assertEquals(AppName.NAME + " version " + version + ", beta. Not a DoorDash app.",
                    footer.getContentDescription().toString());

            Button help = rowStarting(content, "Help");
            assertNotNull("one Help row", help);
            assertEquals("Help\nofferfilter.org/install", help.getText().toString());
            help.performClick();
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW, opened.getAction());
            assertEquals("https://offerfilter.org/install/", opened.getDataString());
        }
    }

    @Test public void aBatteryFixRowOnlyWhileAndroidRestrictsBackgroundWork() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = settings(activity);
            assertNull("not restricted: no row", shownIcon(content, BatteryLimits.PROBLEM + ". Fix."));
            if (android.os.Build.VERSION.SDK_INT < 28) return; // Android 8 has no such restriction to read.
            Shadows.shadowOf(app.getSystemService(ActivityManager.class)).setBackgroundRestricted(true);
            activity.pause().resume();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            View fix = shownIcon(content, BatteryLimits.PROBLEM + ". Fix.");
            assertNotNull(fix);
            fix.performClick();
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            assertEquals("the app's own battery page", BatteryLimits.APP_BATTERY, opened.getAction());
            assertEquals("package:" + app.getPackageName(), opened.getDataString());

            Shadows.shadowOf(app.getSystemService(ActivityManager.class)).setBackgroundRestricted(false);
            activity.pause().resume();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertNull("fixed: the row goes", shownIcon(content, BatteryLimits.PROBLEM + ". Fix."));
        }
    }

    @Test @Config(sdk = 35)
    public void withoutAnAppBatteryPageAppInfoOpensInstead() {
        // A phone whose settings have App info but no page for one app's battery use.
        android.content.ComponentName info = new android.content.ComponentName("android.settings", "android.settings.AppInfo");
        org.robolectric.shadows.ShadowPackageManager packages = Shadows.shadowOf(app.getPackageManager());
        packages.addActivityIfNotPresent(info);
        android.content.IntentFilter filter = new android.content.IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        filter.addDataScheme("package");
        packages.addIntentFilterForActivity(info, filter);
        Shadows.shadowOf(app.getSystemService(ActivityManager.class)).setBackgroundRestricted(true);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = settings(activity);
            Shadows.shadowOf(app).checkActivities(true);
            shownIcon(content, BatteryLimits.PROBLEM + ". Fix.").performClick();
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            assertNotNull(opened);
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.getAction());
            assertEquals("Open Battery, then choose Unrestricted or Optimized.",
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        } finally {
            Shadows.shadowOf(app).checkActivities(false);
        }
    }

    @Test public void settingsStillHoldsNoParagraphForThem() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = settings(activity);
            assertNull(shownTextContaining(content, "beta test"));
            assertNull(shownTextContaining(content, "battery optimization"));
            assertNotNull(shownButton(content, "Share report"));
        }
    }
}
