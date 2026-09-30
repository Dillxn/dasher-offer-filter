package com.local.dasherfilter;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Keeps the filter's tab ({@link DasherTab}) over Dasher while Dasher fills the screen, and only then: in split screen
 * Offer Filter's own half has the mascot, so no tab. It is an accessibility overlay: no extra permission, and it can
 * be neither dragged nor dismissed. It sits on the left edge a little above the middle, clear of Dasher's offer card
 * and its Accept and Decline buttons. A tap pauses or resumes auto-decline, as tapping the mascot does; with no rule
 * saved yet, it opens Offer Filter. A tap during an automatic decline is a touch like any other, so that offer is
 * handed back to the user.
 *
 * <p>Beside the tab (or, in split screen, at the top of Dasher's half) a {@link DasherGuide} points toward the best
 * offer area, while no offer is on screen and no delivery is under way. Touches pass through it.
 *
 * <p>Both are laid out in screen coordinates (as Android reports Dasher's window bounds), not below the status bar,
 * so they land where they are placed over Dasher.
 */
final class DasherOverlay {
    /** Where the tab's top sits, as a share of the screen's height. */
    static final float TOP_SHARE = 0.38f;
    private static final String PREFS = "dasher_overlay";
    private static final String SHOWN = "shown";

    /** The guide is worked out again at most this often (it reads the areas and the last known position). */
    static final long GUIDE_EVERY_MS = 10_000;

    private final AccessibilityService service;
    private DasherTab tab;
    private DasherGuide guide;
    private DasherGuide.Pointer pointer;
    private long pointerAt = -GUIDE_EVERY_MS;
    private long pointerVersion = -1;
    private final Rect area = new Rect();
    private Ui ui;

    DasherOverlay(AccessibilityService service) {
        this.service = service;
    }

    /** Whether the tab may show over Dasher; on unless turned off (no switch: it is always on for users). */
    static boolean enabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SHOWN, true);
    }

    static void setEnabled(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SHOWN, on).apply();
    }

    /** Whether anything of it is over Dasher now. */
    boolean isShowing() {
        return tab != null || guide != null;
    }

    DasherTab tab() {
        return tab;
    }

    DasherGuide guide() {
        return guide;
    }

    /**
     * Follows Dasher: {@code dasher} is where it is on screen (null when it is not), {@code split} whether that is
     * one half of a split screen, and {@code offerShowing} whether an offer or its confirmation is up, which the
     * guide never covers.
     */
    void sync(Rect dasher, boolean split, boolean offerShowing) {
        if (dasher == null || dasher.isEmpty() || !enabled(service)) {
            hide();
            return;
        }
        area.set(dasher);
        if (split) {
            hideTab();
        } else {
            if (tab == null) show();
            if (tab != null) {
                place(tab, dasher.left, tabTop());
                tab.show(state(FilterStore.load(service)));
            }
        }
        syncGuide(split, offerShowing);
    }

    private int tabTop() {
        return area.top + Math.round(area.height() * TOP_SHARE);
    }

    private void syncGuide(boolean split, boolean offerShowing) {
        long now = SystemClock.uptimeMillis();
        if (now - pointerAt >= GUIDE_EVERY_MS || AreaMap.version() != pointerVersion) {
            pointerAt = now;
            pointerVersion = AreaMap.version();
            try {
                pointer = DasherGuide.best(service);
            } catch (RuntimeException unreadable) {
                pointer = null;
            }
        }
        if (pointer == null || offerShowing) {
            hideGuide();
            return;
        }
        Ui ui = ui();
        if (guide == null) {
            DasherGuide view = new DasherGuide(service, ui);
            view.show(pointer.label, pointer.bearing);
            view.measure(0, 0);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, ui.dp(DasherGuide.HEIGHT_DP),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            try {
                service.getSystemService(WindowManager.class).addView(view, params);
                guide = view;
            } catch (RuntimeException refused) {
                DiagnosticLog.log(service, "accessibility", "area guide unavailable: "
                        + refused.getClass().getSimpleName());
                return;
            }
        } else {
            guide.show(pointer.label, pointer.bearing);
        }
        guide.measure(0, 0);
        int width = guide.getMeasuredWidth();
        // Full screen: beside the tab. Split: centred near the top of Dasher's half, under its own top buttons.
        int x = split ? area.left + Math.max(0, (area.width() - width) / 2) : area.left + ui.dp(DasherTab.WIDTH_DP + 8);
        int y = split ? area.top + ui.dp(72)
                : tabTop() + (ui.dp(DasherTab.HEIGHT_DP) - ui.dp(DasherGuide.HEIGHT_DP)) / 2;
        place(guide, x, y);
    }

    /** Made once: the overlay follows the phone's theme as it was when the service started. */
    private Ui ui() {
        if (ui == null) ui = new Ui(service);
        return ui;
    }

    private void place(android.view.View view, int x, int y) {
        if (!(view.getLayoutParams() instanceof WindowManager.LayoutParams)) return;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
        if (params.x == x && params.y == y) return;
        params.x = x;
        params.y = y;
        try {
            service.getSystemService(WindowManager.class).updateViewLayout(view, params);
        } catch (RuntimeException gone) {
            // The window went with the service.
        }
    }

    static FilterHeroView.State state(FilterSettings saved) {
        return saved.enabled ? FilterHeroView.State.ON
                : saved.hasAnyRule() ? FilterHeroView.State.PAUSED : FilterHeroView.State.OFF;
    }

    private void show() {
        WindowManager windows = service.getSystemService(WindowManager.class);
        if (windows == null) return;
        Ui ui = ui();
        DasherTab view = new DasherTab(service, ui);
        view.setOnClickListener(tapped -> toggle(view));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(ui.dp(DasherTab.WIDTH_DP),
                ui.dp(DasherTab.HEIGHT_DP), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = area.left;
        params.y = tabTop();
        try {
            windows.addView(view, params);
            tab = view;
        } catch (RuntimeException refused) {
            DiagnosticLog.log(service, "accessibility", "filter tab unavailable: " + refused.getClass().getSimpleName());
        }
    }

    void hide() {
        hideTab();
        hideGuide();
    }

    private void hideTab() {
        if (tab == null) return;
        DasherTab view = tab;
        tab = null;
        remove(view);
    }

    private void hideGuide() {
        if (guide == null) return;
        DasherGuide view = guide;
        guide = null;
        remove(view);
    }

    private void remove(android.view.View view) {
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
                Toast.makeText(service, "Open Dash Buddy to add a rule.", Toast.LENGTH_SHORT).show();
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
