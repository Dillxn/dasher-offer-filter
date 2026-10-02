package com.local.dasherfilter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowWindowManagerImpl;

import static org.junit.Assert.*;

/** Real own-window dispatch must resolve split touches without weakening takeover on Dasher's half. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class OwnWindowTouchesTest extends AndroidAdapterTestBase {
    private ServiceController<OfferFilterService> service;
    private ActivityController<? extends Activity> activity;
    private Dialog dialog;

    @After public void closeWindows() {
        if (dialog != null) dialog.dismiss();
        if (activity != null) activity.pause().stop().destroy();
        if (service != null) service.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.forgetScreenState();
    }

    @Test public void dialogTouchLetsSplitDeclineContinueWhenDialogHearsFirst() {
        dialogTouchContinues(false);
    }

    @Test public void dialogTouchReleasesPendingConfirmationWhenWatchHearsFirst() {
        dialogTouchContinues(true);
    }

    private void dialogTouchContinues(boolean watchFirst) {
        start(false);
        showOffer(true);
        showDialog();
        long at = SystemClock.uptimeMillis();
        int[] confirms = {0};
        if (watchFirst) {
            watchHears(at);
            showQuestion(confirms);
            assertEquals("confirmation waits until the touch is located", 0, confirms[0]);
        }
        dialogTouch(at, MotionEvent.ACTION_DOWN, 20, 20);
        if (!watchFirst) {
            watchHears(at);
            showQuestion(confirms);
        }
        idle();
        assertEquals("a touch delivered inside our dialog was not on Dasher; action=" + latestAction()
                + "\n" + DiagnosticLog.read(app), 1, confirms[0]);
        assertNotEquals(DecisionLog.Action.USER_TOOK_OVER, latestAction());
    }

    @Test public void legalScreenTouchReleasesPendingSplitConfirmation() {
        start(true);
        showOffer(true);
        long at = SystemClock.uptimeMillis();
        watchHears(at);
        int[] confirms = {0};
        showQuestion(confirms);
        assertEquals(0, confirms[0]);
        MotionEvent down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, 20, 100, 0);
        MotionEvent cancel = MotionEvent.obtain(at, at, MotionEvent.ACTION_CANCEL, 20, 100, 0);
        try {
            activity.get().dispatchTouchEvent(down);
            activity.get().dispatchTouchEvent(cancel);
        } finally {
            down.recycle();
            cancel.recycle();
        }
        idle();
        assertEquals(1, confirms[0]);
        assertNotEquals(DecisionLog.Action.USER_TOOK_OVER, latestAction());
    }

    @Test public void outsideDialogEventDoesNotClaimATouchOnDasher() {
        outsideDialogTouchHandsBack(MotionEvent.ACTION_OUTSIDE, 20, 20);
    }

    @Test public void downOutsideDialogBoundsDoesNotClaimATouchOnDasher() {
        outsideDialogTouchHandsBack(MotionEvent.ACTION_DOWN, -20, 20);
    }

    private void outsideDialogTouchHandsBack(int action, float x, float y) {
        start(false);
        showOffer(true);
        showDialog();
        long at = SystemClock.uptimeMillis();
        dialogTouch(at, action, x, y);
        watchHears(at);
        int[] confirms = {0};
        showQuestion(confirms);
        pass(OfferFilterService.OWN_HALF_WAIT_MS);
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, latestAction());
        assertEquals("an outside touch remains the user's authority", 0, confirms[0]);
    }

    @Test public void ownDialogTouchCannotOverrideFullScreenDasherTakeover() {
        start(false);
        showOffer(false);
        showDialog();
        long at = SystemClock.uptimeMillis();
        dialogTouch(at, MotionEvent.ACTION_DOWN, 20, 20);
        watchHears(at);
        assertEquals("full-screen Dasher keeps the conservative touch rule",
                DecisionLog.Action.USER_TOOK_OVER, latestAction());
    }

    @Test public void trackingPreservesDialogDispatchAndExistingLifecycleListeners() {
        activity = Robolectric.buildActivity(Activity.class).setup();
        List<String> calls = new ArrayList<>();
        Dialog recording = new Dialog(activity.get()) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) { calls.add("touch"); return false; }
            @Override public boolean dispatchKeyEvent(KeyEvent event) { calls.add("key"); return true; }
            @Override public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
                calls.add("accessibility"); return true;
            }
        };
        dialog = recording;
        recording.setOnShowListener(ignored -> calls.add("show"));
        recording.setOnDismissListener(ignored -> calls.add("dismiss"));
        OwnWindowTouches.track(recording);
        Window.Callback tracked = recording.getWindow().getCallback();
        assertSame(recording, OwnWindowTouches.track(recording));
        assertSame("tracking twice must not wrap or replace its delegate", tracked, recording.getWindow().getCallback());
        recording.show();
        idle();
        assertTrue(calls.contains("show"));
        MotionEvent move = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 20, 20, 0);
        try {
            assertFalse("touch handling result is still the dialog's", tracked.dispatchTouchEvent(move));
            assertEquals(MotionEvent.ACTION_MOVE, move.getAction());
        } finally {
            move.recycle();
        }
        assertTrue(tracked.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)));
        AccessibilityEvent announcement = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        try { assertTrue(tracked.dispatchPopulateAccessibilityEvent(announcement)); }
        finally { announcement.recycle(); }
        recording.dismiss();
        idle();
        assertEquals(1, java.util.Collections.frequency(calls, "touch"));
        assertEquals(1, java.util.Collections.frequency(calls, "key"));
        assertTrue(calls.contains("accessibility"));
        assertEquals(1, java.util.Collections.frequency(calls, "dismiss"));
    }

    private void start(boolean legal) {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DecisionLog.clear(app);
        service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        idle();
        activity = legal ? Robolectric.buildActivity(LegalActivity.class,
                LegalActivity.intent(app, LegalTexts.Doc.TERMS)).setup()
                : Robolectric.buildActivity(Activity.class).setup();
        layOut(activity.get().findViewById(android.R.id.content));
        idle();
    }

    private void showDialog() {
        dialog = OwnWindowTouches.show(new AlertDialog.Builder(activity.get()).setTitle("Own dialog")
                .setMessage("Synthetic touch test").setPositiveButton("Done", null));
        // Finish the framework's first layout before giving the synthetic window its measured bounds. Otherwise
        // API 26 can replace those bounds during the watch-first idle, before the dialog receives its touch.
        idle();
        View decor = dialog.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 600, 500);
        assertTrue(decor.isShown());
    }

    private void dialogTouch(long at, int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN && x >= 0 && y >= 0) {
            View decor = dialog.getWindow().getDecorView();
            assertTrue("synthetic own touch must land inside the visible dialog", decor.isShown()
                    && x < decor.getWidth() && y < decor.getHeight());
        }
        MotionEvent event = MotionEvent.obtain(at, at, action, x, y, 0);
        try { dialog.getWindow().getCallback().dispatchTouchEvent(event); }
        finally { event.recycle(); }
        idle();
    }

    private void showOffer(boolean split) {
        AccessibilityNodeInfo root = node("com.doordash.driverapp", null, false);
        for (String label : new String[] {"Decline", "$7.90", "incl. tips", "2 stops (7.2 mi) • 21 min", "Accept", "0:35"}) {
            Shadows.shadowOf(root).addChild(node("com.doordash.driverapp", label,
                    label.equals("Decline") || label.equals("Accept")));
        }
        if (split) splitShows(root);
        else {
            TestWindows.full(service.get(), root);
            changed();
        }
        assertEquals(DecisionLog.Action.DECLINE_TAPPED, latestAction());
        pass(300); // Past the app's own first-tap echo window.
    }

    private void showQuestion(int[] confirms) {
        AccessibilityNodeInfo root = node("com.doordash.driverapp", null, false);
        Shadows.shadowOf(root).addChild(node("com.doordash.driverapp", "Are you sure you want to decline this offer?", false));
        AccessibilityNodeInfo decline = node("com.doordash.driverapp", "Decline offer", true);
        Shadows.shadowOf(decline).setOnPerformActionListener((action, args) -> { confirms[0]++; return true; });
        Shadows.shadowOf(root).addChild(decline);
        splitShows(root);
    }

    private void splitShows(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo own = node("com.local.dasherfilter", null, false);
        Shadows.shadowOf(service.get()).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, own, true, new Rect(0, 0, 1080, 1000)),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, new Rect(0, 1000, 1080, 1040)),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, root, false, new Rect(0, 1040, 1080, 2040))));
        Shadows.shadowOf(service.get()).setRootInActiveWindow(own);
        changed();
    }

    private void changed() {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        service.get().onAccessibilityEvent(event);
        idle();
        event.recycle();
    }

    private AccessibilityNodeInfo node(String pkg, String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName(pkg);
        node.setText(text);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        node.setClickable(clickable);
        if (clickable) {
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(node).setOnPerformActionListener((action, args) -> true);
        }
        return node;
    }

    private static AccessibilityWindowInfo window(int type, AccessibilityNodeInfo root, boolean active, Rect bounds) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(type);
        shadow.setActive(active);
        shadow.setBoundsInScreen(bounds);
        if (root != null) shadow.setRoot(root);
        return window;
    }

    private void watchHears(long at) {
        ShadowWindowManagerImpl manager = Shadow.extract(app.getSystemService(WindowManager.class));
        List<View> watches = new ArrayList<>();
        for (View view : manager.getViews()) if (view.getClass() == View.class) watches.add(view);
        assertEquals(1, watches.size());
        MotionEvent event = MotionEvent.obtain(at, at, MotionEvent.ACTION_OUTSIDE, 0, 0, 0);
        try { watches.get(0).dispatchTouchEvent(event); }
        finally { event.recycle(); }
        idle();
    }

    private DecisionLog.Action latestAction() { return DecisionLog.recent(app, 1).get(0).action; }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static void pass(long ms) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
}
