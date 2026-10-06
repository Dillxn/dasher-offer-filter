package com.local.dasherfilter;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import java.util.function.Consumer;

/**
 * Android's "Restricted" battery setting for this app (ActivityManager.isBackgroundRestricted, Android 9 and later)
 * stops its background jobs: update checks and feedback sending wait until the app is opened. Settings then shows one
 * Fix row, "Battery limits background work", that opens the app's own battery page where Android has one (else App
 * info, one tap from Battery). Shown only while the restriction is on; nothing is changed without the user.
 */
final class BatteryLimits {
    static final String PROBLEM = "Battery limits background work";
    /** AOSP's page for one app's battery use (Android 12 and later); not a public SDK constant, so OEMs may lack it. */
    static final String APP_BATTERY = "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL";

    private BatteryLimits() {}

    /** Whether Android restricts this app's background work for the battery's sake. */
    static boolean restricted(Context context) {
        if (Build.VERSION.SDK_INT < 28) return false;
        try {
            ActivityManager activities = context.getSystemService(ActivityManager.class);
            return activities != null && activities.isBackgroundRestricted();
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /** The app's battery page, or App info where Android has none; a toast says what to choose. */
    static void open(Context context, Consumer<Intent> open) {
        Uri app = Uri.parse("package:" + context.getPackageName());
        DiagnosticLog.log(context, "setup", "battery restriction on; opening the app's battery settings");
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                context.startActivity(new Intent(APP_BATTERY, app));
                Toast.makeText(context, "Choose Unrestricted or Optimized.", Toast.LENGTH_LONG).show();
                return;
            } catch (RuntimeException unsupported) {
                // App info has the Battery entry on every phone.
            }
        }
        Toast.makeText(context, "Open Battery, then choose Unrestricted or Optimized.", Toast.LENGTH_LONG).show();
        open.accept(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app));
    }
}
