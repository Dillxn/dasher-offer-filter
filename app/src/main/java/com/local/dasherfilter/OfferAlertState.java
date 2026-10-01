package com.local.dasherfilter;

/** One notification incarnation. Updates cannot extend its lifetime or reannounce a handled pass. */
final class OfferAlertState {
    static final long LIFETIME_MS = 90_000L;
    /** A re-post with unchanged text this long after the last post on its key is a new offer, not an update. */
    static final long QUIET_REPOST_MS = 15_000L;

    /** {@code SystemClock.elapsedRealtime()} when this incarnation began. */
    final long createdAt;
    /** Latest source-notification post time (wall clock); older removals are stale. */
    long postedAt;
    boolean rang;
    boolean actionRequested;
    boolean displayed;
    String signature = "";
    OfferRule.Result result;
    /** The latest post's text, to tell an unchanged re-post. */
    String text = "";
    /** The offer as the screen read it, once it has; null until then. */
    OfferSnapshot readOnScreen;
    /** The latest post (its post time) the screen has read; a later post on this incarnation is unread. */
    long readThrough;
    /** The screen's reading cleared this incarnation's card, and no card has shown for it since. */
    boolean cardCleared;
    /** The screen read this offer and then showed it gone: Dasher idle, a delivery, or the dash over. */
    boolean endedOnScreen;
    /** An unchanged re-post of this offer was already logged. */
    boolean repostLogged;

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
     * Why a re-post on this incarnation's key, from the same store, is a new offer rather than an update of this
     * one, or null when it is this offer again: the screen read this offer and then saw it end; or, while Dasher is
     * not on screen (where nothing else would tell), the screen read this offer and cleared its card and the text
     * changed, or the same text came again after a quiet gap. DoorDash's own updates of one offer change the text or
     * follow closely; an update of the offer the screen read, or an aging update with unchanged text after a gap,
     * would be taken for a new offer and announced (ringing once) as one.
     */
    String newOfferReason(String nextText, long nextPostTime, boolean foreground) {
        if (endedOnScreen) return "the screen saw the last offer end";
        if (foreground) return null;
        if (cardCleared && !nextText.equals(text)) {
            return "the screen read the last offer and cleared its card; the text changed";
        }
        long gap = nextPostTime - postedAt;
        if (nextText.equals(text) && gap > QUIET_REPOST_MS) {
            return "unchanged text " + gap / 1000 + " s after the last post";
        }
        return null;
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
        this.cardCleared = false;
    }

    /** The screen read this offer, through the latest post so far, and its card was cleared. */
    void markRead(OfferSnapshot screenFacts) {
        this.readOnScreen = screenFacts;
        this.readThrough = postedAt;
        this.cardCleared = true;
    }

    /** The screen read this offer: its notification needs no card of its own. Leaves the ring budget alone. */
    void settle(String signature, OfferRule.Result result, OfferSnapshot screenFacts) {
        this.signature = signature;
        this.result = result;
        this.displayed = true;
        markRead(screenFacts);
    }

    /**
     * An update of an offer the screen already read needs no card or line while the screen is reading it (Dasher on
     * screen), and a replay (a reconnect, pausing, resuming or a rules change) needs none for a post the screen read:
     * one posted at or before its reading ({@code postTime}, the post's own time). A known failure, facts that differ,
     * a replay of a later post, or an update while Dasher is off screen (perhaps a next offer on a reused key) is
     * handled as usual, so a card of an offer the screen never read stays.
     */
    boolean coveredByScreen(OfferRule.Result next, OfferSnapshot facts, boolean foreground, boolean replay,
                            long postTime) {
        if (readOnScreen == null || next == OfferRule.Result.DECLINE || facts.contradicts(readOnScreen)) return false;
        return replay ? postTime <= readThrough : foreground;
    }

    boolean removalMatches(long removedPostTime) {
        return removedPostTime >= postedAt;
    }
}
