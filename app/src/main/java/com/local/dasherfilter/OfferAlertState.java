package com.local.dasherfilter;

/** One notification incarnation. Updates cannot extend its lifetime or reannounce a handled pass. */
final class OfferAlertState {
    static final long LIFETIME_MS = 90_000L;
    final long createdAt;
    final long sourceWhen;
    long postedAt;
    boolean rang;
    boolean actionRequested;
    boolean displayed;
    String signature = "";
    OfferRule.Result result;
    OfferAlertState(long now, long postedAt, long sourceWhen) { this.createdAt = now; this.postedAt = postedAt; this.sourceWhen = sourceWhen; }
    boolean expired(long now) { return now < createdAt || now - createdAt >= LIFETIME_MS; }
    boolean duplicate(String nextSignature, OfferRule.Result nextResult) { return displayed && signature.equals(nextSignature) && result == nextResult; }
    boolean shouldRing(OfferRule.Result nextResult, boolean foreground, boolean replay) { return nextResult == OfferRule.Result.KEEP && !rang && !foreground && !replay; }
    void delivered(String signature, OfferRule.Result result, boolean rang) {
        this.signature = signature; this.result = result;
        // Reconnect/foreground delivery consumes the passing-announcement budget too.
        this.rang |= rang || result == OfferRule.Result.KEEP; this.displayed = true;
    }
    boolean removalMatches(long removedPostTime) { return removedPostTime >= postedAt; }
}
