package com.local.dasherfilter;

import android.Manifest;
import android.app.*;
import android.app.job.*;
import android.content.*;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.lang.reflect.Method;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AndroidAdapterTest {
    private Application app;
    @Before public void setup() { app=RuntimeEnvironment.getApplication();Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);Updater.setEnabled(app,false);OfferAlerts.ensureChannel(app); }
    @Test public void unclassifiedNotificationUsesSilentChannelAndDoesNotLaunch() {
        assertTrue(OfferAlerts.notifyOffer(app,"review",null,OfferRule.Result.REVIEW,"Missing pay",false));
        Notification n=Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getNotification("review",8241);
        assertNotNull(n);assertEquals(OfferAlerts.REVIEW_CHANNEL_ID,n.getChannelId());assertEquals(Notification.GROUP_ALERT_SUMMARY,n.getGroupAlertBehavior());
        NotificationChannel c=app.getSystemService(NotificationManager.class).getNotificationChannel(n.getChannelId());assertNull(c.getSound());assertFalse(c.shouldVibrate());assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
    @Test public void blockedNotificationsReturnFailureInsteadOfClaimingReplacement() {
        Shadows.shadowOf(app.getSystemService(NotificationManager.class)).setNotificationsEnabled(false);
        assertFalse(OfferAlerts.notifyOffer(app,"denied",null,OfferRule.Result.KEEP,"Pass",true));
        assertNull(Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getNotification("denied",8241));
    }
    @Test public void notificationRemovalClearsOnlyItsOwnReplacement() {
        OfferAlerts.notifyOffer(app,"a",null,OfferRule.Result.REVIEW,"A",false);OfferAlerts.notifyOffer(app,"b",null,OfferRule.Result.REVIEW,"B",false);OfferAlerts.clear(app,"a");
        assertNull(Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getNotification("a",8241));assertNotNull(Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getNotification("b",8241));
    }
    @Test public void realPayloadProducesReviewCardWithoutOpeningDasher() {
        FilterStore.save(app,new FilterSettings(true,2000,0,0,0,0));
        org.robolectric.android.controller.ServiceController<OfferNotificationService> controller=Robolectric.buildService(OfferNotificationService.class).create();
        try {
            Notification n=new Notification.Builder(app,"source").setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle("New Delivery!").setContentText("New Order: Go to Chick-fil-A").build();
            StatusBarNotification source=new StatusBarNotification("com.doordash.driverapp","com.doordash.driverapp",3,"NEW_ORDER",10001,0,0,n,android.os.Process.myUserHandle(),System.currentTimeMillis());
            controller.get().onNotificationPosted(source,null);
            List<Notification> all=Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getAllNotifications();assertEquals(1,all.size());assertEquals(OfferAlerts.REVIEW_CHANNEL_ID,all.get(0).getChannelId());assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            controller.get().onNotificationPosted(source,null);assertEquals(1,Shadows.shadowOf(app.getSystemService(NotificationManager.class)).size());
            controller.get().onNotificationRemoved(source);assertEquals(0,Shadows.shadowOf(app.getSystemService(NotificationManager.class)).size());
        } finally { controller.destroy(); }
    }
    @Test public void retryCreatesAnActualAndroidOneShotJob() throws Exception {
        Updater.setEnabled(app,true);Method m=Updater.class.getDeclaredMethod("retry",Context.class,long.class);m.setAccessible(true);m.invoke(null,app,60000L);
        JobInfo job=app.getSystemService(JobScheduler.class).getPendingJob(7243);assertNotNull(job);assertFalse(job.isPeriodic());assertEquals(60000L,job.getMinLatencyMillis());
    }
    @Test public void pauseButtonPersistsWithoutPressingSave() {
        FilterStore.save(app,new FilterSettings(true,2000,150,0,0,0));
        try(org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create()) {
            View button=find(c.get().findViewById(android.R.id.content),"Pause auto-decline immediately");assertNotNull(button);button.performClick();assertFalse(FilterStore.load(app).enabled);assertEquals(2000,FilterStore.load(app).flatCents);
        }
    }
    @Test public void rawCaptureExpiresAndReportStillIncludesUpdateState() {
        DiagnosticLog.setEnabled(app,true);assertTrue(DiagnosticLog.isEnabled(app));
        app.getSharedPreferences("offer_filter_diagnostics",Context.MODE_PRIVATE).edit().putLong("until",System.currentTimeMillis()-1).commit();assertFalse(DiagnosticLog.isEnabled(app));
        Updater.status(app,"Synthetic updater error for test");assertTrue(DiagnosticLog.report(app).contains("Synthetic updater error for test"));
    }
    @Test public void passingReplayCannotAcquireANewBellOnLaterUpdate() {
        OfferAlertState s=new OfferAlertState(1,1,1);s.delivered("first",OfferRule.Result.KEEP,false);assertFalse(s.shouldRing(OfferRule.Result.KEEP,false,false));
    }
    private View find(View v,String text) { if(v instanceof Button && text.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View out=find(g.getChildAt(i),text);if(out!=null)return out;}}return null; }
}
