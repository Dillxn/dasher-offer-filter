package com.local.dasherfilter;

import android.view.InputDevice;
import android.view.MotionEvent;

/** Touches as Android reports them to Offer Filter's touch watch, a window above another app's. */
final class TestTouches {
    private TestTouches() {}

    /**
     * The user's finger landing at {@code at} (uptime), as Android tells a window of another app that watches outside
     * touches: the touchscreen's own device, a finger, the time it landed, and where it landed hidden.
     */
    static MotionEvent finger(long at) {
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
        pointer.id = 0;
        pointer.toolType = MotionEvent.TOOL_TYPE_FINGER;
        return MotionEvent.obtain(at, at, MotionEvent.ACTION_OUTSIDE, 1,
                new MotionEvent.PointerProperties[] {pointer},
                new MotionEvent.PointerCoords[] {new MotionEvent.PointerCoords()},
                0, 0, 1f, 1f, /* deviceId= */ 3, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    /**
     * Android's report of an accessibility click, as AccessibilityInteractionController builds it on Offer Filter's
     * main thread when that thread gets to it, at {@code at}: device 0, no tool type, (0, 0).
     */
    static MotionEvent clickReport(long at) {
        MotionEvent event = MotionEvent.obtain(at, at, MotionEvent.ACTION_OUTSIDE, 0, 0, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return event;
    }
}
