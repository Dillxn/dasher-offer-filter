package com.local.dasherfilter;

import android.content.Context;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * The homepage's note that an offer you accepted set your minimums (0.6.62; the owner, 20 November 2026: accepted
 * offers below the minimums lower them, "but offer undo in app"): "Your accept set your minimums" over what they are
 * now and were, then Undo and OK. Undo puts back the minimums from before it ({@link FilterStore#undoAdoption}); OK
 * closes the note, and Undo stays in Learned minimums. It goes by itself once the minimums change another way. Quiet,
 * as the growth note is: no live region; part of the page, never over Dasher.
 */
final class AcceptNoteCard {
    static final String TITLE = "Your accept set your minimums";
    static final String UNDO = "Undo";
    static final String OK = "OK";

    private final Context context;
    private final OnboardingCard card;
    private long shownId;

    /** @param changed what the page does after Undo put the minimums back */
    AcceptNoteCard(Context context, Ui ui, LinearLayout parent, Runnable changed) {
        this.context = context;
        card = new OnboardingCard(context, ui, parent);
        card.addAction(ui, UNDO, () -> {
            long id = shownId;
            if (id != 0 && FilterStore.undoAdoption(context, id)) changed.run();
            FilterStore.closeAcceptNote(context);
            refresh();
        });
        card.addAction(ui, OK, () -> {
            FilterStore.closeAcceptNote(context);
            refresh();
        });
    }

    void refresh() {
        FilterStore.Adoption change = FilterStore.acceptNote(context);
        if (change == null) {
            shownId = 0;
            card.show(false);
            return;
        }
        shownId = change.id;
        card.setWords(OnboardingCard.titled(TITLE, line(change)));
        card.setSaid(said(change));
        card.show(true);
    }

    /** "$2.70/stop · $1.68/mi · $22.80/hr · was $4.13/stop · $1.65/mi · $22.50/hr": labels, not a sentence. */
    static String line(FilterStore.Adoption c) {
        List<String> now = new ArrayList<>();
        List<String> was = new ArrayList<>();
        if (c.flatAfter != c.flatBefore) {
            now.add(DecisionLog.money(c.flatAfter) + "/stop");
            was.add(c.flatBefore > 0 ? DecisionLog.money(c.flatBefore) + "/stop" : "no pay per stop");
        }
        if (c.mileAfter != c.mileBefore) {
            now.add(DecisionLog.money(c.mileAfter) + "/mi");
            was.add(c.mileBefore > 0 ? DecisionLog.money(c.mileBefore) + "/mi" : "no $/mi");
        }
        if (c.minuteAfter != c.minuteBefore) {
            now.add(DecisionLog.shortMoney(c.minuteAfter * 60L) + "/hr");
            was.add(c.minuteBefore > 0 ? DecisionLog.shortMoney(c.minuteBefore * 60L) + "/hr" : "no $/hr");
        }
        return String.join(" · ", now) + " · was " + String.join(" · ", was);
    }

    static String said(FilterStore.Adoption c) {
        return TITLE + ": " + line(c).replace(" · was ", ", before: ").replace(" · ", ", ")
                + ". Undo puts back your minimums from before.";
    }
}
