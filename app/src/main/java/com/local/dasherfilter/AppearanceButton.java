package com.local.dasherfilter;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

/** The scene supplies the sun/moon; this existing touch target adds only the small mode indicator. */
final class AppearanceButton extends FrameLayout {
    private final TextView badge;

    AppearanceButton(Context context, Ui ui) {
        super(context);
        setBackground(ui.pressable(28));
        setFocusable(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        badge = ui.text("", 10, ui.ink, true);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        badge.setSingleLine(true);
        badge.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2));
        badge.setMinimumWidth(ui.dp(18));
        badge.setMinimumHeight(ui.dp(18));
        badge.setBackground(ui.rounded(ui.page, ui.border, 12));
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        position.setMarginEnd(ui.dp(2));
        position.bottomMargin = ui.dp(2);
        addView(badge, position);
    }

    void show(Appearance.State state) {
        String mark = state.mode == Appearance.Mode.SYSTEM ? "S" : state.mode == Appearance.Mode.AUTO ? "A" : "";
        badge.setText(mark);
        badge.setVisibility(mark.isEmpty() ? View.GONE : View.VISIBLE);
        setContentDescription(state.description());
        // Long-press explains the current mode/fallback without opening a chooser or another Settings row.
        setTooltipText(state.description());
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(android.widget.Button.class.getName());
    }
}
