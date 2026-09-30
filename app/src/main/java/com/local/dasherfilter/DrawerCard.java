package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * A drawer's card that a downward drag closes, as a drawer should: from its handle or edges at any time, and from
 * its content once that is scrolled to the top. It follows the finger, the dimmed page behind it fades as it goes,
 * and on release it closes if pulled past a quarter of its height or flicked down, else springs back. Screen readers
 * get a Close action.
 */
@SuppressLint("ViewConstructor")
final class DrawerCard extends LinearLayout {
    interface Listener {
        void dismissed();
    }

    /** Pulled this share of its own height, it closes on release. */
    static final float CLOSE_SHARE = 0.25f;
    /** Flicked down at least this fast (dp a second), it closes on release. */
    static final int CLOSE_SPEED_DP = 900;

    private final Ui ui;
    private final int touchSlop;
    private ScrollView content;
    private View scrim;
    private Listener listener;
    private VelocityTracker velocity;
    private float downY;
    private boolean dragging;

    DrawerCard(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        setOrientation(VERTICAL);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** The scrolling content (a drag inside it closes only from the top), the dimmed page, and who closes it. */
    void setUp(ScrollView content, View scrim, Listener listener) {
        this.content = content;
        this.scrim = scrim;
        this.listener = listener;
    }

    /** Back in place, fully dimmed behind: for each opening. */
    void settle() {
        animate().cancel();
        setTranslationY(0);
        dim(0);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(event);
                return false;
            case MotionEvent.ACTION_MOVE:
                track(event);
                float pulled = event.getRawY() - downY;
                boolean atTop = content == null || !content.canScrollVertically(-1);
                if (pulled > touchSlop && atTop) {
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(event);
                return true;
            case MotionEvent.ACTION_MOVE: {
                track(event);
                float pulled = event.getRawY() - downY;
                if (!dragging && pulled > touchSlop) dragging = true;
                if (dragging) {
                    float y = Math.max(0, pulled);
                    setTranslationY(y);
                    dim(y);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                track(event);
                if (!dragging) {
                    performClick();
                } else {
                    release(event.getRawY() - downY);
                }
                end();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if (dragging) spring();
                end();
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        // A tap on the card stays on it: only the dimmed page around it closes the drawer.
        return super.performClick();
    }

    private void begin(MotionEvent event) {
        downY = event.getRawY();
        dragging = false;
        if (velocity != null) velocity.recycle();
        velocity = VelocityTracker.obtain();
        track(event);
    }

    private void track(MotionEvent event) {
        if (velocity == null) return;
        // Raw coordinates, so the card moving under the finger does not skew the speed.
        MotionEvent raw = MotionEvent.obtain(event);
        raw.setLocation(event.getRawX(), event.getRawY());
        velocity.addMovement(raw);
        raw.recycle();
    }

    private void end() {
        dragging = false;
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    private void release(float pulled) {
        float speed = 0;
        if (velocity != null) {
            velocity.computeCurrentVelocity(1000);
            speed = velocity.getYVelocity();
        }
        boolean far = pulled > getHeight() * CLOSE_SHARE;
        boolean flicked = speed > ui.dp(CLOSE_SPEED_DP) && pulled > touchSlop;
        if (far || flicked) close();
        else spring();
    }

    /** Slides the rest of the way down, then closes. */
    void close() {
        float to = Math.max(getHeight(), 1);
        animate().translationY(to).setDuration(160).setUpdateListener(animation -> dim(getTranslationY()))
                .withEndAction(() -> {
                    animate().setUpdateListener(null);
                    if (listener != null) listener.dismissed();
                    settle();
                });
    }

    private void spring() {
        animate().translationY(0).setDuration(180).setUpdateListener(animation -> dim(getTranslationY()))
                .withEndAction(() -> animate().setUpdateListener(null));
    }

    private void dim(float pulled) {
        if (scrim == null || scrim.getBackground() == null) return;
        float share = getHeight() > 0 ? Math.min(1, pulled / getHeight()) : 0;
        scrim.getBackground().setAlpha(Math.round(255 * (1 - share)));
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, "Close"));
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_DISMISS) {
            if (listener != null) listener.dismissed();
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
}
