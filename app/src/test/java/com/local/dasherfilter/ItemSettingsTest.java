package com.local.dasherfilter;

import android.app.Application;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Upgrade defaults and rule transformations must never silently remove or enable the optional item floor. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
public final class ItemSettingsTest {
    private Application app;
    @Before public void clear() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("offer_filter", 0).edit().clear().commit();
    }

    @Test public void olderInstallKeepsItemRuleOffAndSavedValueSurvivesEveryControl() {
        app.getSharedPreferences("offer_filter", 0).edit().putInt("flat", 500).commit();
        FilterSettings old = FilterStore.load(app);
        assertEquals(0, old.perItemCents);
        FilterSettings item = old.withPerItem(75);
        FilterStore.save(app, item);
        assertEquals(75, FilterStore.load(app).perItemCents);
        assertEquals(75, item.withEnabled(true).withAdaptive(true).withScoreByArea(true)
                .withMaxStops(4).withHotspotProximity(30).withMinimumScalePercent(97)
                .withoutRisingBaseline().adoptAdaptive().perItemCents);
        assertEquals(75, item.withMinimums(new int[] {600, 100, 25, 150}).perItemCents);
        assertEquals(75, item.withMinimums(new int[] {600, 100, 25, 150, 50}).perItemCents);
        assertEquals(0, item.withMinimums(new int[] {600, 100, 25, 150, 50, 0}).perItemCents);
    }

    @Test public void itemOnlyRuleIsActiveAndGlobalScaleDoesNotRewriteItsBaseline() {
        FilterSettings item = new FilterSettings(true, 0, 0, 0, 0, 0).withPerItem(100);
        assertTrue(item.hasAnyRule());
        FilterStore.save(app, item.withMinimumScalePercent(97));
        FilterSettings read = FilterStore.load(app);
        assertEquals(100, read.perItemCents);
        assertEquals(97, read.minimumScalePercent);
        assertEquals(100, read.minimums()[5]);
        assertTrue(read.describe().contains("offers declaring items or shopping"));
        assertEquals(0, item.withPerItem(-1).perItemCents);
        assertEquals(FilterSettings.MOST_CENTS, item.withPerItem(Integer.MAX_VALUE).perItemCents);
    }
}
