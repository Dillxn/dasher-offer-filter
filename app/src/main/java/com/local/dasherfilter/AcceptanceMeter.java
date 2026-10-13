package com.local.dasherfilter;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.content.Context;
import android.text.Layout;
import android.widget.TextView;

/**
 * The acceptance meter (0.6.6; 0.6.8 as the owner's mockup draws it; 0.6.11 on one line): the status words
 * "ACCEPTANCE 7% / 70%", then on their line a track across the rest of the chips' row, whose fill is the acceptance
 * rate now, amber below the goal and green at or above it, with a tick at the goal. With Autopilot off it shows the
 * rate alone, no goal and no tick, since nothing pursues one. It stands for the sentence "AR 7% → 70%: about 63 more
 * accepts", which stays in the chip's details and for screen readers (the owner, 12 October 2026: say each thing once).
 *
 * <p>Drawn as the status words' background, so it takes none of their room: the words are laid out as they would be
 * without it, never cut or wrapped for it. The track has what their one line leaves, {@link #GAP_DP} after their end,
 * at most {@link #MOST_DP}; with less than {@link #LEAST_DP} it fades, gone at half of it (a narrow window, a large
 * font), and where the words take more than one line, or are cut short, there is none. Until 0.6.10 the track ran under
 * the words, which read as a line break (the owner, 13 October 2026, on his split screen: "the acceptance rating bar
 * width is causing a line break in the layout").
 *
 * <p>The words themselves never wrap or are cut for it: {@link Words} shortens them instead (0.6.11), "ACCEPTANCE 8% /
 * 70%", then "AR 8% / 70%" (Dashers' own word, and this app's label in 0.6.3), each while it leaves the track at least
 * {@link #ROOM_DP}, then "AR 8%", the goal left to the track's tick. Screen readers keep the whole sentence.
 */
final class AcceptanceMeter extends Drawable {
    static final int HEIGHT_DP = 6;
    /** The room between the words' end and the track, and kept after the track at the row's end. */
    static final int GAP_DP = 8;
    static final int END_DP = 4;
    /** The track at its longest, and the least at which it is wholly there (shorter, it fades: none at half of it). */
    static final int MOST_DP = 200;
    static final int LEAST_DP = 32;
    /** The track a longer form of the words must leave, or the next, shorter one is shown. */
    static final int ROOM_DP = 48;

    private final Ui ui;
    /** The words the meter stands beside: the status line it is the background of. */
    private final TextView words;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF track = new RectF();
    private int ratePercent;
    private int goalPercent;
    private int fillColor;

    AcceptanceMeter(Ui ui, TextView words) {
        this.ui = ui;
        this.words = words;
    }

    /** Rate and goal, each 0 to 100 (goal 0: none, no tick); {@code color} the fill's. */
    void set(int ratePercent, int goalPercent, int color) {
        this.ratePercent = Math.max(0, Math.min(100, ratePercent));
        this.goalPercent = Math.max(0, Math.min(100, goalPercent));
        fillColor = color;
        invalidateSelf();
    }

    int rate() { return ratePercent; }
    int goal() { return goalPercent; }

    /**
     * Where the track stands in the words' view (whose background the meter is), into {@code out}: on the words' one
     * line, its middle with theirs (the chips' row stands the chips and the words in its middle, so the chip's too), from
     * {@link #GAP_DP} past their end as far as the row's end less {@link #END_DP}, at most {@link #MOST_DP}. Returns how
     * much of it is there: 1 wholly, 0 none ({@code out} then empty).
     */
    float track(RectF out) {
        out.setEmpty();
        Layout layout = words.getLayout();
        if (layout == null || layout.getLineCount() != 1 || layout.getEllipsisCount(0) > 0) return 0;
        float textLeft = words.getCompoundPaddingLeft();
        boolean ltr = layout.getParagraphDirection(0) == Layout.DIR_LEFT_TO_RIGHT;
        // The room the words' line leaves on the side it ends on.
        float from = ltr ? textLeft + layout.getLineRight(0) + ui.dp(GAP_DP) : textLeft + ui.dp(END_DP);
        float to = ltr ? words.getWidth() - words.getCompoundPaddingRight() - ui.dp(END_DP)
                : textLeft + layout.getLineLeft(0) - ui.dp(GAP_DP);
        float width = Math.min(ui.dp(MOST_DP), to - from);
        float fading = ui.dp(LEAST_DP) / 2f;
        float shown = Math.max(0, Math.min(1, (width - fading) / fading));
        if (shown <= 0) return 0;
        float middle = words.getTotalPaddingTop() + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f;
        float thick = ui.dp(HEIGHT_DP) / 2f;
        float left = ltr ? from : to - width;
        out.set(left, middle - thick, left + width, middle + thick);
        return shown;
    }

    @Override public void draw(Canvas canvas) {
        float shown = track(track);
        if (shown <= 0) return;
        Layout layout = words.getLayout();
        boolean ltr = layout == null || layout.getParagraphDirection(0) == Layout.DIR_LEFT_TO_RIGHT;
        float width = track.width();
        float half = track.height() / 2f;
        paint.setStyle(Paint.Style.FILL);
        ink(ui.gridline, shown);
        canvas.drawRoundRect(track, half, half, paint);
        if (ratePercent > 0) {
            ink(fillColor, shown);
            float fill = Math.max(2 * half, width * ratePercent / 100f);
            if (ltr) rect.set(track.left, track.top, track.left + fill, track.bottom);
            else rect.set(track.right - fill, track.top, track.right, track.bottom);
            canvas.drawRoundRect(rect, half, half, paint);
        }
        if (goalPercent <= 0) return;
        ink(ui.ink, shown);
        float x = ltr ? track.left + width * goalPercent / 100f : track.right - width * goalPercent / 100f;
        float tick = Math.max(1, ui.dp(2));
        rect.set(x - tick / 2, track.top - ui.dp(3), x + tick / 2, track.bottom + ui.dp(3));
        canvas.drawRect(rect, paint);
    }

    /** The paint in {@code color} at {@code shown} of that color's own opacity. */
    private void ink(int color, float shown) {
        paint.setColor(color);
        if (shown < 1) paint.setAlpha(Math.round(paint.getAlpha() * shown));
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    /**
     * The status words, which show the first of their forms that fits their one line: each but the last only while it
     * leaves the meter's track {@link #ROOM_DP} after it. The chips' row ({@link AutopilotChip.Row}) measures them in
     * each form in turn, as it sizes the chips beside them for those words, and keeps the first that fits; with no
     * forms they are plain words.
     */
    static final class Words extends TextView {
        private final Ui ui;
        private CharSequence[] forms;

        Words(Context context, Ui ui) {
            super(context);
            this.ui = ui;
        }

        /** The forms, longest first ({@code null}: none, the words as set). */
        void forms(CharSequence... forms) {
            if (this.forms == null && (forms == null || forms.length == 0)) return;
            this.forms = forms == null || forms.length == 0 ? null : forms;
            if (this.forms != null && !sameText(this.forms[0], getText())) setText(this.forms[0]);
            requestLayout();
        }

        /** How many forms the words have: 0 for plain words. */
        int formCount() {
            return forms == null ? 0 : forms.length;
        }

        /** The words in their form {@code i} (the row, as it measures them). */
        void showForm(int i) {
            if (sameText(forms[i], getText())) return;
            // As the row measures: no new layout pass asked for (the row's own measure is under way), and measured
            // again however it is asked, since new words of the same size are laid out at the width they had before.
            // NORMAL, as the page's own words: a static layout, which keeps to the row's lines.
            measuring = true;
            try {
                super.setText(forms[i], BufferType.NORMAL);
            } finally {
                measuring = false;
            }
            forceLayout();
        }

        /** Form {@code i}'s words. */
        String formText(int i) {
            return forms[i].toString();
        }

        /** The form shown: its index in the forms, or -1 with none. */
        int form() {
            if (forms == null) return -1;
            for (int i = 0; i < forms.length; i++) if (sameText(forms[i], getText())) return i;
            return -1;
        }

        /**
         * As just measured: the words on one line, none cut, and the room after them on it for the track at least
         * {@link #ROOM_DP}, with its gap before and room at the row's end.
         */
        boolean leavesTrack() {
            Layout layout = getLayout();
            if (layout == null || layout.getLineCount() != 1 || layout.getEllipsisCount(0) > 0) return false;
            float line = layout.getLineRight(0) - layout.getLineLeft(0);
            float room = getMeasuredWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight() - line;
            return room >= ui.dp(GAP_DP) + ui.dp(ROOM_DP) + ui.dp(END_DP);
        }

        private boolean measuring;

        @Override public void requestLayout() {
            if (measuring) forceLayout();
            else super.requestLayout();
        }

        @Override public void setText(CharSequence text, BufferType type) {
            // Words set from outside, none of the forms: plain words from now on.
            if (forms != null && !isForm(text)) forms = null;
            super.setText(text, type);
        }

        private boolean isForm(CharSequence text) {
            for (CharSequence form : forms) if (sameText(form, text)) return true;
            return false;
        }

        private static boolean sameText(CharSequence a, CharSequence b) {
            return a != null && b != null && a.toString().contentEquals(b);
        }
    }
}
