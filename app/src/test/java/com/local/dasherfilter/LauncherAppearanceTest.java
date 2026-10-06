package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowApplicationPackageManager;
import org.robolectric.shadows.ShadowSystemClock;

/**
 * BETA-13: the home-screen icon must never vanish. Disabling the alias a pinned icon points to removes that icon on
 * common launchers, so no theme (Auto at sunset, System, an explicit Day or Night) enables or disables an alias any
 * more; whichever is enabled stays, through boot and updates, and only a phone with no launcher entry at all gets the
 * day one back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, shadows = LauncherAppearanceTest.Packages.class)
public class LauncherAppearanceTest {
    private Application app;
    private PackageManager packages;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        packages = app.getPackageManager();
        Packages.changes.clear();
        Packages.refuse = null;
        Updater.setEnabled(app, false);
        app.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private ComponentName component(String name) { return new ComponentName(app, name); }

    /**
     * As an older version's theme switch left the phone: {@code alias} alone enabled. For tests only, written straight
     * to the package manager (the app itself never disables an alias now).
     */
    static void enableOnly(Context context, String alias) {
        PackageManager packages = context.getPackageManager();
        String other = LauncherAppearance.DAY.equals(alias) ? LauncherAppearance.NIGHT : LauncherAppearance.DAY;
        packages.setComponentEnabledSetting(new ComponentName(context, alias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        packages.setComponentEnabledSetting(new ComponentName(context, other),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }

    private List<ResolveInfo> entries() {
        return packages.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(app.getPackageName()), 0);
    }

    private void only(String name) throws Exception {
        List<ResolveInfo> entries = entries();
        assertEquals("exactly one discoverable launcher", 1, entries.size());
        assertEquals(name, entries.get(0).activityInfo.name);
        assertEquals(MainActivity.class.getName(), entries.get(0).activityInfo.targetActivity);
        assertTrue("both SDKs load the alias's adaptive icon", packages.getActivityIcon(component(name))
                instanceof AdaptiveIconDrawable);
        assertEquals(name.equals(LauncherAppearance.NIGHT) ? R.mipmap.ic_launcher_night : R.mipmap.ic_launcher,
                entries.get(0).activityInfo.icon);
        Intent launch = DasherSplit.launcher(app, app.getPackageName());
        assertNotNull("the production launcher resolver must accept the enabled alias", launch);
        assertEquals(component(name), launch.getComponent());
        assertNull(launch.getPackage());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, launch.getFlags());
        ActivityInfo main = packages.getActivityInfo(component(MainActivity.class.getName()), 0);
        assertTrue("consent/updater and previous explicit shortcuts keep their real target", main.enabled);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                packages.getComponentEnabledSetting(component(MainActivity.class.getName())));
        assertNotNull(packages.resolveActivity(new Intent(app, MainActivity.class), 0));
        assertNotNull("previous pinned launcher components still resolve explicitly", packages.resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(component(MainActivity.class.getName())), 0));
    }

    @Test public void everyThemeChoiceKeepsTheOneLauncherEntryUntouched() throws Exception {
        only(LauncherAppearance.DAY);
        for (Appearance.Mode mode : new Appearance.Mode[] {Appearance.Mode.NIGHT, Appearance.Mode.DAY,
                Appearance.Mode.SYSTEM, Appearance.Mode.AUTO, Appearance.Mode.NIGHT}) {
            assertTrue(Appearance.choose(app, mode));
            only(LauncherAppearance.DAY);
        }
        assertTrue("an explicit Day or Night choice changes the app's screens only: " + Packages.changes,
                Packages.changes.isEmpty());
    }

    @Test public void autoAtSunsetAndSystemNightNeverSwapTheEntry() throws Exception {
        AreaMap.setEnabled(app, false);
        TimeZone old = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            for (String utc : new String[] {"2026-10-04T05:59:00Z", "2026-10-04T06:00:00Z",
                    "2026-10-04T17:59:00Z", "2026-10-04T18:00:00Z"}) {
                Appearance.State state = Appearance.resolve(app, Instant.parse(utc).toEpochMilli());
                assertEquals(Appearance.Mode.AUTO, state.mode);
                assertTrue(LauncherAppearance.keep(app));
                only(LauncherAppearance.DAY);
            }
            Appearance.choose(app, Appearance.Mode.SYSTEM);
            RuntimeEnvironment.setQualifiers("night");
            assertTrue(Appearance.resolve(app).night);
            assertTrue(LauncherAppearance.keep(app));
            only(LauncherAppearance.DAY);
            RuntimeEnvironment.setQualifiers("notnight");
            assertTrue(LauncherAppearance.keep(app));
            only(LauncherAppearance.DAY);
        } finally { TimeZone.setDefault(old); }
        assertTrue(Packages.changes.toString(), Packages.changes.isEmpty());
    }

    @Test public void aNightEntryAnOlderVersionLeftIsKeptOnBootUpdateAndEveryTheme() throws Exception {
        enableOnly(app, LauncherAppearance.NIGHT);
        only(LauncherAppearance.NIGHT);
        Packages.changes.clear();
        // Never swapped back during the update to this version: its pinned home-screen icon points to it.
        new UpdateReceiver().onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        only(LauncherAppearance.NIGHT);
        new UpdateReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        only(LauncherAppearance.NIGHT);
        for (Appearance.Mode mode : Appearance.Mode.values()) {
            Appearance.choose(app, mode);
            ShadowSystemClock.advanceBy(Duration.ofMillis(LauncherAppearance.CHECK_MS));
            LauncherAppearance.syncIfDue(app);
            only(LauncherAppearance.NIGHT);
        }
        assertTrue(Packages.changes.toString(), Packages.changes.isEmpty());
    }

    @Test public void theMainPageFollowingTheSunLeavesTheLauncherAlone() throws Exception {
        enableOnly(app, LauncherAppearance.NIGHT);
        Packages.changes.clear();
        Appearance.choose(app, Appearance.Mode.DAY);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertFalse("the page itself is in the day palette", new Ui(activity.get()).dark);
            List<ResolveInfo> entries = entries();
            assertEquals(1, entries.size());
            assertEquals("its pinned night entry stays", LauncherAppearance.NIGHT, entries.get(0).activityInfo.name);
        }
        assertTrue(Packages.changes.toString(), Packages.changes.isEmpty());
    }

    @Test public void twoEntriesLeftByAnInterruptedOldSwitchBothStay() throws Exception {
        packages.setComponentEnabledSetting(component(LauncherAppearance.NIGHT),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        Packages.changes.clear();
        assertTrue(LauncherAppearance.keep(app));
        new UpdateReceiver().onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        assertEquals("either may be the one pinned, so neither is disabled", 2, entries().size());
        assertTrue(Packages.changes.toString(), Packages.changes.isEmpty());
    }

    @Test public void noEntryAtAllGetsTheDayEntryBackAndNothingElse() throws Exception {
        for (String alias : new String[] {LauncherAppearance.DAY, LauncherAppearance.NIGHT}) {
            packages.setComponentEnabledSetting(component(alias), PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
        assertTrue(entries().isEmpty());
        Packages.changes.clear();
        new UpdateReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        only(LauncherAppearance.DAY);
        assertEquals(1, Packages.changes.size());
        assertEquals(LauncherAppearance.DAY + ":" + PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                Packages.changes.get(0));
    }

    @Test public void aRefusedRepairChangesNothingElseAndALaterServiceEventRetries() throws Exception {
        for (String alias : new String[] {LauncherAppearance.DAY, LauncherAppearance.NIGHT}) {
            packages.setComponentEnabledSetting(component(alias), PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
        Packages.refuse = LauncherAppearance.DAY;
        assertFalse(LauncherAppearance.keep(app));
        assertTrue(entries().isEmpty());
        Packages.refuse = null;
        Packages.changes.clear();
        LauncherAppearance.syncIfDue(app);
        assertTrue("service events look at most once a minute", Packages.changes.isEmpty());
        ShadowSystemClock.advanceBy(Duration.ofMillis(LauncherAppearance.CHECK_MS));
        LauncherAppearance.syncIfDue(app);
        only(LauncherAppearance.DAY);
        Packages.changes.clear();
        ShadowSystemClock.advanceBy(Duration.ofMillis(LauncherAppearance.CHECK_MS));
        LauncherAppearance.syncIfDue(app);
        assertTrue("a working entry is left as it is", Packages.changes.isEmpty());
    }

    @Test public void onlyOurExactHomeComponentsQualifyForTaskReturn() {
        for (String name : new String[] {MainActivity.class.getName(), LauncherAppearance.DAY, LauncherAppearance.NIGHT}) {
            assertTrue(LauncherAppearance.ourHome(app, component(name)));
            assertFalse(LauncherAppearance.ourHome(app, new ComponentName("other.package", name)));
        }
        assertFalse(LauncherAppearance.ourHome(app, component(LegalActivity.class.getName())));
        assertFalse(LauncherAppearance.ourHome(app, null));
    }

    @Test @Config(sdk = 35) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void renderBothAdaptiveIconsWithTheSameRecognizableMascot() throws Exception {
        File directory = new File("build/reports/launcher-appearance");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        for (boolean night : new boolean[] {false, true}) {
            String alias = night ? LauncherAppearance.NIGHT : LauncherAppearance.DAY;
            enableOnly(app, alias);
            only(alias);
            Drawable icon = packages.getActivityIcon(component(alias));
            assertTrue(icon instanceof AdaptiveIconDrawable);
            Bitmap bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, 432, 432);
            icon.draw(new Canvas(bitmap));
            try (FileOutputStream stream = new FileOutputStream(new File(directory, night ? "night.png" : "day.png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            } finally { bitmap.recycle(); }
        }
    }

    @Implements(className = "android.app.ApplicationPackageManager")
    public static class Packages extends ShadowApplicationPackageManager {
        static final List<String> changes = new ArrayList<>();
        static String refuse;

        @Implementation protected void setComponentEnabledSetting(ComponentName component, int state, int flags) {
            if (component.getClassName().equals(LauncherAppearance.DAY)
                    || component.getClassName().equals(LauncherAppearance.NIGHT)) {
                changes.add(component.getClassName() + ":" + state);
                assertEquals(PackageManager.DONT_KILL_APP, flags);
            }
            if (component.getClassName().equals(refuse)) throw new SecurityException("simulated refusal");
            super.setComponentEnabledSetting(component, state, flags);
        }

        @Implementation(minSdk = 33)
        protected void setComponentEnabledSettings(List<PackageManager.ComponentEnabledSetting> settings) {
            for (PackageManager.ComponentEnabledSetting setting : settings) {
                changes.add(setting.getComponentName().getClassName() + ":" + setting.getEnabledState());
                // Whichever setter changes a launcher entry, the app is never killed for it.
                assertEquals(PackageManager.DONT_KILL_APP, setting.getEnabledFlags());
            }
            super.setComponentEnabledSettings(settings);
        }
    }
}
