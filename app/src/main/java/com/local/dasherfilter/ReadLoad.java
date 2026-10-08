package com.local.dasherfilter;

/**
 * What reading Dasher cost over the last minute, for one fixed-vocabulary line a minute while reads happen ("[screen]
 * read load: …"): counts and milliseconds only, never a word of any screen. Scanner thread only.
 */
final class ReadLoad {
    static final long EVERY_MS = 60_000;

    private int reads;
    private int factFree;
    private long nodes;
    private long slowestMs;
    private int truncated;
    private int pruned;
    private int yields;
    /** Reads made at once (a window change, a notification's check, an event's own sign of an offer), not routine. */
    private int atOnce;

    /** One read of Dasher's content. */
    void read(boolean showedNothingOfAnOffer, int visited, long tookMs, boolean cut, int mapsPruned) {
        read(showedNothingOfAnOffer, visited, tookMs, cut, mapsPruned, false);
    }

    /** @param atOnceRead whether the read was made at once rather than on the quiet gap or the budget's cadence */
    void read(boolean showedNothingOfAnOffer, int visited, long tookMs, boolean cut, int mapsPruned,
              boolean atOnceRead) {
        reads++;
        if (atOnceRead) atOnce++;
        if (showedNothingOfAnOffer) factFree++;
        nodes += Math.max(0, visited);
        slowestMs = Math.max(slowestMs, tookMs);
        if (cut) truncated++;
        pruned += Math.max(0, mapsPruned);
    }

    /** Reads that showed nothing of an offer were paused for Dasher. */
    void yielded() {
        yields++;
    }

    boolean any() {
        return reads > 0 || yields > 0;
    }

    /** The minute's line, and a fresh minute after it. */
    String take(long medianFetchMs) {
        String line = "read load: " + reads + " reads/min (fact-free " + factFree + "), nodes " + nodes + ", slowest "
                + ms(slowestMs) + ", median fetch " + ms(medianFetchMs) + ", truncated " + truncated
                + ", pruned map subtrees " + pruned + ", yields " + yields + ", at once " + atOnce;
        reads = 0;
        factFree = 0;
        nodes = 0;
        slowestMs = 0;
        truncated = 0;
        pruned = 0;
        yields = 0;
        atOnce = 0;
        return line;
    }

    /**
     * A duration for a log line, "N ms". From ten seconds on its digits are grouped ("12,345 ms"): the log's masking
     * takes five digits before "ms" for a ZIP code and a Mississippi address, and would hide the very waits worth seeing.
     */
    static String ms(long millis) {
        return millis >= 10_000 ? String.format(java.util.Locale.US, "%,d ms", millis) : millis + " ms";
    }
}
