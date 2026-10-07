package com.local.dasherfilter;

import android.content.Context;
import android.widget.LinearLayout;
import java.util.function.LongConsumer;

/**
 * The homepage's one-time note that the minimums grew ({@link Growth}, 0.5.1), a card in the sky like the other
 * one-time cards ({@link OnboardingCard}): "Your minimums grew 8%" over one line of what grew and why, then Undo and OK.
 * It stays until a button is tapped and goes, with its Undo, once the minimums change any other way or Clear history
 * runs ({@link AutopilotRuntime#growthNote}). Quiet: no live region and nothing announced (the change was automatic);
 * screen readers hear its words without symbols. It is part of the page, never over Dasher, so it never covers an
 * offer, and the driving strip, which shows none of the sky, shows none of it (it waits for the page).
 */
final class GrowthCard {
    private final Context context;
    private final OnboardingCard card;
    /** The growth shown, by when it was; 0 while none is. */
    private long shownAt;

    /**
     * @param undo the page's Undo of the growth made at the time it is given: puts the minimums back
     *     ({@link AutopilotRuntime#undoGrowth}) and follows them
     */
    GrowthCard(Context context, Ui ui, LinearLayout parent, LongConsumer undo) {
        this.context = context;
        card = new OnboardingCard(context, ui, parent);
        card.addAction(ui, AutopilotText.GROWTH_UNDO, () -> {
            if (shownAt != 0) undo.accept(shownAt);
            refresh();
        });
        card.addAction(ui, AutopilotText.GROWTH_OK, () -> {
            if (shownAt != 0) AutopilotRuntime.growthNoted(context, shownAt);
            refresh();
        });
    }

    /** Shown while the last growth's note waits and still applies; hidden otherwise. */
    void refresh() {
        AutopilotStore.Grew grew = AutopilotRuntime.growthNote(context);
        if (grew == null) {
            shownAt = 0;
            card.show(false);
            return;
        }
        if (grew.at != shownAt) {
            shownAt = grew.at;
            card.setWords(OnboardingCard.titled(AutopilotText.growthTitle(grew.grown), AutopilotText.growthLine(grew)));
            card.setSaid(AutopilotText.growthSaid(grew));
        }
        card.show(true);
    }

    boolean shown() {
        return card.shown();
    }
}
