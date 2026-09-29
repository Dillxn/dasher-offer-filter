package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Rules screen: new 0.5.0 fields round-trip through Save, pause keeps them, and settings intents have the right shape. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivityRulesTest {
    private Application app;
    @Before public void setup() { app=RuntimeEnvironment.getApplication();Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);Updater.setEnabled(app,false); }

    @Test public void newRuleFieldsSaveAndPauseKeepsThem() {
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start().resume()) {
            View root=c.get().findViewById(android.R.id.content);
            set(root,"Minimum payout ($)","7");set(root,"Minimum dollars per hour (uses only minutes Dasher shows)","25,50");
            set(root,"Maximum miles (0 disables)","8.5");set(root,"Safety limit: decline requests per hour (0 = no limit)","12");
            ((EditText)after(root,"Stores to avoid (one per line or comma-separated)")).setText("Chick-fil-A\nWalmart, McDonald's");
            ((Switch)find(root,Switch.class,"Auto-decline (Save to enable; switching off is immediate)")).setChecked(true);
            find(root,Button.class,"Save rules").performClick();
            FilterSettings s=FilterStore.load(app);
            assertTrue(s.enabled);assertEquals(700,s.flatCents);assertEquals(2550,s.perHourCents);assertEquals(0,s.perMinuteCents);
            assertEquals(850,s.maxMilesHundredths);assertEquals(12,s.maxDeclinesPerHour);
            assertEquals(Arrays.asList("chick fil a","walmart","mcdonalds"),s.avoidStores);
            find(root,Button.class,"Pause auto-decline immediately").performClick();
            FilterSettings paused=FilterStore.load(app);
            assertFalse(paused.enabled);assertEquals(2550,paused.perHourCents);assertEquals(850,paused.maxMilesHundredths);assertEquals(3,paused.avoidStores.size());assertEquals(12,paused.maxDeclinesPerHour);
        }
    }
    @Test public void legacyPerMinuteRuleIsShownAsExactHourlyRateAndSavedAsHourly() {
        app.getSharedPreferences("offer_filter",0).edit().putBoolean("enabled",true).putInt("minute",50).commit();
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start().resume()) {
            View root=c.get().findViewById(android.R.id.content);
            assertEquals("30.00",((EditText)after(root,"Minimum dollars per hour (uses only minutes Dasher shows)")).getText().toString());
            find(root,Button.class,"Save rules").performClick();
            FilterSettings s=FilterStore.load(app);assertEquals(3000,s.perHourCents);assertEquals(0,s.perMinuteCents);assertTrue(s.enabled);
        }
    }
    @Test public void invalidStoreOrLimitIsRejectedWithoutSaving() {
        FilterStore.save(app,new FilterSettings(true,2000,0,0,0,0));
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start().resume()) {
            View root=c.get().findViewById(android.R.id.content);
            ((EditText)after(root,"Stores to avoid (one per line or comma-separated)")).setText("7-11");
            set(root,"Minimum payout ($)","9");
            find(root,Button.class,"Save rules").performClick();
            assertEquals(2000,FilterStore.load(app).flatCents);assertTrue(FilterStore.load(app).avoidStores.isEmpty());
        }
    }
    @Test public void notificationAccessIntentCarriesAFlattenedComponentString() {
        Intent i=MainActivity.notificationAccessIntent(app);
        if(android.os.Build.VERSION.SDK_INT>=30) {
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,i.getAction());
            Object extra=i.getExtras().get(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME);
            assertTrue(extra instanceof String);assertEquals(new ComponentName(app,OfferNotificationService.class).flattenToString(),extra);
        } else assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,i.getAction());
    }
    @Test public void undatedLegacyBaselineIsShownAsNotApplied() {
        app.getSharedPreferences("offer_filter",0).edit().putInt("last_accepted",2500).commit();
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start().resume()) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(allText(c.get().findViewById(android.R.id.content)).contains("$25.00 (undated from an older version; not applied)"));
        }
    }

    private static void set(View root,String label,String value) { ((EditText)after(root,label)).setText(value); }
    private static View find(View v,Class<?> type,String text) {
        if(type.isInstance(v) && text.contentEquals(((TextView)v).getText())) return v;
        if(v instanceof ViewGroup) { ViewGroup g=(ViewGroup)v; for(int i=0;i<g.getChildCount();i++){ View out=find(g.getChildAt(i),type,text); if(out!=null) return out; } }
        return null;
    }
    /** The input that follows a label TextView in the same container. */
    private static View after(View v,String label) {
        if(v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) {
                View child=g.getChildAt(i);
                if(child instanceof TextView && !(child instanceof EditText) && !(child instanceof Button) && label.contentEquals(((TextView)child).getText()) && i+1<g.getChildCount()) return g.getChildAt(i+1);
                View out=after(child,label); if(out!=null) return out;
            }
        }
        return null;
    }
    private static String allText(View v) {
        StringBuilder b=new StringBuilder(); if(v instanceof TextView) b.append(((TextView)v).getText()).append('\n');
        if(v instanceof ViewGroup) { ViewGroup g=(ViewGroup)v; for(int i=0;i<g.getChildCount();i++) b.append(allText(g.getChildAt(i))); }
        return b.toString();
    }
}
