package com.local.dasherfilter;

import android.app.ActivityManager;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import java.util.function.Consumer;

/**
 * Android's "Restricted" battery setting for this app (ActivityManager.isBackgroundRestricted, Android 9 and later)
 * stops its background jobs: update checks and feedback sending wait until the app is opened. So does Android's
 * restricted standby bucket (Android 11 and later), where a services-only app its user rarely opens can land: One UI's
 * "Deep sleeping apps" may put it there (unverified on a Galaxy). Settings then shows one Fix row, "Battery limits
 * background work", that opens the app's own battery page where Android has one (else App info, one tap from Battery),
 * with a toast naming the choice that lifts it. Shown only while a restriction is on; nothing is changed without the
 * user.
 */
final class BatteryLimits {
    static final String PROBLEM = "Battery limits background work";
    /** AOSP's page for one app's battery use (Android 12 and later); not a public SDK constant, so OEMs may lack it. */
    static final String APP_BATTERY = "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL";

    private BatteryLimits() {}

    /** Whether Android restricts this app's background work for the battery's sake. */
    static boolean restricted(Context context) {
        return backgroundRestricted(context) || sleeping(context);
    }

    /** The app's battery setting is "Restricted". */
    private static boolean backgroundRestricted(Context context) {
        if (Build.VERSION.SDK_INT < 28) return false;
        try {
            ActivityManager activities = context.getSystemService(ActivityManager.class);
            return activities != null && activities.isBackgroundRestricted();
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /** Android put the app in its restricted standby bucket (Android 11 and later; its jobs run about once a day). */
    private static boolean sleeping(Context context) {
        if (Build.VERSION.SDK_INT < 30) return false;
        try {
            UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
            return usage != null && usage.getAppStandbyBucket() == UsageStatsManager.STANDBY_BUCKET_RESTRICTED;
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /** The app's battery page, or App info where Android has none; a toast says what to choose. */
    static void open(Context context, Consumer<Intent> open) {
        Uri app = Uri.parse("package:" + context.getPackageName());
        boolean setting = backgroundRestricted(context);
        DiagnosticLog.log(context, "setup", (setting ? "battery restriction on" : "restricted standby bucket")
                + "; opening the app's battery settings");
        // Optimized lifts the Restricted setting; only Unrestricted takes the app out of a sleeping list.
        String choose = setting ? "Unrestricted or Optimized" : "Unrestricted";
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                context.startActivity(new Intent(APP_BATTERY, app));
                Toast.makeText(context, "Choose " + choose + ".", Toast.LENGTH_LONG).show();
                return;
            } catch (RuntimeException unsupported) {
                // App info has the Battery entry on every phone.
            }
        }
        Toast.makeText(context, "Open Battery, then choose " + choose + ".", Toast.LENGTH_LONG).show();
        open.accept(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app));
    }
}
