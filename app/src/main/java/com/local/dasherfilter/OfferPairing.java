package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of Dasher's offer notifications is the same offer as a screen reading of it, so one offer is one history
 * line. Dasher does not queue offers: the notification nearest a screen offer, with no other readable screen offer
 * between them, is that offer. A notification the app only announced (never a known failure it acted on, and never
 * one left to the user) folds into either the first readable screen offer read after it, within what that offer's
 * countdown allows, or (while Dasher is on screen) the screen offer read moments before it. Nothing is decided here:
 * the screen's own reading keeps its decision, and the notification keeps its own.
 */
final class OfferPairing {
    /** No offer outlives this: Dasher's countdown starts at 0:47. */
    static final long OFFER_MS = 60_000;
    /** Without a countdown on screen; a notification was seen to come at most 18 s before its screen reading. */
    static final long UNTIMED_MS = 30_000;
    /** With Dasher on screen, its notification came at most 10 s after the screen read the offer. */
    static final long NOTICE_LAG_MS = 12_000;
    static final long CLOCK_SLACK_MS = 2_000;

    /** A screen reading with at least one figure read: a frame with nothing read is neither folded nor in the way. */
    static boolean readable(DecisionLog.Entry e) {
        return e.source == DecisionLog.Source.SCREEN && (e.facts.payCents != null || e.facts.miles != null
                || e.facts.minutes != null || e.facts.stops != null);
    }

    static boolean foldable(DecisionLog.Entry e) {
        return e.source == DecisionLog.Source.NOTIFICATION && e.result != OfferRule.Result.DECLINE
                && e.action != DecisionLog.Action.USER_TOOK_OVER && e.notification == null;
    }

    /**
     * A notification without add-on words proves nothing about add-on-ness, so it may fold into an add-on; one that
     * says add-on never folds into a standalone offer. Facts that differ are different offers.
     */
    static boolean compatible(DecisionLog.Entry notice, DecisionLog.Entry screen) {
        return !(notice.addOn && !screen.addOn) && !notice.facts.contradicts(screen.facts);
    }

    /** How long before a screen reading its notification can have come, given the seconds its countdown shows. */
    static long window(int secondsLeft) {
        if (secondsLeft >= 0 && secondsLeft <= 60) {
            return Math.min(OFFER_MS, OFFER_MS - secondsLeft * 1000L + CLOCK_SLACK_MS);
        }
        return UNTIMED_MS;
    }

    /** The index of the notification a new screen offer folds, or -1. */
    static int notificationFor(List<DecisionLog.Entry> oldestFirst, DecisionLog.Entry screen, int secondsLeft) {
        if (!readable(screen)) return -1;
        long window = window(secondsLeft);
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            DecisionLog.Entry e = oldestFirst.get(i);
            if (readable(e) || e.at < screen.at - OFFER_MS) return -1;
            long before = screen.at - e.at;
            if (foldable(e) && before >= 0 && before <= window && compatible(e, screen)) return i;
        }
        return -1;
    }

    /**
     * Indices, oldest first, of the notifications recorded after the screen line at {@code line} that a new reading
     * of that same offer (merged into that line) shows to be its own: Dasher shows one offer at a time, so a
     * notification between two readings of one offer, within what the offer's countdown allows, is that offer's.
     * Typically Dasher left the screen after the first reading, its notification was announced as a possible next
     * offer, and the user came back to the same offer.
     */
    static List<Integer> notificationsSince(List<DecisionLog.Entry> oldestFirst, int line, DecisionLog.Entry reading,
                                            int secondsLeft) {
        List<Integer> out = new ArrayList<>();
        if (!readable(reading)) return out;
        long window = window(secondsLeft);
        for (int i = line + 1; i < oldestFirst.size(); i++) {
            DecisionLog.Entry e = oldestFirst.get(i);
            long before = reading.at - e.at;
            if (foldable(e) && before >= 0 && before <= window && compatible(e, reading)) out.add(i);
        }
        return out;
    }

    /** The index of the screen offer read moments before a notification of it, or -1. */
    static int screenFor(List<DecisionLog.Entry> oldestFirst, DecisionLog.Entry notice) {
        if (!foldable(notice)) return -1;
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            DecisionLog.Entry s = oldestFirst.get(i);
            if (!readable(s)) continue;
            long after = notice.at - s.at;
            boolean pairs = s.notification == null && after >= 0 && after <= NOTICE_LAG_MS && compatible(notice, s);
            return pairs ? i : -1;
        }
        return -1;
    }

    /**
     * Folds a history recorded before notifications were folded, in one pass, oldest first. A notification whose
     * card was posted without sound (Dasher was on screen) folds into the screen offer read just before it; a
     * readable screen offer takes the notification just before it. Replaces the list's contents.
     *
     * @return the notifications folded away
     */
    static List<DecisionLog.Entry> foldHistory(List<DecisionLog.Entry> oldestFirst) {
        List<DecisionLog.Entry> out = new ArrayList<>();
        List<DecisionLog.Entry> folded = new ArrayList<>();
        for (DecisionLog.Entry e : oldestFirst) {
            if (e.action == DecisionLog.Action.SILENT_CARD || e.action == DecisionLog.Action.QUIET_PASS_CARD) {
                int j = screenFor(out, e);
                if (j >= 0) {
                    out.set(j, out.get(j).withNotification(e));
                    folded.add(e);
                    continue;
                }
            }
            int k = notificationFor(out, e, -1);
            if (k >= 0) {
                DecisionLog.Entry notice = out.remove(k);
                out.add(e.withNotification(notice));
                folded.add(notice);
                continue;
            }
            out.add(e);
        }
        oldestFirst.clear();
        oldestFirst.addAll(out);
        return folded;
    }

    private OfferPairing() {}
}
