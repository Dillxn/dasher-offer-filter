package com.local.dasherfilter;

import android.content.Context;
import android.view.View;
import android.widget.Switch;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Peek's one Settings row (the user: "peek mode should have a toggle and be on by default"): a switch, on for a phone
 * that never stored it (an existing install updating, or a new one), saved at once and kept.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class PeekSettingsTest extends AndroidAdapterTestBase {
    private static final String ROW = "Peek at background offers";

    private Switch peekSwitch(ActivityController<MainActivity> activity) {
        View content = activity.get().findViewById(android.R.id.content);
        iconButton(content, "Settings").performClick();
        settle();
        View row = findButton(content, ROW);
        assertNotNull("a Settings row", row);
        assertTrue("a switch", row instanceof Switch);
        assertTrue(row.isShown());
        return (Switch) row;
    }

    private boolean stored() {
        return app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE).getBoolean("peek_background_offers",
                true);
    }

    @Test
    public void peekIsOneSettingsSwitchOnByDefaultSavedAtOnceAndKept() {
        assertFalse("nothing stored, as on a phone updating from an older version",
                app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE).contains("peek_background_offers"));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            Switch peek = peekSwitch(activity);
            assertTrue("on by default", peek.isChecked());
            peek.performClick();
            assertFalse(peek.isChecked());
            assertFalse("saved at once", stored());
        }
        assertTrue(DiagnosticLog.read(app).contains("[peek] turned off in Settings"));
        try (ActivityController<MainActivity> again = Robolectric.buildActivity(MainActivity.class).setup()) {
            Switch peek = peekSwitch(again);
            assertFalse("kept", peek.isChecked());
            peek.performClick();
            assertTrue(peek.isChecked());
            assertTrue(stored());
        }
    }
}
