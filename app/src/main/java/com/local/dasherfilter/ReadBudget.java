package com.local.dasherfilter;

import java.util.Arrays;

/**
 * How often Dasher's screen may be read when a read cannot catch a readable offer any sooner, so the reader never keeps
 * Dasher's own UI thread from drawing or from answering the user's taps (every node read is a call that thread must
 * serve; Dasher's map and delivery screens are big and change many times a second). It holds back only reads the
 * caller decides it may hold back: what a timer asks for (settling, outcome observation), and the changes of a screen
 * too big to read in full that showed nothing of an offer (no offer on it can be judged). A change after a read of a
 * screen that could be read in full is never held here: the caller's 150 ms quiet gap is its only wait. Three limits,
 * all on the scanner's uptimes, one budget for Dasher (its windows share one UI thread):
 *
 * <ul>
 *   <li>A token bucket: at most {@link #TOKEN_MS one read every 250 ms} (4 a second) sustained, {@link #BURST} at
 *   once; while a delivery or route is under way, at most one every {@link #CALM_MS}.</li>
 *   <li>Backoff from what such a read cost: one that took D ms is followed by the next no sooner than
 *   max({@link #MIN_GAP_MS}, {@link #COST_FACTOR}·D) after it ended, so these reads take a quarter of Dasher's time at
 *   most however long each takes; slow reads in a row ({@link #SLOW_READ_MS} or more each) double that, up to
 *   {@link #MAX_GAP_MS} (or the cost gap, when longer). A read that cost under 50 ms leaves the caller's own 150 ms
 *   quiet gap in charge.</li>
 *   <li>A watchdog: Dasher's slowest node fetch in each of the last {@link #FETCH_SAMPLES} reads. When their median
 *   is over {@link #SLOW_FETCH_MS} after a read that showed nothing of an offer, such reads stop for
 *   {@link #FIRST_YIELD_MS}; the first read after that is a probe, and while it is still slow the pause doubles, up to
 *   {@link #MAX_YIELD_MS}.</li>
 * </ul>
 *
 * The moment a read, the text of an event Android delivered, a new Dasher window or an offer notification shows any
 * sign of an offer, the caller reads as it always did: none of this applies to an offer. An offer's sign refills the
 * bucket and forgets the cost ({@link #evidence}); a pause for Dasher's slowness stands until a probe finds Dasher
 * answering in time again. Pure arithmetic, no Android; scanner thread only.
 */
final class ReadBudget {
    /** The scanner's quiet gap: never less than this after a read of a burst. */
    static final long MIN_GAP_MS = 150;
    /** The gap after a read is this many times its own time: such reads keep Dasher busy a quarter of the time at most. */
    static final long COST_FACTOR = 3;
    /** Slow reads in a row double the gap up to this (a single read's own cost gap may be longer). */
    static final long MAX_GAP_MS = 2_000;
    static final long SLOW_READ_MS = 250;
    static final int BURST = 2;
    /** One read per token: four a second. */
    static final long TOKEN_MS = 250;
    /** A delivery or route under way: one read a second. */
    static final long CALM_MS = 1_000;
    static final long SLOW_FETCH_MS = 200;
    static final int FETCH_SAMPLES = 10;
    /** The watchdog judges no fewer reads than this. */
    static final int MIN_FETCH_SAMPLES = 3;
    static final long FIRST_YIELD_MS = 3_000;
    static final long MAX_YIELD_MS = 10_000;

    private static final long NEVER = Long.MIN_VALUE / 4;

    /** The bucket as a theoretical arrival time: a read may start once it is no further than the burst away. */
    private long tokenAt = NEVER;
    /** Until when the cost of the last read holds the next one back. */
    private long costUntil = NEVER;
    private long lastGap;
    private int slowInRow;

    private final long[] fetches = new long[FETCH_SAMPLES];
    private int fetchCount;
    private int fetchNext;
    private long yieldUntil = NEVER;
    private long yieldMs = FIRST_YIELD_MS;
    /** The next read that shows nothing of an offer is the first after a pause: it decides whether to pause again. */
    private boolean probe;

    /**
     * The earliest uptime the next budgeted read may start.
     *
     * @param quietDue when the caller's own quiet gap allows it (150 ms after the last read of a burst)
     * @param calm whether a delivery or route is under way
     */
    long dueAt(long quietDue, boolean calm) {
        long tokens = calm ? tokenAt : tokenAt - (BURST - 1) * TOKEN_MS;
        return Math.max(Math.max(quietDue, costUntil), Math.max(tokens, yieldUntil));
    }

    /** A budgeted read starts now: it takes a token. */
    void take(long now, boolean calm) {
        tokenAt = Math.max(tokenAt, now) + (calm ? CALM_MS : TOKEN_MS);
    }

    /** A read showed nothing of an offer: what it cost holds the next budgeted one back. */
    void factFree(long started, long ended) {
        long took = Math.max(0, ended - started);
        long cost = Math.max(MIN_GAP_MS, COST_FACTOR * took);
        long gap = cost;
        if (took >= SLOW_READ_MS) {
            slowInRow++;
            if (slowInRow >= 2) gap = Math.max(gap, Math.min(MAX_GAP_MS, 2 * lastGap));
        } else {
            slowInRow = 0;
        }
        lastGap = gap;
        // At the floor, the caller's own quiet gap rules, exactly as before (a read right after a window change's).
        costUntil = gap > MIN_GAP_MS ? ended + gap : NEVER;
    }

    /** A sign of an offer: the bucket is full and the cost forgotten. A pause for Dasher's slowness stands. */
    void evidence() {
        tokenAt = NEVER;
        costUntil = NEVER;
        lastGap = 0;
        slowInRow = 0;
    }

    /** An offer notification, or reads resuming after a pause: as {@link #evidence}, and Dasher's slowness is judged anew. */
    void fresh() {
        evidence();
        yieldUntil = NEVER;
        Arrays.fill(fetches, 0);
        fetchCount = 0;
        fetchNext = 0;
        yieldMs = FIRST_YIELD_MS;
        probe = false;
    }

    /**
     * A read's slowest single fetch from Dasher, for the watchdog: every read of Dasher's content counts, and only one
     * that showed nothing of an offer may start a pause.
     *
     * @return the median that started a pause (ms), or -1 when none started
     */
    long fetched(long slowestMs, boolean factFree, long now) {
        if (probe && factFree) {
            // The first such read since the pause began decides: Dasher still slow, a longer pause; else none.
            probe = false;
            if (slowestMs <= SLOW_FETCH_MS) {
                Arrays.fill(fetches, 0);
                fetchCount = 0;
                fetchNext = 0;
                yieldMs = FIRST_YIELD_MS;
                yieldUntil = NEVER;
                add(slowestMs);
                return -1;
            }
            add(slowestMs);
            return pause(now, median());
        }
        add(slowestMs);
        if (!factFree || now < yieldUntil || fetchCount < MIN_FETCH_SAMPLES) return -1;
        long median = median();
        return median > SLOW_FETCH_MS ? pause(now, median) : -1;
    }

    private long pause(long now, long median) {
        yieldUntil = now + yieldMs;
        yieldMs = Math.min(MAX_YIELD_MS, yieldMs * 2);
        probe = true;
        return median;
    }

    private void add(long ms) {
        fetches[fetchNext] = Math.max(0, ms);
        fetchNext = (fetchNext + 1) % FETCH_SAMPLES;
        fetchCount = Math.min(FETCH_SAMPLES, fetchCount + 1);
    }

    /** The median of the last reads' slowest fetches (ms), 0 with none. */
    long median() {
        if (fetchCount == 0) return 0;
        long[] sorted = Arrays.copyOf(fetches, fetchCount);
        // The ring holds the newest FETCH_SAMPLES; with fewer, its first fetchCount slots are the ones written.
        Arrays.sort(sorted);
        return fetchCount % 2 == 1 ? sorted[fetchCount / 2]
                : (sorted[fetchCount / 2 - 1] + sorted[fetchCount / 2]) / 2;
    }

    /** Whether reads that show nothing of an offer are paused now. */
    boolean yielding(long now) {
        return now < yieldUntil;
    }

    /** When the pause ends (uptime), for a read held back until then. */
    long yieldUntil() {
        return yieldUntil;
    }
}
