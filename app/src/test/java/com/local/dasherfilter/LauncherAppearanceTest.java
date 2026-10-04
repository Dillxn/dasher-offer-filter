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
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowApplicationPackageManager;
import org.robolectric.shadows.ShadowSystemClock;

/** Launcher identity must survive changing color, interrupted switches, reboot and package replacement. */
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

    private void only(String name) throws Exception {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(app.getPackageName());
        List<ResolveInfo> entries = packages.queryIntentActivities(query, 0);
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

    @Test public void initialIconAndBothDirectionsKeepOneActualLauncherAndTheStableTarget() throws Exception {
        only(LauncherAppearance.DAY);
        Appearance.choose(app, Appearance.Mode.NIGHT);
        only(LauncherAppearance.NIGHT);
        Appearance.choose(app, Appearance.Mode.DAY);
        only(LauncherAppearance.DAY);
        for (String change : Packages.changes) assertFalse(change.contains(MainActivity.class.getName()));
    }

    @Test public void repeatedDayDefaultAndNightRefreshDoNotChurnPackageState() throws Exception {
        LauncherAppearance.sync(app, new Appearance.State(Appearance.Mode.DAY, false, false));
        assertTrue("manifest default day already has the right state", Packages.changes.isEmpty());
        Appearance.choose(app, Appearance.Mode.NIGHT);
        only(LauncherAppearance.NIGHT);
        Packages.changes.clear();
        for (int i = 0; i < 4; i++) LauncherAppearance.sync(app, Appearance.resolve(app));
        assertTrue(Packages.changes.isEmpty());
    }

    @Test public void autoAndSystemUseTheSameResolvedPaletteAsTheApp() throws Exception {
        AreaMap.setEnabled(app, false);
        TimeZone old = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            for (String utc : new String[] {"2026-10-04T05:59:00Z", "2026-10-04T06:00:00Z",
                    "2026-10-04T17:59:00Z", "2026-10-04T18:00:00Z"}) {
                Appearance.State state = Appearance.resolve(app, Instant.parse(utc).toEpochMilli());
                assertEquals(Appearance.Mode.AUTO, state.mode);
                LauncherAppearance.sync(app, state);
                only(state.night ? LauncherAppearance.NIGHT : LauncherAppearance.DAY);
            }
            Appearance.choose(app, Appearance.Mode.SYSTEM);
            RuntimeEnvironment.setQualifiers("night");
            LauncherAppearance.sync(app, Appearance.resolve(app));
            only(LauncherAppearance.NIGHT);
            RuntimeEnvironment.setQualifiers("notnight");
            LauncherAppearance.sync(app, Appearance.resolve(app));
            only(LauncherAppearance.DAY);
        } finally { TimeZone.setDefault(old); }
    }

    @Test public void persistedNightOverridesAndInterruptedStatesAreReconciledOnBootAndUpdate() throws Exception {
        Appearance.choose(app, Appearance.Mode.NIGHT);
        only(LauncherAppearance.NIGHT);
        // Android keeps component overrides on upgrade. Boot/update must preserve the chosen night entry.
        new UpdateReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        only(LauncherAppearance.NIGHT);
        new UpdateReceiver().onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        only(LauncherAppearance.NIGHT);
        for (int broken : new int[] {PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED}) {
            packages.setComponentEnabledSetting(component(LauncherAppearance.DAY), broken, PackageManager.DONT_KILL_APP);
            packages.setComponentEnabledSetting(component(LauncherAppearance.NIGHT), broken, PackageManager.DONT_KILL_APP);
            assertTrue(LauncherAppearance.sync(app, Appearance.resolve(app)));
            only(LauncherAppearance.NIGHT);
        }
    }

    @Test public void existingServiceEventsBoundRefreshWithoutSchedulingTheirOwnWork() throws Exception {
        RuntimeEnvironment.setQualifiers("notnight");
        Appearance.choose(app, Appearance.Mode.SYSTEM);
        RuntimeEnvironment.setQualifiers("night");
        Packages.changes.clear();
        for (int i = 0; i < 10; i++) LauncherAppearance.syncIfDue(app);
        only(LauncherAppearance.DAY);
        assertTrue(Packages.changes.isEmpty());
        ShadowSystemClock.advanceBy(Duration.ofMillis(LauncherAppearance.CHECK_MS));
        LauncherAppearance.syncIfDue(app);
        only(LauncherAppearance.NIGHT);
        Packages.changes.clear();
        LauncherAppearance.syncIfDue(app);
        assertTrue(Packages.changes.isEmpty());
    }

    @Test @Config(sdk = 26) public void failedEnableCannotRemoveTheWorkingLauncherAndFailedDisableCanRecover() throws Exception {
        Appearance.State night = new Appearance.State(Appearance.Mode.NIGHT, true, false);
        Packages.refuse = LauncherAppearance.NIGHT;
        assertFalse(LauncherAppearance.sync(app, night));
        only(LauncherAppearance.DAY);
        assertEquals("no disable after failed enable", 1, Packages.changes.size());
        Packages.changes.clear();
        Packages.refuse = LauncherAppearance.DAY;
        assertFalse(LauncherAppearance.sync(app, night));
        List<ResolveInfo> entries = packages.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.getPackageName()), 0);
        assertEquals("a failed old-entry disable leaves discoverability, never zero entries", 2, entries.size());
        assertTrue(Packages.changes.get(0).startsWith(LauncherAppearance.NIGHT));
        Packages.refuse = null;
        assertTrue(LauncherAppearance.sync(app, night));
        only(LauncherAppearance.NIGHT);
    }

    @Test @Config(sdk = 35) public void failedAtomicSwitchLeavesThePreviousLauncherDiscoverable() throws Exception {
        Packages.refuse = "atomic";
        assertFalse(LauncherAppearance.sync(app, new Appearance.State(Appearance.Mode.NIGHT, true, false)));
        only(LauncherAppearance.DAY);
        Packages.refuse = null;
        assertTrue(LauncherAppearance.sync(app, new Appearance.State(Appearance.Mode.NIGHT, true, false)));
        only(LauncherAppearance.NIGHT);
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
            Appearance.choose(app, night ? Appearance.Mode.NIGHT : Appearance.Mode.DAY);
            only(night ? LauncherAppearance.NIGHT : LauncherAppearance.DAY);
            Drawable icon = packages.getActivityIcon(component(night ? LauncherAppearance.NIGHT : LauncherAppearance.DAY));
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
            if ("atomic".equals(refuse)) throw new SecurityException("simulated refusal");
            assertEquals(2, settings.size());
            for (PackageManager.ComponentEnabledSetting setting : settings) {
                changes.add(setting.getComponentName().getClassName() + ":" + setting.getEnabledState());
                assertEquals(PackageManager.DONT_KILL_APP, setting.getEnabledFlags());
            }
            super.setComponentEnabledSettings(settings);
        }
    }
}
