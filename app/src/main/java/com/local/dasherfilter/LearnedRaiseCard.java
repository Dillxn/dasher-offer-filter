package com.local.dasherfilter;

import android.content.Context;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * The homepage's one-time note that learning raised the learned minimums (0.6.1; the owner, 11 October 2026: "new
 * highs ... raise mins"): "Learned minimums rose" over what they now ask above the user's own, then Adopt and OK. A new
 * high the user accepted made a learned minimum ask more than the saved one ({@link FilterStore.LearnedRaise}). The
 * saved minimums never move by themselves (the owner, 12 October 2026: "this shouldn't happen ... we have the adopt
 * mins feature for that"): Adopt makes the learned minimums the user's own, as Learned minimums' Adopt does, with its
 * Undo there; OK closes the note. It goes by itself once the learned bests change, learning is turned off or no learned
 * minimum asks more than its saved one. Quiet, as the growth note is: no live region and nothing announced; part of
 * the page, never over Dasher, among the lines that never fade.
 */
final class LearnedRaiseCard {
    static final String TITLE = "Learned minimums rose";
    static final String ADOPT = "Adopt";
    static final String OK = "OK";

    private final Context context;
    private final OnboardingCard card;
    /** The raise shown, by its lesson's id; 0 while none is. */
    private long shownId;

    /** @param adopted what the page does after Adopt made the learned minimums the user's own (follow them) */
    LearnedRaiseCard(Context context, Ui ui, LinearLayout parent, Runnable adopted) {
        this.context = context;
        card = new OnboardingCard(context, ui, parent);
        card.addAction(ui, ADOPT, () -> {
            long id = shownId;
            if (id != 0) {
                FilterStore.adoptAccepted(context);
                FilterStore.closeLearnedRaise(context, id);
                adopted.run();
            }
            refresh();
        });
        card.addAction(ui, OK, () -> {
            if (shownId != 0) FilterStore.closeLearnedRaise(context, shownId);
            refresh();
        });
    }

    /** Shown while a learned raise's note waits and it still asks more than the saved minimums; hidden otherwise. */
    void refresh() {
        FilterStore.LearnedRaise raise = FilterStore.learnedRaise(context);
        if (raise == null) {
            shownId = 0;
            card.show(false);
            return;
        }
        shownId = raise.id;
        card.setWords(OnboardingCard.titled(TITLE, line(raise)));
        card.setSaid(said(raise));
        card.show(true);
    }

    boolean shown() {
        return card.shown();
    }

    /** "$14.21 · $2.37/mi · $36/hr · yours $7.00 · $1.50/mi · $18/hr": labels, not a sentence. */
    static String line(FilterStore.LearnedRaise raise) {
        List<String> learned = new ArrayList<>();
        List<String> yours = new ArrayList<>();
        if (raise.flat > 0) {
            learned.add(DecisionLog.money(raise.flat));
            yours.add(raise.savedFlat > 0 ? DecisionLog.money(raise.savedFlat) : "no minimum pay");
        }
        if (raise.mile > 0) {
            learned.add(DecisionLog.money(raise.mile) + "/mi");
            yours.add(raise.savedMile > 0 ? DecisionLog.money(raise.savedMile) + "/mi" : "no $/mi");
        }
        if (raise.minute > 0) {
            learned.add(DecisionLog.shortMoney(raise.minute * 60L) + "/hr");
            yours.add(raise.savedMinute > 0 ? DecisionLog.shortMoney(raise.savedMinute * 60L) + "/hr" : "no $/hr");
        }
        return String.join(" · ", learned) + " · yours " + String.join(" · ", yours);
    }

    /** The note in words, for screen readers. */
    static String said(FilterStore.LearnedRaise raise) {
        List<String> parts = new ArrayList<>();
        if (raise.flat > 0) {
            parts.add("minimum pay " + DecisionLog.money(raise.flat) + " (yours "
                    + (raise.savedFlat > 0 ? DecisionLog.money(raise.savedFlat) : "not set") + ")");
        }
        if (raise.mile > 0) {
            parts.add("per mile " + DecisionLog.money(raise.mile) + " (yours "
                    + (raise.savedMile > 0 ? DecisionLog.money(raise.savedMile) : "not set") + ")");
        }
        if (raise.minute > 0) {
            parts.add("per hour " + DecisionLog.shortMoney(raise.minute * 60L) + " (yours "
                    + (raise.savedMinute > 0 ? DecisionLog.shortMoney(raise.savedMinute * 60L) : "not set") + ")");
        }
        return TITLE + ". A new high you accepted raised them: " + String.join(", ", parts)
                + ". Your own minimums stay as you set them; Adopt makes these yours.";
    }
}
