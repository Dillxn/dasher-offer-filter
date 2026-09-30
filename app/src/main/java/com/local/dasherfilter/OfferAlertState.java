package com.local.dasherfilter;

/** One notification incarnation. Updates cannot extend its lifetime or reannounce a handled pass. */
final class OfferAlertState {
    static final long LIFETIME_MS = 90_000L;

    /** {@code SystemClock.elapsedRealtime()} when this incarnation began. */
    final long createdAt;
    /** Latest source-notification post time (wall clock); older removals are stale. */
    long postedAt;
    boolean rang;
    boolean actionRequested;
    boolean displayed;
    String signature = "";
    OfferRule.Result result;

    OfferAlertState(long now, long postedAt) {
        this.createdAt = now;
        this.postedAt = postedAt;
    }

    boolean expired(long now) {
        return now < createdAt || now - createdAt >= LIFETIME_MS;
    }

    /** True when this exact content and decision are already displayed. */
    boolean duplicate(String nextSignature, OfferRule.Result nextResult) {
        return displayed && signature.equals(nextSignature) && result == nextResult;
    }

    /**
     * Once per offer, while Dasher is not on screen and never on a replay: a passing offer, or one that could not be
     * judged, so a background offer is heard. A known failure never rings.
     */
    boolean shouldRing(OfferRule.Result nextResult, boolean foreground, boolean replay) {
        return nextResult != OfferRule.Result.DECLINE && !rang && !foreground && !replay;
    }

    void delivered(String signature, OfferRule.Result result, boolean rang) {
        this.signature = signature;
        this.result = result;
        // Reconnect/foreground delivery consumes the passing-announcement budget too.
        this.rang |= rang || result == OfferRule.Result.KEEP;
        this.displayed = true;
    }

    boolean removalMatches(long removedPostTime) {
        return removedPostTime >= postedAt;
    }
}
