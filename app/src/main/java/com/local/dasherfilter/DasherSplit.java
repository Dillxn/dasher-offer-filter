package com.local.dasherfilter;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Split screen with Dasher, at the user's tap only: Offer Filter above, Dasher below, so Dasher stays on screen with
 * its offers in view (and its offers are still read and declined at once) while the map here is looked at. Android
 * gives apps no way to enter split screen themselves, so Offer Filter's screen reading asks for it, the same as
 * Android's own Split screen accessibility shortcut, and Dasher is then opened in the other half. Many newer phones
 * ignore that request (split screen now lives in the launcher's recent apps); then recent apps are opened instead,
 * the user picks split screen on Offer Filter's card, and Dasher still opens in the other half by itself.
 */
final class DasherSplit {
    static final String DASHER_PACKAGE = "com.doordash.driverapp";
    /** How long after the tap the screen may take to split before Dasher is no longer opened into it. */
    static final long PENDING_MS = 8_000;
    /** The same when the user splits it themselves from recent apps, which takes a few taps. */
    static final long BY_HAND_MS = 60_000;

    /** Asks Android to split the screen, then to open recent apps; replaced only by tests. */
    static java.util.function.BooleanSupplier split = OfferFilterService::splitScreen;
    static java.util.function.BooleanSupplier recents = OfferFilterService::openRecents;

    /** When the user last tapped Split (uptime), 0 when nothing is waiting. */
    private static long requestedAt;
    /** How long that tap waits for the split. */
    private static long waitMs = PENDING_MS;

    /** Dasher's own launch intent, set to open beside this app in split screen; null when Dasher is not installed. */
    static Intent dasher(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(DASHER_PACKAGE);
        if (launch == null) return null;
        return launch.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Whether the Split button shows: Dasher is installed and the screen is not split already. */
    static boolean offered(Activity activity) {
        return !activity.isInMultiWindowMode() && dasher(activity) != null;
    }

    /**
     * The tap. Already split, Dasher opens in the other half; otherwise the screen is split first and Dasher opens
     * when it is (see {@link #resumed}).
     *
     * @return what to tell the user (what to do in recent apps, or why it cannot be done), else null
     */
    static String start(Activity activity) {
        Intent dasher = dasher(activity);
        if (dasher == null) return "Dasher is not installed.";
        if (activity.isInMultiWindowMode()) {
            requestedAt = 0;
            return open(activity, dasher);
        }
        if (!OfferFilterService.isConnected()) {
            return "Turn on screen reading first: Android splits the screen for Offer Filter through it.";
        }
        requestedAt = SystemClock.uptimeMillis();
        waitMs = PENDING_MS;
        if (split.getAsBoolean()) return null;
        // This phone splits the screen only from recent apps: open them, and wait for the user to do it there.
        waitMs = BY_HAND_MS;
        if (recents.getAsBoolean()) {
            return "Tap Offer Filter's icon above its card and choose split screen. Dasher then opens in the other half.";
        }
        requestedAt = 0;
        return "This phone did not split the screen. Open recent apps and choose split screen from Offer Filter's icon.";
    }

    /** Once the screen is split after a tap, Dasher opens in the other half; a late or unasked split opens nothing. */
    static void resumed(Activity activity) {
        if (requestedAt == 0 || !activity.isInMultiWindowMode()) return;
        boolean fresh = SystemClock.uptimeMillis() - requestedAt < waitMs;
        requestedAt = 0;
        Intent dasher = dasher(activity);
        if (fresh && dasher != null) open(activity, dasher);
    }

    private static String open(Activity activity, Intent dasher) {
        try {
            activity.startActivity(dasher);
            return null;
        } catch (ActivityNotFoundException | SecurityException refused) {
            return "Dasher could not be opened.";
        }
    }

    /** For tests: forget a pending split, and ask Android for real again. */
    static void forget() {
        requestedAt = 0;
        split = OfferFilterService::splitScreen;
        recents = OfferFilterService::openRecents;
    }

    private DasherSplit() {}
}
