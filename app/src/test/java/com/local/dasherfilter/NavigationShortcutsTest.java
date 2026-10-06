package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Parcelable;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
public class NavigationShortcutsTest extends AndroidAdapterTestBase {
    @Test public void bestUsesPooledRateAndExistingThreeOfferMinimum() {
        AreaMap.Cell sparse = cell(0, 0, 2, 9000, 1);
        AreaMap.Cell longMiles = cell(100, -100, 4, 2000, 10);
        AreaMap.Cell best = cell(101, -101, 3, 1800, 3);
        assertSame(best, NavigationShortcuts.best(Arrays.asList(sparse, longMiles, best)));
        assertNull(NavigationShortcuts.best(Collections.singletonList(sparse)));
        assertNull(NavigationShortcuts.best(Collections.emptyList()));
    }

    @Test public void directionsUseApproximateCellCentreWithNoStoredOrInventedOrigin() {
        NavigationShortcuts.Destination area = NavigationShortcuts.area(cell(1888, -6121, 3, 1800, 3));
        assertEquals("1", area.maps.getQueryParameter("api"));
        assertEquals("37.7700,-122.4100", area.maps.getQueryParameter("destination"));
        assertEquals("driving", area.maps.getQueryParameter("travelmode"));
        assertEquals("navigate", area.maps.getQueryParameter("dir_action"));
        assertNull(area.maps.getQueryParameter("origin"));
        assertEquals("37.7700,-122.4100", area.waze.getQueryParameter("ll"));
        assertEquals("yes", area.waze.getQueryParameter("navigate"));
    }

    @Test public void gasSearchesNeverChooseAStationOrStartNavigation() {
        NavigationShortcuts.Destination nearby = NavigationShortcuts.gas(false);
        NavigationShortcuts.Destination prices = NavigationShortcuts.gas(true);
        assertEquals("gas stations near me", nearby.maps.getQueryParameter("query"));
        assertEquals("gas prices near me", prices.maps.getQueryParameter("query"));
        for (NavigationShortcuts.Destination gas : Arrays.asList(nearby, prices)) {
            assertEquals("gas stations", gas.waze.getQueryParameter("q"));
            assertNull(gas.maps.getQueryParameter("destination"));
            assertNull(gas.maps.getQueryParameter("dir_action"));
            assertNull(gas.waze.getQueryParameter("ll"));
            assertNull(gas.waze.getQueryParameter("navigate"));
        }
    }

    @Test public void withoutMapAppsUniversalUrlCanOpenInAvailableBrowser() {
        installHandler("test.browser", "www.google.com");
        Intent intent = NavigationShortcuts.intent(app.getPackageManager(), NavigationShortcuts.gas(false));
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertNull(intent.getPackage());
        assertEquals("https", intent.getData().getScheme());
        assertEquals(0, intent.getFlags());
        assertNotNull(intent.resolveActivity(app.getPackageManager()));
    }

    @Test public void onlyInstalledWazeGetsItsDocumentedLinkWithoutTaskPlacementFlags() {
        installHandler(NavigationShortcuts.WAZE, "waze.com");
        Intent intent = NavigationShortcuts.intent(app.getPackageManager(), NavigationShortcuts.gas(false));
        assertEquals(NavigationShortcuts.WAZE, intent.getPackage());
        assertEquals("waze.com", intent.getData().getHost());
        assertEquals(0, intent.getFlags());
    }

    @Test public void twoInstalledMapAppsOfferBothAndNoInventedDefault() {
        installHandler(NavigationShortcuts.WAZE, "waze.com");
        installHandler(NavigationShortcuts.MAPS, "www.google.com");
        Intent intent = NavigationShortcuts.intent(app.getPackageManager(), NavigationShortcuts.gas(true));
        assertEquals(Intent.ACTION_CHOOSER, intent.getAction());
        Intent primary = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        Parcelable[] alternatives = intent.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS);
        assertEquals(NavigationShortcuts.MAPS, primary.getPackage());
        assertEquals(1, alternatives.length);
        assertEquals(NavigationShortcuts.WAZE, ((Intent) alternatives[0]).getPackage());
    }

    @Test public void appLaunchRaceFallsBackToUnpackagedUniversalUrl() {
        installHandler(NavigationShortcuts.MAPS, "www.google.com");
        try (org.robolectric.android.controller.ActivityController<VanishingMapActivity> activity =
                Robolectric.buildActivity(VanishingMapActivity.class).setup()) {
            assertTrue(NavigationShortcuts.open(activity.get(), NavigationShortcuts.gas(true)));
            assertEquals(2, activity.get().attempts);
            assertNull(activity.get().opened.getPackage());
            assertEquals("https", activity.get().opened.getData().getScheme());
        }
    }

    public static class VanishingMapActivity extends Activity {
        int attempts;
        Intent opened;
        @Override public void startActivity(Intent intent) {
            if (++attempts == 1) throw new ActivityNotFoundException();
            opened = intent;
        }
    }

    private void installHandler(String name, String host) {
        ComponentName component = new ComponentName(name, name + ".MapActivity");
        org.robolectric.shadows.ShadowPackageManager packages = Shadows.shadowOf(app.getPackageManager());
        packages.addActivityIfNotPresent(component);
        IntentFilter filter = new IntentFilter(Intent.ACTION_VIEW);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        filter.addDataScheme("https");
        filter.addDataAuthority(host, null);
        packages.addIntentFilterForActivity(component, filter);
    }

    private static AreaMap.Cell cell(int row, int col, int samples, long pay, double miles) {
        AreaMap.Cell cell = new AreaMap.Cell(row, col);
        cell.mileOffers = samples;
        cell.milePayCents = pay;
        cell.miles = miles;
        return cell;
    }
}
