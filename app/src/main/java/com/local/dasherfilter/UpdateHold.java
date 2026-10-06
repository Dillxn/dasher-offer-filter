package com.local.dasherfilter;

import android.content.Context;
import android.os.PowerManager;

/**
 * Whether a verified automatic update still waits for the dash. It waits while a dash has not been seen to end
 * ({@link Dashing#awaitingEnd}): installing closes Offer Filter, and its half of a split screen beside Dasher with it.
 * But a dash whose end was never seen (Dasher killed, a reboot, an end screen read while screen reading was down)
 * would hold every update for good, and a beta lives on its fixes. So the hold has a ceiling: after
 * {@link #CEILING_MS} with no sign of a dash at all (no offer, no waiting or delivery screen since, nothing of a dash
 * on, no route stored, no offer tracked), while Dasher is not in front and the screen is off, the held update may
 * install. Only the wait ends: the fresh feed and every check of the APK still run, and the reason is logged. A pause,
 * thirty quiet minutes or a stopped service alone still never end it. Pure decision; nothing is stored here.
 */
final class UpdateHold {
    /** How long nothing of a dash may be seen before an unended dash stops holding an automatic update back. */
    static final long CEILING_MS = 8 * 60 * 60_000L;

    /** Whether an automatic installation waits for the dash now. */
    static boolean holds(Context context) {
        return Dashing.awaitingEnd(context) && ceilingReached(context, System.currentTimeMillis()) == null;
    }

    /**
     * Why an unended dash no longer holds an automatic update back ("nothing of a dash seen for 9 h, …"), or null
     * while it still does. Every condition must hold; anything unknown keeps the hold.
     */
    static String ceilingReached(Context context, long now) {
        long seen = Dashing.lastSeen(context);
        long quiet = now - seen;
        // Never seen while a dash is open, or a clock moved back: no basis for a ceiling.
        if (seen <= 0 || quiet < CEILING_MS) return null;
        if (Dashing.on(context) || ActiveRouteStore.load(context) != null) return null;
        if (OfferNotificationService.hasActiveOffer() || OfferFilterService.isDasherForeground()) return null;
        if (screenMayBeOn(context)) return null;
        return "nothing of a dash seen for " + quiet / 3_600_000L + " h (no offer, waiting or delivery screen), "
                + "Dasher not in front, screen off";
    }

    /** The screen is on, or Android cannot say: either keeps the hold. */
    private static boolean screenMayBeOn(Context context) {
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            return power == null || power.isInteractive();
        } catch (RuntimeException unknown) {
            return true;
        }
    }

    private UpdateHold() {}
}
