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
 * downloaded or local file, or an unknown source with no app store as installer (a self-update, say, where the
 * restriction only may apply). Android's own record (the access_restricted_settings app-op) decides instead whenever
 * Android lets the app read it, except that its "allowed" is not taken over a sideload's source unless the same record
 * said restricted before (Android 15's Enhanced Confirmation Mode may guard an app by its source while that record
 * stays at its default). An access that
 * turns on while the step showed proves the restriction lifted, and so do both accesses on. Nothing here reads
 * another app or changes a setting: the user does every step. Main thread only.
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
    /** As {@link #TRY_FIRST}, for an install whose source only suggests the restriction (a self-update, say). */
    static final String TRY_FIRST_MAYBE = "Android may block this switch for apps from outside a store: tap it once; "
            + "if it's blocked, App info → ⋮ → Allow restricted settings.";
    /**
     * After a try: the exact path, and back; the switch stays in reach for one who allowed it already, or whose try
     * never reached the switch (App info then has no such item).
     */
    static final String PATH = "In App info, tap ⋮ → Allow restricted settings, then come back. Not there? Try the "
            + "switch first.";
    /** How long a try or a visit to App info started here is still the reason the page acts on its return. */
    static final long RETURN_WITHIN_MS = 10 * 60_000L;

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
    private static final String TRYING_AT = "restricted_trying_at";
    private static final String RETURN_KEY = "restricted_return_to";
    private static final String RETURN_AT = "restricted_return_at";
    /** Android's record was once read saying restricted: on this phone it is readable and means what it says. */
    private static final String RECORD_KEY = "restricted_record_seen";

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

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(SetupChecklist.PREFS, Context.MODE_PRIVATE);
    }

    /** Where this install came from, as getInstallSourceInfo says (Android 13 and later). */
    static Source source(Context context) {
        if (Build.VERSION.SDK_INT < 33) return Source.UNKNOWN;
        try {
            return Sources.source(context);
        } catch (Exception unknown) {
            // No answer: the guide stays out of the way; the failed-attempt hint still helps.
            return Source.UNKNOWN;
        }
    }

    /** Whether this install came from a downloaded or local file (or an unknown source no app store installed). */
    static boolean sideloaded(Context context) {
        Source source = source(context);
        return source == Source.FILE || source == Source.OTHER;
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

    /** What the app could tell of the restriction: its {@link State}, and how it knows. */
    static final class Reading {
        final State state;
        /** Android's access_restricted_settings record was readable and decided it (else the install source did). */
        final boolean fromRecord;
        /** Android's record or a downloaded or local file says so, not only an unknown source's guess. */
        final boolean certain;

        Reading(State state, boolean fromRecord, boolean certain) {
            this.state = state;
            this.fromRecord = fromRecord;
            this.certain = certain;
        }

        /**
         * Whether a switch tried from here that stayed off gets the "switch greyed out?" hint: the restriction could
         * not be told in advance, or Android's record says allowed (and may not be the whole story).
         */
        boolean hintAfterTry() {
            return state == State.UNKNOWN || (state == State.CLEAR && fromRecord);
        }
    }

    /**
     * Whether the restriction may stand between the user and either access on this phone. Asks Android twice (the
     * app-op and the install source), so callers keep the answer a while.
     */
    static Reading read(Context context) {
        if (Build.VERSION.SDK_INT < 33 || prefs(context).getBoolean(CLEARED_KEY, false)) {
            return new Reading(State.CLEAR, false, true);
        }
        SharedPreferences prefs = prefs(context);
        int mode = opMode(context);
        if (mode == AppOpsManager.MODE_ERRORED || mode == AppOpsManager.MODE_IGNORED) {
            if (!prefs.getBoolean(RECORD_KEY, false)) prefs.edit().putBoolean(RECORD_KEY, true).apply();
            return new Reading(mode == AppOpsManager.MODE_ERRORED ? State.UNTRIED : State.TRIED, true, true);
        }
        if (mode == AppOpsManager.MODE_ALLOWED && prefs.getBoolean(RECORD_KEY, false)) {
            // The same record said restricted before: "allowed" now is the user's own doing, lifted for good (as
            // every later read says; no "greyed out?" hint for it either).
            cleared(context, "Android's record says allowed");
            return new Reading(State.CLEAR, false, true);
        }
        Source source = source(context);
        boolean sideload = source == Source.FILE || source == Source.OTHER;
        if (!sideload) {
            if (mode < 0 || mode == AppOpsManager.MODE_DEFAULT) return new Reading(State.UNKNOWN, false, false);
            return new Reading(State.CLEAR, true, true);
        }
        // A sideload: its source decides, even over a readable "allowed" never seen otherwise (an access turning on
        // settles it).
        State state = prefs.getBoolean(TRIED_KEY, false) ? State.TRIED : State.UNTRIED;
        return new Reading(state, false, source == Source.FILE);
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
     * An access that turned on while it showed proves the restriction lifted; so does an access already on when only
     * the install source suggested a restriction (allowed before, say, or granted before Android 13), both of them on
     * included. Each is kept, and logged once. Android's own record, while readable, is never overruled by that.
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
        if (!reading.fromRecord && missing != 3) {
            cleared(context, missing == 0 ? "both accesses on" : "an access was already on");
            return false;
        }
        if (missing == 0) return false;
        if (missing != before) prefs.edit().putInt(MISSING_KEY, missing).apply();
        return true;
    }

    private static void cleared(Context context, String why) {
        prefs(context).edit().putBoolean(CLEARED_KEY, true).remove(MISSING_KEY).remove(TRIED_KEY).remove(RECORD_KEY)
                .apply();
        DiagnosticLog.log(context, "setup", "restricted settings allowed: " + why);
    }

    /**
     * Back on the page (from Android's settings, perhaps), within {@link #RETURN_WITHIN_MS} of leaving from here: a
     * switch tried from here and still off was blocked, so App info now offers the menu item; back from App info, the
     * access being set up opens next (itself a try), unless Android says the restriction still stands or
     * {@code mayOpen} is false (a dash on, or the page beside another app). Anything older is forgotten unread.
     *
     * @return the access to open now, or null
     */
    static Access resumed(Context context, boolean accessibilityOn, boolean listenerOn, boolean mayOpen) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.contains(TRYING_KEY) && !prefs.contains(RETURN_KEY)) return null;
        long now = System.currentTimeMillis();
        Access trying = fresh(prefs, TRYING_AT, now) ? access(prefs.getString(TRYING_KEY, null)) : null;
        Access back = fresh(prefs, RETURN_AT, now) ? access(prefs.getString(RETURN_KEY, null)) : null;
        SharedPreferences.Editor edit = prefs.edit().remove(TRYING_KEY).remove(TRYING_AT).remove(RETURN_KEY)
                .remove(RETURN_AT);
        if (trying != null && !on(trying, accessibilityOn, listenerOn)) edit.putBoolean(TRIED_KEY, true);
        Access next = back == null || on(back, accessibilityOn, listenerOn) || !mayOpen ? null : back;
        if (next != null) {
            int mode = opMode(context);
            if (mode == AppOpsManager.MODE_ERRORED || mode == AppOpsManager.MODE_IGNORED) next = null;
        }
        // Opening it is a try from here too: still off on the next return, it was blocked.
        if (next != null) edit.putString(TRYING_KEY, next.name()).putLong(TRYING_AT, now);
        edit.apply();
        return next;
    }

    /** Within {@link #RETURN_WITHIN_MS} of the time saved at {@code key}. */
    private static boolean fresh(SharedPreferences prefs, String key, long now) {
        long at = prefs.getLong(key, 0);
        return at > 0 && now >= at && now - at <= RETURN_WITHIN_MS;
    }

    /** {@code target}'s switch is being tried from here: noted, so a switch still off on return was blocked. */
    static void trying(Context context, Access target) {
        prefs(context).edit().putString(TRYING_KEY, target.name()).putLong(TRYING_AT, System.currentTimeMillis())
                .apply();
    }

    /**
     * The step's one-line guide, for {@code target}: before a try, the switch first (or App info for one tried
     * elsewhere); after one, App info with the exact path, the switch still offered (allowed already, or never really
     * tried). {@code openAccess} opens the access's own page.
     */
    static void show(Activity activity, Access target, Consumer<Access> openAccess, Consumer<Intent> open) {
        Reading reading = read(activity);
        boolean tried = reading.state != State.UNTRIED;
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle(TITLE)
                .setMessage(tried ? PATH : reading.certain ? TRY_FIRST : TRY_FIRST_MAYBE)
                .setNegativeButton("Cancel", null);
        Runnable trySwitch = () -> {
            trying(activity, target);
            openAccess.accept(target);
        };
        if (tried) {
            dialog.setPositiveButton("Open App info", (shown, which) -> appInfo(activity, target, open));
            dialog.setNeutralButton("Try the switch", (shown, which) -> trySwitch.run());
        } else {
            dialog.setPositiveButton("Try the switch", (shown, which) -> trySwitch.run());
            dialog.setNeutralButton("App info", (shown, which) -> appInfo(activity, target, open));
        }
        OwnWindowTouches.show(dialog);
    }

    /** App info, remembering to come back to {@code target}'s page. */
    static void appInfo(Context context, Access target, Consumer<Intent> open) {
        prefs(context).edit().putString(RETURN_KEY, target.name()).putLong(RETURN_AT, System.currentTimeMillis())
                .apply();
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
    private static final class Sources {
        static Source source(Context context) throws Exception {
            if (Build.VERSION.SDK_INT < 33) return Source.UNKNOWN;
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
