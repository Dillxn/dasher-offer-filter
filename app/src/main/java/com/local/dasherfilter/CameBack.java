package com.local.dasherfilter;

import android.content.Context;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * "Came back?" (0.6.9). When a standalone offer comes from the store of an offer declined in the last 30 minutes,
 * both tickets say so: the declined one "Same store: 9 min later, $10.80 (+$2.10)", the later one "Same store:
 * declined 9 min earlier at $8.70". It never claims it is the same order, and it decides nothing.
 *
 * <p>The store's name ({@link StoreName}) is kept in memory only, for {@link #WINDOW_MS}, and never written: the
 * history lines keep only the minutes and pay, as {@link DecisionLog.StepKind#SAME_STORE_LATER} and
 * {@link DecisionLog.StepKind#SAME_STORE_EARLIER} marks. Single-store offers only; an add-on, an offer with unread pay
 * or an unread store is left out.
 */
final class CameBack {
    private CameBack() {}

    static final long WINDOW_MS = 30 * 60_000L;

    /** A declined offer, by store, in memory only. */
    private static final class Declined {
        final String store;
        final OfferSnapshot facts;
        final long at;

        Declined(String store, OfferSnapshot facts, long at) {
            this.store = store;
            this.facts = facts;
            this.at = at;
        }
    }

    /** A later offer from a declined offer's store. */
    static final class Match {
        final OfferSnapshot earlier;
        final OfferSnapshot later;
        final int minutes;

        Match(OfferSnapshot earlier, OfferSnapshot later, int minutes) {
            this.earlier = earlier;
            this.later = later;
            this.minutes = minutes;
        }

        /** On the declined offer's ticket: "9 min later, $10.80 (+$2.10)". */
        String laterDetail() {
            long diff = (long) later.payCents - earlier.payCents;
            return minutes + " min later, " + DecisionLog.money(later.payCents) + " ("
                    + (diff == 0 ? "same pay" : (diff > 0 ? "+" : "-") + DecisionLog.money(Math.abs(diff))) + ")";
        }

        /** On the later offer's ticket: "declined 9 min earlier at $8.70". */
        String earlierDetail() {
            return "declined " + minutes + " min earlier at " + DecisionLog.money(earlier.payCents);
        }
    }

    private static final List<Declined> DECLINED = new ArrayList<>();
    /** Offers already noted (fingerprint and when), so a later reading of the same offer is not a new one. */
    private static final List<Declined> SEEN = new ArrayList<>();

    /**
     * Notes an offer read on screen, the first time it is seen: the match with a declined offer from its store, if
     * any, and the offer itself as the store's latest declined one when the rules declined it.
     */
    static synchronized Match note(String store, boolean singleStore, boolean addOn, boolean declined,
                                   OfferSnapshot facts, long now) {
        forgetOlderThan(now - WINDOW_MS);
        if (store == null || store.isEmpty() || !singleStore || addOn || facts == null || facts.payCents == null) {
            return null;
        }
        String key = StoreName.key(store);
        String print = facts.fingerprint();
        for (Declined seen : SEEN) {
            if (seen.store.equals(key) && seen.facts.fingerprint().equals(print)) return null;
        }
        SEEN.add(new Declined(key, facts, now));
        Match match = null;
        for (int i = DECLINED.size() - 1; i >= 0; i--) {
            Declined earlier = DECLINED.get(i);
            if (!earlier.store.equals(key)) continue;
            match = new Match(earlier.facts, facts, (int) Math.max(1, Math.round((now - earlier.at) / 60_000.0)));
            break;
        }
        if (declined) {
            for (Iterator<Declined> it = DECLINED.iterator(); it.hasNext(); ) {
                if (it.next().store.equals(key)) it.remove();
            }
            DECLINED.add(new Declined(key, facts, now));
        }
        return match;
    }

    /** As {@link #note}, marking both offers' history lines (main thread, after the later offer was recorded). */
    static void note(Context context, DecisionLog.Entry entry, List<String> labels) {
        Match match = note(StoreName.read(labels), StoreName.singleStore(labels), entry.addOn,
                entry.result == OfferRule.Result.DECLINE, entry.facts, System.currentTimeMillis());
        if (match == null) return;
        DecisionLog.markStep(context, match.earlier, DecisionLog.StepKind.SAME_STORE_LATER, match.laterDetail(),
                WINDOW_MS + 60_000L);
        DecisionLog.markStep(context, match.later, DecisionLog.StepKind.SAME_STORE_EARLIER, match.earlierDetail(),
                60_000L);
    }

    private static void forgetOlderThan(long time) {
        DECLINED.removeIf(d -> d.at < time);
        SEEN.removeIf(d -> d.at < time);
    }

    /** Forgets everything (Clear history, and tests). */
    static synchronized void clear() {
        DECLINED.clear();
        SEEN.clear();
    }
}
