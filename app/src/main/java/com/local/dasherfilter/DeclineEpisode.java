package com.local.dasherfilter;

/**
 * One automatic decline, from its first Decline tap on an offer, as far as Dasher's question ("Are you sure you want
 * to decline this offer?") goes: whether the offer's screen gave way (to the question, or to any screen without the
 * offer and its Accept), whether the app's tap on the question's Decline was requested, and what that offer showing
 * again without its question means.
 *
 * <ul>
 *   <li>Before the offer's screen gave way, the offer may be declined again (Dasher may have missed the first tap):
 *       {@link Back#NONE}, and the usual retry limits apply.
 *   <li>After it gave way, with no request of the question's Decline, the offer showing again means the user went
 *       back to it ("View offer details", "Go back"): {@link Back#HAND_BACK}.
 *   <li>After the question's Decline was requested, Dasher closing the question and still showing the offer is a
 *       glitch of Dasher's only once the offer has kept showing for {@link #GLITCH_MS}: {@link Back#DECLINE_AGAIN},
 *       once per episode; until then {@link Back#HOLD}, and after a second time {@link Back#GIVE_UP}.
 * </ul>
 *
 * <p>An episode ends with the decline handed back or given up, Dasher idle or on a delivery, a clearly different
 * offer, or {@link #MAX_MS} after its first tap. Pure Java; the scanner thread owns it.
 */
final class DeclineEpisode {
    /** How long the declined offer must keep showing after its confirmed question closed to be declined once more. */
    static final long GLITCH_MS = 2_000;
    /** An episode lasts this long at most after its first tap, as a takeover does. */
    static final long MAX_MS = 120_000;
    private static final long NEVER = Long.MIN_VALUE;

    /** What the declined offer showing again, with no question on it, means. */
    enum Back {
        /** No episode for it, or its screen never gave way: judged as usual. */
        NONE,
        /** Nothing is tapped yet: its question is still up, or Dasher may still be closing it. */
        HOLD,
        /** The user went back to it: it is theirs. */
        HAND_BACK,
        /** Dasher closed the question the app confirmed and kept showing the offer: one more first Decline. */
        DECLINE_AGAIN,
        /** The same, a second time: the offer is left to the user. */
        GIVE_UP
    }

    private String key = "";
    private OfferSnapshot offer = OfferSnapshot.UNKNOWN;
    private long declinedAt = NEVER;
    private long questionAt = NEVER;
    private long leftAt = NEVER;
    private boolean confirmed;
    private long backAt = NEVER;
    private boolean glitchUsed;
    private boolean over = true;
    private long glitchMs = GLITCH_MS;
    private long backWaitMs = GLITCH_MS;
    private long tentativeLeftAt = NEVER;

    void readDuration(long durationMs) { glitchMs = Math.max(GLITCH_MS, Math.min(10_000, 2 * durationMs)); }
    long glitchMs() { return backAt == NEVER ? glitchMs : backWaitMs; }

    /** Animation alone cannot prove the driver went back. */
    void screenLeft(long at, boolean windowChanged) {
        if (tentativeLeftAt == NEVER) tentativeLeftAt = at;
        if (windowChanged || at - tentativeLeftAt >= glitchMs) screenLeft(at);
    }

    void offerPresent() { tentativeLeftAt = NEVER; }

    /**
     * A first Decline was tapped on this offer. The same offer, while its episode lasts, keeps it (after
     * {@link Back#DECLINE_AGAIN}, the question is awaited afresh); any other starts a new one.
     */
    void declined(String offerKey, OfferSnapshot facts, long at) {
        boolean same = active(at) && covers(offerKey, facts);
        if (!same) {
            declinedAt = at;
            glitchUsed = false;
        }
        key = offerKey;
        offer = facts;
        questionAt = NEVER;
        tentativeLeftAt = NEVER;
        leftAt = NEVER;
        confirmed = false;
        backAt = NEVER;
        over = false;
    }

    /** An episode under way: begun, not ended, and younger than {@link #MAX_MS}. */
    boolean active(long now) {
        return !over && declinedAt != NEVER && now - declinedAt < MAX_MS;
    }

    /** Whether these are the declined offer: the same labels and facts, or facts that agree with its own. */
    boolean covers(String offerKey, OfferSnapshot facts) {
        return (!key.isEmpty() && key.equals(offerKey)) || facts.agreesWith(offer);
    }

    OfferSnapshot offer() {
        return offer;
    }

    /** The declined offer's question was read (and found to be its own). */
    void questionSeen(long at) {
        if (questionAt == NEVER) questionAt = at;
        backAt = NEVER;
    }

    boolean questionWasSeen() {
        return questionAt != NEVER;
    }

    /** A complete read of Dasher showed neither the declined offer nor its Accept: its screen gave way. */
    void screenLeft(long at) {
        if (leftAt == NEVER) leftAt = at;
        backAt = NEVER;
    }

    /** Whether the declined offer's screen gave way since its last first Decline tap. */
    boolean left() {
        return questionAt != NEVER || leftAt != NEVER;
    }

    /** Android took the app's tap on the question's Decline. */
    void confirmed() {
        confirmed = true;
    }

    boolean wasConfirmed() {
        return confirmed;
    }

    /** Waiting to see whether a confirmed question's offer keeps showing ({@link Back#HOLD} after a confirmation). */
    boolean holding(long now) {
        return active(now) && confirmed && backAt != NEVER;
    }

    /** When the declined offer was first seen again without its question after a confirmation, or -1. */
    long backSince() {
        return backAt == NEVER ? -1 : backAt;
    }

    /**
     * The declined offer showing, in a read at {@code now}.
     *
     * @param questionUp whether this read shows a question about declining too (drawn over the offer, but not taken
     *     for its own): nothing is tapped then
     */
    Back offerShowing(long now, boolean questionUp) {
        if (!active(now) || !left()) return Back.NONE;
        if (questionUp) return Back.HOLD;
        if (!confirmed) return Back.HAND_BACK;
        if (backAt == NEVER) { backAt = now; backWaitMs = glitchMs; }
        if (now - backAt < backWaitMs) return Back.HOLD;
        if (glitchUsed) return Back.GIVE_UP;
        glitchUsed = true;
        return Back.DECLINE_AGAIN;
    }

    /** The decline is over: handed back, given up, or Dasher moved on. */
    void end() {
        over = true;
        backAt = NEVER;
    }
}
