package com.local.dasherfilter;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Split screen during a delivery: the half that isn't navigating is made small with the divider's own accessibility
 * actions, and gets its size back when the delivery ends (the owner, 13 October 2026: "when either app is in a maps mode
 * for nav the other when in split needs to resize to like 20%"). This class is pure: what the divider's labels say,
 * which one to take, whether the moment is safe, and what this dash has done so far. {@link SplitDividerMover} reads
 * Android's list of windows and performs the one action. Nothing here touches Dasher, and no gesture is ever made.
 *
 * <p>The rule: Dasher beside Offer Filter, Offer Filter's half shrinks (Dasher navigates); Offer Filter beside a
 * navigation app ({@link Peek#NAVIGATION}), Offer Filter's half shrinks; Dasher beside a navigation app, Dasher's half
 * shrinks. Once per delivery per split, to the smallest stop of at least {@link #LEAST_SHARE}%, and back at the wait for
 * offers or the dash's end, unless the user moved the divider: then it is left alone for the rest of the dash.
 */
final class SplitDivider {
    /** The shrunk half's least share: the owner's "like 20%". Stock Android's smallest usable stop is 30%. */
    static final int LEAST_SHARE = 20;
    /** A half already within this many points of its target is left alone (the user made it small already). */
    static final int ROOM = 8;
    /** The shrunk half's share moving by more than this, not by Offer Filter, is the user's move. */
    static final int USER_MOVED = 3;
    /** A move verified within this many points of its target moved. */
    static final int MOVED_WITHIN = 6;
    /** No sign of an offer for this long before a move. */
    static final long QUIET_MS = 3_000;
    /** No click of the user's on Dasher, and no finger on Offer Filter's half, for this long before a move. */
    static final long TOUCH_MS = 2_000;
    /** A move is checked from Android's list of windows this long after it. */
    static final long VERIFY_MS = 1_500;
    /** The divider's labels are logged once a split has stood this long ... */
    static final long SETTLE_MS = 1_000;
    /** ... and at most this often. */
    static final long PROBE_EVERY_MS = 60_000;
    /** At most this many of the divider's nodes are walked for its actions. */
    static final int MAX_NODES = 20;
    static final int MOVES_PER_DELIVERY = 2;
    static final int MOVES_PER_DASH = 12;

    /** Whose a half is, for the rule and the log. */
    enum Pane {
        OURS(AppName.NAME + "'s"), DASHER("Dasher's"), MAP("the map's"), OTHER("another app's");

        /** The half's name in the log: never another app's own name. */
        final String possessive;

        Pane(String possessive) {
            this.possessive = possessive;
        }
    }

    /** What the plan wants done next. */
    enum Want { NONE, SHRINK, RESTORE }

    // ---- The divider's labels ----

    /** The first whole number followed by "%" (a space, or a no-break space as some languages write it, allowed). */
    private static final Pattern SHARE = Pattern.compile("(?<![\\d.,])(\\d{1,3})[ \\u00A0\\u202F]?%");

    /**
     * The top or left app's share an action's label names ("Top 30%" and "30% top, 70% bottom" are 30, "Left 70%" and
     * "70 %" are 70), or -1 for a label naming none (full screen, swap): such an action is never taken.
     */
    static int share(CharSequence label) {
        if (label == null) return -1;
        Matcher found = SHARE.matcher(label);
        if (!found.find()) return -1;
        int share = Integer.parseInt(found.group(1));
        return share >= 1 && share <= 99 ? share : -1;
    }

    /** One of the divider's own actions that names a share. */
    static final class Target {
        final int id;
        final String label;
        /** The top or left app's share it gives. */
        final int share;

        Target(int id, String label, int share) {
            this.id = id;
            this.label = label;
            this.share = share;
        }
    }

    /**
     * The action that makes the half shrunk small: for the top or left half the smallest share of at least
     * {@link #LEAST_SHARE}; for the bottom or right half the largest of at most 100 less that. Null when none.
     */
    static Target forShrink(List<Target> targets, boolean firstHalf) {
        Target best = null;
        for (Target target : targets) {
            if (firstHalf) {
                if (target.share >= LEAST_SHARE && (best == null || target.share < best.share)) best = target;
            } else if (target.share <= 100 - LEAST_SHARE && (best == null || target.share > best.share)) {
                best = target;
            }
        }
        return best;
    }

    /** The action whose share is nearest the top or left half's share before the move; null when none. */
    static Target forRestore(List<Target> targets, int firstShareBefore) {
        Target best = null;
        for (Target target : targets) {
            if (best == null || Math.abs(target.share - firstShareBefore) < Math.abs(best.share - firstShareBefore)) {
                best = target;
            }
        }
        return best;
    }

    /**
     * Which half shrinks for this pair, or null when this pair is not one the rule names (Dasher beside an app that
     * is not a map, two other apps, a half not identified).
     */
    static Pane shrinks(Pane first, Pane second) {
        if (first == null || second == null) return null;
        if (has(first, second, Pane.OURS) && (has(first, second, Pane.DASHER) || has(first, second, Pane.MAP))) {
            return Pane.OURS;
        }
        if (has(first, second, Pane.DASHER) && has(first, second, Pane.MAP)) return Pane.DASHER;
        return null;
    }

    private static boolean has(Pane first, Pane second, Pane pane) {
        return first == pane || second == pane;
    }

    // ---- The safe moment ----

    /** What the screen reader knows at a moment, for {@link #blocked}. Times since are {@link Long#MAX_VALUE} for never. */
    static final class Moment {
        boolean noticeAccepted;
        /** Auto-decline off or no rule: nothing of Dasher's is read. */
        boolean paused;
        /** The screen on and the phone unlocked. */
        boolean unlocked;
        /** An offer or its question is up (the last read showed a sign of one), or a decline is under way. */
        boolean offerUp;
        long sinceOfferSign = Long.MAX_VALUE;
        /** What is under way that a move must wait for (a decline, a peek, a read queued...), or null for nothing. */
        String underWay;
        long sinceUserClick = Long.MAX_VALUE;
        long sinceOwnTouch = Long.MAX_VALUE;
        /** The last read of Dasher was its own turn-by-turn navigation. */
        boolean navigationRead;
    }

    /** Why a move must wait now, or null when the moment is safe. */
    static String blocked(Moment moment) {
        if (!moment.noticeAccepted) return "the notice is not accepted";
        if (moment.paused) return "auto-decline is paused";
        if (!moment.unlocked) return "the phone is locked or its screen is off";
        if (moment.offerUp) return "an offer is up";
        if (moment.sinceOfferSign < QUIET_MS) return "an offer was seen " + moment.sinceOfferSign + " ms ago";
        if (moment.underWay != null) return moment.underWay + " is under way";
        if (moment.sinceUserClick < TOUCH_MS) return "you tapped Dasher " + moment.sinceUserClick + " ms ago";
        if (moment.sinceOwnTouch < TOUCH_MS) {
            return "you touched " + AppName.NAME + "'s half " + moment.sinceOwnTouch + " ms ago";
        }
        // Dasher's own navigation may not survive a resize (the owner, 10 October 2026, 20:54).
        if (moment.navigationRead) return "Dasher's navigation is on screen";
        return null;
    }

    // ---- What this dash has done (scanner thread) ----

    /** A half Offer Filter shrank, in the split it shrank it in. */
    private static final class Shrunk {
        final Pane pane;
        /** The top or left half's share before the move. */
        final int firstBefore;
        /** The shrunk half's share measured once the move was verified; -1 until then. */
        int after = -1;

        Shrunk(Pane pane, int firstBefore) {
            this.pane = pane;
            this.firstBefore = firstBefore;
        }
    }

    /** A move to check {@link #VERIFY_MS} after it. */
    private static final class Verify {
        final boolean restore;
        final int firstWanted;
        final long due;

        Verify(boolean restore, int firstWanted, long due) {
            this.restore = restore;
            this.firstWanted = firstWanted;
            this.due = due;
        }
    }

    private long dashKey;
    private boolean delivering;
    private int deliveryMoves;
    private int dashMoves;
    /** Set: no more moves this dash. */
    private String stoppedFor;
    /** A shrink was made (or found needless) this delivery in this split. */
    private boolean doneThisDelivery;
    private Shrunk shrunk;
    private boolean restoreDue;
    private Verify verify;
    private boolean capNoted;

    /** A dash under way, by its start (0: none). A new dash starts over; the end of one keeps a restore due. */
    void dash(long key) {
        if (key == 0 || key == dashKey) return;
        dashKey = key;
        dashMoves = 0;
        stoppedFor = null;
        capNoted = false;
    }

    /** Whether a delivery is under way: its start allows one shrink, its end asks for the restore. */
    void delivery(boolean on) {
        if (on && !delivering) {
            deliveryMoves = 0;
            doneThisDelivery = false;
            restoreDue = false;
            capNoted = false;
        } else if (!on && delivering && shrunk != null) {
            restoreDue = true;
        }
        delivering = on;
    }

    /**
     * The split ended: what was shrunk in it is forgotten, and a split entered again during the delivery may shrink
     * again. Returns a log line when a move was still to be checked (the move then stops for the dash), else null.
     */
    String splitEnded() {
        Verify pending = verify;
        verify = null;
        shrunk = null;
        restoreDue = false;
        doneThisDelivery = false;
        if (pending == null) return null;
        return stop("split ended after the divider moved");
    }

    /**
     * {@link #VERIFY_MS} after a move: whether the halves came within {@link #MOVED_WITHIN} points of its target. A
     * shrink's half share is taken then, for the user's move. Returns a log line when it didn't move, else null.
     *
     * @param firstShare the top or left half's share now
     * @param shrunkShare the shrunk half's share now (-1: that half is gone)
     */
    String verify(int firstShare, int shrunkShare, long now) {
        Verify pending = verify;
        if (pending == null || now < pending.due) return null;
        verify = null;
        if (Math.abs(firstShare - pending.firstWanted) > MOVED_WITHIN || (shrunk != null && shrunkShare < 0)) {
            shrunk = null;
            restoreDue = false;
            return stop("divider didn't move (top or left half " + firstShare + "%, wanted " + pending.firstWanted
                    + "%)");
        }
        if (pending.restore) shrunk = null;
        else if (shrunk != null) shrunk.after = shrunkShare;
        return null;
    }

    /**
     * The user's touch wins: once a shrink is verified, the shrunk half's share moving by more than
     * {@link #USER_MOVED} points (or the half gone from the pair) is the user's move, and nothing moves again this dash.
     * Returns the log line, else null.
     */
    String watchUser(int shrunkShare) {
        if (shrunk == null || shrunk.after < 0 || verify != null || stoppedFor != null) return null;
        if (shrunkShare >= 0 && Math.abs(shrunkShare - shrunk.after) <= USER_MOVED) return null;
        shrunk = null;
        restoreDue = false;
        return stop("you moved the divider");
    }

    /** The half shrunk and waiting for its restore, or null. */
    Pane shrunkPane() {
        return shrunk == null ? null : shrunk.pane;
    }

    /** Whether a move is still to be checked, or a shrunk half is watched: the shares are needed. */
    boolean watching() {
        return verify != null || shrunk != null;
    }

    /** What to do now, before the moment's guards: a restore due, a shrink this delivery, or nothing. */
    Want want() {
        if (stoppedFor != null || verify != null) return Want.NONE;
        if (restoreDue && shrunk != null && shrunk.after >= 0) return Want.RESTORE;
        if (delivering && !doneThisDelivery && shrunk == null) return Want.SHRINK;
        return Want.NONE;
    }

    /**
     * Null when the caps allow a move; otherwise the log line saying which cap stops it, the first time, and "" after.
     */
    String capped() {
        if (deliveryMoves < MOVES_PER_DELIVERY && dashMoves < MOVES_PER_DASH) return null;
        if (capNoted) return "";
        capNoted = true;
        return dashMoves >= MOVES_PER_DASH ? "divider: " + MOVES_PER_DASH + " moves this dash already; left as it is"
                : "divider: " + MOVES_PER_DELIVERY + " moves this delivery already; left as it is";
    }

    /**
     * Whether the half to shrink is more than {@link #ROOM} points larger than its target; when not, it is left
     * alone for this delivery in this split.
     */
    boolean roomFor(int halfShare, int halfTarget) {
        if (halfShare - halfTarget > ROOM) return true;
        doneThisDelivery = true;
        return false;
    }

    /** The top or left half's share before the shrink, for the restore. */
    int firstBefore() {
        return shrunk == null ? -1 : shrunk.firstBefore;
    }

    /**
     * Offer Filter moved the divider: counted, checked {@link #VERIFY_MS} from now.
     *
     * @param pane the half shrunk (a shrink), else ignored
     * @param firstShareBefore the top or left half's share before the move
     * @param firstWanted the top or left half's share the action gives
     */
    void moved(Want kind, Pane pane, int firstShareBefore, int firstWanted, long now) {
        dashMoves++;
        deliveryMoves++;
        verify = new Verify(kind == Want.RESTORE, firstWanted, now + VERIFY_MS);
        if (kind == Want.SHRINK) {
            shrunk = new Shrunk(pane, firstShareBefore);
            doneThisDelivery = true;
        } else {
            restoreDue = false;
        }
    }

    /** No more moves this dash; the log line. */
    String stop(String why) {
        stoppedFor = why;
        verify = null;
        shrunk = null;
        restoreDue = false;
        return why + ": left alone for the rest of this dash";
    }

    /** Why nothing moves again this dash, or null. */
    String stoppedFor() {
        return stoppedFor;
    }
}
