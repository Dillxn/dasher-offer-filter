package com.local.dasherfilter;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Framework-only styling for the app's screen: one palette per light/dark theme and the few components it is built
 * from, kept quiet: soft pill buttons, rounded fields, small captions for headings, and switches with a face. Status
 * colors are fixed across themes and always travel with a symbol and a word, never color alone.
 */
final class Ui {
    static final int GOOD = 0xFF0CA30C;
    static final int WARNING = 0xFFFAB219;
    static final int CRITICAL = 0xFFD03B3B;
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    final Context context;
    final boolean dark;
    final int page;
    final int surface;
    final int border;
    final int ink;
    final int inkSecondary;
    final int inkMuted;
    final int gridline;
    final int baseline;
    final int accent;
    /** Link text: the accent, lightened at night so small text still reads on the dark page. */
    final int link;
    /** The second data series (adaptive minimums), apart from the status colors. */
    final int learned;
    final int onAccent;
    final int fieldFill;
    final int selectionWash;

    Ui(Context context) {
        this.context = context;
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = mode == Configuration.UI_MODE_NIGHT_YES;
        page = dark ? 0xFF0D0D0D : 0xFFF1F0EC;
        surface = dark ? 0xFF1A1A19 : 0xFFFCFCFB;
        border = dark ? 0x1AFFFFFF : 0x1A0B0B0B;
        ink = dark ? 0xFFFFFFFF : 0xFF0B0B0B;
        inkSecondary = dark ? 0xFFC3C2B7 : 0xFF52514E;
        inkMuted = 0xFF898781;
        gridline = dark ? 0xFF2C2C2A : 0xFFE1E0D9;
        baseline = dark ? 0xFF383835 : 0xFFC3C2B7;
        accent = 0xFF256ABF;
        link = dark ? 0xFF8AB4F0 : 0xFF1D5499;
        learned = dark ? 0xFFB08CF0 : 0xFF7A4CC8;
        onAccent = 0xFFFFFFFF;
        fieldFill = dark ? 0xFF262625 : 0xFFF1F0EC;
        selectionWash = dark ? 0xFF262625 : 0xFFF1F0EC;
    }

    /** True when the user's font setting is large enough that side-by-side text would crowd. */
    boolean largeText() {
        return context.getResources().getConfiguration().fontScale >= 1.3f;
    }

    int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.getResources().getDisplayMetrics());
    }

    /**
     * Shrinks {@code paint}'s text size (down to {@code minFraction} of it) until {@code value} fits
     * {@code maxWidth}, for drawn text that must survive large system font sizes; returns the text, ellipsized if
     * it still does not fit.
     */
    static CharSequence fit(android.text.TextPaint paint, String value, float maxWidth, float minFraction) {
        float size = paint.getTextSize();
        float width = paint.measureText(value);
        if (width > maxWidth && width > 0) paint.setTextSize(Math.max(size * minFraction, size * maxWidth / width));
        return android.text.TextUtils.ellipsize(value, paint, Math.max(0, maxWidth),
                android.text.TextUtils.TruncateAt.END);
    }

    /** Height of one line of text at {@code paint}'s current size. */
    static float lineHeight(Paint paint) {
        Paint.FontMetrics metrics = paint.getFontMetrics();
        return metrics.descent - metrics.ascent;
    }

    static int resultColor(OfferRule.Result result) {
        return result == OfferRule.Result.KEEP ? GOOD : result == OfferRule.Result.DECLINE ? CRITICAL : WARNING;
    }

    static String resultSymbol(OfferRule.Result result) {
        return result == OfferRule.Result.KEEP ? "✓" : result == OfferRule.Result.DECLINE ? "✕" : "?";
    }

    static String resultLabel(OfferRule.Result result) {
        return result == OfferRule.Result.KEEP ? "Passed" : result == OfferRule.Result.DECLINE ? "Declined" : "Review";
    }

    /** Symbol ink that stays legible on the status fill (the pale warning fill takes dark ink). */
    static int onStatus(int statusColor) {
        return statusColor == WARNING ? 0xFF0B0B0B : 0xFFFFFFFF;
    }

    GradientDrawable rounded(int fill, int stroke, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (stroke != 0) shape.setStroke(Math.max(1, dp(1)), stroke);
        return shape;
    }

    /** Two equal-width buttons side by side. */
    LinearLayout buttonPair(LinearLayout parent, Button first, Button second) {
        LinearLayout pair = row();
        LinearLayout.LayoutParams left = weighted();
        left.setMarginEnd(dp(5));
        pair.addView(first, left);
        LinearLayout.LayoutParams right = weighted();
        right.setMarginStart(dp(5));
        pair.addView(second, right);
        LinearLayout.LayoutParams params = matchWidth();
        params.topMargin = dp(10);
        parent.addView(pair, params);
        return pair;
    }

    LinearLayout column() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    TextView text(String value, float sizeSp, int color, boolean medium) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.15f);
        if (medium) view.setTypeface(MEDIUM);
        return view;
    }

    /** Body copy in secondary ink with a little space above. */
    TextView note(String value) {
        TextView view = text(value, 14, inkSecondary, false);
        view.setPadding(0, dp(6), 0, 0);
        return view;
    }

    Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTypeface(MEDIUM);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setStateListAnimator(null);
        button.setPadding(dp(20), 0, dp(20), 0);
        style(button, primary);
        button.setOnClickListener(clicked -> action.run());
        return button;
    }

    /** Restyles a button as a soft, flat pill: filled accent (primary) or a faint wash (secondary). */
    void style(Button button, boolean primary) {
        int face = primary ? accent : (dark ? 0x14FFFFFF : 0x0F0B0B0B);
        int pressed = primary ? 0xFF1D5499 : (dark ? 0x29FFFFFF : 0x1F0B0B0B);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_pressed}, rounded(pressed, 0, 24));
        states.addState(new int[] {}, rounded(face, 0, 24));
        button.setBackground(states);
        button.setTextColor(primary ? onAccent : ink);
    }

    /** A soft press highlight inside rounded bounds, for rows that act when tapped. */
    Drawable pressable(float radiusDp) {
        int press = dark ? 0x1FFFFFFF : 0x140B0B0B;
        return new RippleDrawable(ColorStateList.valueOf(press), null, rounded(0xFFFFFFFF, 0, radiusDp));
    }

    /** A full-width line of text that opens something, with a chevron at its end. */
    Button listRow(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setTextColor(ink);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinHeight(dp(52));
        button.setMinimumHeight(dp(52));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setStateListAnimator(null);
        button.setCompoundDrawablesRelative(null, null, new Glyph(Glyph.Shape.CHEVRON, inkMuted, dp(18)), null);
        button.setBackground(pressable(12));
        button.setOnClickListener(clicked -> action.run());
        parent.addView(button, matchWidth());
        return button;
    }

    /** A few words of link text that open something, still a 48 dp touch target; screen readers hear a button. */
    Button link(String label, Runnable action) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTypeface(MEDIUM);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTextColor(link);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setStateListAnimator(null);
        button.setBackground(pressable(24));
        button.setOnClickListener(clicked -> action.run());
        return button;
    }

    /** A full-width button added to {@code parent} with standard spacing. */
    Button addButton(LinearLayout parent, String label, boolean primary, Runnable action) {
        Button button = button(label, primary, action);
        LinearLayout.LayoutParams params = matchWidth();
        params.topMargin = dp(10);
        parent.addView(button, params);
        return button;
    }

    void styleField(EditText field) {
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setTextColor(ink);
        field.setHintTextColor(inkMuted);
        field.setBackground(rounded(fieldFill, border, 10));
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
    }

    /** A switch whose knob is the mascot's face: awake when on, asleep when off. */
    Switch toggle(LinearLayout parent, String label, boolean value) {
        Switch view = new Switch(context);
        StateListDrawable knob = new StateListDrawable();
        knob.addState(new int[] {android.R.attr.state_checked}, new Decor.FaceKnob(this, true));
        knob.addState(new int[] {}, new Decor.FaceKnob(this, false));
        view.setThumbDrawable(knob);
        StateListDrawable track = new StateListDrawable();
        track.addState(new int[] {android.R.attr.state_checked}, new Decor.Track(this, true));
        track.addState(new int[] {}, new Decor.Track(this, false));
        view.setTrackDrawable(track);
        view.setText(label);
        view.setTextColor(ink);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setChecked(value);
        view.setMinHeight(dp(48));
        LinearLayout.LayoutParams params = matchWidth();
        params.topMargin = dp(6);
        parent.addView(view, params);
        return view;
    }

    /** A section heading: a small, quiet, letter-spaced caption. */
    TextView heading(LinearLayout parent, String title) {
        TextView view = text(title.toUpperCase(java.util.Locale.US), 12, inkMuted, true);
        view.setLetterSpacing(0.16f);
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        view.setContentDescription(title);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(36);
        params.bottomMargin = dp(10);
        parent.addView(view, params);
        return view;
    }

    /**
     * A rule field on a soft rounded card: a small icon and label on top, the amount large beneath. The label is
     * linked to the field for screen readers.
     */
    EditText tagField(LinearLayout parent, String label, String value, boolean decimal, Glyph.Shape icon) {
        LinearLayout tag = column();
        tag.setBackground(rounded(dark ? 0x0FFFFFFF : 0x0A0B0B0B, 0, 16));
        tag.setPadding(dp(14), dp(10), dp(14), dp(8));
        TextView caption = text(label, 12, inkSecondary, false);
        caption.setCompoundDrawablesRelative(new Glyph(icon, inkSecondary, dp(15)), null, null, null);
        caption.setCompoundDrawablePadding(dp(6));
        EditText field = new EditText(context);
        field.setId(View.generateViewId());
        caption.setLabelFor(field.getId());
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        field.setText(value);
        field.setSelectAllOnFocus(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        field.setTypeface(MEDIUM);
        field.setTextColor(ink);
        field.setHintTextColor(inkMuted);
        field.setBackground(null);
        field.setPadding(0, dp(2), 0, dp(2));
        tag.addView(caption);
        tag.addView(field, matchWidth());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        params.topMargin = dp(8);
        parent.addView(tag, params);
        return field;
    }

    static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }
}
