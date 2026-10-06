package com.local.dasherfilter;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

/** Optional Android 8.1 regression: its framework shares Android 8.0's minimum-offset defects. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class SelectivityAndroid81Test extends AndroidAdapterTestBase {
    @Test public void minimumRangeTouchEndpointsAdjacentTapKeyboardAndCancelRemainExact() {
        FilterSettings rules = new FilterSettings(true, 1000, 200, 30, 100, 3)
                .withScoreByArea(true).withMinimumScalePercent(74);
        try (SelectivityControlTest.Fixture page = new SelectivityControlTest.Fixture(rules)) {
            AccessibilityNodeInfo.RangeInfo range = page.slider.createAccessibilityNodeInfo().getRangeInfo();
            assertEquals(1, range.getMin(), 0);
            assertEquals(200, range.getMax(), 0);
            assertEquals(74, range.getCurrent(), 0);
            page.touch(MotionEvent.ACTION_DOWN, 75);
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
            for (int percent : new int[] {1, 2, 199, 200}) {
                page.touch(MotionEvent.ACTION_DOWN, page.rules.minimumScalePercent);
                page.touch(MotionEvent.ACTION_MOVE, percent);
                page.touch(MotionEvent.ACTION_UP, percent);
                assertEquals(percent, page.rules.minimumScalePercent);
                assertEquals(percent, page.slider.getProgress());
            }
            Bundle value = new Bundle();
            value.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 1);
            assertTrue(page.slider.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, value));
            assertEquals(1, page.rules.minimumScalePercent);
            assertArrayEquals(rules.minimums(), page.rules.minimums());
            assertTrue(page.rules.scoreByArea);
        }
    }
}
