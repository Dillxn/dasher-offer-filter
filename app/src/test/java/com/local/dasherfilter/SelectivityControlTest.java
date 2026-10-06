package com.local.dasherfilter;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

import static org.junit.Assert.*;

/** Native selectivity, independent mode selection, and ownership of every input through window changes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class SelectivityControlTest extends AndroidAdapterTestBase {
    @org.junit.Rule public final VisibleActivityWindows visibleWindows = new VisibleActivityWindows();
    private static final FilterSettings RULES = new FilterSettings(true, 1000, 200, 30, 100, 3)
            .withPerItem(75).withAdaptive(true).withScoreByArea(true);

    @Test public void nativeRangeAndWrittenDirectionsAreSeparateFromMode() {
        try (Fixture page = new Fixture(RULES)) {
            AccessibilityNodeInfo node = page.slider.createAccessibilityNodeInfo();
            assertEquals(SeekBar.class.getName(), node.getClassName().toString());
            assertEquals(1f, node.getRangeInfo().getMin(), 0);
            assertEquals(200f, node.getRangeInfo().getMax(), 0);
            assertEquals(100f, node.getRangeInfo().getCurrent(), 0);
            assertTrue(page.slider.getHeight() >= page.ui.dp(48));
            assertTrue(page.slider.getWidth() >= page.ui.dp(48));
            String said = node.getContentDescription().toString();
            assertTrue(said.contains("Left: more offers, lower minimums"));
            assertTrue(said.contains("Right: more money goal, higher minimums"));
            assertTrue(said.contains("do not guarantee higher earnings"));
            assertNotNull(shownTextContaining(page.control, "More offers"));
            assertNotNull(shownTextContaining(page.control, "More money goal"));
            assertNotNull(shownTextContaining(page.control, "100%"));
            assertSame(page.control, page.star.createSelectivityControl());
            assertEquals(0, page.saves);
        }
    }

    @Test public void readerUsesOnePointStepsAndExactRangeWithoutEditingFloors() {
        try (Fixture page = new Fixture(RULES)) {
            assertEquals("positive fixture has a visible app window", View.VISIBLE, page.star.getWindowVisibility());
            assertTrue("host attached=" + page.star.isAttachedToWindow() + ", shown=" + page.star.isShown()
                            + ", window=" + page.star.getWindowVisibility() + "; slider attached="
                            + page.slider.isAttachedToWindow() + ", shown=" + page.slider.isShown()
                            + ", window=" + page.slider.getWindowVisibility(),
                    page.slider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
            assertEquals(101, page.rules.minimumScalePercent);
            assertTrue(page.slider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null));
            assertEquals(100, page.rules.minimumScalePercent);
            for (int percent : new int[] {1, 97, 200}) {
                assertTrue(setProgress(page.slider, percent));
                assertEquals(percent, page.rules.minimumScalePercent);
                assertEquals(percent, page.slider.getProgress());
                assertArrayEquals(RULES.minimums(), page.rules.minimums());
                assertEquals(RULES.maxStops, page.rules.maxStops);
                assertTrue(page.rules.scoreByArea);
                assertTrue(page.rules.risingOffers);
            }
            assertFalse(page.slider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
            assertTrue(setProgress(page.slider, 1));
            assertFalse(page.slider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null));
            int saved = page.saves;
            for (float invalid : new float[] {0, 201, Float.NaN, Float.POSITIVE_INFINITY}) {
                assertFalse(setProgress(page.slider, invalid));
            }
            assertEquals(saved, page.saves);
        }
    }

    @Test public void keyboardMovesOnePercentAndBothModesKeepTheirExistingFloors() {
        for (boolean area : new boolean[] {false, true}) {
            try (Fixture page = new Fixture(RULES.withScoreByArea(area))) {
                assertTrue(page.slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
                assertEquals(101, page.rules.minimumScalePercent);
                assertTrue(page.slider.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,
                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)));
                assertEquals(100, page.rules.minimumScalePercent);
                assertEquals(area, page.rules.scoreByArea);
                assertArrayEquals(RULES.minimums(), page.rules.minimums());
                assertEquals(2, page.saves);
            }
        }
    }

    @Test public void refreshUpdatesTheNativeControlWithoutCallingSave() {
        try (Fixture page = new Fixture(RULES)) {
            page.show(RULES.withMinimumScalePercent(137));
            assertEquals(137, page.slider.getProgress());
            assertNotNull(shownTextContaining(page.control, "137%"));
            assertEquals(0, page.saves);
            page.show(RULES.withMinimumScalePercent(1));
            assertEquals(1, page.slider.getProgress());
            assertEquals(0, page.saves);
        }
    }

    @Test public void aRejectedHostSaveRestoresTheSliderAndNeverAnnouncesSuccess() {
        try (Fixture page = new Fixture(RULES)) {
            page.rejectScaleSave = true;
            String said = page.star.lastSaid();
            assertFalse(setProgress(page.slider, 150));
            assertEquals(100, page.rules.minimumScalePercent);
            assertEquals(100, page.slider.getProgress());
            assertEquals(said, page.star.lastSaid());
            assertEquals(1, page.saves);
        }
    }

    @Test public void dragSavesOnlyOnReleaseAndCancellationRestoresTheSavedPercent() {
        try (Fixture page = new Fixture(RULES.withMinimumScalePercent(97))) {
            page.touch(MotionEvent.ACTION_DOWN, 97);
            page.touch(MotionEvent.ACTION_MOVE, 175);
            assertEquals(175, page.slider.getProgress());
            assertEquals(97, page.rules.minimumScalePercent);
            assertEquals(0, page.saves);
            page.touch(MotionEvent.ACTION_CANCEL, 175);
            assertEquals(97, page.slider.getProgress());
            assertEquals(0, page.saves);
            page.touch(MotionEvent.ACTION_DOWN, 97);
            page.touch(MotionEvent.ACTION_MOVE, 40);
            page.touch(MotionEvent.ACTION_UP, 40);
            assertEquals(40, page.rules.minimumScalePercent);
            assertEquals(1, page.saves);
            assertTrue(page.rules.scoreByArea);
            assertArrayEquals(RULES.minimums(), page.rules.minimums());
        }
    }

    @Test public void detachingDuringDragDoesNotSaveThePreview() {
        try (Fixture page = new Fixture(RULES)) {
            page.touch(MotionEvent.ACTION_DOWN, 100);
            page.touch(MotionEvent.ACTION_MOVE, 180);
            page.root.removeView(page.control);
            page.touch(MotionEvent.ACTION_UP, 180);
            assertEquals(100, page.rules.minimumScalePercent);
            assertEquals(100, page.slider.getProgress());
            assertEquals(0, page.saves);
        }
    }

    @Test @Config(qualifiers = "w320dp-h410dp-xxhdpi")
    public void narrowLayoutKeepsBothDirectionsReadableAndTheTrackAccessible() {
        try (Fixture page = new Fixture(RULES)) {
            assertTrue(page.slider.getHeight() >= page.ui.dp(48));
            assertTrue(page.slider.getWidth() > page.ui.dp(200));
            assertTextInside(page.control);
        }
    }

    @Test @Config(qualifiers = "w320dp-h410dp-xxhdpi")
    public void largeTextWrapsNaturallyRatherThanShrinkingOrClippingTheDirections() {
        RuntimeEnvironment.setFontScale(2f);
        try (Fixture page = new Fixture(RULES)) {
            assertTextInside(page.control);
            assertTrue(page.slider.getHeight() >= page.ui.dp(48));
        }
    }

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void shortPageOpensClearNativeControlsAndOnlyItsSeparateSwitchChangesMode() {
        FilterStore.save(app, RULES);
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        ServiceController<OfferNotificationService> listener = Robolectric.buildService(OfferNotificationService.class).create();
        service.get().onServiceConnected();
        listener.get().onListenerConnected();
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> controller = built.setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            assertNull("no extra row squeezes the compact picture", find(content, SeekBar.class));
            MinimumsStarView star = find(content, MinimumsStarView.class);
            if (star.beside()) { star.performClick(); settleSky(content); }
            AccessibilityNodeInfo entry = node(star, MinimumsStarView.SCORE_ID);
            assertEquals(android.widget.Button.class.getName(), entry.getClassName().toString());
            assertTrue(entry.getContentDescription().toString().contains("Minimums 100 percent"));
            assertFalse(entry.isCheckable());
            AlertDialog dialog = openCompact(star);
            assertTrue(FilterStore.load(app).scoreByArea);
            assertFalse(FilterStore.autoAcceptEnabled(app));
            SeekBar slider = find(dialog.getWindow().getDecorView(), SeekBar.class);
            assertTrue(slider.getHeight() >= new Ui(app).dp(48));
            assertNotNull(shownTextContaining(dialog.getWindow().getDecorView(), "More offers"));
            assertNotNull(shownTextContaining(dialog.getWindow().getDecorView(), "More money goal"));
            assertTrue(setProgress(slider, 97));
            Switch mode = find(dialog.getWindow().getDecorView(), Switch.class);
            assertTrue(mode.isChecked());
            mode.performClick();
            assertFalse(FilterStore.load(app).scoreByArea);
            assertEquals(97, FilterStore.load(app).minimumScalePercent);
            assertArrayEquals(RULES.minimums(), FilterStore.load(app).minimums());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(dialog.isShowing());
            dialog = openCompact(star);
            assertEquals(97, find(dialog.getWindow().getDecorView(), SeekBar.class).getProgress());
            assertFalse(find(dialog.getWindow().getDecorView(), Switch.class).isChecked());
            dialog.onBackPressed();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(dialog.isShowing());
            assertEquals(97, FilterStore.load(app).minimumScalePercent);
            assertFalse(FilterStore.autoAcceptEnabled(app));
            settleSky(content);
            ScenePage scene = find(content, ScenePage.class);
            assertTrue("core compact picture fits one screen: scene " + scene.getHeight()
                            + ", viewport " + ((View) scene.getParent()).getHeight() + ", sky " + star.getHeight(),
                    scene.getHeight() <= ((View) scene.getParent()).getHeight());
        } finally {
            listener.destroy();
            service.destroy();
        }
    }

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void dismissingCompactPanelDuringDragDiscardsPreviewAndReopensSavedValues() {
        FilterStore.save(app, RULES);
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> controller = built.setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            if (star.beside()) { star.performClick(); settleSky(content); }
            AlertDialog dialog = openCompact(star);
            SeekBar slider = find(dialog.getWindow().getDecorView(), SeekBar.class);
            sendTouch(slider, MotionEvent.ACTION_DOWN, 100);
            sendTouch(slider, MotionEvent.ACTION_MOVE, 180);
            assertEquals(100, FilterStore.load(app).minimumScalePercent);
            dialog.cancel();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            sendTouch(slider, MotionEvent.ACTION_UP, 180);
            assertEquals(100, FilterStore.load(app).minimumScalePercent);
            dialog = openCompact(star);
            assertEquals(100, find(dialog.getWindow().getDecorView(), SeekBar.class).getProgress());
            assertTrue(FilterStore.load(app).scoreByArea);
            assertFalse(FilterStore.autoAcceptEnabled(app));
            dialog.dismiss();
        }
    }

    @Test public void hiddenOrDetachedHostRejectsEverySliderInputAndNoRulesKeepsItsExistingScaleSemantics() {
        try (Fixture page = new Fixture(RULES)) {
            page.control.setVisibility(View.GONE);
            assertStaleSlider(page.slider);
            assertEquals(0, page.saves);
            page.control.setVisibility(View.VISIBLE);
            assertTrue(setProgress(page.slider, 97));
            page.touch(MotionEvent.ACTION_DOWN, 97);
            page.touch(MotionEvent.ACTION_MOVE, 180);
            page.star.setVisibility(View.GONE);
            page.star.setVisibility(View.VISIBLE);
            page.touch(MotionEvent.ACTION_UP, 180);
            assertEquals("showing the host cannot revive the old gesture", 97, page.rules.minimumScalePercent);
            page.root.removeView(page.star);
            assertStaleSlider(page.slider);
            assertEquals(97, page.rules.minimumScalePercent);
            assertEquals(1, page.saves);
        }
        try (Fixture page = new Fixture(new FilterSettings(false, 0, 0, 0, 0, 0))) {
            assertTrue(page.slider.isEnabled());
            assertTrue(setProgress(page.slider, 97));
            assertEquals(97, page.rules.minimumScalePercent);
            assertFalse(page.rules.hasAnyRule());
            assertFalse(page.rules.enabled);
            assertEquals(1, page.saves);
        }
    }

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void canceledPanelCannotActAfterReopenButItsFreshControlsStillWork() {
        FilterStore.save(app, RULES);
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> controller = built.setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            if (star.beside()) { star.performClick(); settleSky(content); }
            AlertDialog old = openCompact(star);
            SeekBar stale = find(old.getWindow().getDecorView(), SeekBar.class);
            Switch staleMode = find(old.getWindow().getDecorView(), Switch.class);
            sendTouch(stale, MotionEvent.ACTION_DOWN, 100);
            sendTouch(stale, MotionEvent.ACTION_MOVE, 180);
            old.cancel();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertStaleSlider(stale);
            staleMode.setChecked(false);
            assertTrue(FilterStore.load(app).scoreByArea);
            AlertDialog fresh = openCompact(star);
            SeekBar current = find(fresh.getWindow().getDecorView(), SeekBar.class);
            assertNotSame("each panel gets new native controls", stale, current);
            sendTouch(stale, MotionEvent.ACTION_UP, 180);
            assertStaleSlider(stale);
            staleMode.setChecked(true);
            staleMode.setChecked(false);
            assertEquals(100, FilterStore.load(app).minimumScalePercent);
            assertTrue(FilterStore.load(app).scoreByArea);
            assertTrue(setProgress(current, 97));
            find(fresh.getWindow().getDecorView(), Switch.class).performClick();
            assertFalse(FilterStore.load(app).scoreByArea);
            assertEquals(97, FilterStore.load(app).minimumScalePercent);
            assertFalse(FilterStore.autoAcceptEnabled(app));
            fresh.dismiss();
        }
    }

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void recreatingHostDismissesPanelCancelsDragAndRejectsItsOldInputs() {
        FilterStore.save(app, RULES);
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> controller = built.setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView oldHost = find(content, MinimumsStarView.class);
            if (oldHost.beside()) { oldHost.performClick(); settleSky(content); }
            android.view.accessibility.AccessibilityNodeProvider oldNodes = oldHost.getAccessibilityNodeProvider();
            AlertDialog old = openCompact(oldHost);
            SeekBar stale = find(old.getWindow().getDecorView(), SeekBar.class);
            Switch staleMode = find(old.getWindow().getDecorView(), Switch.class);
            sendTouch(stale, MotionEvent.ACTION_DOWN, 100);
            sendTouch(stale, MotionEvent.ACTION_MOVE, 180);
            controller.recreate();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse("host owns and dismisses its panel", old.isShowing());
            assertStaleSlider(stale);
            staleMode.setChecked(false);
            assertFalse(oldNodes.performAction(MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_CLICK, null));
            assertEquals(100, FilterStore.load(app).minimumScalePercent);
            assertTrue(FilterStore.load(app).scoreByArea);
            content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView host = find(content, MinimumsStarView.class);
            if (host.beside()) { host.performClick(); settleSky(content); }
            AlertDialog fresh = openCompact(host);
            SeekBar current = find(fresh.getWindow().getDecorView(), SeekBar.class);
            assertEquals(100, current.getProgress());
            assertTrue(setProgress(current, 97));
            fresh.dismiss();
        }
    }

    private static void assertStaleSlider(SeekBar slider) {
        assertFalse(slider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
        assertFalse(setProgress(slider, 150));
        assertFalse(slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
        sendTouch(slider, MotionEvent.ACTION_UP, 180);
    }

    @Test public void actualWindowVisibilityLossCancelsTheGestureAndBlocksAllInputs() {
        try (Fixture page = new Fixture(RULES)) {
            assertEquals(View.VISIBLE, page.star.getWindowVisibility());
            page.touch(MotionEvent.ACTION_DOWN, 100);
            page.touch(MotionEvent.ACTION_MOVE, 180);
            Object root = page.star.getRootView().getParent();
            assertNotNull(root);
            // Exercise the framework's real window-manager visibility callback, not a fake View.isShown override.
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(root, "handleAppVisibility",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, false));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotEquals(View.VISIBLE, page.star.getWindowVisibility());
            assertStaleSlider(page.slider);
            assertEquals(100, page.rules.minimumScalePercent);
            assertEquals(0, page.saves);
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(root, "handleAppVisibility",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, true));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(View.VISIBLE, page.star.getWindowVisibility());
            page.touch(MotionEvent.ACTION_UP, 180);
            assertEquals("restored window cannot revive the old gesture", 100, page.rules.minimumScalePercent);
            assertTrue(setProgress(page.slider, 97));
            assertEquals(97, page.rules.minimumScalePercent);
            assertEquals(1, page.saves);
        }
    }

    @Test public void stoppedActivityRejectsInlineInputsAndResumeRequiresAFreshGesture() {
        FilterStore.save(app, RULES);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            SeekBar slider = find(content, SeekBar.class);
            assertNotNull(slider);
            sendTouch(slider, MotionEvent.ACTION_DOWN, 100);
            sendTouch(slider, MotionEvent.ACTION_MOVE, 180);
            activity.pause().stop();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertStaleSlider(slider);
            assertEquals(100, FilterStore.load(app).minimumScalePercent);
            activity.start().resume().visible();
            settleSky(content);
            sendTouch(slider, MotionEvent.ACTION_UP, 180);
            assertEquals("resume cannot revive the stopped gesture", 100, FilterStore.load(app).minimumScalePercent);
            assertTrue(setProgress(slider, 97));
            assertEquals(97, FilterStore.load(app).minimumScalePercent);
        }
    }

    @Test public void adjacentTapWithoutNativeProgressCallbackStillSavesAndKeyboardStaysOneStep() {
        try (Fixture page = new Fixture(RULES.withMinimumScalePercent(74))) {
            // Android 8's bug is a tap with unchanged raw progress. Newer native sliders preserve thumb grab offsets.
            page.touch(MotionEvent.ACTION_DOWN, android.os.Build.VERSION.SDK_INT <= 27 ? 75 : 74);
            if (android.os.Build.VERSION.SDK_INT >= 28) page.touch(MotionEvent.ACTION_MOVE, 75);
            page.touch(MotionEvent.ACTION_UP, 75);
            assertEquals(75, page.rules.minimumScalePercent);
            assertTrue(page.slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                    new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
            assertEquals(76, page.rules.minimumScalePercent);
            page.touch(MotionEvent.ACTION_DOWN, 76);
            page.touch(MotionEvent.ACTION_MOVE, 200);
            page.touch(MotionEvent.ACTION_CANCEL, 200);
            assertEquals(76, page.rules.minimumScalePercent);
            assertEquals(76, page.slider.getProgress());
        }
    }

    @Test public void nativeTouchReachesBothExactEndpointsAndAdjacentValues() {
        try (Fixture page = new Fixture(RULES)) {
            for (int percent : new int[] {1, 2, 199, 200}) {
                page.touch(MotionEvent.ACTION_DOWN, page.rules.minimumScalePercent);
                page.touch(MotionEvent.ACTION_MOVE, percent);
                page.touch(MotionEvent.ACTION_UP, percent);
                assertEquals(percent, page.rules.minimumScalePercent);
                assertEquals(percent, page.slider.getProgress());
            }
        }
    }

    static AlertDialog openCompact(MinimumsStarView star) {
        assertTrue(act(star, MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_CLICK));
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // Attach the native window before reader input.
        View decor = dialog.getWindow().getDecorView();
        Ui ui = new Ui(star.getContext());
        decor.measure(View.MeasureSpec.makeMeasureSpec(star.getResources().getDisplayMetrics().widthPixels
                        - ui.dp(32), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(star.getResources().getDisplayMetrics().heightPixels
                        - ui.dp(32), View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
        assertTrue(dialog.getWindow().getCallback().getClass().getName().contains("OwnWindowTouches"));
        return dialog;
    }

    private static void sendTouch(SeekBar slider, int action, int percent) {
        float x = slider.getPaddingLeft()
                + (slider.getWidth() - slider.getPaddingLeft() - slider.getPaddingRight()) * (percent - 1) / 199f;
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(1, Math.max(1, now), action, x, slider.getHeight() / 2f, 0);
        slider.dispatchTouchEvent(event);
        event.recycle();
    }

    private static void assertTextInside(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            assertNotNull(text.getLayout());
            for (int line = 0; line < text.getLineCount(); line++) {
                assertEquals(0, text.getLayout().getEllipsisCount(line));
                assertTrue(text.getLayout().getLineWidth(line)
                        <= text.getWidth() - text.getPaddingLeft() - text.getPaddingRight() + 1);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) assertTextInside(group.getChildAt(i));
        }
    }

    private static boolean setProgress(SeekBar slider, float percent) {
        Bundle arguments = new Bundle();
        arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, percent);
        return slider.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, arguments);
    }

    static final class Fixture implements AutoCloseable {
        final ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        final Ui ui = new Ui(controller.get());
        final MinimumsStarView star = new MinimumsStarView(controller.get(), ui);
        final LinearLayout root = ui.column();
        final View control;
        final SeekBar slider;
        FilterSettings rules;
        int saves;
        boolean rejectScaleSave;
        long time = SystemClock.uptimeMillis();
        long gestureStarted;

        Fixture(FilterSettings rules) {
            this.rules = rules;
            star.setChanges(new MinimumsStarView.Changes() {
                @Override public void setMinimum(int axis, int cents) { fail("slider cannot edit individual floors"); }
                @Override public int[] adoptLearned() { fail("slider cannot adopt learning"); return null; }
                @Override public void restore(int[] cents) { fail("slider cannot undo floors"); }
                @Override public void setScoreByArea(boolean on) { fail("slider cannot toggle mode"); }
                @Override public void setMinimumScalePercent(int percent) {
                    saves++;
                    if (!rejectScaleSave) show(Fixture.this.rules.withMinimumScalePercent(percent));
                }
            });
            show(rules);
            control = star.createSelectivityControl();
            root.addView(control, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(star, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(200)));
            controller.get().setContentView(root);
            layOut(root);
            slider = find(control, SeekBar.class);
            assertNotNull(slider);
        }

        void show(FilterSettings rules) {
            this.rules = rules;
            star.show(rules, new OfferSnapshot(1200, 5.0, 20, 2), Collections.emptyList());
        }

        void touch(int action, int percent) {
            float x = slider.getPaddingLeft()
                    + (slider.getWidth() - slider.getPaddingLeft() - slider.getPaddingRight()) * (percent - 1) / 199f;
            if (action == MotionEvent.ACTION_DOWN) gestureStarted = time;
            MotionEvent event = MotionEvent.obtain(gestureStarted, time + 100, action, x, slider.getHeight() / 2f, 0);
            slider.dispatchTouchEvent(event);
            event.recycle();
            time += 100;
        }

        @Override public void close() { controller.close(); }
    }
}
