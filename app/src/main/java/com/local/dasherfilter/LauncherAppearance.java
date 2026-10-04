package com.local.dasherfilter;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import java.util.Arrays;

/** The existing appearance choice also colors the launcher. No alarm, worker, fix or stored location is added. */
final class LauncherAppearance {
    static final String DAY = "com.local.dasherfilter.DayLauncher";
    static final String NIGHT = "com.local.dasherfilter.NightLauncher";
    static final long CHECK_MS = 60_000;
    private static long checkedAt = -1;

    /** Only called by already-running, consented service work; sleeping processes need not wake for an icon. */
    static synchronized void syncIfDue(Context context) {
        long now = SystemClock.uptimeMillis();
        if (checkedAt >= 0 && now >= checkedAt && now - checkedAt < CHECK_MS) return;
        sync(context, Appearance.resolve(context));
    }

    /** Idempotent and best effort. Launcher refresh timing and home-screen placement belong to Android. */
    static synchronized boolean sync(Context context, Appearance.State appearance) {
        checkedAt = SystemClock.uptimeMillis();
        try {
            PackageManager packages = context.getPackageManager();
            ComponentName wanted = new ComponentName(context, appearance.night ? NIGHT : DAY);
            ComponentName other = new ComponentName(context, appearance.night ? DAY : NIGHT);
            boolean wantedEnabled = enabled(packages, wanted);
            boolean otherEnabled = enabled(packages, other);
            if (wantedEnabled && !otherEnabled) return true;
            if (Build.VERSION.SDK_INT >= 33) {
                AtomicSwitch.apply(packages, wanted, other);
            } else {
                // Older Android cannot update the pair atomically. Never disable the old entry until the new
                // one is enabled: an interrupted switch may leave two discoverable icons, never deliberately zero.
                if (!wantedEnabled) packages.setComponentEnabledSetting(wanted,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
                if (!enabled(packages, wanted)) return false;
                if (otherEnabled) packages.setComponentEnabledSetting(other,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
            return enabled(packages, wanted) && !enabled(packages, other);
        } catch (RuntimeException refused) {
            // Icon presentation cannot interrupt filtering or prevent the real Activity from being opened.
            // The next existing lifecycle/event refresh retries; no extra worker, alarm or location is needed.
            return false;
        }
    }

    private static boolean enabled(PackageManager packages, ComponentName component) {
        int state = packages.getComponentEnabledSetting(component);
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && DAY.equals(component.getClassName()));
    }

    /** Both current launcher tasks and pre-alias tasks can be brought back without stacking another Activity. */
    static boolean ourHome(Context context, ComponentName component) {
        return component != null && context.getPackageName().equals(component.getPackageName())
                && (MainActivity.class.getName().equals(component.getClassName())
                        || DAY.equals(component.getClassName()) || NIGHT.equals(component.getClassName()));
    }

    @android.annotation.TargetApi(33)
    private static final class AtomicSwitch {
        static void apply(PackageManager packages, ComponentName wanted, ComponentName other) {
            packages.setComponentEnabledSettings(Arrays.asList(
                    new PackageManager.ComponentEnabledSetting(wanted,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP),
                    new PackageManager.ComponentEnabledSetting(other,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)));
        }
    }

    private LauncherAppearance() {}
}
