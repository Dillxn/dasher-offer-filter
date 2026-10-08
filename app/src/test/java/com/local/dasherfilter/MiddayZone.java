package com.local.dasherfilter;

import java.util.Locale;
import java.util.TimeZone;

/**
 * The minimums' growth counts calendar days in the phone's own zone (Growth.day). Its adapter tests record offers in
 * the two hours before now and a day before those, two calendar days only once it is past two in the morning: run
 * between midnight and then (UTC on the build machine), the newest of them fell on two days and the evidence counted
 * three. These tests run in a zone where it is midday now, whatever hour the suite runs at, and put the zone back.
 */
final class MiddayZone {
    private static final long DAY_MS = 24 * 3_600_000L;
    private final TimeZone was = TimeZone.getDefault();

    /** From now on, a zone where {@code now} (a wall-clock time) is noon, to the minute. */
    MiddayZone(long now) {
        long minutes = Math.round((DAY_MS / 2 - Math.floorMod(now, DAY_MS)) / 60_000.0);
        // A "GMT+hh:mm" zone, which java.time (the page's dates) knows as well as java.util does.
        TimeZone.setDefault(TimeZone.getTimeZone(String.format(Locale.US, "GMT%s%02d:%02d", minutes < 0 ? "-" : "+",
                Math.abs(minutes) / 60, Math.abs(minutes) % 60)));
    }

    /** The zone as it was. */
    void restore() {
        TimeZone.setDefault(was);
    }
}
