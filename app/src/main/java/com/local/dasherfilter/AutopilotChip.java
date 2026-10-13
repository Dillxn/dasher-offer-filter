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
 * the goal, in amber), "Auto 50%" (its lowest bar), "Auto" (it needs a minimum; the status line beside it says so) or
 * "Auto waits". A pill in Autopilot's purple (amber below the goal, grey while off) inside a target at least 48 dp each
 * way. A tap opens the details and a long press the goal chooser: the page that holds it says how
 * ({@link #setOnClickListener}, {@link #setOnLongClickListener}).
 *
 * <p>Screen readers hear the whole status in words and what a tap does ({@link AutopilotText#chipDescription}), and
 * the long press as "Change acceptance goal". It is no live region and never announces itself, so an automatic bar
 * change never interrupts TalkBack while driving. The homepage shows it at every window size at the start of the
 * status line, in the band along the top ({@link Row}), so Autopilot stays one tap away however short the window. It
 * is Autopilot's one control (0.6.1: the radar's round button is the learner's).
 */
@SuppressLint("ViewConstructor")
final class AutopilotChip extends TextView {
    /** How the chip is drawn: grey while off, purple while on, amber while below the goal. */
    private enum Look { OFF, ON, BELOW_GOAL }

    /**
     * The pill's words stand this far in from its rounded ends (0.6.3: 8 dp, from 14, so Autopilot's chip, the mode's
     * and the status words share one row of the band in a split screen).
     */
    private static final int SIDE_DP = 8;
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
        dress(this, ui, ink, next != Look.OFF);
    }

    /**
     * {@code chip} as a pill 36 dp tall inside its 48 dp target, its words in {@code ink}: ringed in that ink
     * ({@code ringed}), else in a quiet grey line (off, or a default). The homepage's chips share it.
     */
    static void dress(TextView chip, Ui ui, int ink, boolean ringed) {
        chip.setTextColor(ink);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(ui.surface);
        pill.setCornerRadius(ui.dp(18));
        pill.setStroke(ringed ? ui.dp(1.5f) : Math.max(1, ui.dp(1)),
                ringed ? ink : (ui.dark ? 0x40FFFFFF : 0x330B0B0B));
        int press = ui.dark ? 0x1FFFFFFF : 0x140B0B0B;
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf(press), new InsetDrawable(pill, 0, ui.dp(6), 0,
                ui.dp(6)), null));
        // A background with insets brings its own padding; the words keep theirs, clear of the pill's rounded ends.
        chip.setPadding(ui.dp(SIDE_DP), 0, ui.dp(SIDE_DP), 0);
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
     * The chips beside a line of words (the status line), the chips at the start, costing the page no height: the row
     * keeps the height the words have alone across its whole width. Where the words would wrap onto more lines beside
     * the chips (a large font, a narrow window), the chips' words and the line's shrink together by the least that
     * keeps the row that tall, never below three quarters of the user's size nor below the default size (as a setup
     * line's words do, {@link SetupRow.Words}); where even that is not enough (twice the font on the narrowest pane)
     * they stay at that least size, their lines closing up to single spacing (as the map's line does at a large font)
     * and their space above and below halved, and the row grows by the least it can. The chips go on a line of their
     * own above the words only where that is shorter still (a window too narrow for both side by side). Autopilot's
     * chip comes first, then the scoring mode's (0.6.3); a chip that is gone takes no room, and with none left the
     * words have the whole width, at their full size, spacing and padding.
     *
     * <p>Given a height exactly (0.6.3: the band's row, 56 dp), the row is that tall, never more, and never stacks:
     * the chips and the words side by side at the largest share of their sizes (down to the least) at which the words
     * fit beside the chips in that height; else at the least, the words cut to the lines that fit, an ellipsis at
     * their end. Chips and words stand in the row's middle. The page keeps the chips the room they take at their
     * widest ({@link #widestChips}), so only the words are ever cut, and those only where the page is narrow; their
     * tap is the page's to give ({@link #setWordsTappable}), and they take it only while they are at least a touch
     * target wide.
     */
    @SuppressLint("ViewConstructor")
    static final class Row extends ViewGroup {
        /** Size steps tried between the full size and the least, as {@link SetupRow.Words} does. */
        private static final int STEPS = 40;

        private final TextView[] chips;
        private final TextView words;
        private final int gap;
        /** The chips' and the words' text sizes as the page made them (the user's font size), and the least of both. */
        private final float[] chipsFull;
        private final float wordsFull;
        private final float least;
        /** The words' line spacing and space above and below as the page made them, closed up only at the least size. */
        private final float wordsSpacing;
        private final int padTop;
        private final int padBottom;
        /** The words' own line limit and ellipsis, as the page made them; a row given its height limits the lines. */
        private final int wordsMaxLines;
        private final TextUtils.TruncateAt wordsEllipsize;
        /** A touch target's width, the least at which the words take a tap. */
        private final int target;
        /** Whether the words take a tap where they are wide enough; null: the page manages the words' tap itself. */
        private Boolean wordsTappable;
        private final TextPaint measuring = new TextPaint();
        /** How the row stands now: the chips above the words, the share of the full sizes, the lines closed up. */
        private boolean stacked;
        private float scale = 1f;
        private boolean tight;
        /** What the last fitting was worked out for, and its answer (kept while the chips are gone). */
        private int fittedWidth = -1;
        private String fittedChips;
        private String fittedWords;
        private boolean fitStacked;
        private float fitScale = 1f;
        private boolean fitTight;
        /** The same, for a row given its height: what it was worked out for, and the share of the full sizes. */
        private int exactWidth = -1;
        private int exactHeight = -1;
        private String exactChips;
        private String exactWords;
        private float exactScale = 1f;

        /** One chip beside {@code words}. */
        Row(Context context, Ui ui, TextView chip, TextView words, float wordsSp) {
            this(context, ui, new TextView[] {chip}, words, wordsSp);
        }

        /**
         * The chips, in order, beside {@code words} whose own size is {@code wordsSp} (their size at the normal font:
         * the status line is 14 sp). All shrink together by one share, so it stops where any would go below its own
         * floor: three quarters of the user's size, and never below its default size.
         */
        Row(Context context, Ui ui, TextView[] chips, TextView words, float wordsSp) {
            super(context);
            this.chips = chips.clone();
            this.words = words;
            gap = ui.dp(4);
            chipsFull = new float[chips.length];
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            wordsFull = words.getTextSize();
            float floor = Math.max(SetupRow.LEAST_SCALE,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, wordsSp, metrics) / wordsFull);
            for (int i = 0; i < chips.length; i++) {
                chipsFull[i] = chips[i].getTextSize();
                floor = Math.max(floor, Math.max(SetupRow.LEAST_SCALE,
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SP, metrics) / chipsFull[i]));
            }
            least = Math.min(1f, floor);
            wordsSpacing = words.getLineSpacingMultiplier();
            padTop = words.getPaddingTop();
            padBottom = words.getPaddingBottom();
            wordsMaxLines = words.getMaxLines();
            wordsEllipsize = words.getEllipsize();
            target = ui.dp(48);
            for (TextView chip : chips) addView(chip);
            addView(words);
        }

        /**
         * The most the chips take side by side, in pixels, at the least share of their sizes, with the gaps between
         * them, shown or not: each at the widest of the words it can show (Autopilot's
         * {@link AutopilotText#CHIP_WIDEST}; the mode's "Strict" and "Area"; another chip its words now), at least
         * 48 dp. The page keeps them this much room (0.6.3), so beside the status line they are never cut.
         */
        int widestChips() {
            int total = 0;
            for (int i = 0; i < chips.length; i++) {
                if (i > 0) total += gap;
                int widest = 0;
                for (String each : chipWords(chips[i])) {
                    widest = Math.max(widest, chipWidth(chips[i], each, chipsFull[i] * least, Integer.MAX_VALUE));
                }
                total += widest;
            }
            return total;
        }

        /** Every word {@code chip} can show. */
        private static String[] chipWords(TextView chip) {
            if (chip instanceof AutopilotChip) return AutopilotText.CHIP_WIDEST;
            if (chip instanceof ModeChip) return new String[] {ModeChip.STRICT, ModeChip.AREA};
            return new String[] {chip.getText().toString()};
        }

        /**
         * Whether the words take a tap (the page's: their line opens something now). They take it only while they are
         * also at least 48 dp wide as laid out, so a sliver of words beside the chips is never a target; until the page
         * says, the words' own tap stands as the page set it.
         */
        void setWordsTappable(boolean tappable) {
            if (wordsTappable != null && wordsTappable == tappable) return;
            wordsTappable = tappable;
            applyWordsTap();
        }

        /** The words take a tap only while the page gives them one and they are a touch target wide. */
        private void applyWordsTap() {
            if (wordsTappable == null) return;
            boolean tappable = wordsTappable && words.getVisibility() == VISIBLE && words.getWidth() >= target;
            if (words.isClickable() != tappable) words.setClickable(tappable);
            if (words.isFocusable() != tappable) words.setFocusable(tappable);
        }

        /** The chips stand above the words, not beside them (for tests). */
        boolean stacked() {
            return stacked;
        }

        /** The share of the user's text size the chips and the words are drawn at now (1: full; for tests). */
        float textScale() {
            return scale;
        }

        /** The words' lines are closed up: single spacing, half the space above and below (the last resort; for tests). */
        boolean tight() {
            return tight;
        }

        private boolean anyChip() {
            for (TextView chip : chips) if (chip.getVisibility() != GONE) return true;
            return false;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            // Words with shorter forms (the acceptance meter's, 0.6.11): the first that keeps to one line with the
            // meter's track after it, each measured as the row would size the chips beside it; else the last.
            AcceptanceMeter.Words forms = words instanceof AcceptanceMeter.Words ? (AcceptanceMeter.Words) words : null;
            int count = forms == null ? 0 : forms.formCount();
            if (count == 0) {
                measureRow(widthSpec, heightSpec);
                return;
            }
            // The same row and words as last time: the form chosen then, with no new words (each would ask for
            // another layout).
            StringBuilder key = new StringBuilder().append(widthSpec).append('/').append(heightSpec).append('/')
                    .append(wordsFull).append('/').append(forms.formText(0));
            for (TextView chip : chips) key.append('/').append(chip.getVisibility()).append(chip.getText());
            String now = key.toString();
            Integer chosen = formsChosen.get(now);
            if (chosen != null && chosen < count) {
                forms.showForm(chosen);
                measureRow(widthSpec, heightSpec);
                return;
            }
            for (int i = 0; i < count; i++) {
                forms.showForm(i);
                measureRow(widthSpec, heightSpec);
                formsChosen.put(now, i);
                if (forms.leavesTrack()) return;
            }
        }

        /** The words' form chosen for the last few ways the row was measured (an index into their forms). */
        private final java.util.Map<String, Integer> formsChosen =
                new java.util.LinkedHashMap<String, Integer>(8, 0.75f, true) {
                    @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Integer> eldest) {
                        return size() > 4;
                    }
                };

        private void measureRow(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int inner = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
                measureIn(width, inner, MeasureSpec.getSize(heightSpec));
                return;
            }
            limitLines(wordsMaxLines, wordsEllipsize);
            if (!anyChip()) {
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
            int chipsWide = 0;
            int chipsTall = 0;
            for (int i = 0; i < chips.length; i++) {
                TextView chip = chips[i];
                size(chip, chipsFull[i] * scale);
                if (chip.getVisibility() == GONE) continue;
                chip.measure(MeasureSpec.makeMeasureSpec(Math.max(0, inner - chipsWide), MeasureSpec.AT_MOST), any);
                chipsWide += (chipsWide > 0 ? gap : 0) + chip.getMeasuredWidth();
                chipsTall = Math.max(chipsTall, chip.getMeasuredHeight());
            }
            size(words, wordsFull * scale);
            spacing(words, tight ? 1f : wordsSpacing);
            padding(words, tight ? padTop / 2 : padTop, tight ? padBottom / 2 : padBottom);
            int height;
            if (stacked) {
                words.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), any);
                height = chipsTall + words.getMeasuredHeight();
            } else {
                int beside = Math.max(0, inner - chipsWide - gap);
                words.measure(MeasureSpec.makeMeasureSpec(beside, MeasureSpec.EXACTLY), any);
                height = Math.max(chipsTall, words.getMeasuredHeight());
            }
            setMeasuredDimension(width, height + getPaddingTop() + getPaddingBottom());
        }

        /**
         * The row given its height (the band's): exactly {@code height} tall, never stacked. The chips and the words
         * side by side at the largest share of their full sizes, down to {@link #least}, at which the words fit beside
         * the chips in that height ({@link #fitIn}), their spacing and padding as the page made them; the words cut
         * to the lines that fit there, an ellipsis at their end.
         */
        private void measureIn(int width, int inner, int height) {
            int tall = Math.max(0, height - getPaddingTop() - getPaddingBottom());
            int any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            boolean chipsShown = anyChip();
            stacked = false;
            tight = false;
            scale = chipsShown ? fitIn(inner, tall) : 1f;
            int chipsWide = 0;
            for (int i = 0; i < chips.length; i++) {
                TextView chip = chips[i];
                size(chip, chipsFull[i] * scale);
                if (chip.getVisibility() == GONE) continue;
                chip.measure(MeasureSpec.makeMeasureSpec(Math.max(0, inner - chipsWide), MeasureSpec.AT_MOST), any);
                chipsWide += (chipsWide > 0 ? gap : 0) + chip.getMeasuredWidth();
            }
            size(words, wordsFull * scale);
            spacing(words, wordsSpacing);
            padding(words, padTop, padBottom);
            int beside = chipsShown ? Math.max(0, inner - chipsWide - gap) : inner;
            // As many lines as the height holds, at least one, the last cut short with an ellipsis where they run on.
            measuring.set(words.getPaint());
            measuring.setTextSize(wordsFull * scale);
            int line = Math.max(1, Math.round(measuring.getFontMetricsInt(null) * wordsSpacing
                    + words.getLineSpacingExtra()));
            int room = tall - padTop - padBottom;
            int lines = Math.max(1, room / line);
            limitLines(lines, TextUtils.TruncateAt.END);
            words.measure(MeasureSpec.makeMeasureSpec(beside, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(tall, MeasureSpec.AT_MOST));
            // The font's own padding can make the last line run a pixel past the height: one line fewer then.
            while (lines > 1 && words.getLayout() != null && words.getLayout().getHeight() > room) {
                limitLines(--lines, TextUtils.TruncateAt.END);
                words.measure(MeasureSpec.makeMeasureSpec(beside, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(tall, MeasureSpec.AT_MOST));
            }
            setMeasuredDimension(width, height);
        }

        /**
         * The largest share of the full sizes, from 1 down to {@link #least}, at which the chips and the words side by
         * side across {@code inner} are no taller than {@code tall}; the least where none is. Worked out without
         * touching any view, and kept while the width, the height and the words stay the same.
         */
        private float fitIn(int inner, int tall) {
            StringBuilder key = new StringBuilder();
            for (TextView chip : chips) {
                if (chip.getVisibility() != GONE) key.append(chip.getText()).append('\u0000');
            }
            String chipsText = key.toString();
            String wordsText = words.getText().toString();
            if (inner == exactWidth && tall == exactHeight && chipsText.equals(exactChips)
                    && wordsText.equals(exactWords)) {
                return exactScale;
            }
            exactWidth = inner;
            exactHeight = tall;
            exactChips = chipsText;
            exactWords = wordsText;
            float found = largestFitting(wordsText, inner, false, tall);
            exactScale = found > 0 ? found : least;
            return exactScale;
        }

        /** The words' line limit and ellipsis ({@code lines} lines at most, cut at {@code where}), when they change. */
        private void limitLines(int lines, TextUtils.TruncateAt where) {
            if (words.getMaxLines() != lines) words.setMaxLines(lines);
            if (words.getEllipsize() != where) words.setEllipsize(where);
        }

        /**
         * Works out, without touching any view, the largest size (the user's, else down to {@link #least}) at which
         * the chips and the words side by side are no taller than the words alone across {@code inner}; failing that,
         * the shorter of the two side by side at the least size with the words' lines closed up, and the chips above
         * the words at the full size.
         */
        private void fit(int inner) {
            StringBuilder key = new StringBuilder();
            for (TextView chip : chips) {
                if (chip.getVisibility() != GONE) key.append(chip.getText()).append('\u0000');
            }
            String chipsText = key.toString();
            String wordsText = words.getText().toString();
            if (inner == fittedWidth && chipsText.equals(fittedChips) && wordsText.equals(fittedWords)) return;
            fittedWidth = inner;
            fittedChips = chipsText;
            fittedWords = wordsText;
            int alone = wordsHeight(wordsText, wordsFull, inner, false);
            fitStacked = false;
            // The largest size that keeps the row to the words' own height, their lines as the page made them; else,
            // their lines closed up, the largest that does so then.
            for (boolean closed : new boolean[] {false, true}) {
                fitTight = closed;
                fitScale = largestFitting(wordsText, inner, closed, alone);
                if (fitScale > 0) return;
            }
            int beside = besideHeight(wordsText, least, inner, true);
            int above = chipsHeight(1f) + alone;
            fitStacked = above < beside;
            fitTight = !fitStacked;
            fitScale = fitStacked ? 1f : least;
        }

        /**
         * The largest share of the full sizes, from 1 down to {@link #least}, at which the row side by side is no
         * taller than {@code alone}; 0 when none is.
         */
        private float largestFitting(String wordsText, int inner, boolean closed, int alone) {
            float step = (1f - least) / STEPS;
            for (int i = 0; i <= STEPS; i++) {
                float s = i == STEPS ? least : 1f - i * step;
                if (besideHeight(wordsText, s, inner, closed) <= alone) return s;
                if (step <= 0) break;
            }
            return 0;
        }

        /**
         * The row's height with the chips and the words side by side at {@code s} of their full sizes, the words'
         * lines closed up ({@code closed}) or as the page made them.
         */
        private int besideHeight(String wordsText, float s, int inner, boolean closed) {
            int room = inner - chipsWidth(s, inner) - gap;
            if (room <= 0) return Integer.MAX_VALUE;
            return Math.max(chipsHeight(s), wordsHeight(wordsText, wordsFull * s, room, closed));
        }

        /** The chips' width side by side at {@code s} of their full sizes (each on one line), gaps between them. */
        private int chipsWidth(float s, int inner) {
            int total = 0;
            for (int i = 0; i < chips.length; i++) {
                TextView chip = chips[i];
                if (chip.getVisibility() == GONE) continue;
                if (total > 0) total += gap;
                total += chipWidth(chip, chip.getText().toString(), chipsFull[i] * s, inner);
            }
            return Math.min(inner, total);
        }

        /** The tallest chip at {@code s} of their full sizes. */
        private int chipsHeight(float s) {
            int tall = 0;
            for (int i = 0; i < chips.length; i++) {
                if (chips[i].getVisibility() != GONE) tall = Math.max(tall, chipHeight(chips[i], chipsFull[i] * s));
            }
            return tall;
        }

        /** A chip's width with its words at {@code px} (one line, as wide as the row allows). */
        private int chipWidth(TextView chip, String text, float px, int inner) {
            measuring.set(chip.getPaint());
            measuring.setTextSize(px);
            int wide = (int) Math.ceil(measuring.measureText(text)) + chip.getCompoundPaddingLeft()
                    + chip.getCompoundPaddingRight();
            return Math.min(inner, Math.max(chip.getMinWidth(), wide));
        }

        /** A chip's height with its words at {@code px}: its 48 dp target, or its one line if that is taller. */
        private int chipHeight(TextView chip, float px) {
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
            layoutParts(left, top, right, bottom);
            applyWordsTap();
        }

        private void layoutParts(int left, int top, int right, int bottom) {
            int x = getPaddingLeft();
            int y = getPaddingTop();
            if (!anyChip()) {
                int height = bottom - top - getPaddingTop() - getPaddingBottom();
                // Given its height, the words stand in its middle; else from its top, the row as tall as they are.
                int wordsTop = Math.max(y, y + (height - words.getMeasuredHeight()) / 2);
                words.layout(x, wordsTop, x + words.getMeasuredWidth(), wordsTop + words.getMeasuredHeight());
                return;
            }
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            int width = right - left - getPaddingRight();
            int height = bottom - top - getPaddingTop() - getPaddingBottom();
            int chipsTall = 0;
            for (TextView chip : chips) {
                if (chip.getVisibility() != GONE) chipsTall = Math.max(chipsTall, chip.getMeasuredHeight());
            }
            // The chips from the start edge, in order, each centred on their row (the line's, side by side).
            int edge = rtl ? width : x;
            int rowTop = stacked ? y : y + (height - chipsTall) / 2;
            for (TextView chip : chips) {
                if (chip.getVisibility() == GONE) continue;
                int wide = chip.getMeasuredWidth();
                int chipTop = rowTop + (chipsTall - chip.getMeasuredHeight()) / 2;
                if (rtl) {
                    chip.layout(edge - wide, chipTop, edge, chipTop + chip.getMeasuredHeight());
                    edge -= wide + gap;
                } else {
                    chip.layout(edge, chipTop, edge + wide, chipTop + chip.getMeasuredHeight());
                    edge += wide + gap;
                }
            }
            if (stacked) {
                int wordsTop = y + chipsTall;
                words.layout(x, wordsTop, x + words.getMeasuredWidth(), wordsTop + words.getMeasuredHeight());
                return;
            }
            int wordsTop = y + (height - words.getMeasuredHeight()) / 2;
            int wordsLeft = rtl ? x : edge;
            words.layout(wordsLeft, wordsTop, wordsLeft + words.getMeasuredWidth(), wordsTop + words.getMeasuredHeight());
        }
    }
}
