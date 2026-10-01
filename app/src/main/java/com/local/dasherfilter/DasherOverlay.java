package com.local.dasherfilter;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Keeps the filter's tab ({@link DasherTab}) over Dasher while Dasher fills the screen, and only then: in split screen
 * Offer Filter's own half has the mascot, so no tab. It is an accessibility overlay: no extra permission. It starts on
 * the left edge a little above the middle; the user drags it up or down either edge, or across to the other edge (it
 * lands on the nearer one), and it stays where it was put, one place for portrait and one for landscape. A drag is
 * never a tap. At rest about half of it is tucked past the edge. During an offer or its confirmation, during a
 * delivery, and on a screen not recognised during a dash, only a slim peek shows, so it covers none of Dasher's offer
 * or delivery. Away from an offer a tap on the peek brings the tab out for a few seconds; over an offer the peek takes
 * no touches at all, so every touch there is Dasher's. A tap on the tab pauses or resumes
 * auto-decline, as tapping the mascot does; with no rule saved yet, it opens Offer Filter. A tap during an automatic
 * decline is a touch like any other, so that offer is handed back to the user.
 *
 * <p>Beside the tab (or, in split screen, at the top of Dasher's half) a {@link DasherGuide} points toward the best
 * offer area, only while Dasher's screen positively shows the wait for offers ({@link DasherScene#WAITING}): never
 * over an offer, a confirmation, a delivery or a screen not recognised. Touches pass through it.
 *
 * <p>Both are laid out in screen coordinates (as Android reports Dasher's window bounds), not below the status bar,
 * so they land where they are placed over Dasher. All of it runs on the main thread.
 */
final class DasherOverlay implements DasherTab.Listener {
    /** Where the tab's top sits until the user moves it, as a share of the screen's height. */
    static final float TOP_SHARE = 0.38f;
    private static final String PREFS = "dasher_overlay";
    private static final String SHOWN = "shown";
    private static final String RIGHT = "tab_right_";
    private static final String TOP = "tab_top_";
    /** A tab tucked away for a delivery comes out at a tap for this long after the last tap, then tucks away again. */
    static final long OUT_FOR_MS = 6_000;
    /** The tab keeps this far from the top and the bottom of Dasher's window (the status and navigation bars). */
    static final int TOP_MARGIN_DP = 48;
    static final int BOTTOM_MARGIN_DP = 64;
    /** One "Move up" or "Move down" from a screen reader. */
    static final int STEP_DP = 64;
    /** Between the tab and the guide beside it. */
    private static final int GUIDE_GAP_DP = 6;

    /** The guide is worked out again at most this often (it reads the areas and the last known position). */
    static final long GUIDE_EVERY_MS = 10_000;

    private final AccessibilityService service;
    private DasherTab tab;
    private DasherGuide guide;
    private DasherGuide.Pointer pointer;
    private long pointerAt = -GUIDE_EVERY_MS;
    private long pointerVersion = -1;
    private final Rect area = new Rect();
    private boolean split;
    private DasherScene scene = DasherScene.UNKNOWN;
    /** Whether a dash is on, as far as the app has seen ({@link Dashing#now}). */
    private boolean dashing;
    /** Until when (uptime) a tab tucked away for a delivery stays out after a tap. */
    private long outUntil;
    private boolean dragging;
    private int dragFromX;
    private int dragFromY;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tuckAgain = this::layoutTab;
    private Ui ui;

    DasherOverlay(AccessibilityService service) {
        this.service = service;
    }

    /** Whether the tab may show over Dasher; on unless turned off (no switch: it is always on for users). */
    static boolean enabled(Context context) {
        return prefs(context).getBoolean(SHOWN, true);
    }

    static void setEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean(SHOWN, on).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
     * one half of a split screen, and {@code scene} what the last read made of Dasher's screen.
     */
    void sync(Rect dasher, boolean split, DasherScene scene) {
        if (dasher == null || dasher.isEmpty() || !enabled(service)) {
            hide();
            return;
        }
        area.set(dasher);
        this.split = split;
        this.scene = scene == null ? DasherScene.UNKNOWN : scene;
        dashing = Dashing.now(service);
        if (split) {
            hideTab();
        } else {
            if (tab == null) show();
            if (tab != null) {
                tab.show(state(FilterStore.load(service)));
                if (!dragging) layoutTab();
            }
        }
        syncGuide();
    }

    // ---- The tab's place ----

    /** One place per orientation, as Dasher's window is now. */
    private String orientation() {
        return area.width() > area.height() ? "landscape" : "portrait";
    }

    private boolean onRight() {
        return prefs(service).getBoolean(RIGHT + orientation(), false);
    }

    private int minTop() {
        return Math.min(area.top + ui().dp(TOP_MARGIN_DP), centeredTop());
    }

    private int maxTop() {
        return Math.max(area.bottom - ui().dp(DasherTab.HEIGHT_DP) - ui().dp(BOTTOM_MARGIN_DP), minTop());
    }

    private int centeredTop() {
        return area.top + (area.height() - ui().dp(DasherTab.HEIGHT_DP)) / 2;
    }

    /** Where the tab's top is: as the user left it, else a little above the middle; never off its edge's band. */
    private int tabTop() {
        float share = prefs(service).getFloat(TOP + orientation(), TOP_SHARE);
        return clamp(area.top + Math.round(area.height() * share), minTop(), maxTop());
    }

    private void remember(boolean right, int top) {
        float share = area.height() > 0 ? (top - area.top) / (float) area.height() : TOP_SHARE;
        prefs(service).edit().putBoolean(RIGHT + orientation(), right).putFloat(TOP + orientation(), share).apply();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Whether the tab tucks away until tapped: during a delivery, and on a screen not recognised during a dash, which
     * is most likely a delivery or shopping screen in words not known yet.
     */
    private boolean tucksAway() {
        return scene == DasherScene.ROUTE || (scene == DasherScene.UNKNOWN && dashing);
    }

    /** How the tab shows now: a slim peek over an offer, and while it tucks away unless a tap brought it out. */
    private DasherTab.Look restingLook() {
        if (scene == DasherScene.OFFER) return DasherTab.Look.PEEK;
        if (tucksAway() && SystemClock.uptimeMillis() >= outUntil) return DasherTab.Look.PEEK;
        return DasherTab.Look.REST;
    }

    /** Puts the tab on its edge at its place, looking as it should now. */
    private void layoutTab() {
        if (tab == null || dragging) return;
        DasherTab.Look look = restingLook();
        boolean right = onRight();
        tab.setLook(look, right, tucksAway());
        int width = ui().dp(DasherTab.widthDp(look));
        int top = tabTop();
        tab.setMoves(top > minTop(), top < maxTop());
        place(tab, right ? area.right - width : area.left, top, width);
        touchable(scene != DasherScene.OFFER);
    }

    /** Over an offer the peek takes no touches at all: every touch there is Dasher's (the touch watch still sees it). */
    private void touchable(boolean on) {
        if (tab == null || !(tab.getLayoutParams() instanceof WindowManager.LayoutParams)) return;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) tab.getLayoutParams();
        int flags = on ? params.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                : params.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (flags == params.flags) return;
        params.flags = flags;
        try {
            service.getSystemService(WindowManager.class).updateViewLayout(tab, params);
        } catch (RuntimeException gone) {
            // The window went with the service.
        }
    }

    private void tuckLater() {
        main.removeCallbacks(tuckAgain);
        main.postDelayed(tuckAgain, Math.max(0, outUntil - SystemClock.uptimeMillis()));
    }

    // ---- What the user does with the tab ----

    @Override public void tapped() {
        DasherTab view = tab;
        if (view == null) return;
        if (tucksAway()) {
            // Out for a few seconds after each tap, then tucked away again.
            boolean wasTucked = view.look() == DasherTab.Look.PEEK;
            outUntil = SystemClock.uptimeMillis() + OUT_FOR_MS;
            tuckLater();
            if (wasTucked) {
                layoutTab();
                return;
            }
        } else if (view.look() == DasherTab.Look.PEEK) {
            // Over an offer it stays tucked away: the touch is only a touch.
            return;
        }
        toggle(view);
    }

    @Override public void dragStarted() {
        if (tab == null) return;
        dragging = true;
        hideGuide();
        boolean right = onRight();
        tab.setLook(DasherTab.Look.FLOATING, right, false);
        int width = ui().dp(DasherTab.WIDTH_DP);
        dragFromX = right ? area.right - width : area.left;
        dragFromY = tabTop();
        place(tab, dragFromX, dragFromY, width);
    }

    @Override public void dragged(float dx, float dy) {
        if (tab == null || !dragging) return;
        int width = ui().dp(DasherTab.WIDTH_DP);
        place(tab, clamp(dragFromX + Math.round(dx), area.left, Math.max(area.left, area.right - width)),
                clamp(dragFromY + Math.round(dy), minTop(), maxTop()), width);
    }

    @Override public void dropped() {
        if (tab == null || !dragging) return;
        dragging = false;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) tab.getLayoutParams();
        // It lands on the nearer edge, where it was let go.
        boolean right = params.x + ui().dp(DasherTab.WIDTH_DP) / 2 > area.centerX();
        remember(right, clamp(params.y, minTop(), maxTop()));
        layoutTab();
        syncGuide();
    }

    @Override public boolean move(DasherTab.Move how) {
        if (tab == null || dragging) return false;
        boolean right = onRight();
        int top = tabTop();
        int next = how == DasherTab.Move.UP ? clamp(top - ui().dp(STEP_DP), minTop(), maxTop())
                : how == DasherTab.Move.DOWN ? clamp(top + ui().dp(STEP_DP), minTop(), maxTop()) : top;
        if (how == DasherTab.Move.OTHER_SIDE) right = !right;
        else if (next == top) return false;
        remember(right, next);
        layoutTab();
        syncGuide();
        return true;
    }

    // ---- The guide ----

    private void syncGuide() {
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
        // Only over the wait for offers; never while the tab is being moved, or beside a tab that is not there.
        if (pointer == null || scene != DasherScene.WAITING || dragging || area.isEmpty() || (!split && tab == null)) {
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
        int x;
        int y;
        if (split) {
            // Centred near the top of Dasher's half, under its own top buttons.
            x = area.left + Math.max(0, (area.width() - width) / 2);
            y = area.top + ui.dp(72);
        } else {
            // Beside the tab, on the side away from its edge.
            int beside = ui.dp(DasherTab.shownDp(tab.look()) + GUIDE_GAP_DP);
            x = tab.onRight() ? Math.max(area.left, area.right - beside - width) : area.left + beside;
            y = tabTop() + (ui.dp(DasherTab.HEIGHT_DP) - ui.dp(DasherGuide.HEIGHT_DP)) / 2;
        }
        place(guide, x, y, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    /** Made once: the overlay follows the phone's theme as it was when the service started. */
    private Ui ui() {
        if (ui == null) ui = new Ui(service);
        return ui;
    }

    private void place(android.view.View view, int x, int y, int width) {
        if (!(view.getLayoutParams() instanceof WindowManager.LayoutParams)) return;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
        if (params.x == x && params.y == y && params.width == width) return;
        params.x = x;
        params.y = y;
        params.width = width;
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
        DasherTab view = new DasherTab(service, ui, this);
        DasherTab.Look look = restingLook();
        boolean right = onRight();
        view.setLook(look, right, tucksAway());
        int width = ui.dp(DasherTab.widthDp(look));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width,
                ui.dp(DasherTab.HEIGHT_DP), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | (scene == DasherScene.OFFER ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0),
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = right ? area.right - width : area.left;
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
        dragging = false;
        main.removeCallbacks(tuckAgain);
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
                Toast.makeText(service, "Open " + AppName.NAME + " to add a rule.", Toast.LENGTH_SHORT).show();
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
