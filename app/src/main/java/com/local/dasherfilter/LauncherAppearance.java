package com.local.dasherfilter;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;

/**
 * The app's launcher entry. Two aliases, DayLauncher and NightLauncher, target the permanently enabled MainActivity.
 * Versions 0.4.68 to 0.4.72 swapped them with the theme, but disabling the alias a home-screen icon points to removes
 * that icon on common launchers (Pixel, One UI): the app seemed to vanish at sunset. So no theme touches them any more:
 * Auto, System and an explicit Day or Night change only the app's own screens, and whichever alias is enabled stays
 * enabled, with its icon, through every update (an older version's night entry included). The one repair: with no
 * alias enabled at all (a switch an older version left half done), the day alias is enabled again. Nothing here ever
 * disables an alias. No alarm, worker, location or wake is added.
 */
final class LauncherAppearance {
    static final String DAY = "com.local.dasherfilter.DayLauncher";
    static final String NIGHT = "com.local.dasherfilter.NightLauncher";
    static final long CHECK_MS = 60_000;
    private static long checkedAt = -1;

    /** From already-running, consented service work: {@link #keep}, at most once a minute. */
    static synchronized void syncIfDue(Context context) {
        long now = SystemClock.uptimeMillis();
        if (checkedAt >= 0 && now >= checkedAt && now - checkedAt < CHECK_MS) return;
        keep(context);
    }

    /**
     * Keeps the launcher entry the phone has, whatever the theme: never disables an alias and never swaps one for the
     * other. Only when neither alias is enabled is the day one enabled, so the app always has a way to be opened.
     * Idempotent and best effort; launcher refresh timing and home-screen placement belong to Android.
     *
     * @return whether an alias is enabled now
     */
    static synchronized boolean keep(Context context) {
        checkedAt = SystemClock.uptimeMillis();
        try {
            PackageManager packages = context.getPackageManager();
            ComponentName day = new ComponentName(context, DAY);
            if (enabled(packages, day) || enabled(packages, new ComponentName(context, NIGHT))) return true;
            packages.setComponentEnabledSetting(day, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            DiagnosticLog.log(context, "launcher", "no launcher entry was enabled; the day entry was enabled again");
            return enabled(packages, day);
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

    private LauncherAppearance() {}
}
