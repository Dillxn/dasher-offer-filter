package com.local.dasherfilter;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One quiet line on the homepage, the whole of it a 48 dp button: a small mark (a setup step's number, a warning sign
 * for something that stopped, or an arrow for an update), a few words, and the word for what a tap does ("Fix",
 * "Install now"). Screen readers hear "words. action." Hidden until shown; words are set only when they change.
 */
final class SetupRow {
    enum Mark {
        /** A setup step still to do: its number in a blue ring. */
        STEP,
        /** Something set up that stopped working: the red warning sign. */
        PROBLEM,
        /** An update waiting: an arrow in a blue ring. */
        UPDATE
    }

    final LinearLayout row;
    private final TextView badge;
    private final View sign;
    private final TextView text;
    private final TextView action;
    private String shown = "";

    SetupRow(Context context, Ui ui, LinearLayout parent, Runnable onTap) {
        row = ui.row();
        row.setBackground(ui.pressable(16));
        row.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6));
        row.setMinimumHeight(ui.dp(48));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(tapped -> onTap.run());
        badge = ui.text("", 12, ui.onAccent, true);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        badge.setBackground(ui.rounded(ui.accent, 0, 11));
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(badge, new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
        sign = new View(context);
        sign.setBackground(new Glyph(Glyph.Shape.SIGN, Ui.CRITICAL, ui.dp(20)));
        sign.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(sign, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        text = ui.text("", 15, ui.ink, false);
        // A few words that may wrap at large font sizes: compact lines keep the sky's other words in one screen.
        text.setLineSpacing(0, 1f);
        text.setPadding(ui.dp(10), 0, ui.dp(8), 0);
        row.addView(text, Ui.weighted());
        // Link ink, not the accent: it keeps its contrast on both skies (BETA-21).
        action = ui.text("", 15, ui.link, true);
        row.addView(action);
        row.setVisibility(View.GONE);
        parent.addView(row, Ui.matchWidth());
    }

    /** Shows the line: {@code number} is the step's number for {@link Mark#STEP}, unused otherwise. */
    void show(Mark mark, int number, String words, String actionWord) {
        String key = mark + "/" + number + "/" + words + "/" + actionWord;
        if (!key.equals(shown)) {
            shown = key;
            badge.setVisibility(mark == Mark.PROBLEM ? View.GONE : View.VISIBLE);
            badge.setText(mark == Mark.UPDATE ? "↑" : String.valueOf(number));
            sign.setVisibility(mark == Mark.PROBLEM ? View.VISIBLE : View.GONE);
            text.setText(words);
            action.setText(actionWord);
            row.setContentDescription(words + ". " + actionWord + ".");
        }
        if (row.getVisibility() != View.VISIBLE) row.setVisibility(View.VISIBLE);
    }

    void hide() {
        if (row.getVisibility() != View.GONE) row.setVisibility(View.GONE);
    }

    boolean shown() {
        return row.getVisibility() == View.VISIBLE;
    }

    /** The words shown, for tests and the log. */
    String words() {
        return text.getText().toString();
    }
}
