package com.local.dasherfilter;

import android.content.Context;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * The homepage's note that an offer you accepted set your minimums (0.6.62; the owner, 20 November 2026: accepted
 * offers below the minimums lower them, "but offer undo in app"): "Your accept set your minimums" over what they are
 * now and were, then Undo and OK. Undo puts back the minimums from before it ({@link FilterStore#undoAdoption}); OK
 * closes the note, and Undo stays in How offers are judged (0.6.68; Learned minimums before). It goes by itself once the minimums change another way. Quiet,
 * as the growth note is: no live region; part of the page, never over Dasher.
 *
 * <p>An accept under your minimums sets the bar your accepts set instead (0.6.64). Autopilot's bar is the lower of that
 * and your goal's limit, so from 0.6.67 the note says where the accept set it, which way or not ("Your accept set the
 * bar to 81%"; 0.6.64 to 0.6.66 said "Your accept lowered the bar" for a rise too), and, while the goal holds the bar
 * deciding offers lower, that it does ("your Platinum goal holds it at 37% for now"; the owner, 22 November 2026, had
 * "bar 81% · was bar 35%" beside a chip still at 35%).
 */
final class AcceptNoteCard {
    static final String TITLE = "Your accept set your minimums";
    static final String UNDO = "Undo";
    static final String OK = "OK";

    private final Context context;
    private final OnboardingCard card;
    private long shownId;
    /** Autopilot as the page last showed it, for the note's own refresh after a button. */
    private AutopilotText.Status status;

    /** @param changed what the page does after Undo put the minimums (or the bar) back */
    AcceptNoteCard(Context context, Ui ui, LinearLayout parent, Runnable changed) {
        this.context = context;
        card = new OnboardingCard(context, ui, parent);
        card.addAction(ui, UNDO, () -> {
            long id = shownId;
            if (id != 0 && FilterStore.undoAdoption(context, id)) changed.run();
            FilterStore.closeAcceptNote(context);
            refresh(status);
        });
        card.addAction(ui, OK, () -> {
            FilterStore.closeAcceptNote(context);
            refresh(status);
        });
    }

    /** @param status Autopilot as the page shows it now: what holds its bar, and where */
    void refresh(AutopilotText.Status status) {
        this.status = status;
        FilterStore.Adoption change = FilterStore.acceptNote(context);
        if (change == null) {
            shownId = 0;
            card.show(false);
            return;
        }
        shownId = change.id;
        String held = change.barAfter != change.barBefore ? AutopilotText.barHeldBy(status, change.barAfter) : null;
        int inUse = status == null ? FilterSettings.BAR_AT_MINIMUMS : status.bar;
        card.setWords(OnboardingCard.titled(title(change), line(change, held, inUse)));
        card.setSaid(said(change, held, inUse));
        card.show(true);
    }

    /** "Your accept set your minimums"; with the bar alone, where it set it: "Your accept set the bar to 81%". */
    static String title(FilterStore.Adoption c) {
        if (c.minimumsChanged()) return TITLE;
        return c.barAfter >= FilterSettings.BAR_AT_MINIMUMS ? "Your accept set the bar back to 100%"
                : "Your accept set the bar to " + c.barAfter + "%";
    }

    /**
     * Labels, not a sentence: "$2.70/stop · $1.68/mi · $22.80/hr · was $4.13/stop · $1.65/mi · $22.50/hr"; with the bar
     * alone "was 35%"; then, while {@code held} (what holds the bar deciding offers lower, or null) does,
     * "your Platinum goal holds it at 37% for now".
     */
    static String line(FilterStore.Adoption c, String held, int inUse) {
        String words;
        if (c.minimumsChanged()) {
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
            if (c.barAfter != c.barBefore) {
                now.add("bar " + c.barAfter + "%");
                was.add("bar " + c.barBefore + "%");
            }
            words = String.join(" · ", now) + " · was " + String.join(" · ", was);
        } else {
            words = "was " + c.barBefore + "%";
        }
        if (held == null) return words;
        return words + " · " + held + " holds " + (c.minimumsChanged() ? "the bar" : "it") + " at " + inUse
                + "% for now";
    }

    static String said(FilterStore.Adoption c, String held, int inUse) {
        String heard = c.minimumsChanged()
                ? TITLE + ": " + line(c, null, inUse).replace(" · was ", ", before: ").replace(" · ", ", ")
                : title(c) + ", from " + c.barBefore + "%";
        if (held != null) {
            heard += ". " + Character.toUpperCase(held.charAt(0)) + held.substring(1) + " holds the bar at " + inUse
                    + "% for now, so enough offers pass";
        }
        return heard + ". Undo puts back what it was before.";
    }
}
