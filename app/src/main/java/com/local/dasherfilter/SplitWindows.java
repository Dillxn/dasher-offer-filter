package com.local.dasherfilter;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import java.util.function.Function;

/**
 * Split screen as Android's list of windows shows it: whether Dasher's half is really in view beside the app the user
 * is in, and one compact field for the log saying where Dasher was ({@link #field}). Reads only what Android listed
 * (kinds, bounds, which is active); it asks no app anything, so any thread may use it.
 */
final class SplitWindows {
    /** Whose a listed application window is, as far as is known. */
    enum Owner { DASHER, OURS, OTHER }

    /**
     * A system window (the shade, the power menu, a system dialog) over this share of Dasher's half or more hides it.
     * The status and navigation bars, a volume panel or a notification popping up over its edge cover far less.
     */
    static final float SYSTEM_COVER_SHARE = 0.5f;

    /**
     * Why Dasher's half, beside the active window of another app, is not really in view, or null when it is: its
     * bounds are known, the active window does not overlap it (so it is the other half, not recent apps, a pop-up or a
     * window floating over Dasher), no keyboard covers any of it and no system window covers
     * {@link #SYSTEM_COVER_SHARE} of it or more.
     *
     * @param dasher Dasher's window in its half
     * @param activeOther the active window, another app's
     */
    static String covered(List<AccessibilityWindowInfo> windows, AccessibilityWindowInfo dasher,
                          AccessibilityWindowInfo activeOther) {
        Rect half = new Rect();
        dasher.getBoundsInScreen(half);
        if (half.isEmpty()) return "its bounds are not known";
        Rect other = new Rect();
        if (activeOther != null) activeOther.getBoundsInScreen(other);
        if (Rect.intersects(half, other)) return "the active app's window overlaps it";
        Rect window = new Rect();
        long area = (long) half.width() * half.height();
        for (AccessibilityWindowInfo listed : windows) {
            int type = listed.getType();
            if (type != AccessibilityWindowInfo.TYPE_INPUT_METHOD && type != AccessibilityWindowInfo.TYPE_SYSTEM) {
                continue;
            }
            listed.getBoundsInScreen(window);
            if (!window.intersect(half)) continue;
            if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return "the keyboard covers it";
            if ((long) window.width() * window.height() >= area * SYSTEM_COVER_SHARE) {
                return "a system window covers it";
            }
        }
        return null;
    }

    /**
     * One compact field for a log line: {@code win=<layout>/<edge>/<active>/<share>}, such as
     * {@code win=split/bottom/ours/48}, {@code win=full/-/dasher/100} or {@code win=hidden/-/system/0}.
     * <ul>
     *   <li>layout: {@code split} (Android's split-screen divider is listed), {@code full} (Dasher's window is the
     *       active one, not split) or {@code hidden} (neither: another app, the shade or the lock screen in front);
     *   <li>edge: where Dasher's half is when split ({@code top}, {@code bottom}, {@code left}, {@code right}), else
     *       {@code -};
     *   <li>active: whose window is active ({@code dasher}, {@code ours}, {@code other}, {@code system} for the
     *       shade, a keyboard or any other system window, {@code none});
     *   <li>share: how much of the display Dasher's window takes, in percent of its height (of its width when the
     *       halves are side by side); 0 when hidden.
     * </ul>
     *
     * @param owner whose each listed application window is
     * @param display the display's bounds (widened to every listed window)
     */
    static String field(List<AccessibilityWindowInfo> windows, Function<AccessibilityWindowInfo, Owner> owner,
                        Rect display) {
        Rect screen = new Rect(display);
        Rect bounds = new Rect();
        boolean split = false;
        AccessibilityWindowInfo dasher = null;
        long dasherArea = -1;
        String active = "none";
        boolean dasherActive = false;
        for (AccessibilityWindowInfo window : windows) {
            int type = window.getType();
            window.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) screen.union(bounds);
            if (type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) split = true;
            Owner whose = type == AccessibilityWindowInfo.TYPE_APPLICATION ? owner.apply(window) : null;
            if (whose == Owner.DASHER) {
                long area = (long) bounds.width() * bounds.height();
                // Dasher's own dialogs are windows too: its half is the biggest of them.
                if (area > dasherArea) {
                    dasher = window;
                    dasherArea = area;
                }
            }
            if (!window.isActive() || !"none".equals(active)) continue;
            if (type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                active = whose == Owner.DASHER ? "dasher" : whose == Owner.OURS ? "ours" : "other";
                dasherActive = whose == Owner.DASHER;
            } else if (type == AccessibilityWindowInfo.TYPE_SYSTEM || type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                active = "system";
            }
        }
        String layout = split ? "split" : dasherActive ? "full" : "hidden";
        String edge = "-";
        int share = 0;
        if (dasher != null && !"hidden".equals(layout) && screen.width() > 0 && screen.height() > 0) {
            dasher.getBoundsInScreen(bounds);
            boolean sideBySide = false;
            if (split) {
                float dx = (bounds.exactCenterX() - screen.exactCenterX()) / screen.width();
                float dy = (bounds.exactCenterY() - screen.exactCenterY()) / screen.height();
                sideBySide = Math.abs(dx) > Math.abs(dy);
                edge = sideBySide ? (dx < 0 ? "left" : "right") : (dy < 0 ? "top" : "bottom");
            }
            share = Math.round(100f * (sideBySide ? bounds.width() / (float) screen.width()
                    : bounds.height() / (float) screen.height()));
            share = Math.max(0, Math.min(100, share));
        }
        return "win=" + layout + "/" + edge + "/" + active + "/" + share;
    }

    private SplitWindows() {}
}
