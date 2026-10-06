package com.local.dasherfilter;

import java.util.Arrays;

/**
 * How often Dasher's screen is read while the last read showed no sign of an offer, so the reader never keeps
 * Dasher's own UI thread from drawing or from answering the user's taps (every node read is a call that thread must
 * serve; Dasher's map and delivery screens are big and change many times a second). Three limits, all on the
 * scanner's uptimes, none of them on an offer:
 *
 * <ul>
 *   <li>A token bucket for the Dasher window being read: at most {@link #TOKEN_MS one read every 250 ms} (4 a second)
 *   sustained, {@link #BURST} at once; while a delivery or route is under way, at most one every {@link #CALM_MS}.
 *   A new window starts with a full bucket.</li>
 *   <li>Backoff from what such a read cost: one that took D ms is followed by the next no sooner than
 *   min({@link #MAX_GAP_MS}, max({@link #MIN_GAP_MS}, 2·D)) after it ended; slow reads in a row
 *   ({@link #SLOW_READ_MS} or more each) double that, up to {@link #MAX_GAP_MS}. A read that cost under 75 ms
 *   leaves the caller's own 150 ms quiet gap in charge, as before.</li>
 *   <li>A watchdog: Dasher's slowest node fetch in each of the last {@link #FETCH_SAMPLES} reads. When their median
 *   is over {@link #SLOW_FETCH_MS} after a read that showed nothing of an offer, such reads stop for
 *   {@link #FIRST_YIELD_MS}; the first read after that is a probe, and while it is still slow the pause doubles, up to
 *   {@link #MAX_YIELD_MS}.</li>
 * </ul>
 *
 * The moment a read, the text of an event Android delivered, a new Dasher window or an offer notification shows any
 * sign of an offer, the caller reads as it always did ({@link #evidence}): none of this applies to an offer. Pure
 * arithmetic, no Android; scanner thread only.
 */
final class ReadBudget {
    /** The scanner's quiet gap: never less than this after a read of a burst. */
    static final long MIN_GAP_MS = 150;
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
    /** No window known: Android gave none an ID (tests and stand-ins). */
    static final int NO_WINDOW = Integer.MIN_VALUE;

    private static final long NEVER = Long.MIN_VALUE / 4;

    private int window = NO_WINDOW;
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

    /** The Dasher window a read found: a different one starts afresh (a full bucket, no backoff). */
    void window(int id) {
        if (id == window) return;
        window = id;
        tokenAt = NEVER;
        costUntil = NEVER;
        lastGap = 0;
        slowInRow = 0;
    }

    /** A read showed nothing of an offer: what it cost holds the next one back. */
    void factFree(long started, long ended) {
        long took = Math.max(0, ended - started);
        long gap = Math.max(MIN_GAP_MS, 2 * took);
        if (took >= SLOW_READ_MS) {
            slowInRow++;
            if (slowInRow >= 2) gap = Math.max(gap, 2 * lastGap);
        } else {
            slowInRow = 0;
        }
        gap = Math.min(MAX_GAP_MS, gap);
        lastGap = gap;
        // At the floor, the caller's own quiet gap rules, exactly as before (a read right after a window change's).
        costUntil = gap > MIN_GAP_MS ? ended + gap : NEVER;
    }

    /** A sign of an offer: reads go as they always did, and the budget starts afresh after it. */
    void evidence() {
        tokenAt = NEVER;
        costUntil = NEVER;
        lastGap = 0;
        slowInRow = 0;
        yieldUntil = NEVER;
    }

    /** An offer notification or a rules change: as {@link #evidence}, and Dasher's slowness is judged anew. */
    void fresh() {
        evidence();
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
