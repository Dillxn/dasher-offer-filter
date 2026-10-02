package com.local.dasherfilter;

import android.app.AlertDialog;
import android.app.Dialog;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import java.util.List;

/**
 * Identifies touches delivered to this app's own windows. A dialog has its own Window.Callback, so the hosting
 * Activity never sees those touches. Only the event's time is passed on; no coordinates or events are retained.
 */
final class OwnWindowTouches {
    private OwnWindowTouches() {}

    /** For an Activity's dispatchTouchEvent, before it dispatches the event normally. */
    static void onTouch(MotionEvent event) {
        if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            OfferFilterService.ownScreenTouched(event.getEventTime());
        }
    }

    /** Installs tracking before the window can receive its first touch, without replacing show/dismiss listeners. */
    static AlertDialog show(AlertDialog.Builder builder) {
        AlertDialog dialog = track(builder.create());
        dialog.show();
        return dialog;
    }

    static <T extends Dialog> T track(T dialog) {
        Window window = dialog.getWindow();
        if (window == null) return dialog;
        Window.Callback callback = window.getCallback();
        if (callback != null && !(callback instanceof TrackedCallback)) {
            window.setCallback(new TrackedCallback(window, callback));
        }
        return dialog;
    }

    /** All behavior remains with the dialog, including Back, menus, accessibility and selection action modes. */
    private static final class TrackedCallback implements Window.Callback {
        private final Window window;
        private final Window.Callback delegate;

        TrackedCallback(Window window, Window.Callback delegate) {
            this.window = window;
            this.delegate = delegate;
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            // Outside taps can dismiss a dialog, but may belong to Dasher's half. Never claim them as our own.
            View decor = window.peekDecorView();
            if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN && decor != null
                    && decor.isShown() && event.getX() >= 0 && event.getX() < decor.getWidth()
                    && event.getY() >= 0 && event.getY() < decor.getHeight()) {
                onTouch(event);
            }
            return delegate.dispatchTouchEvent(event);
        }

        @Override public boolean dispatchKeyEvent(KeyEvent event) { return delegate.dispatchKeyEvent(event); }
        @Override public boolean dispatchKeyShortcutEvent(KeyEvent event) {
            return delegate.dispatchKeyShortcutEvent(event);
        }
        @Override public boolean dispatchTrackballEvent(MotionEvent event) {
            return delegate.dispatchTrackballEvent(event);
        }
        @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return delegate.dispatchGenericMotionEvent(event);
        }
        @Override public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
            return delegate.dispatchPopulateAccessibilityEvent(event);
        }
        @Override public View onCreatePanelView(int featureId) { return delegate.onCreatePanelView(featureId); }
        @Override public boolean onCreatePanelMenu(int featureId, Menu menu) {
            return delegate.onCreatePanelMenu(featureId, menu);
        }
        @Override public boolean onPreparePanel(int featureId, View view, Menu menu) {
            return delegate.onPreparePanel(featureId, view, menu);
        }
        @Override public boolean onMenuOpened(int featureId, Menu menu) {
            return delegate.onMenuOpened(featureId, menu);
        }
        @Override public boolean onMenuItemSelected(int featureId, MenuItem item) {
            return delegate.onMenuItemSelected(featureId, item);
        }
        @Override public void onWindowAttributesChanged(WindowManager.LayoutParams attributes) {
            delegate.onWindowAttributesChanged(attributes);
        }
        @Override public void onContentChanged() { delegate.onContentChanged(); }
        @Override public void onWindowFocusChanged(boolean focused) { delegate.onWindowFocusChanged(focused); }
        @Override public void onAttachedToWindow() { delegate.onAttachedToWindow(); }
        @Override public void onDetachedFromWindow() { delegate.onDetachedFromWindow(); }
        @Override public void onPanelClosed(int featureId, Menu menu) { delegate.onPanelClosed(featureId, menu); }
        @Override public boolean onSearchRequested() { return delegate.onSearchRequested(); }
        @Override public boolean onSearchRequested(SearchEvent event) { return delegate.onSearchRequested(event); }
        @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
            return delegate.onWindowStartingActionMode(callback);
        }
        @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
            return delegate.onWindowStartingActionMode(callback, type);
        }
        @Override public void onActionModeStarted(ActionMode mode) { delegate.onActionModeStarted(mode); }
        @Override public void onActionModeFinished(ActionMode mode) { delegate.onActionModeFinished(mode); }
        @Override public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> groups, Menu menu, int deviceId) {
            delegate.onProvideKeyboardShortcuts(groups, menu, deviceId);
        }
        @Override public void onPointerCaptureChanged(boolean captured) { delegate.onPointerCaptureChanged(captured); }
    }
}
