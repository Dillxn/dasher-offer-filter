package com.local.dasherfilter;

import android.content.Context;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One quiet line on the homepage, the whole of it a 48 dp button: a small mark (a setup step's number, a warning sign
 * for something that stopped, or an arrow for an update), a few words, and the word for what a tap does ("Fix",
 * "Install now"). Screen readers hear "words. action." Hidden until shown; words are set only when they change. At
 * the largest font sizes on a narrow phone the words keep to two lines ({@link Words}).
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

    /** The words' size at Android's default font size, as every homepage line. */
    static final float WORDS_SP = 15;
    /** The least the words shrink to keep to two lines: three quarters of the user's size. */
    static final float LEAST_SCALE = 0.75f;

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
        text = new Words(context);
        text.setTextColor(ui.ink);
        text.setLineSpacing(0, 1.15f);
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

    /** The words' view, for tests. */
    TextView wordsView() {
        return text;
    }

    /**
     * A step's words at the user's text size, unless they would take more than two lines: a large font on a narrow
     * phone ("Allow / notification / access"). Then a little smaller, by the least that keeps them to two lines (or,
     * for longer words, to as few as it can), never below {@link #LEAST_SCALE} of the user's size nor below the
     * default size, so a line to fix still leaves the homepage's sky and map in one screen.
     */
    static final class Words extends TextView {
        private final float full;
        private final float least;
        private final TextPaint measuring = new TextPaint();
        private String fittedText;
        private int fittedWidth = -1;
        private float fittedSize;

        Words(Context context) {
            super(context);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, WORDS_SP);
            full = getTextSize();
            float unscaled = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, WORDS_SP,
                    context.getResources().getDisplayMetrics());
            least = Math.min(full, Math.max(full * LEAST_SCALE, unscaled));
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec) - getCompoundPaddingLeft() - getCompoundPaddingRight();
            if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && width > 0) {
                float size = fitting(width);
                if (size != getTextSize()) super.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        private float fitting(int width) {
            CharSequence words = getText();
            if (width == fittedWidth && fittedText != null && fittedText.contentEquals(words)) return fittedSize;
            // Largest first: the first size with fewer lines is the largest with that many.
            float size = full;
            int fewest = lines(words, full, width);
            float step = Math.max(1f, full / 40f);
            for (float smaller = full - step; fewest > 2 && smaller >= least; smaller -= step) {
                int count = lines(words, smaller, width);
                if (count < fewest) {
                    fewest = count;
                    size = smaller;
                }
            }
            fittedText = words.toString();
            fittedWidth = width;
            fittedSize = size;
            return size;
        }

        private int lines(CharSequence words, float size, int width) {
            measuring.set(getPaint());
            measuring.setTextSize(size);
            return StaticLayout.Builder.obtain(words, 0, words.length(), measuring, width)
                    .setBreakStrategy(getBreakStrategy())
                    .setHyphenationFrequency(getHyphenationFrequency())
                    .setIncludePad(getIncludeFontPadding())
                    .build()
                    .getLineCount();
        }
    }
}
