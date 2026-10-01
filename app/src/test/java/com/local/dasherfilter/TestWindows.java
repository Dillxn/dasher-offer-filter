package com.local.dasherfilter;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.robolectric.Shadows;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;

/** Full-screen fixtures include the window metadata used for the no-root pre-tap and notification checks. */
final class TestWindows {
    private static final AtomicInteger ids = new AtomicInteger(100);

    static void full(OfferFilterService service, AccessibilityNodeInfo root) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setId(ids.incrementAndGet());
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadow.setRoot(root);
        shadow.setActive(true);
        shadow.setBoundsInScreen(new Rect(0, 0, 1080, 2040));
        associate(root, window);
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
    }

    private static void associate(AccessibilityNodeInfo node, AccessibilityWindowInfo window) {
        if (node == null) return;
        Shadows.shadowOf(node).setAccessibilityWindowInfo(window);
        for (int i = 0; i < node.getChildCount(); i++) associate(node.getChild(i), window);
    }

    private TestWindows() {}
}
