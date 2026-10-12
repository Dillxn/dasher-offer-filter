package com.local.dasherfilter;

import android.content.Context;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * The homepage's one-time note that learning raised the minimums (0.6.1; the owner, 11 October 2026: "new highs ...
 * raise mins"): "Your minimums rose" over the minimums that rose, then Undo and OK. A new high the user accepted
 * raised a minimum in use, so the saved minimums were raised to match ({@link FilterStore#adoptLearned}). Undo puts
 * the minimums back and forgets that lesson; OK closes the note, and Undo stays in Learned minimums (the learner
 * button's long press). It goes by itself once the minimums change any other way. Quiet, as the growth note is: no
 * live region and nothing announced; part of the page, never over Dasher, among the lines that never fade.
 */
final class LearnedRaiseCard {
    static final String TITLE = "Your minimums rose";
    static final String UNDO = "Undo";
    static final String OK = "OK";

    private final Context context;
    private final OnboardingCard card;
    /** The raise shown, by its adoption id; 0 while none is. */
    private long shownId;

    /** @param changed what the page does after Undo or OK changed the rules (follow them) */
    LearnedRaiseCard(Context context, Ui ui, LinearLayout parent, Runnable changed) {
        this.context = context;
        card = new OnboardingCard(context, ui, parent);
        card.addAction(ui, UNDO, () -> {
            if (shownId != 0) FilterStore.undoAdoption(context, shownId);
            changed.run();
            refresh();
        });
        card.addAction(ui, OK, () -> {
            if (shownId != 0) FilterStore.noteAdoption(context, shownId);
            refresh();
        });
    }

    /** Shown while an automatic raise's note waits and the minimums are still the raised ones; hidden otherwise. */
    void refresh() {
        FilterStore.Adoption raise = FilterStore.learnedRaiseNote(context);
        if (raise == null) {
            shownId = 0;
            card.show(false);
            return;
        }
        if (raise.id != shownId) {
            shownId = raise.id;
            card.setWords(OnboardingCard.titled(TITLE, line(raise)));
            card.setSaid(said(raise));
        }
        card.show(true);
    }

    boolean shown() {
        return card.shown();
    }

    /** "A new high you accepted raised them: $1.70/mi → $2.77/mi." */
    static String line(FilterStore.Adoption raise) {
        List<String> parts = new ArrayList<>();
        if (raise.flatAfter != raise.flatBefore) {
            parts.add(DecisionLog.money(raise.flatBefore) + " → " + DecisionLog.money(raise.flatAfter));
        }
        if (raise.mileAfter != raise.mileBefore) {
            parts.add(DecisionLog.money(raise.mileBefore) + "/mi → " + DecisionLog.money(raise.mileAfter) + "/mi");
        }
        if (raise.minuteAfter != raise.minuteBefore) {
            parts.add(DecisionLog.shortMoney(raise.minuteBefore * 60L) + "/hr → "
                    + DecisionLog.shortMoney(raise.minuteAfter * 60L) + "/hr");
        }
        return "A new high you accepted raised them: " + String.join(" · ", parts) + ".";
    }

    /** The note in words, for screen readers: "minimum pay $15.50 to $16.00, per mile $1.70 to $2.77". */
    static String said(FilterStore.Adoption raise) {
        List<String> parts = new ArrayList<>();
        if (raise.flatAfter != raise.flatBefore) {
            parts.add("minimum pay " + DecisionLog.money(raise.flatBefore) + " to "
                    + DecisionLog.money(raise.flatAfter));
        }
        if (raise.mileAfter != raise.mileBefore) {
            parts.add("per mile " + DecisionLog.money(raise.mileBefore) + " to " + DecisionLog.money(raise.mileAfter));
        }
        if (raise.minuteAfter != raise.minuteBefore) {
            parts.add("per hour " + DecisionLog.shortMoney(raise.minuteBefore * 60L) + " to "
                    + DecisionLog.shortMoney(raise.minuteAfter * 60L));
        }
        return TITLE + ". A new high you accepted raised them: " + String.join(", ", parts) + ".";
    }
}
