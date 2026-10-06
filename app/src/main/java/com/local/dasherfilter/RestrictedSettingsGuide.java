package com.local.dasherfilter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Android 13 and later block Accessibility and notification access ("Restricted setting") for an app installed from a
 * downloaded or local file until the user allows it in App info, ⋮ → Allow restricted settings; and Android shows
 * that menu item only after the switch was tried once. Strangers who sideload Offer Filter meet this before anything
 * works, so the setup checklist leads with a step for it, for both accesses, before the wall: one line of
 * explanation, App info with the exact path, then back to the access being turned on; success is seen on return.
 *
 * <p>Whether the restriction applies comes from the install source (PackageManager.getInstallSourceInfo, API 33): a
 * downloaded or local file, or an unknown source with no app store as installer (a self-update, say). Android's own
 * record (the access_restricted_settings app-op) decides instead whenever Android lets the app read it. An access that
 * turns on while the step showed proves the restriction lifted. Nothing here reads another app or changes a setting:
 * the user does every step. Main thread only.
 */
final class RestrictedSettingsGuide {
    enum Access { ACCESSIBILITY, NOTIFICATIONS }

    enum State {
        /** Not restricted: Android older than 13, an app-store install, or the user allowed it already. */
        CLEAR,
        /**
         * No evidence either way (Android 13+, its record unreadable and the install source not a sideload): the step
         * stays away, and a switch that stays off after a try gets the hint instead.
         */
        UNKNOWN,
        /** Restricted, and the switch not tried yet: Android does not offer the App info menu item yet. */
        UNTRIED,
        /** Restricted, the switch tried: App info → ⋮ → Allow restricted settings is there. */
        TRIED
    }

    static final String TITLE = "Allow restricted settings";
    /** Before the switch was tried: Android shows the menu item only after one try. */
    static final String TRY_FIRST = "Android blocks this switch for apps from the web: tap it once, then App info → ⋮ → "
            + "Allow restricted settings.";
    /** After a try: the exact path, and back. */
    static final String PATH = "In App info, tap ⋮ → Allow restricted settings, then come back here.";

    /** Android's own record of the restriction (hidden constant OPSTR_ACCESS_RESTRICTED_SETTINGS). */
    static final String OP = "android:access_restricted_settings";
    /** App stores whose installs Android does not restrict. */
    private static final Set<String> STORES = new HashSet<>(Arrays.asList("com.android.vending",
            "com.sec.android.app.samsungapps", "com.amazon.venezia", "com.huawei.appmarket", "com.xiaomi.market",
            "com.heytap.market", "com.oppo.market", "com.bbk.appstore"));

    // In SetupChecklist.PREFS, kept across restarts.
    private static final String TRIED_KEY = "restricted_tried";
    private static final String CLEARED_KEY = "restricted_cleared";
    private static final String MISSING_KEY = "restricted_missing";
    private static final String TRYING_KEY = "restricted_trying";
    private static final String RETURN_KEY = "restricted_return_to";

    private RestrictedSettingsGuide() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(SetupChecklist.PREFS, Context.MODE_PRIVATE);
    }

    /** Whether this install came from a downloaded or local file (or an unknown source no app store installed). */
    static boolean sideloaded(Context context) {
        if (Build.VERSION.SDK_INT < 33) return false;
        try {
            return Sources.sideloaded(context);
        } catch (Exception unknown) {
            // No answer: the guide stays out of the way; the failed-attempt hint still helps.
            return false;
        }
    }

    /** Android's mode for the restriction, or -1 when Android does not let the app read it. */
    static int opMode(Context context) {
        if (Build.VERSION.SDK_INT < 33) return -1;
        try {
            AppOpsManager ops = context.getSystemService(AppOpsManager.class);
            return ops == null ? -1 : ops.unsafeCheckOpNoThrow(OP, Process.myUid(), context.getPackageName());
        } catch (RuntimeException unreadable) {
            return -1;
        }
    }

    /** What the app could tell of the restriction: its {@link State}, and whether Android's own record said so. */
    static final class Reading {
        final State state;
        /** Android's access_restricted_settings record was readable and decided it (else the install source did). */
        final boolean fromRecord;

        Reading(State state, boolean fromRecord) {
            this.state = state;
            this.fromRecord = fromRecord;
        }
    }

    /**
     * Whether the restriction may stand between the user and either access on this phone. Asks Android twice (the
     * app-op and the install source), so callers keep the answer a while.
     */
    static Reading read(Context context) {
        if (Build.VERSION.SDK_INT < 33 || prefs(context).getBoolean(CLEARED_KEY, false)) {
            return new Reading(State.CLEAR, false);
        }
        int mode = opMode(context);
        if (mode == AppOpsManager.MODE_ALLOWED) return new Reading(State.CLEAR, true);
        if (mode == AppOpsManager.MODE_ERRORED) return new Reading(State.UNTRIED, true);
        if (mode == AppOpsManager.MODE_IGNORED) return new Reading(State.TRIED, true);
        if (!sideloaded(context)) {
            return new Reading(mode < 0 || mode == AppOpsManager.MODE_DEFAULT ? State.UNKNOWN : State.CLEAR, false);
        }
        return new Reading(prefs(context).getBoolean(TRIED_KEY, false) ? State.TRIED : State.UNTRIED, false);
    }

    static State state(Context context) {
        return read(context).state;
    }

    /** Whether this phone has the step at all (for the checklist's numbering): still to do, or done here. */
    static boolean applies(Context context, Reading reading) {
        return reading.state == State.UNTRIED || reading.state == State.TRIED
                || prefs(context).getBoolean(CLEARED_KEY, false);
    }

    /**
     * Whether the step shows now ({@code reading} as {@link #read} said lately): restricted, with an access still off.
     * An access that turned on while it showed proves the restriction lifted; so does one already on when only the
     * install source suggested a restriction (allowed before, say, or granted before Android 13). Both are kept, and
     * logged once.
     */
    static boolean needed(Context context, Reading reading, boolean accessibilityOn, boolean listenerOn) {
        State state = reading.state;
        if (state == State.CLEAR || state == State.UNKNOWN || prefs(context).getBoolean(CLEARED_KEY, false)) {
            return false;
        }
        SharedPreferences prefs = prefs(context);
        int missing = (accessibilityOn ? 0 : 1) | (listenerOn ? 0 : 2);
        int before = prefs.getInt(MISSING_KEY, 0);
        if ((before & ~missing) != 0) {
            cleared(context, "an access turned on");
            return false;
        }
        if (missing == 0) return false;
        if (!reading.fromRecord && missing != 3) {
            cleared(context, "an access was already on");
            return false;
        }
        if (missing != before) prefs.edit().putInt(MISSING_KEY, missing).apply();
        return true;
    }

    private static void cleared(Context context, String why) {
        prefs(context).edit().putBoolean(CLEARED_KEY, true).remove(MISSING_KEY).remove(TRIED_KEY).apply();
        DiagnosticLog.log(context, "setup", "restricted settings allowed: " + why);
    }

    /**
     * Back on the page (from Android's settings, perhaps): a switch tried from here and still off was blocked, so App
     * info now offers the menu item; back from App info, the access being set up opens next, unless Android says the
     * restriction still stands.
     *
     * @return the access to open now, or null
     */
    static Access resumed(Context context, boolean accessibilityOn, boolean listenerOn) {
        SharedPreferences prefs = prefs(context);
        Access trying = access(prefs.getString(TRYING_KEY, null));
        Access back = access(prefs.getString(RETURN_KEY, null));
        if (trying == null && back == null) return null;
        SharedPreferences.Editor edit = prefs.edit().remove(TRYING_KEY).remove(RETURN_KEY);
        if (trying != null && !on(trying, accessibilityOn, listenerOn)) edit.putBoolean(TRIED_KEY, true);
        edit.apply();
        if (back == null || on(back, accessibilityOn, listenerOn)) return null;
        int mode = opMode(context);
        if (mode == AppOpsManager.MODE_ERRORED || mode == AppOpsManager.MODE_IGNORED) return null;
        return back;
    }

    /**
     * The step's one-line guide, for {@code target}: before a try, the switch first (or App info for one tried
     * elsewhere); after one, App info with the exact path. {@code open} opens the access's own page.
     */
    static void show(Activity activity, Access target, Consumer<Access> openAccess, Consumer<Intent> open) {
        boolean tried = state(activity) != State.UNTRIED;
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle(TITLE)
                .setMessage(tried ? PATH : TRY_FIRST)
                .setNegativeButton("Cancel", null);
        if (tried) {
            dialog.setPositiveButton("Open App info", (shown, which) -> appInfo(activity, target, open));
        } else {
            dialog.setPositiveButton("Try the switch", (shown, which) -> {
                prefs(activity).edit().putString(TRYING_KEY, target.name()).apply();
                openAccess.accept(target);
            });
            dialog.setNeutralButton("App info", (shown, which) -> appInfo(activity, target, open));
        }
        OwnWindowTouches.show(dialog);
    }

    /** App info, remembering to come back to {@code target}'s page. */
    static void appInfo(Context context, Access target, Consumer<Intent> open) {
        prefs(context).edit().putString(RETURN_KEY, target.name()).apply();
        open.accept(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName())));
    }

    private static boolean on(Access access, boolean accessibilityOn, boolean listenerOn) {
        return access == Access.ACCESSIBILITY ? accessibilityOn : listenerOn;
    }

    private static Access access(String name) {
        if (name == null) return null;
        try {
            return Access.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** API 33 calls, kept apart so older Android never loads them. */
    @android.annotation.TargetApi(33)
    private static final class Sources {
        static boolean sideloaded(Context context) throws Exception {
            InstallSourceInfo info = context.getPackageManager().getInstallSourceInfo(context.getPackageName());
            int source = info.getPackageSource();
            if (source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
                    || source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE) return true;
            if (source == PackageInstaller.PACKAGE_SOURCE_STORE) return false;
            // Unspecified or other (a self-update leaves this): a sideload unless an app store put it here.
            return !STORES.contains(info.getInitiatingPackageName())
                    && !STORES.contains(info.getInstallingPackageName());
        }
    }
}
