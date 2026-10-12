package com.local.dasherfilter;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

/** Real dialog measurement and coordinate dispatch in a resized viewport; not a physical IME/phone receipt. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36}, qualifiers = "w360dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class ReportOfferViewportTest extends AndroidAdapterTestBase {
    private ActivityController<Activity> activity;
    private FeedbackDialogs dialogs;
    @Before public void openScreen() {
        activity = Robolectric.buildActivity(Activity.class).setup();
        dialogs = new FeedbackDialogs(activity.get(), new Ui(activity.get()), () -> {});
        dialogs.start();
    }
    @After public void closeScreen() {
        dialogs.destroy(); activity.close(); Feedback.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private AlertDialog open() {
        DecisionLog.Entry entry = new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN,
                false, new OfferSnapshot(null, 10.3, null, null), 0, OfferRule.Result.REVIEW, "pay unread",
                DecisionLog.Action.NEEDS_REVIEW, true, Collections.singletonList("10.3 mi"));
        dialogs.reportOffer(entry);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }
    private Rect layout(AlertDialog dialog, int availableHeight) {
        View decor = dialog.getWindow().getDecorView();
        // Android ADJUST_RESIZE supplies a shorter viewport above IME/system insets. Exercise the actual layout
        // at that constraint, including nonzero system-bar insets, rather than calling Send.performClick().
        WindowInsets insets =
                new WindowInsets.Builder().setSystemWindowInsets(android.graphics.Insets.of(0, 8, 0, 12)).build();
        decor.dispatchApplyWindowInsets(insets);
        decor.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
        Button send = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        int[] button = new int[2], origin = new int[2];
        send.getLocationOnScreen(button); decor.getLocationOnScreen(origin);
        Rect bounds = new Rect(button[0] - origin[0], button[1] - origin[1],
                button[0] - origin[0] + send.getWidth(), button[1] - origin[1] + send.getHeight());
        assertTrue("Send needs a 48dp hit region: " + bounds, bounds.width() >= 48 && bounds.height() >= 48);
        assertTrue("Send must be wholly inside the resized viewport: " + bounds + " height=" + availableHeight,
                new Rect(0, 0, 360, availableHeight).contains(bounds));
        Rect visible = new Rect();
        assertTrue(send.getGlobalVisibleRect(visible));
        assertEquals("not clipped by a parent", send.getHeight(), visible.height());
        return bounds;
    }
    @Test public void shortSplitAndKeyboardKeepSendReachableWithOptionalNoteEmpty() throws Exception {
        AlertDialog dialog = open();
        EditText note = find(dialog.getWindow().getDecorView(), EditText.class);
        note.requestFocus();
        android.view.inputmethod.InputMethodManager keyboard = activity.get()
                .getSystemService(android.view.inputmethod.InputMethodManager.class);
        keyboard.showSoftInput(note, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        assertTrue("keyboard request active in the adapter", Shadows.shadowOf(keyboard).isSoftInputVisible());
        Rect send = layout(dialog, 240); // short split pane after the keyboard took its share
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                dialog.getWindow().getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
        assertEquals(0, FakeFeedbackTransport.installed().count());
        long at = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, send.centerX(), send.centerY(), 0);
        MotionEvent up = MotionEvent.obtain(at, at + 50, MotionEvent.ACTION_UP, send.centerX(), send.centerY(), 0);
        try {
            assertTrue(dialog.getWindow().getCallback().dispatchTouchEvent(down));
            assertTrue(dialog.getWindow().getCallback().dispatchTouchEvent(up));
        } finally { down.recycle(); up.recycle(); }
        Shadows.shadowOf(Looper.getMainLooper()).idle(); Feedback.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("a coordinate tap, with no required note, submits exactly once", 1,
                FakeFeedbackTransport.installed().count());
        assertTrue(FakeFeedbackTransport.installed().requests().get(0).getBoolean("diagnosticsConsented"));
    }
    @Test public void disclosureAndPreviewScrollWhileSendStaysOutsideTheBody() {
        AlertDialog dialog = open();
        View decor = dialog.getWindow().getDecorView();
        TextView disclosure = findText(decor, FeedbackDialogs.OFFER_REPORT_SAYS);
        assertNotNull(disclosure);
        View ancestor = disclosure;
        while (!(ancestor instanceof ScrollView) && ancestor.getParent() instanceof View) {
            ancestor = (View) ancestor.getParent();
        }
        assertTrue(ancestor instanceof ScrollView);
        ScrollView body = (ScrollView) ancestor;
        assertTrue("the entire unchanged disclosure belongs to the scrolling body", isDescendant(body, disclosure));
        assertTrue(isDescendant(body, shownButton(decor, "Preview what is sent")));
        assertFalse(isDescendant(body, dialog.getButton(AlertDialog.BUTTON_POSITIVE)));
        Rect original = layout(dialog, 320);
        body.fullScroll(View.FOCUS_DOWN);
        assertEquals(original, layout(dialog, 320));
        layout(dialog, 240);
        layout(dialog, 460); // divider/keyboard leaves more room again, with no new submission
        assertEquals(0, FakeFeedbackTransport.installed().count());
    }
}
