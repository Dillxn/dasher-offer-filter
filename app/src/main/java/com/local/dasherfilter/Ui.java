package com.local.dasherfilter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
 * from. Status colors are fixed across themes and always travel with a symbol and a word, never color alone.
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

    /** A section card appended to {@code page}; returns the card so the caller can add rows. */
    LinearLayout card(LinearLayout page, String title) {
        LinearLayout card = column();
        card.setBackground(rounded(surface, border, 16));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams params = matchWidth();
        params.bottomMargin = dp(12);
        page.addView(card, params);
        if (title != null) {
            TextView heading = text(title, 17, ink, true);
            if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
            card.addView(heading);
        }
        return card;
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
        button.setPadding(dp(16), 0, dp(16), 0);
        style(button, primary);
        button.setOnClickListener(clicked -> action.run());
        return button;
    }

    /** Restyles a button as filled accent (primary) or outlined (secondary). */
    void style(Button button, boolean primary) {
        Drawable shape = primary ? rounded(accent, 0, 12) : rounded(surface, dark ? 0x40FFFFFF : 0x330B0B0B, 12);
        int ripple = primary ? 0x33FFFFFF : (dark ? 0x22FFFFFF : 0x1A0B0B0B);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), shape, null));
        button.setTextColor(primary ? onAccent : ink);
    }

    /** A full-width button added to {@code parent} with standard spacing. */
    Button addButton(LinearLayout parent, String label, boolean primary, Runnable action) {
        Button button = button(label, primary, action);
        LinearLayout.LayoutParams params = matchWidth();
        params.topMargin = dp(10);
        parent.addView(button, params);
        return button;
    }

    /** A filled circle carrying a result symbol: ✓ passed, ✕ declined, ? review. */
    TextView badge(OfferRule.Result result, int sizeDp) {
        return badge(resultSymbol(result), resultColor(result), sizeDp);
    }

    TextView badge(String symbol, int color, int sizeDp) {
        TextView view = text(symbol, sizeDp * 0.5f, onStatus(color), true);
        // The symbol belongs to the circle, so it scales with the circle, not with the font setting.
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp * 0.5f);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        view.setBackground(circle);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    void recolorBadge(TextView badge, String symbol, int color) {
        badge.setText(symbol);
        badge.setTextColor(onStatus(color));
        ((GradientDrawable) badge.getBackground()).setColor(color);
    }

    /** A labeled numeric field; the label is linked to the field for screen readers. */
    EditText field(LinearLayout parent, String label, String value, boolean decimal) {
        return field(parent, label, value, decimal, null);
    }

    /** A labeled numeric field whose label starts with a drawn icon for what it measures. */
    EditText field(LinearLayout parent, String label, String value, boolean decimal, Glyph.Shape icon) {
        TextView caption = text(label, 13, inkSecondary, false);
        caption.setPadding(0, dp(10), 0, dp(4));
        if (icon != null) {
            caption.setCompoundDrawablesRelative(new Glyph(icon, inkSecondary, dp(16)), null, null, null);
            caption.setCompoundDrawablePadding(dp(6));
        }
        EditText field = new EditText(context);
        field.setId(View.generateViewId());
        caption.setLabelFor(field.getId());
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        field.setText(value);
        field.setSelectAllOnFocus(true);
        styleField(field);
        parent.addView(caption);
        parent.addView(field, matchWidth());
        return field;
    }

    void styleField(EditText field) {
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setTextColor(ink);
        field.setHintTextColor(inkMuted);
        field.setBackground(rounded(fieldFill, border, 10));
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
    }

    Switch toggle(LinearLayout parent, String label, boolean value) {
        Switch view = new Switch(context);
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

    View divider() {
        View line = new View(context);
        line.setBackgroundColor(gridline);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1)));
        params.topMargin = dp(12);
        params.bottomMargin = dp(4);
        line.setLayoutParams(params);
        return line;
    }

    static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }
}
