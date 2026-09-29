package com.local.dasherfilter;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Opt-in runaway guard. Counts distinct decline requests (screen taps, notification decline actions, and hides) in the
 * trailing hour. It only ever turns a would-be decline into review; it never delays a request that is under budget.
 */
final class DeclineBudget {
    static final long WINDOW_MS = 60L * 60 * 1000;
    static final int MAX_ENTRIES = 512;
    private static final class Entry { final long at; final String key; Entry(long at, String key) { this.at = at; this.key = key; } }
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    /** Records one request. A repeat of the same offer key inside the window (retry taps, confirmation) is not counted again. */
    synchronized void record(String offerKey, long now) {
        prune(now);
        if (offerKey != null) for (Entry e : entries) if (offerKey.equals(e.key) && inWindow(e.at, now)) return;
        entries.addLast(new Entry(now, offerKey));
        while (entries.size() > MAX_ENTRIES) entries.removeFirst();
    }
    synchronized int count(long now) { return count(now, null); }
    /** Requests in the trailing hour, excluding the offer currently being evaluated so its own retry is never blocked by itself. */
    synchronized int count(long now, String excludeKey) {
        int n = 0;
        for (Entry e : entries) if (inWindow(e.at, now) && (excludeKey == null || !excludeKey.equals(e.key))) n++;
        return n;
    }
    /** True when a limit is set and the trailing-hour count has reached it. */
    synchronized boolean exhausted(int limitPerHour, long now, String excludeKey) { return limitPerHour > 0 && count(now, excludeKey) >= limitPerHour; }
    synchronized void clear() { entries.clear(); }

    /** Seeds from on-device history records (wall clock) so a service restart cannot reset the guard. */
    static DeclineBudget fromHistory(Iterable<OfferRecord> records, long nowWall) {
        DeclineBudget budget = new DeclineBudget();
        if (records != null) for (OfferRecord r : records) if (r != null && r.declineRequestCounted() && inWindow(r.at, nowWall)) budget.record("record:" + r.id, r.at);
        return budget;
    }
    /** A backwards clock step keeps recent entries counted (conservative); entries far in the future are ignored. */
    private static boolean inWindow(long at, long now) { return now - at < WINDOW_MS && at - now < WINDOW_MS; }
    private void prune(long now) { for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) if (!inWindow(it.next().at, now)) it.remove(); }
}
