package com.local.dasherfilter;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Keeps the filter's tab ({@link DasherTab}) over Dasher while Dasher is on screen, and only then. It is an
 * accessibility overlay: no extra permission, and it can be neither dragged nor dismissed. It sits on the left edge a
 * little above the middle, clear of Dasher's offer card and its Accept and Decline buttons. A tap pauses or resumes
 * auto-decline, as tapping the mascot does; with no rule saved yet, it opens Offer Filter. A tap during an automatic
 * decline is a touch like any other, so that offer is handed back to the user. Turned off in Settings, it never shows.
 */
final class DasherOverlay {
    /** Where the tab's top sits, as a share of the screen's height. */
    static final float TOP_SHARE = 0.38f;
    private static final String PREFS = "dasher_overlay";
    private static final String SHOWN = "shown";

    private final AccessibilityService service;
    private DasherTab tab;

    DasherOverlay(AccessibilityService service) {
        this.service = service;
    }

    /** Whether the tab may show over Dasher; on unless turned off in Settings. */
    static boolean enabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SHOWN, true);
    }

    static void setEnabled(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SHOWN, on).apply();
    }

    boolean isShowing() {
        return tab != null;
    }

    DasherTab tab() {
        return tab;
    }

    /** Shows the tab while Dasher is on screen and the tab is wanted, hides it otherwise, and keeps its state fresh. */
    void sync(boolean dasherOnScreen) {
        if (!dasherOnScreen || !enabled(service)) {
            hide();
            return;
        }
        if (tab == null) show();
        if (tab != null) tab.show(state(FilterStore.load(service)));
    }

    static FilterHeroView.State state(FilterSettings saved) {
        return saved.enabled ? FilterHeroView.State.ON
                : saved.hasAnyRule() ? FilterHeroView.State.PAUSED : FilterHeroView.State.OFF;
    }

    private void show() {
        WindowManager windows = service.getSystemService(WindowManager.class);
        if (windows == null) return;
        Ui ui = new Ui(service);
        DasherTab view = new DasherTab(service, ui);
        view.setOnClickListener(tapped -> toggle(view));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(ui.dp(DasherTab.WIDTH_DP),
                ui.dp(DasherTab.HEIGHT_DP), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = Math.round(service.getResources().getDisplayMetrics().heightPixels * TOP_SHARE);
        try {
            windows.addView(view, params);
            tab = view;
        } catch (RuntimeException refused) {
            DiagnosticLog.log(service, "accessibility", "filter tab unavailable: " + refused.getClass().getSimpleName());
        }
    }

    void hide() {
        if (tab == null) return;
        DasherTab view = tab;
        tab = null;
        try {
            service.getSystemService(WindowManager.class).removeViewImmediate(view);
        } catch (RuntimeException alreadyGone) {
            // The window went with the service.
        }
    }

    /** Pauses or resumes auto-decline, keeping every saved rule; with none saved, opens Offer Filter to add one. */
    private void toggle(DasherTab view) {
        FilterSettings saved = FilterStore.load(service);
        if (!saved.enabled && !saved.hasAnyRule()) {
            try {
                service.startActivity(new Intent(service, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException refused) {
                Toast.makeText(service, "Open Offer Filter to add a rule.", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        FilterSettings next = saved.withEnabled(!saved.enabled);
        FilterStore.save(service, next);
        OfferNotificationService.rulesChanged();
        OfferFilterService.requestCheckFromNotification();
        view.show(state(next));
        view.announceForAccessibility(next.enabled ? "Auto-decline on" : "Auto-decline paused");
        Toast.makeText(service, next.enabled ? "Auto-decline is on." : "Paused. Nothing will be declined.",
                Toast.LENGTH_SHORT).show();
    }
}
