package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Layout;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

/**
 * Autopilot's one-line chip ({@link AutopilotText#chip}): "Auto off", "Auto learning", "Auto 82%", "Auto 82% ▲" (below
 * the goal, in amber), "Auto 50% lowest", "Auto needs a minimum" or "Auto waits". A pill in Autopilot's purple (amber
 * below the goal, grey while off) inside a target at least 48 dp each way. A tap opens the details and a long press the
 * goal chooser: the page that holds it says how ({@link #setOnClickListener}, {@link #setOnLongClickListener}).
 *
 * <p>Screen readers hear the whole status line and what a tap does ({@link AutopilotText#chipDescription}), and the
 * long press as "Change acceptance goal". It is no live region and never announces itself, so an automatic bar change
 * never interrupts TalkBack while driving. The compact homepage shows it under the mascot, beside the latest offer's
 * line ({@link Row}), so the header gains no row; a driving strip of a short split screen can host the same view
 * ({@code MainActivity.newAutopilotChip}).
 */
@SuppressLint("ViewConstructor")
final class AutopilotChip extends TextView {
    /** How the chip is drawn: grey while off, purple while on, amber while below the goal. */
    private enum Look { OFF, ON, BELOW_GOAL }

    /** The pill's words stand this far in from its rounded ends. */
    private static final int SIDE_DP = 14;

    private final Ui ui;
    private Look look;

    AutopilotChip(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
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
     * The chip beside a line of words (the latest offer's), the chip at the start: side by side while the words keep
     * to two lines beside it, else the chip on a line of its own above them (a large font, a narrow window), so
     * neither is squeezed. A chip that is gone leaves the words the whole width.
     */
    @SuppressLint("ViewConstructor")
    static final class Row extends ViewGroup {
        /** The most lines the words may take beside the chip before the chip goes above them. */
        static final int MOST_LINES_BESIDE = 2;

        private final View chip;
        private final TextView words;
        private final int gap;
        private boolean stacked;

        Row(Context context, Ui ui, View chip, TextView words) {
            super(context);
            this.chip = chip;
            this.words = words;
            gap = ui.dp(4);
            addView(chip);
            addView(words);
        }

        /** The chip stands above the words, not beside them (for tests). */
        boolean stacked() {
            return stacked;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int inner = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            stacked = false;
            if (chip.getVisibility() == GONE) {
                words.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), any);
                setMeasuredDimension(width, words.getMeasuredHeight() + getPaddingTop() + getPaddingBottom());
                return;
            }
            chip.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), any);
            int beside = inner - chip.getMeasuredWidth() - gap;
            if (beside > 0) {
                words.measure(MeasureSpec.makeMeasureSpec(beside, MeasureSpec.EXACTLY), any);
                Layout layout = words.getLayout();
                stacked = layout != null && layout.getLineCount() > MOST_LINES_BESIDE;
            } else {
                stacked = true;
            }
            int height;
            if (stacked) {
                words.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), any);
                height = chip.getMeasuredHeight() + words.getMeasuredHeight();
            } else {
                height = Math.max(chip.getMeasuredHeight(), words.getMeasuredHeight());
            }
            setMeasuredDimension(width, height + getPaddingTop() + getPaddingBottom());
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
