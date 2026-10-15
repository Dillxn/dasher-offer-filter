package com.local.dasherfilter;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Split screen beside a map: whenever a navigation app ({@link Peek#NAVIGATION}) is in one half and Dasher or Offer
 * Filter in the other, the map's half is made about 70% with the divider's own accessibility actions, and the divider
 * goes back where it was once no half is a map's (0.6.21, the owner: "whenever one half has nav open make it 70% height
 * / whenever one half has nav open the other shouldn't"; 0.6.11 to 0.6.20 shrank the half beside Dasher's delivery
 * screens instead). This class is pure: what the divider's labels say, which one to take, whether the moment is safe,
 * and what this split and this dash have done so far. {@link SplitDividerMover} reads Android's list of windows and
 * performs the one action. Nothing here touches Dasher or the map, and no gesture is ever made.
 *
 * <p>Dasher beside Offer Filter is left as it is, and nothing moves while Dasher's own navigation is on screen (it may
 * not survive a resize). The user's own move of the divider leaves it alone for the rest of the dash.
 */
final class SplitDivider {
    /** The map's half: the owner's "make it 70% height". */
    static final int MAP_SHARE = 70;
    /** A half already within this many points of its target is left alone (the user sized it already). */
    static final int ROOM = 8;
    /** The top or left half's share moving by more than this from where Offer Filter left it is the user's move. */
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
     * The action that gives the map's half the share nearest {@link #MAP_SHARE}, the larger of two equally near, and
     * never less than half the screen. Null when none.
     *
     * @param mapFirst the map is the top or left half
     */
    static Target forMap(List<Target> targets, boolean mapFirst) {
        Target best = null;
        int bestShare = -1;
        for (Target target : targets) {
            int map = mapFirst ? target.share : 100 - target.share;
            if (map < 50) continue;
            int distance = Math.abs(map - MAP_SHARE);
            int bestDistance = Math.abs(bestShare - MAP_SHARE);
            if (best == null || distance < bestDistance || (distance == bestDistance && map > bestShare)) {
                best = target;
                bestShare = map;
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
     * Which half is made small for this pair: the one beside a map, when it is Dasher's or Offer Filter's; null for
     * any other pair (Dasher beside Offer Filter, a map beside another app or another map, a half not identified).
     */
    static Pane shrinks(Pane first, Pane second) {
        if (first == Pane.MAP && (second == Pane.DASHER || second == Pane.OURS)) return second;
        if (second == Pane.MAP && (first == Pane.DASHER || first == Pane.OURS)) return first;
        return null;
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

    // ---- What this split and this dash have done (scanner thread) ----

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
    private int dashMoves;
    /** Set: no more moves this dash. */
    private String stoppedFor;
    private boolean capNoted;
    private Verify verify;
    /** The top or left half's share before Offer Filter first moved the divider in this split; -1 when it hasn't. */
    private int firstBefore = -1;
    /** The top or left half's share where Offer Filter's last move for a map left it, once verified; -1 otherwise. */
    private int placed = -1;
    /** Offer Filter moved the divider in this split. */
    private boolean movedThisSplit;
    /** The pair last said to be sized already in this split, so it is said once. */
    private String saidAlready;
    /** Whose the halves were at the last look (null: not identified), since when, and the top or left share then. */
    private Pane pairFirst;
    private Pane pairSecond;
    private long pairSince;
    private int lastShare = -1;

    /** A dash under way, by its start (0: none). A new dash starts over. */
    void dash(long key) {
        if (key == 0 || key == dashKey) return;
        dashKey = key;
        dashMoves = 0;
        stoppedFor = null;
        capNoted = false;
    }

    /**
     * The split ended: what Offer Filter did in it is forgotten. Returns a log line when a move was still to be
     * checked (the move then stops for the dash), else null.
     */
    String splitEnded() {
        Verify pending = verify;
        verify = null;
        firstBefore = -1;
        placed = -1;
        movedThisSplit = false;
        saidAlready = null;
        pairFirst = null;
        pairSecond = null;
        lastShare = -1;
        if (pending == null) return null;
        return stop("split ended after the divider moved");
    }

    /**
     * {@link #VERIFY_MS} after a move: whether the top or left half came within {@link #MOVED_WITHIN} points of its
     * target. Returns a log line when it didn't move, else null.
     *
     * @param firstShare the top or left half's share now
     */
    String verify(int firstShare, long now) {
        Verify pending = verify;
        if (pending == null || now < pending.due) return null;
        verify = null;
        if (Math.abs(firstShare - pending.firstWanted) > MOVED_WITHIN) {
            return stop("divider didn't move (top or left half " + firstShare + "%, wanted " + pending.firstWanted
                    + "%)");
        }
        if (pending.restore) restored();
        else placed = firstShare;
        return null;
    }

    /**
     * Each look at the two halves: whose they are (null when not identified) and the top or left half's share. A pair
     * newly seen settles for {@link #SETTLE_MS} before anything moves for it. The user's touch wins: while a map stands
     * beside Dasher or Offer Filter, or the divider is still to be put back, the share moving by more than
     * {@link #USER_MOVED} points between two looks at the same settled pair, with no move of Offer Filter's to check,
     * is the user's move, and nothing moves again this dash (another app taking a half moves nothing, so it never is).
     * Returns the log line, else null.
     */
    String look(Pane first, Pane second, int firstShare, long now) {
        if (first == null || second == null) {
            pairFirst = null;
            pairSecond = null;
            lastShare = -1;
            return null;
        }
        if (first != pairFirst || second != pairSecond) {
            pairFirst = first;
            pairSecond = second;
            pairSince = now;
            lastShare = firstShare;
            return null;
        }
        if (stoppedFor != null || verify != null) {
            lastShare = -1;
            return null;
        }
        boolean watched = shrinks(first, second) != null || placed >= 0;
        int before = lastShare;
        lastShare = firstShare;
        if (!watched || before < 0 || now - pairSince < SETTLE_MS || Math.abs(firstShare - before) <= USER_MOVED) {
            return null;
        }
        return stop("you moved the divider");
    }

    /**
     * What to do for the pair of the last look, before the moment's guards: make the half beside a map small, put the
     * divider back once no half is a map's (after a move for one), or nothing. Nothing while a move is still to be
     * checked, after a stop, while either half is not identified or before the pair has settled.
     */
    Want want(long now) {
        if (stoppedFor != null || verify != null || pairFirst == null || pairSecond == null) return Want.NONE;
        if (now - pairSince < SETTLE_MS) return Want.NONE;
        if (shrinks(pairFirst, pairSecond) != null) return Want.SHRINK;
        if (placed >= 0 && pairFirst != Pane.MAP && pairSecond != Pane.MAP) return Want.RESTORE;
        return Want.NONE;
    }

    /**
     * Null when the dash's cap allows a move; otherwise the log line saying so, the first time, and "" after.
     */
    String capped() {
        if (dashMoves < MOVES_PER_DASH) return null;
        if (capNoted) return "";
        capNoted = true;
        return "divider: " + MOVES_PER_DASH + " moves this dash already; left as it is";
    }

    /** Whether the half to make small is more than {@link #ROOM} points larger than its target. */
    static boolean roomFor(int halfShare, int halfTarget) {
        return halfShare - halfTarget > ROOM;
    }

    /**
     * Whether to say the last look's pair is sized already: once per pair in a split, and only while Offer Filter has
     * not moved the divider in it (the user sized it).
     */
    boolean sayAlready() {
        String pair = pairFirst + "/" + pairSecond;
        if (movedThisSplit || pair.equals(saidAlready)) return false;
        saidAlready = pair;
        return true;
    }

    /** The top or left half's share before Offer Filter first moved the divider in this split, for the restore. */
    int firstBefore() {
        return firstBefore;
    }

    /** The divider is back where it was (or already there): nothing to restore. */
    void restored() {
        firstBefore = -1;
        placed = -1;
    }

    /**
     * Offer Filter moved the divider: counted, checked {@link #VERIFY_MS} from now.
     *
     * @param firstShareBefore the top or left half's share before the move
     * @param firstWanted the top or left half's share the action gives
     */
    void moved(Want kind, int firstShareBefore, int firstWanted, long now) {
        dashMoves++;
        movedThisSplit = true;
        verify = new Verify(kind == Want.RESTORE, firstWanted, now + VERIFY_MS);
        if (kind == Want.SHRINK && firstBefore < 0) firstBefore = firstShareBefore;
        placed = -1;
        lastShare = -1;
    }

    /** No more moves this dash; the log line. */
    String stop(String why) {
        stoppedFor = why;
        verify = null;
        firstBefore = -1;
        placed = -1;
        return why + ": left alone for the rest of this dash";
    }

    /** Why nothing moves again this dash, or null. */
    String stoppedFor() {
        return stoppedFor;
    }
}
