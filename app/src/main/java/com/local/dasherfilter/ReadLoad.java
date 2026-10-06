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

    /** One read of Dasher's content. */
    void read(boolean showedNothingOfAnOffer, int visited, long tookMs, boolean cut, int mapsPruned) {
        reads++;
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
                + slowestMs + " ms, median fetch " + medianFetchMs + " ms, truncated " + truncated
                + ", pruned map subtrees " + pruned + ", yields " + yields;
        reads = 0;
        factFree = 0;
        nodes = 0;
        slowestMs = 0;
        truncated = 0;
        pruned = 0;
        yields = 0;
        return line;
    }
}
