package com.local.dasherfilter;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Notices when the user touches the screen while an automatic decline is in progress, so they can take over. It is
 * a one-pixel, invisible accessibility overlay that asks Android for a note of touches outside it: the touch itself
 * still goes to Dasher untouched. Our own taps are accessibility actions, not touches, so they never trigger it.
 */
final class TouchWatch {
    interface Listener {
        void touched();
    }

    private final AccessibilityService service;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
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
            // Handled after this event, since the listener removes this very window.
            if (action == MotionEvent.ACTION_OUTSIDE || action == MotionEvent.ACTION_DOWN) {
                handler.post(listener::touched);
            }
            return false;
        });
        try {
            windows.addView(watcher, params);
            view = watcher;
        } catch (RuntimeException refused) {
            DiagnosticLog.log(service, "accessibility",
                    "touch watch unavailable: " + refused.getClass().getSimpleName());
        }
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
