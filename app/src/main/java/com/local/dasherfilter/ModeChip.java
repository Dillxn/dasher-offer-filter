package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

/**
 * How offers are judged, as one word on the homepage (0.6.3; the owner: the mode belongs on the homepage, not in
 * Settings): "Area" (the default from 0.6.27; Strict before) ringed in the saved minimums' blue, or "Strict" in a quiet
 * grey pill, never Autopilot's purple or the learner's teal. A tap opens the chooser ("How offers are judged"), whose one more tap picks a mode: a
 * stray tap beside Autopilot's chip changes nothing, and each choice says what it means. It is its own control, never
 * Autopilot's (the 0.6.0 directive). Screen readers hear the mode and what it means; it is no live region.
 */
@SuppressLint("ViewConstructor")
final class ModeChip extends TextView {
    static final String STRICT = "Strict";
    static final String AREA = "Area";
    static final String ACTION = "choose scoring mode";
    static final String STRICT_SAID = "Scoring mode: strict. Every active minimum must be met.";
    static final String AREA_SAID = "Scoring mode: area. Stronger pay or rates can compensate for weaker ones; max "
            + "stops stays a hard limit.";

    private final Ui ui;
    private Boolean area;

    ModeChip(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        setTextSize(TypedValue.COMPLEX_UNIT_SP, AutopilotChip.SP);
        setTypeface(Ui.MEDIUM);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setGravity(Gravity.CENTER);
        setMinHeight(ui.dp(48));
        setMinWidth(ui.dp(48));
        setClickable(true);
        setLongClickable(false);
        setFocusable(true);
        setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_NONE);
        show(false);
    }

    /** Shows the mode: its word, its pill and what screen readers hear. */
    void show(boolean area) {
        if (this.area != null && this.area == area) return;
        this.area = area;
        setText(area ? AREA : STRICT);
        setContentDescription(area ? AREA_SAID : STRICT_SAID);
        AutopilotChip.dress(this, ui, area ? ui.link : ui.inkSecondary, area);
    }

    /** What a tap does, in words, for screen readers. */
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(android.widget.Button.class.getName());
        if (isClickable()) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, ACTION));
        }
    }
}
