package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

/**
 * BETA-21: small text meets WCAG AA (4.5:1). The muted ink ("Read:" lines, disabled rows, captions) on the light page,
 * cards and day sky; and the word a Fix line's tap does, on both skies and both pages.
 */
@RunWith(RobolectricTestRunner.class)
public class ContrastTest extends AndroidAdapterTestBase {
    /** ScenePage's sky gradient, top to bottom (day, night); its top is ScenePage.skyTop. */
    private static final int[] DAY_SKY = {0xFFD4E4F4, 0xFFE7EEF2, 0xFFF6E7D2};
    private static final int[] NIGHT_SKY = {0xFF070B16, 0xFF0D1428, 0xFF1C1B34};

    static double luminance(int color) {
        double[] channels = {Color.red(color), Color.green(color), Color.blue(color)};
        double sum = 0;
        double[] weights = {0.2126, 0.7152, 0.0722};
        for (int i = 0; i < 3; i++) {
            double c = channels[i] / 255.0;
            c = c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
            sum += weights[i] * c;
        }
        return sum;
    }

    static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private Ui ui(boolean night) {
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        Context themed = app.createConfigurationContext(config);
        Ui ui = new Ui(themed);
        assertEquals(night, ui.dark);
        return ui;
    }

    private static void atLeast(String what, int ink, int background) {
        double ratio = contrast(ink, background);
        assertTrue(String.format("%s: %.2f:1 (%08X on %08X)", what, ratio, ink, background), ratio >= 4.5);
    }

    @Test public void mutedInkReadsOnEveryLightAndDarkSurface() {
        Ui day = ui(false);
        atLeast("muted on the light page", day.inkMuted, day.page);
        atLeast("muted on a light card", day.inkMuted, day.surface);
        for (int sky : DAY_SKY) atLeast("muted on the day sky", day.inkMuted, sky);
        Ui night = ui(true);
        atLeast("muted on the dark page", night.inkMuted, night.page);
        atLeast("muted on a dark card", night.inkMuted, night.surface);
        for (int sky : NIGHT_SKY) atLeast("muted on the night sky", night.inkMuted, sky);
    }

    @Test public void theFixWordReadsOnBothSkiesAndBothPages() {
        Ui day = ui(false);
        Ui night = ui(true);
        for (int sky : DAY_SKY) atLeast("Fix on the day sky", day.link, sky);
        for (int sky : NIGHT_SKY) atLeast("Fix on the night sky", night.link, sky);
        atLeast("Fix on the light page", day.link, day.page);
        atLeast("Fix on the dark page", night.link, night.page);
        assertTrue("the accent alone did not: " + contrast(night.accent, NIGHT_SKY[0]),
                contrast(night.accent, NIGHT_SKY[0]) < 4.5);
    }

    /** "N more to set up", the folded steps' line, is quieter than a step's words, and still reads on the sky. */
    @Test public void theFoldedStepsQuieterWordsStillReadOnBothSkies() {
        Ui day = ui(false);
        Ui night = ui(true);
        for (int sky : DAY_SKY) atLeast("folded steps on the day sky", day.inkSecondary, sky);
        for (int sky : NIGHT_SKY) atLeast("folded steps on the night sky", night.inkSecondary, sky);
    }

    @Test public void everyFixWordOnTheHomepageAndInSettingsUsesTheLinkInk() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            Ui ui = new Ui(activity.get());
            List<TextView> fixes = new ArrayList<>();
            collect(content, "Fix", fixes);
            assertFalse("the homepage's setup lines and Settings' rows", fixes.isEmpty());
            for (TextView fix : fixes) assertEquals(ui.link, fix.getCurrentTextColor());
        }
    }

    private static void collect(View view, String words, List<TextView> out) {
        if (view instanceof TextView && !(view instanceof android.widget.Button)
                && words.contentEquals(((TextView) view).getText())) out.add((TextView) view);
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), words, out);
        }
    }
}
