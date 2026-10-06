package com.local.dasherfilter;

import java.util.Locale;

/**
 * How often update checks may start, by what started them. A check the user asked for starts at once. Opening the
 * app fresh, turning updates on and the retry job wait only for the cooldown after a check (and the backoff after a
 * failure). Everything else (coming back to the app, a service reconnecting, the periodic job) also waits
 * {@link #AUTOMATIC_SPACING_MS} after the last attempt, so switching between Dasher and Offer Filter does not check
 * every minute. Pure Java.
 */
final class UpdateCadence {
    /** Automatic checks other than the immediate ones start at most this often. */
    static final long AUTOMATIC_SPACING_MS = 300_000L;

    enum Trigger {
        /** The user tapped Updates in Settings. */
        MANUAL,
        /** Offer Filter's screen opened fresh. */
        OPENED,
        /** Automatic updates were turned on (by the switch Settings had before checks were always automatic). */
        TURNED_ON,
        /** The one-shot retry job after a failure or a deferred install. */
        RETRY,
        /** Offer Filter's screen came back (or was recreated, as a split resize or day and night do). */
        RESUMED,
        /** The screen reader or the notification listener connected. */
        CONNECTED,
        /** The periodic job. */
        PERIODIC,
        /** Any other automatic check. */
        AUTOMATIC;

        boolean manual() {
            return this == MANUAL;
        }

        /** Starts as soon as the cooldown allows, without waiting {@link #AUTOMATIC_SPACING_MS}. */
        boolean immediate() {
            return this == MANUAL || this == OPENED || this == TURNED_ON || this == RETRY;
        }

        String label() {
            return name().toLowerCase(Locale.US).replace('_', ' ');
        }
    }

    /**
     * Whether a check may start now.
     *
     * @param lastAttemptAt when the last check started (wall clock), 0 for never
     * @param nextCheckAt   the end of the cooldown or failure backoff (wall clock)
     */
    static boolean mayStart(Trigger trigger, long now, long lastAttemptAt, long nextCheckAt) {
        if (trigger.manual()) return true;
        if (UpdatePolicy.coolingDown(now, nextCheckAt)) return false;
        if (trigger.immediate()) return true;
        // A clock moved back, or no attempt yet, never holds checks off.
        return lastAttemptAt <= 0 || lastAttemptAt > now || now - lastAttemptAt >= AUTOMATIC_SPACING_MS;
    }

    /** What started a job: the retry job, or else the periodic one. */
    static Trigger forJob(int jobId, int retryJobId) {
        return jobId == retryJobId ? Trigger.RETRY : Trigger.PERIODIC;
    }

    private UpdateCadence() {}
}
