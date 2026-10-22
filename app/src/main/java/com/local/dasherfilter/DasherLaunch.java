package com.local.dasherfilter;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.List;

/**
 * How this app starts Dasher, and itself, as their launcher icons do: the header's Open Dasher (F22), the launcher
 * intents a card's tap, Peek and its return use, and the homepage's line for Dasher stuck on its launch screen. Every
 * start of Dasher is plain, full screen, its task as it was: none asks for the other half of a split screen (0.6.33
 * retired the Split button, its Swap in Dasher and the divider's moves for a map: the owner, 22 October 2026, "i say
 * let's get rid of split screen mode. we haven't been able to get the auto resizing to work and it's so buggy"). The
 * way back is the tab over Dasher ({@link DasherOverlay}). Each step of Open Dasher goes in the log as a [launch] line.
 */
final class DasherLaunch {
    static final String DASHER_PACKAGE = "com.doordash.driverapp";
    /** The header's one-tap Dasher, full screen (F22). */
    static final String OPEN_LABEL = "Open Dasher";

    /** Asks Android to open recent apps; replaced only by tests. */
    static java.util.function.BooleanSupplier recents = OfferFilterService::openRecents;

    /** The homepage's line while Dasher was seen only on its launch screen after a start of this app's. */
    static final String LAUNCH_SCREEN_LINE = "Dasher stuck on its launch screen?";
    /** That line's action, and what screen readers hear of the line. */
    static final String LAUNCH_SCREEN_ACTION = "Recent apps";
    static final String LAUNCH_SCREEN_SPOKEN = "Dasher may be stuck on its launch screen. " + LAUNCH_SCREEN_ACTION;
    /** Said over recent apps once that line's button opened them. */
    static final String PICK_DASHER_CARD = "Pick the Dasher card with your dash";
    /** Said when Android would not open recent apps for that line's button. */
    static final String PICK_DASHER_CARD_REFUSED = "Open recent apps, then pick the Dasher card with your dash";

    /** The class of Dasher's launcher activity, as last resolved (any thread): the launch watch tells it apart. */
    private static volatile String launcherClass;

    /**
     * Dasher's own launch intent as its launcher icon starts it: its task comes to the front showing whatever it
     * was showing, such as an offer or its turn-by-turn navigation. Never launch-adjacent. Null when Dasher is not
     * installed.
     */
    static Intent launcher(Context context) {
        Intent launch = launcher(context, DASHER_PACKAGE);
        return launch == null ? null : asItsIconOpensIt(context, launch);
    }

    /**
     * {@code launch} with the flag Android's home screen adds to every app it opens (Launcher3: NEW_TASK |
     * RESET_TASK_IF_NEEDED). Without it, when Dasher's task began at another of its screens (Dasher opened from its
     * notification, as during a dash), Android starts a new launcher screen on top of that task: Dasher came up
     * showing "Searching for offers" while its navigation went on talking underneath (the owner, 8 October 2026, after
     * the Split button on 0.5.3; "this has happened before"). With it, an existing task comes to the front as it was
     * and nothing new is started on it. Never for an activity that clears its task when launched that way
     * (clearTaskOnLaunch; Dasher's launcher reports it does not), nor when that cannot be told: then as before.
     */
    static Intent asItsIconOpensIt(Context context, Intent launch) {
        ComponentName component = launch.getComponent();
        if (component == null) return launch;
        try {
            ActivityInfo info = context.getPackageManager().getActivityInfo(component, 0);
            if ((info.flags & ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH) == 0) {
                launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException unknown) {
            // Not known: launched as before, with nothing that could reset it.
        }
        return launch;
    }

    /** Resolve the actual launcher entry, not an INFO front door, and preserve the existing task. */
    static Intent launcher(Context context, String pkg) {
        try {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
            List<ResolveInfo> choices = context.getPackageManager().queryIntentActivities(query, 0);
            if (choices != null) for (ResolveInfo choice : choices) {
                ActivityInfo info = choice.activityInfo;
                if (info != null && info.exported && pkg.equals(info.packageName)
                        && launcherEnabled(context, info)) {
                    if (DASHER_PACKAGE.equals(pkg)) launcherClass = info.name;
                    // A package-bearing launch can create another start screen on older Android. Keep only the
                    // real launcher component/category; no task reset/clear, and no invented category on INFO.
                    return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(new ComponentName(info.packageName, info.name))
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
            }
        } catch (RuntimeException unavailable) {
            // Refuse rather than guess a different activity or reset its task.
        }
        return null;
    }

    /**
     * The class of Dasher's launcher activity as last resolved ({@link #launcher(Context, String)}), or null before
     * Dasher's launcher was looked up in this process. Any thread.
     */
    static String launcherClass() {
        return launcherClass;
    }

    /** An enabled alias can override android:enabled="false" without changing its manifest ActivityInfo. */
    private static boolean launcherEnabled(Context context, ActivityInfo info) {
        int state = context.getPackageManager().getComponentEnabledSetting(new ComponentName(info.packageName, info.name));
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled);
    }

    /**
     * The user's tap on Open Dasher: Dasher's own launch intent, as its launcher icon opens it (full screen, its task as
     * it was; nothing cleared or reset). Never by itself.
     *
     * @return what to tell the user when Dasher could not be opened, else null
     */
    static String openDasher(Activity activity) {
        Intent dasher = launcher(activity);
        if (dasher == null) {
            log(activity, "Open Dasher: Dasher is not installed");
            return "Dasher is not installed.";
        }
        log(activity, "Open Dasher tapped: Dasher's launch intent");
        String failed = open(activity, dasher);
        if (failed == null) OfferFilterService.launchWatch(OfferFilterService.LAUNCH_OPEN_DASHER);
        return failed;
    }

    /**
     * The homepage's "Recent apps" on {@link #LAUNCH_SCREEN_LINE}, at the user's tap (main thread): recent apps, where
     * the user picks Dasher's card with the dash. Nothing else is done to Dasher.
     *
     * @return what to tell the user
     */
    static String recentAppsForLaunchScreen(Context context) {
        boolean opened = recents.getAsBoolean();
        log(context, "launch-screen line: Recent apps tapped" + (opened ? "" : "; Android refused to open them"));
        if (opened) OfferFilterService.launchScreenAnswered();
        return opened ? PICK_DASHER_CARD : PICK_DASHER_CARD_REFUSED;
    }

    private static String open(Activity activity, Intent dasher) {
        try {
            activity.startActivity(dasher);
            return null;
        } catch (ActivityNotFoundException | SecurityException refused) {
            return "Dasher could not be opened.";
        }
    }

    private static void log(Context context, String step) {
        DiagnosticLog.log(context, "launch", step);
    }

    private DasherLaunch() {}
}
