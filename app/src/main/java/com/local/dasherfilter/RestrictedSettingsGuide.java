package com.local.dasherfilter;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInstaller;
import android.os.Process;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * What Offer Filter can tell of Android's block on Accessibility and notification access ("Restricted setting"), for
 * {@link SetupFlow}: Android 13 and later block both for an app installed from a downloaded or local file until the
 * user allows it in App info, ⋮ → Allow restricted settings, a menu item Android offers only after the switch was tried
 * once. Two answers, neither of them proof on its own: where the install came from (getInstallSourceInfo, API 33), and
 * Android's own record (the access_restricted_settings app-op) where Android lets the app read it. Android 15's
 * Enhanced Confirmation Mode may block an app by its source while that record stays at its default, and One UI 8 blocks
 * Accessibility but not notification access whatever the record says, so setup weighs them itself. Nothing here reads
 * another app or changes a setting.
 */
final class RestrictedSettingsGuide {
    enum Access { ACCESSIBILITY, NOTIFICATIONS }

    /** How long a page setup opened is still the reason it acts on the user's return. */
    static final long RETURN_WITHIN_MS = 10 * 60_000L;

    /** Android's own record of the restriction (hidden constant OPSTR_ACCESS_RESTRICTED_SETTINGS). */
    static final String OP = "android:access_restricted_settings";
    /** App stores whose installs Android does not restrict. */
    private static final Set<String> STORES = new HashSet<>(Arrays.asList("com.android.vending",
            "com.sec.android.app.samsungapps", "com.amazon.venezia", "com.huawei.appmarket", "com.xiaomi.market",
            "com.heytap.market", "com.oppo.market", "com.bbk.appstore"));

    /** Where this install came from, as far as the restriction goes. */
    enum Source {
        /** Android did not say (older than 13, or it refused): no sideload assumed. */
        UNKNOWN,
        /** An app store installed it. */
        STORE,
        /** A downloaded or local file: Android restricts it. */
        FILE,
        /** Unspecified or other, with no app store as installer (a self-update, adb, another installer): it may. */
        OTHER
    }

    private RestrictedSettingsGuide() {}

    /** Where this install came from, as getInstallSourceInfo says. */
    static Source source(Context context) {
        try {
            return Sources.source(context);
        } catch (Exception unknown) {
            return Source.UNKNOWN;
        }
    }

    /** Whether this install came from a downloaded or local file (or an unknown source no app store installed). */
    static boolean sideloaded(Context context) {
        Source source = source(context);
        return source == Source.FILE || source == Source.OTHER;
    }

    /**
     * Android's mode for the restriction, or -1 when Android does not let the app read it: errored means restricted
     * and untried, ignored restricted and tried (App info offers the menu item), allowed the user allowed it, default
     * that Android decides by other means (the install source, under Enhanced Confirmation Mode).
     */
    static int opMode(Context context) {
        try {
            AppOpsManager ops = context.getSystemService(AppOpsManager.class);
            return ops == null ? -1 : ops.unsafeCheckOpNoThrow(OP, Process.myUid(), context.getPackageName());
        } catch (RuntimeException unreadable) {
            return -1;
        }
    }

    /** Android's record in fixed words, for the log. */
    static String modeWord(int mode) {
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED: return "allowed";
            case AppOpsManager.MODE_IGNORED: return "tried";
            case AppOpsManager.MODE_ERRORED: return "restricted";
            case AppOpsManager.MODE_DEFAULT: return "default";
            default: return "unreadable";
        }
    }

    /** The install source's own calls. */
    private static final class Sources {
        static Source source(Context context) throws Exception {
            InstallSourceInfo info = context.getPackageManager().getInstallSourceInfo(context.getPackageName());
            int source = info.getPackageSource();
            if (source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
                    || source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE) return Source.FILE;
            if (source == PackageInstaller.PACKAGE_SOURCE_STORE) return Source.STORE;
            // Unspecified or other (a self-update leaves this): a sideload unless an app store put it here.
            return STORES.contains(info.getInitiatingPackageName()) || STORES.contains(info.getInstallingPackageName())
                    ? Source.STORE : Source.OTHER;
        }
    }
}
