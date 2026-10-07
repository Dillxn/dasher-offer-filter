package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

/**
 * Autopilot's one-line chip ({@link AutopilotText#chip}): "Auto off", "Auto learning", "Auto 82%", "Auto 82% ▲" (below
 * the goal, in amber), "Auto 50% lowest", "Auto needs a minimum" or "Auto waits". A pill in Autopilot's purple (amber
 * below the goal, grey while off) inside a target at least 48 dp each way. A tap opens the details and a long press the
 * goal chooser: the page that holds it says how ({@link #setOnClickListener}, {@link #setOnLongClickListener}).
 *
 * <p>Screen readers hear the whole status in words and what a tap does ({@link AutopilotText#chipDescription}), and
 * the long press as "Change acceptance goal". It is no live region and never announces itself, so an automatic bar
 * change never interrupts TalkBack while driving. The homepage shows it beside the latest offer's line ({@link Row}),
 * where no Autopilot button is on screen (a short window with the constellation up in the header), or where a whole
 * screen has no room for the status line; the header gains no row. A driving strip of a short split screen can host
 * the same view ({@code MainActivity.newAutopilotChip}).
 */
@SuppressLint("ViewConstructor")
final class AutopilotChip extends TextView {
    /** How the chip is drawn: grey while off, purple while on, amber while below the goal. */
    private enum Look { OFF, ON, BELOW_GOAL }

    /** The pill's words stand this far in from its rounded ends. */
    private static final int SIDE_DP = 14;
    /** The chip's words, at the normal font size (its default size). */
    static final float SP = 13;

    private final Ui ui;
    private Look look;

    AutopilotChip(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        setTextSize(TypedValue.COMPLEX_UNIT_SP, SP);
        setTypeface(Ui.MEDIUM);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setGravity(Gravity.CENTER);
        setMinHeight(ui.dp(48));
        setMinWidth(ui.dp(48));
        setClickable(true);
        setLongClickable(true);
        setFocusable(true);
        setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_NONE);
        // Until the page shows the status (AutopilotText.chip's words while off).
        show(Look.OFF, "Auto off", null);
    }

    /** Autopilot's purple (it used to mean "learned"): 5.5:1 on the day surface, 6.5:1 on the night one. */
    static int purple(Ui ui) {
        return ui.learned;
    }

    /** Below the goal: an amber that reads as text, 5.8:1 on the day surface and 9.5:1 on the night one. */
    static int amber(Ui ui) {
        return ui.dark ? Ui.WARNING : 0xFF8A5A00;
    }

    /** Shows {@code status}: its words, its color and what screen readers hear. Quiet: nothing is announced. */
    void show(AutopilotText.Status status) {
        if (status == null) return;
        Look next = !status.on ? Look.OFF : status.belowGoal() ? Look.BELOW_GOAL : Look.ON;
        show(next, AutopilotText.chip(status), AutopilotText.chipDescription(status));
    }

    private void show(Look next, String words, String said) {
        if (!words.contentEquals(getText())) setText(words);
        if (said != null && !said.contentEquals(String.valueOf(getContentDescription()))) setContentDescription(said);
        if (next == look) return;
        look = next;
        int ink = next == Look.OFF ? ui.inkSecondary : next == Look.BELOW_GOAL ? amber(ui) : purple(ui);
        int stroke = next == Look.OFF ? (ui.dark ? 0x40FFFFFF : 0x330B0B0B) : ink;
        setTextColor(ink);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(ui.surface);
        pill.setCornerRadius(ui.dp(18));
        pill.setStroke(next == Look.OFF ? Math.max(1, ui.dp(1)) : ui.dp(1.5f), stroke);
        int press = ui.dark ? 0x1FFFFFFF : 0x140B0B0B;
        // The pill is 36 dp tall inside the 48 dp target.
        setBackground(new RippleDrawable(ColorStateList.valueOf(press), new InsetDrawable(pill, 0, ui.dp(6), 0,
                ui.dp(6)), null));
        // A background with insets brings its own padding; the words keep theirs, clear of the pill's rounded ends.
        setPadding(ui.dp(SIDE_DP), 0, ui.dp(SIDE_DP), 0);
    }

    /** What a tap and a long press do, in words, for screen readers. */
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(android.widget.Button.class.getName());
        if (isClickable()) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                    AutopilotText.ACTION_DETAILS));
        }
        if (isLongClickable()) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,
                    AutopilotText.ACTION_CHANGE_GOAL));
        }
    }

    /**
     * The chip beside a line of words (the latest offer's), the chip at the start, costing the page no height: the row
     * keeps the height the words have alone across its whole width (their 0.4.x height). Where the words would wrap
     * onto more lines beside the chip (a large font, a narrow window), the chip's words and the line's shrink together
     * by the least that keeps the row that tall, never below three quarters of the user's size nor below the default
     * size (as a setup line's words do, {@link SetupRow.Words}); where even that is not enough (twice the font on the
     * narrowest pane) they stay at that least size, their lines closing up to single spacing (as the map's line does at
     * a large font) and their space above and below halved, and the row grows by the least it can. The chip goes on a
     * line of its own above the words only where that is shorter still (a window too narrow for both side by side). A
     * chip that is gone leaves the words the whole width, at their full size, spacing and padding.
     */
    @SuppressLint("ViewConstructor")
    static final class Row extends ViewGroup {
        /** Size steps tried between the full size and the least, as {@link SetupRow.Words} does. */
        private static final int STEPS = 40;

        private final TextView chip;
        private final TextView words;
        private final int gap;
        /** The two's text sizes as the page made them (the user's font size), and the least they shrink to. */
        private final float chipFull;
        private final float wordsFull;
        private final float least;
        /** The words' line spacing and space above and below as the page made them, closed up only at the least size. */
        private final float wordsSpacing;
        private final int padTop;
        private final int padBottom;
        private final TextPaint measuring = new TextPaint();
        /** How the row stands now: the chip above the words, the share of the full sizes, the lines closed up. */
        private boolean stacked;
        private float scale = 1f;
        private boolean tight;
        /** What the last fitting was worked out for, and its answer (kept while the chip is gone). */
        private int fittedWidth = -1;
        private String fittedChip;
        private String fittedWords;
        private boolean fitStacked;
        private float fitScale = 1f;
        private boolean fitTight;

        /** The chip beside words of the chip's own size, 13 sp (the homepage's latest offer's line). */
        Row(Context context, Ui ui, TextView chip, TextView words) {
            this(context, ui, chip, words, SP);
        }

        /**
         * The chip beside {@code words} whose own size is {@code wordsSp} (their size at the normal font: the driving
         * strip's status line is 14 sp). The two shrink together by one share, so it stops where either would go
         * below its own floor: three quarters of the user's size, and never below its default size.
         */
        Row(Context context, Ui ui, TextView chip, TextView words, float wordsSp) {
            super(context);
            this.chip = chip;
            this.words = words;
            gap = ui.dp(4);
            chipFull = chip.getTextSize();
            wordsFull = words.getTextSize();
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            float chipFloor = Math.max(SetupRow.LEAST_SCALE,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SP, metrics) / chipFull);
            float wordsFloor = Math.max(SetupRow.LEAST_SCALE,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, wordsSp, metrics) / wordsFull);
            least = Math.min(1f, Math.max(chipFloor, wordsFloor));
            wordsSpacing = words.getLineSpacingMultiplier();
            padTop = words.getPaddingTop();
            padBottom = words.getPaddingBottom();
            addView(chip);
            addView(words);
        }

        /** The chip stands above the words, not beside them (for tests). */
        boolean stacked() {
            return stacked;
        }

        /** The share of the user's text size the chip and the words are drawn at now (1: full; for tests). */
        float textScale() {
            return scale;
        }

        /** The words' lines are closed up: single spacing, half the space above and below (the last resort; for tests). */
        boolean tight() {
            return tight;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int inner = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            if (chip.getVisibility() == GONE) {
                stacked = false;
                scale = 1f;
                tight = false;
                size(words, wordsFull);
                spacing(words, wordsSpacing);
                padding(words, padTop, padBottom);
                words.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), any);
                setMeasuredDimension(width, words.getMeasuredHeight() + getPaddingTop() + getPaddingBottom());
                return;
            }
            fit(inner);
            stacked = fitStacked;
            scale = fitScale;
            tight = fitTight;
            size(chip, chipFull * scale);
            size(words, wordsFull * scale);
            spacing(words, tight ? 1f : wordsSpacing);
            padding(words, tight ? padTop / 2 : padTop, tight ? padBottom / 2 : padBottom);
            chip.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), any);
            int height;
            if (stacked) {
                words.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), any);
                height = chip.getMeasuredHeight() + words.getMeasuredHeight();
            } else {
                int beside = Math.max(0, inner - chip.getMeasuredWidth() - gap);
                words.measure(MeasureSpec.makeMeasureSpec(beside, MeasureSpec.EXACTLY), any);
                height = Math.max(chip.getMeasuredHeight(), words.getMeasuredHeight());
            }
            setMeasuredDimension(width, height + getPaddingTop() + getPaddingBottom());
        }

        /**
         * Works out, without touching either view, the largest size (the user's, else down to {@link #least}) at which
         * the chip and the words side by side are no taller than the words alone across {@code inner}; failing that,
         * the shorter of the two side by side at the least size with the words' lines closed up, and the chip above
         * the words at the full size.
         */
        private void fit(int inner) {
            String chipText = chip.getText().toString();
            String wordsText = words.getText().toString();
            if (inner == fittedWidth && chipText.equals(fittedChip) && wordsText.equals(fittedWords)) return;
            fittedWidth = inner;
            fittedChip = chipText;
            fittedWords = wordsText;
            int alone = wordsHeight(wordsText, wordsFull, inner, false);
            fitStacked = false;
            // The largest size that keeps the row to the words' own height, their lines as the page made them; else,
            // their lines closed up, the largest that does so then.
            for (boolean closed : new boolean[] {false, true}) {
                fitTight = closed;
                fitScale = largestFitting(chipText, wordsText, inner, closed, alone);
                if (fitScale > 0) return;
            }
            int beside = besideHeight(chipText, wordsText, least, inner, true);
            int above = chipHeight(chipFull) + alone;
            fitStacked = above < beside;
            fitTight = !fitStacked;
            fitScale = fitStacked ? 1f : least;
        }

        /**
         * The largest share of the full sizes, from 1 down to {@link #least}, at which the row side by side is no
         * taller than {@code alone}; 0 when none is.
         */
        private float largestFitting(String chipText, String wordsText, int inner, boolean closed, int alone) {
            float step = (1f - least) / STEPS;
            for (int i = 0; i <= STEPS; i++) {
                float s = i == STEPS ? least : 1f - i * step;
                if (besideHeight(chipText, wordsText, s, inner, closed) <= alone) return s;
                if (step <= 0) break;
            }
            return 0;
        }

        /**
         * The row's height with the chip and the words side by side at {@code s} of their full sizes, the words' lines
         * closed up ({@code closed}) or as the page made them.
         */
        private int besideHeight(String chipText, String wordsText, float s, int inner, boolean closed) {
            int room = inner - chipWidth(chipText, chipFull * s, inner) - gap;
            if (room <= 0) return Integer.MAX_VALUE;
            return Math.max(chipHeight(chipFull * s), wordsHeight(wordsText, wordsFull * s, room, closed));
        }

        /** The chip's width with its words at {@code px} (one line, as wide as the row allows). */
        private int chipWidth(String text, float px, int inner) {
            measuring.set(chip.getPaint());
            measuring.setTextSize(px);
            int wide = (int) Math.ceil(measuring.measureText(text)) + chip.getCompoundPaddingLeft()
                    + chip.getCompoundPaddingRight();
            return Math.min(inner, Math.max(chip.getMinWidth(), wide));
        }

        /** The chip's height with its words at {@code px}: its 48 dp target, or its one line if that is taller. */
        private int chipHeight(float px) {
            measuring.set(chip.getPaint());
            measuring.setTextSize(px);
            android.graphics.Paint.FontMetricsInt line = measuring.getFontMetricsInt();
            int tall = line.bottom - line.top + chip.getCompoundPaddingTop() + chip.getCompoundPaddingBottom();
            return Math.max(chip.getMinHeight(), tall);
        }

        /**
         * The words' height at {@code px} in {@code width}, as their view lays them out: their lines closed up
         * ({@code closed}: single spacing, half the space above and below) or as the page made them.
         */
        private int wordsHeight(String text, float px, int width, boolean closed) {
            int across = Math.max(1, width - words.getCompoundPaddingLeft() - words.getCompoundPaddingRight());
            measuring.set(words.getPaint());
            measuring.setTextSize(px);
            Layout layout = StaticLayout.Builder.obtain(text, 0, text.length(), measuring, across)
                    .setLineSpacing(words.getLineSpacingExtra(), closed ? 1f : wordsSpacing)
                    .setIncludePad(words.getIncludeFontPadding())
                    .setBreakStrategy(words.getBreakStrategy())
                    .setHyphenationFrequency(words.getHyphenationFrequency())
                    .build();
            int tall = layout.getHeight() + (closed ? padTop / 2 + padBottom / 2 : padTop + padBottom);
            return Math.max(words.getMinHeight(), tall);
        }

        /** {@code view}'s text at {@code px}, set only when it changes (no layout pass for nothing). */
        private static void size(TextView view, float px) {
            if (Math.abs(view.getTextSize() - px) > 0.01f) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        }

        /** {@code view}'s lines {@code multiplier} apart, set only when it changes. */
        private static void spacing(TextView view, float multiplier) {
            if (Math.abs(view.getLineSpacingMultiplier() - multiplier) > 0.001f) {
                view.setLineSpacing(view.getLineSpacingExtra(), multiplier);
            }
        }

        /** {@code view}'s space above and below, its sides as they are, set only when it changes. */
        private static void padding(TextView view, int top, int bottom) {
            if (view.getPaddingTop() != top || view.getPaddingBottom() != bottom) {
                view.setPadding(view.getPaddingLeft(), top, view.getPaddingRight(), bottom);
            }
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int x = getPaddingLeft();
            int y = getPaddingTop();
            if (chip.getVisibility() == GONE) {
                words.layout(x, y, x + words.getMeasuredWidth(), y + words.getMeasuredHeight());
                return;
            }
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            int width = right - left - getPaddingRight();
            int chipWidth = chip.getMeasuredWidth();
            if (stacked) {
                int chipLeft = rtl ? width - chipWidth : x;
                chip.layout(chipLeft, y, chipLeft + chipWidth, y + chip.getMeasuredHeight());
                int wordsTop = y + chip.getMeasuredHeight();
                words.layout(x, wordsTop, x + words.getMeasuredWidth(), wordsTop + words.getMeasuredHeight());
                return;
            }
            int height = bottom - top - getPaddingTop() - getPaddingBottom();
            int chipTop = y + (height - chip.getMeasuredHeight()) / 2;
            int wordsTop = y + (height - words.getMeasuredHeight()) / 2;
            if (rtl) {
                chip.layout(width - chipWidth, chipTop, width, chipTop + chip.getMeasuredHeight());
                words.layout(x, wordsTop, x + words.getMeasuredWidth(), wordsTop + words.getMeasuredHeight());
            } else {
                chip.layout(x, chipTop, x + chipWidth, chipTop + chip.getMeasuredHeight());
                int wordsLeft = x + chipWidth + gap;
                words.layout(wordsLeft, wordsTop, wordsLeft + words.getMeasuredWidth(),
                        wordsTop + words.getMeasuredHeight());
            }
        }
    }
}
