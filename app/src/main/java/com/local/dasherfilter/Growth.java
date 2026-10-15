package com.local.dasherfilter;

/**
 * The minimums' growth, retired (0.6.21). From 0.5.1 (the owner's decision of 7 October 2026) to 0.6.20 Autopilot grew
 * the saved minimums, at most 10% at a time, once its bar had stayed at 103% or more for 30 offers over at least 2
 * days. The owner, 15 October 2026: "mins growing should not be narried to autopilot", then "mins should grow as we
 * accept offers with higher mins when adaptive / learned mins is on. autopilot is the buffer" and "this was already
 * established": the minimums offers must meet grow only through learning, from the offers the user accepts, and
 * Autopilot only moves its bar around them. So nothing grows the minimums from 0.6.21, and "Let my minimums grow" is
 * gone. What is left is a growth made before: its record ({@link AutopilotStore#lastGrowth}), the homepage's note
 * ({@link GrowthCard}) and its Undo ({@link AutopilotRuntime#undoGrowth}), while the minimums are still the grown ones.
 */
final class Growth {
    /** The minimums and the bar before and after one growth of {@link #percent} points (money in stored units). */
    static final class Grown {
        final int percent;
        final int flatBefore;
        final int mileBefore;
        final int minuteBefore;
        final int barBefore;
        final int flatAfter;
        final int mileAfter;
        final int minuteAfter;
        final int barAfter;

        Grown(int percent, int flatBefore, int mileBefore, int minuteBefore, int barBefore, int flatAfter,
              int mileAfter, int minuteAfter, int barAfter) {
            this.percent = percent;
            this.flatBefore = flatBefore;
            this.mileBefore = mileBefore;
            this.minuteBefore = minuteBefore;
            this.barBefore = barBefore;
            this.flatAfter = flatAfter;
            this.mileAfter = mileAfter;
            this.minuteAfter = minuteAfter;
            this.barAfter = barAfter;
        }
    }

    private Growth() {}
}
