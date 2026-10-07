package com.local.dasherfilter;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * A one-time note on the homepage's sky, as a small card: a few words (a bold lead, or a title over short lines) and
 * its actions as links at its foot (OK closes it for good). Shown only until dismissed; used by the What is new card,
 * Peek's introduction and the note that the minimums grew. Also builds the notice's "What changed" box.
 */
final class OnboardingCard {
    final LinearLayout card;
    private final TextView words;
    private final LinearLayout actions;

    OnboardingCard(Context context, Ui ui, LinearLayout parent) {
        card = box(ui);
        words = ui.text("", 14, ui.ink, false);
        card.addView(words, Ui.matchWidth());
        actions = ui.row();
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        card.addView(actions, Ui.matchWidth());
        card.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = Ui.matchWidth();
        params.topMargin = ui.dp(8);
        parent.addView(card, params);
    }

    /** The card's background and padding, for the notice's box as well. */
    static LinearLayout box(Ui ui) {
        LinearLayout box = ui.column();
        box.setBackground(ui.rounded(ui.surface, ui.border, 16));
        box.setPadding(ui.dp(14), ui.dp(12), ui.dp(6), ui.dp(2));
        return box;
    }

    /** {@code lead} in bold, then {@code rest} on the same line. */
    static CharSequence lead(String lead, String rest) {
        SpannableStringBuilder text = new SpannableStringBuilder(lead);
        text.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text.append(' ').append(rest);
    }

    /** {@code title} in bold over the lines, each a bullet. */
    static CharSequence titled(String title, List<String> lines) {
        SpannableStringBuilder text = new SpannableStringBuilder(title);
        text.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        for (String line : lines) text.append("\n• ").append(line);
        return text;
    }

    /** {@code title} in bold over one line of its own. */
    static CharSequence titled(String title, String line) {
        SpannableStringBuilder text = new SpannableStringBuilder(title);
        text.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text.append('\n').append(line);
    }

    void setWords(CharSequence text) {
        if (!android.text.TextUtils.equals(text, words.getText())) words.setText(text);
    }

    /** What a screen reader hears for the words, in place of their symbols; null for the words themselves. */
    void setSaid(String said) {
        if (!android.text.TextUtils.equals(said, words.getContentDescription())) words.setContentDescription(said);
    }

    /** A link at the card's foot; its tap runs {@code action}. */
    Button addAction(Ui ui, String label, Runnable action) {
        Button link = ui.link(label, action);
        actions.addView(link);
        return link;
    }

    void show(boolean shown) {
        int visibility = shown ? View.VISIBLE : View.GONE;
        if (card.getVisibility() != visibility) card.setVisibility(visibility);
    }

    boolean shown() {
        return card.getVisibility() == View.VISIBLE;
    }

    CharSequence words() {
        return words.getText();
    }
}
