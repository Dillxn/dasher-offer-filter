package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.os.Build;
import android.os.Bundle;

/**
 * Dasher's own tap on its offer notification (its content intent), sent only when Dasher's launcher brought Dasher up
 * without drawing the offer: once by a peek, once after a card tap, or first from a card that says Dasher did not show
 * the offer. Only an intent Dasher itself made, to start one of its own screens, is ever sent.
 *
 * <p>From Android 14 a sender must say that its own right to start a screen goes with a pending intent it sends.
 * Android 16 replaced the one "allowed" mode with two: always (what the screen reader's own sending needs, as no
 * screen of ours is visible then) and only while the sender is visible (what the tap screen behind a card needs).
 */
final class DasherOwnIntent {
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    /** No mode: Android before 14 asks for none. */
    static final int NO_MODE = -1;

    /**
     * The background-start mode a send needs on Android {@code sdk}: none before 14; "allowed" on 14 and 15; from 16,
     * "always" for the screen reader's own send and "if visible" for the visible tap screen.
     */
    @SuppressLint("InlinedApi")
    @SuppressWarnings("deprecation")
    static int startMode(int sdk, boolean visibleSender) {
        if (sdk < 34) return NO_MODE;
        if (sdk >= 36) {
            return visibleSender ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                    : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS;
        }
        return ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
    }

    /** The options for one send on this phone, or null where Android asks for none. */
    static Bundle options(boolean visibleSender) {
        if (Build.VERSION.SDK_INT < 34) return null;
        int mode = startMode(Build.VERSION.SDK_INT, visibleSender);
        return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle();
    }

    /**
     * Whether a pending intent is Dasher's own, to start one of its screens: Dasher made it, and (from Android 12,
     * where it can be told) it starts an activity.
     */
    static boolean fromDasher(PendingIntent intent) {
        if (intent == null) return false;
        try {
            if (!DASHER_PACKAGE.equals(intent.getCreatorPackage())) return false;
            return Build.VERSION.SDK_INT < 31 || intent.isActivity();
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    private DasherOwnIntent() {}
}
