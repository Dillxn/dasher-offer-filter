package com.local.dasherfilter;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Notices when the user touches the screen while an automatic decline is in progress, so they can take over. It is
 * a one-pixel, invisible accessibility overlay that asks Android for a note of touches outside it: the touch itself
 * still goes to Dasher untouched. Our own taps are accessibility actions, not touches, but Android reports each one to
 * the watch as a touch outside it ({@link #madeUp}), so each touch is passed on with its time and whether Android made
 * it up, and the listener tells the two apart.
 * It is a view, so it is started, stopped and told of touches on the main thread only.
 */
final class TouchWatch {
    interface Listener {
        /**
         * On the main thread, the moment the touch is delivered, so an automatic tap already under way on another
         * thread can still be stopped. Must not remove this watch itself: the touch is still being delivered to it.
         *
         * @param at when the touch landed ({@link android.os.SystemClock#uptimeMillis} time base), as Android stamped
         *     it: the listener tells the app's own tap echoing back from the user's finger by it
         * @param madeUp Android made the touch up for an accessibility click ({@link #madeUp}), not a finger: its
         *     time is when this app's main thread got to it, however long after the click
         */
        void touched(long at, boolean madeUp);
    }

    private final AccessibilityService service;
    private final Listener listener;
    private View view;

    TouchWatch(AccessibilityService service, Listener listener) {
        this.service = service;
        this.listener = listener;
    }

    boolean isWatching() {
        return view != null;
    }

    void start() {
        if (view != null) return;
        WindowManager windows = service.getSystemService(WindowManager.class);
        if (windows == null) return;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        View watcher = new View(service);
        watcher.setOnTouchListener((touched, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_OUTSIDE || action == MotionEvent.ACTION_DOWN) {
                listener.touched(event.getEventTime(), madeUp(event));
            }
            return false;
        });
        try {
            windows.addView(watcher, params);
            view = watcher;
            DiagnosticLog.logOnChange(service, "accessibility", "touch-watch", "touch watch available");
        } catch (RuntimeException refused) {
            DiagnosticLog.logOnChange(service, "accessibility", "touch-watch",
                    "touch watch unavailable: " + refused.getClass().getSimpleName());
        }
    }

    /**
     * Whether Android made this touch up for an accessibility click rather than a finger. Since Android 10, a click an
     * accessibility service asks for (ACTION_CLICK or ACTION_LONG_CLICK) is told to each window above the clicked one
     * that watches outside touches, as an ACTION_OUTSIDE that window's own main thread builds when it gets to it
     * (AccessibilityInteractionController.notifyOutsideTouchUiThread): device 0, no tool type, stamped at that moment,
     * so a busy main thread stamps it hundreds of milliseconds after the click. A finger's touch comes from the
     * touchscreen, a device of its own, with a finger or stylus tool and the time it landed (another app's window
     * only hides where).
     */
    static boolean madeUp(MotionEvent event) {
        return event.getActionMasked() == MotionEvent.ACTION_OUTSIDE && event.getPointerCount() == 1
                && event.getDeviceId() == 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_UNKNOWN;
    }

    void stop() {
        if (view == null) return;
        View watcher = view;
        view = null;
        try {
            service.getSystemService(WindowManager.class).removeViewImmediate(watcher);
        } catch (RuntimeException alreadyGone) {
            // The window was removed with the service.
        }
    }
}
