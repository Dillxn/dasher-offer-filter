package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.Arrays;
import static org.junit.Assert.*;

/** SharedPreferences round trips and 0.4.x migration for saved rules (spec B2). Simulated Android, not a handset. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class FilterStoreTest {
    private Application app;
    private SharedPreferences prefs() { return app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE); }
    @Before public void setup() { app=RuntimeEnvironment.getApplication(); prefs().edit().clear().commit(); }

    @Test public void legacyConstructorSettingsRoundTrip() {
        for (FilterSettings s : new FilterSettings[]{new FilterSettings(true,2000,150,30,100,3), new FilterSettings(false,0,0,0,0,0), new FilterSettings(true,2200,0,7,0,0,true,0)}) {
            FilterStore.save(app,s); assertEquals(s,FilterStore.load(app));
        }
    }
    @Test public void fullSettingsRoundTripExceptTheBaselineOwnedByRecordAccepted() {
        FilterSettings s=new FilterSettings(true,700,150,0,2500,200,3,625,Arrays.asList("Chick-fil-A","A&W"),true,0,0L,4);
        FilterStore.save(app,s); FilterSettings loaded=FilterStore.load(app);
        assertEquals(s,loaded); assertEquals(Arrays.asList("chick fil a","a and w"),loaded.avoidStores);
        assertEquals("chick fil a\na and w",prefs().getString("avoid_stores",null)); assertEquals(0,prefs().getInt("minute",-1)); assertEquals(2500,prefs().getInt("hour",-1));
    }
    @Test public void legacyPerMinuteRateMigratesExactlyToPerHour() {
        prefs().edit().putBoolean("enabled",true).putInt("flat",700).putInt("minute",42).putInt("mile",150).putInt("last_accepted",2500).commit();
        FilterSettings s=FilterStore.load(app);
        assertEquals(0,s.perMinuteCents); assertEquals(2520,s.perHourCents); assertEquals(700,s.flatCents); assertEquals(150,s.perMileCents);
        assertEquals("a legacy undated baseline is loaded but never applies",0L,s.lastAcceptedAt); assertFalse(s.baselineFresh(System.currentTimeMillis()));
        FilterStore.save(app,s); assertEquals(s,FilterStore.load(app)); assertEquals(0,prefs().getInt("minute",-1));
    }
    @Test public void noMigrationOnceTheHourKeyExists() {
        prefs().edit().putInt("minute",30).putInt("hour",0).commit();
        FilterSettings s=FilterStore.load(app); assertEquals(30,s.perMinuteCents); assertEquals(0,s.perHourCents);
        prefs().edit().clear().putInt("minute",0).commit(); assertEquals(0,FilterStore.load(app).perHourCents);
    }
    @Test public void recordAcceptedStoresATimestampAndResetClearsIt() {
        long before=System.currentTimeMillis(); FilterStore.recordAccepted(app,2750); long after=System.currentTimeMillis();
        FilterSettings s=FilterStore.load(app); assertEquals(2750,s.lastAcceptedCents); assertTrue(s.lastAcceptedAt>=before && s.lastAcceptedAt<=after);
        assertTrue(s.withRisingOffers(true).baselineActive(after));
        FilterStore.save(app,s.withFlatCents(900)); assertEquals("saving rules never clobbers the baseline",2750,FilterStore.load(app).lastAcceptedCents);
        FilterStore.recordAccepted(app,0); assertEquals(0,FilterStore.load(app).lastAcceptedCents); assertEquals(0L,FilterStore.load(app).lastAcceptedAt);
    }
    @Test public void pauseThroughCopyHelperKeepsEveryNewRule() {
        FilterSettings s=new FilterSettings(true,700,150,0,2500,200,3,625,Arrays.asList("KFC"),true,0,0L,4);
        FilterStore.save(app,s); FilterStore.save(app,FilterStore.load(app).withEnabled(false));
        FilterSettings paused=FilterStore.load(app); assertFalse(paused.enabled); assertEquals(s.withEnabled(false),paused);
    }
    @Test public void malformedAvoidListIsSanitizedOnLoad() {
        prefs().edit().putString("avoid_stores","KFC\n\n!\nkfc\nTaco Bell").commit();
        assertEquals(Arrays.asList("kfc","taco bell"),FilterStore.load(app).avoidStores);
    }
}
