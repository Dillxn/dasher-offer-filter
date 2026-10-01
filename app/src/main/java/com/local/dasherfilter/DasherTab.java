package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Collections;
import java.util.List;

/**
 * The filter's own control inside Dasher: a small tab on the screen's edge with the mascot's face in a ring. Blue and
 * awake while auto-decline is on, amber and asleep while paused, grey with no rules yet. At rest about half of it is
 * tucked past the edge, the mascot peeking out, while its touch target stays 48 dp wide. During an offer or a
 * delivery only a slim bar in its colour shows, with a touch strip no wider than the margin Dasher keeps at its edge.
 * The user drags it up and down either edge or across to the other one; a drag is never a tap. Screen readers get
 * "Move up", "Move down" and "Move to other side".
 */
@SuppressLint("ViewConstructor")
final class DasherTab extends View {
    /** The whole tab, as it floats under a finger; at rest its touch target is this wide too. */
    static final int WIDTH_DP = 48;
    static final int HEIGHT_DP = 56;
    /** How much of the tab shows at rest: about half, the rest tucked past the screen's edge. */
    static final int REST_SHOWN_DP = 22;
    /** During an offer or a delivery: a slim peek at the edge, with a touch strip this wide along it. */
    static final int PEEK_SHOWN_DP = 6;
    static final int PEEK_TOUCH_DP = 16;

    /** How the tab shows. */
    enum Look {
        /** About half tucked past the edge. */
        REST,
        /** A slim peek at the edge. */
        PEEK,
        /** Whole, under a finger dragging it. */
        FLOATING
    }

    /** What the user does with the tab; on the main thread. */
    interface Listener {
        /** A tap: no drag came of the touch. */
        void tapped();

        void dragStarted();

        /** The finger moved this far (pixels) from where the drag began. */
        void dragged(float dx, float dy);

        void dropped();

        /** A screen reader's move. @return whether the tab moved */
        boolean move(Move how);
    }

    /** The screen reader's moves: a step up or down the edge, or across to the other edge. */
    enum Move { UP, DOWN, OTHER_SIDE }

    private final Ui ui;
    private final Listener listener;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    /** The whole tab, kept from gesture navigation's back swipe. */
    private final Rect exclusion = new Rect();
    private final List<Rect> exclusions = Collections.singletonList(exclusion);
    private final DashPathEffect dashed;
    private final int touchSlop;
    private FilterHeroView.State state = FilterHeroView.State.OFF;
    private Look look = Look.REST;
    private boolean right;
    /** Whether a tap on the peek brings the tab out (a delivery), or it stays tucked (an offer). */
    private boolean tapToShow;
    private boolean canMoveUp = true;
    private boolean canMoveDown = true;
    private float downX;
    private float downY;
    private boolean tracking;
    private boolean dragging;

    DasherTab(Context context, Ui ui, Listener listener) {
        super(context);
        this.ui = ui;
        this.listener = listener;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        dashed = new DashPathEffect(new float[] {ui.dp(4), ui.dp(3)}, 0);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        describe();
    }

    void show(FilterHeroView.State next) {
        if (next == state) return;
        state = next;
        describe();
        invalidate();
    }

    /** How it shows, on which edge, and whether a tap on the peek brings it out. */
    void setLook(Look next, boolean onRight, boolean peekTapShows) {
        if (next == look && onRight == right && peekTapShows == tapToShow) return;
        look = next;
        right = onRight;
        tapToShow = peekTapShows;
        describe();
        requestLayout();
        invalidate();
    }

    /** Which screen-reader moves make sense where it is now. */
    void setMoves(boolean up, boolean down) {
        canMoveUp = up;
        canMoveDown = down;
    }

    FilterHeroView.State state() {
        return state;
    }

    Look look() {
        return look;
    }

    boolean onRight() {
        return right;
    }

    /** The window's width for a look: the touch target, which may be wider than what shows. */
    static int widthDp(Look look) {
        return look == Look.PEEK ? PEEK_TOUCH_DP : WIDTH_DP;
    }

    /** How much of it shows from the edge at rest or peeking. */
    static int shownDp(Look look) {
        return look == Look.PEEK ? PEEK_SHOWN_DP : look == Look.REST ? REST_SHOWN_DP : WIDTH_DP;
    }

    private void describe() {
        String now = state == FilterHeroView.State.ON ? AppName.NAME + ": auto-decline on."
                : state == FilterHeroView.State.PAUSED ? AppName.NAME + ": paused." : AppName.NAME + ": no rules yet.";
        String tap = state == FilterHeroView.State.ON ? " Tap to pause."
                : state == FilterHeroView.State.PAUSED ? " Tap to resume." : " Tap to set them up.";
        setContentDescription(look != Look.PEEK ? now + tap : tapToShow ? now + " Tucked away. Tap to show it." : now);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(ui.dp(widthDp(look)), ui.dp(HEIGHT_DP));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int rightEdge, int bottom) {
        super.onLayout(changed, left, top, rightEdge, bottom);
        // Gesture navigation's back swipe starts at the screen's edge, where the tab is: a drag starting on the tab
        // is the tab's (Android allows a little of each edge to be kept like this).
        if (changed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exclusion.set(0, 0, rightEdge - left, bottom - top);
            setSystemGestureExclusionRects(exclusions);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        int color = state == FilterHeroView.State.ON ? ui.accent
                : state == FilterHeroView.State.PAUSED ? Ui.WARNING : ui.inkMuted;
        canvas.save();
        // Drawn for the left edge; mirrored on the right.
        if (right) canvas.scale(-1, 1, width / 2, height / 2);
        int border = ui.dark ? 0x40FFFFFF : 0x330B0B0B;
        line.setPathEffect(null);
        if (look == Look.PEEK) {
            // A slim bar in the state's colour along the edge.
            float shown = ui.dp(PEEK_SHOWN_DP);
            float inset = ui.dp(8);
            rect.set(-shown, inset, shown, height - inset);
            fill.setColor(color);
            canvas.drawRoundRect(rect, shown, shown, fill);
            canvas.restore();
            return;
        }
        float round;
        float cx;
        float radius;
        float face;
        if (look == Look.REST) {
            // Half a capsule out of the edge, the mascot peeking around it.
            float shown = ui.dp(REST_SHOWN_DP);
            round = shown;
            rect.set(-shown, 0, shown, height);
            cx = ui.dp(7);
            radius = ui.dp(12);
            face = ui.dp(12);
        } else {
            round = width / 2;
            rect.set(0, 0, width, height);
            cx = width / 2;
            radius = ui.dp(16);
            face = ui.dp(15);
        }
        fill.setColor(isPressed() ? ui.gridline : ui.surface);
        canvas.drawRoundRect(rect, round, round, fill);
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        line.setColor(border);
        canvas.drawRoundRect(rect, round, round, line);

        float cy = height / 2;
        line.setColor(color);
        line.setStrokeWidth(ui.dp(3));
        line.setPathEffect(state == FilterHeroView.State.PAUSED ? dashed : null);
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        if (state == FilterHeroView.State.OFF) canvas.drawCircle(cx, cy, radius, line);
        else canvas.drawArc(rect, 135, state == FilterHeroView.State.ON ? 320 : 240, false, line);
        line.setPathEffect(null);
        Mascot.face(canvas, Mascot.moodOf(state), cx, cy + ui.dp(1), face,
                state == FilterHeroView.State.OFF ? ui.inkMuted : ui.ink);
        canvas.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                tracking = true;
                dragging = false;
                setPressed(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return true;
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                    dragging = true;
                    setPressed(false);
                    listener.dragStarted();
                }
                if (dragging) listener.dragged(dx, dy);
                return true;
            case MotionEvent.ACTION_UP:
                setPressed(false);
                if (!tracking) return true;
                tracking = false;
                if (dragging) {
                    dragging = false;
                    listener.dropped();
                } else {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                tracking = false;
                if (dragging) {
                    dragging = false;
                    listener.dropped();
                }
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        listener.tapped();
        return true;
    }

    // The tab's own accessibility actions. Plain constants rather than R ids: build-local.sh compiles without a
    // generated R class. Custom action ids only need to differ from the standard ones.
    static final int ACTION_MOVE_UP = 0x7e000001;
    static final int ACTION_MOVE_DOWN = 0x7e000002;
    static final int ACTION_OTHER_SIDE = 0x7e000003;

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (canMoveUp) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_MOVE_UP, "Move up"));
        if (canMoveDown) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_MOVE_DOWN, "Move down"));
        }
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_OTHER_SIDE, "Move to other side"));
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == ACTION_MOVE_UP) return listener.move(Move.UP);
        if (action == ACTION_MOVE_DOWN) return listener.move(Move.DOWN);
        if (action == ACTION_OTHER_SIDE) return listener.move(Move.OTHER_SIDE);
        return super.performAccessibilityAction(action, arguments);
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }
}
