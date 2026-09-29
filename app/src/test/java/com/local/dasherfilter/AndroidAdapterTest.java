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
    // ---- 0.5.0 notification regressions (each fails on 0.4.5 behavior; auditor probes ported) ----
    private NotificationManager nm() { return app.getSystemService(NotificationManager.class); }
    private StatusBarNotification post(String tag, String title, String text) { return post(tag,title,text,System.currentTimeMillis(),null); }
    private StatusBarNotification post(String tag, String title, String text, long when, Notification.Action action) {
        Notification.Builder b=new Notification.Builder(app,"dasher_offers").setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(title).setContentText(text).setWhen(when);
        if (action!=null) b.addAction(action);
        return new StatusBarNotification("com.doordash.driverapp","com.doordash.driverapp",10001,tag,10001,0,0,b.build(),android.os.Process.myUserHandle(),System.currentTimeMillis());
    }
    private static boolean audible(Notification n) { return n.getGroup()==null && (n.flags & Notification.FLAG_ONLY_ALERT_ONCE)==0 && OfferAlerts.CHANNEL_ID.equals(n.getChannelId()); }
    private List<Notification> cards() { return Shadows.shadowOf(nm()).getAllNotifications(); }
    private boolean anyAudible() { for (Notification n : cards()) if (audible(n)) return true; return false; }
    private org.robolectric.android.controller.ServiceController<OfferNotificationService> listener() { return Robolectric.buildService(OfferNotificationService.class).create(); }
    private OfferHistoryStore history() { return OfferHistoryStore.get(app); }

    /** H4.1: DoorDash's real merchant-only offer from an avoided store is hidden (or declined via its safe action) — never a bell or review card. */
    @Test public void avoidedMerchantOnlyOfferNotificationIsHiddenOrDeclinedNeverAlerted() {
        FilterStore.save(app,new FilterSettings(true,2000,0,0,0,0).withAvoidStores(java.util.Arrays.asList("Chick-fil-A")));
        org.robolectric.android.controller.ServiceController<OfferNotificationService> c=listener();
        try {
            android.app.PendingIntent declineIntent=android.app.PendingIntent.getBroadcast(app,1,new android.content.Intent("com.doordash.DECLINE"),android.app.PendingIntent.FLAG_IMMUTABLE);
            Shadows.shadowOf(declineIntent).setCreatorPackage("com.doordash.driverapp");
            StatusBarNotification source=post("NEW_ORDER","New Delivery!","New Order: Go to Chick-fil-A",System.currentTimeMillis(),new Notification.Action.Builder(null,"Decline",declineIntent).build());
            Shadows.shadowOf(c.get()).addActiveNotification(source);
            c.get().onNotificationPosted(source,null);
            assertTrue("no passing bell and no review card for an avoided store",cards().isEmpty());
            OfferRecord r=history().newestFirst().get(0);assertEquals(OfferRecord.Source.NOTIFICATION,r.source);assertEquals("Chick-fil-A",r.store);assertEquals(OfferRule.Code.AVOIDED_STORE,r.reasonCode);
            if (Build.VERSION.SDK_INT>=31) {
                assertTrue(r.has(OfferRecord.NOTIF_DECLINE_SENT));assertFalse(r.has(OfferRecord.NOTIF_HIDDEN));
                assertEquals("com.doordash.DECLINE",Shadows.shadowOf(app).getBroadcastIntents().get(0).getAction());
                assertTrue(FilterStore.lastStatus(app).contains("Decline requested from the notification; completion unverified"));
            } else {
                assertTrue("API < 31 cannot inspect action types: hide only",r.has(OfferRecord.NOTIF_HIDDEN));assertEquals(0,c.get().getActiveNotifications().length);
                assertTrue(FilterStore.lastStatus(app).contains("Notification hidden — order NOT declined"));assertEquals("Notification hidden — order NOT declined",r.outcome());
            }
            assertTrue(r.declineRequestCounted());
        } finally { c.destroy(); }
    }
    /** H4.1: without a safe action the avoided merchant-only offer is hidden on every API level, with honest wording. */
    @Test public void avoidedMerchantOnlyOfferWithoutActionIsHiddenNotDeclined() {
        FilterStore.save(app,new FilterSettings(true,2000,0,0,0,0).withAvoidStores(java.util.Arrays.asList("Chick-fil-A")));
        org.robolectric.android.controller.ServiceController<OfferNotificationService> c=listener();
        try {
            StatusBarNotification source=post("NEW_ORDER","New Delivery!","New Order: Go to Chick-fil-A");Shadows.shadowOf(c.get()).addActiveNotification(source);
            c.get().onNotificationPosted(source,null);
            assertEquals("the DoorDash notification was hidden",0,c.get().getActiveNotifications().length);assertTrue(cards().isEmpty());
            assertTrue(history().newestFirst().get(0).hiddenOnly());assertTrue(FilterStore.lastStatus(app).contains("order NOT declined"));
            assertFalse(FilterStore.lastStatus(app).contains("Declined"));
        } finally { c.destroy(); }
    }
    /** H4.1: a customer message naming an avoided store is never an offer: untouched, no card, no history. */
    @Test public void customerMessageNamingAnAvoidedStoreIsUntouched() {
        FilterStore.save(app,new FilterSettings(true,2000,0,0,0,0).withAvoidStores(java.util.Arrays.asList("Chick-fil-A")));
        org.robolectric.android.controller.ServiceController<OfferNotificationService> c=listener();
        try {
            StatusBarNotification source=post("MSG","New message from customer","Can you grab extra sauce from Chick-fil-A?");Shadows.shadowOf(c.get()).addActiveNotification(source);
            c.get().onNotificationPosted(source,null);
            assertEquals(1,c.get().getActiveNotifications().length);assertTrue(cards().isEmpty());assertEquals(0,history().size());
            assertTrue(Shadows.shadowOf(app).getBroadcastIntents().isEmpty());
        } finally { c.destroy(); }
    }
    /** B5: store rules alone never produce a passing bell, even for a fully readable, well-paid offer. */
    @Test public void storeOnlyRulesNeverRingThePassingBell() {
        FilterStore.save(app,new FilterSettings(true,0,0,0,0,0).withAvoidStores(java.util.Arrays.asList("Chick-fil-A")));
        org.robolectric.android.controller.ServiceController<OfferNotificationService> c=listener();
        try {
            c.get().onNotificationPosted(post("NEW_ORDER","New delivery offer","$25.00 · 5.2 mi · 20 min"),null);
            assertEquals(1,cards().size());assertFalse(anyAudible());assertEquals(OfferAlerts.REVIEW_CHANNEL_ID,cards().get(0).getChannelId());
            assertEquals(OfferRule.Code.NO_PAY_RULE,history().newestFirst().get(0).reasonCode);
        } finally { c.destroy(); }
    }
    /** H2-4 stays conservative: a same-key update (even a different passing offer) updates the card silently, never a second bell. */
    @Test public void sameKeyUpdateNeverRingsASecondBell() {
        FilterStore.save(app,new FilterSettings(true,1000,0,0,0,0));
        org.robolectric.android.controller.ServiceController<OfferNotificationService> c=listener();
        try {
            c.get().onNotificationPosted(post("NEW_ORDER","New delivery offer","$25.00 · 5.2 mi · 20 min"),null);assertTrue(audible(cards().get(0)));
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(30));
            c.get().onNotificationPosted(post("NEW_ORDER","New delivery offer","$31.50 · 2.8 mi · 14 min"),null);
            assertEquals(1,cards().size());assertFalse("an update never rings again",audible(cards().get(0)));
            assertEquals("one record per listener entry, updated in place",1,history().size());assertEquals(Integer.valueOf(3150),history().newestFirst().get(0).payCents);
            assertTrue(history().newestFirst().get(0).has(OfferRecord.PASS_BELL));
        } finally { c.destroy(); }
    }
    /** H2-5: the identical notification re-posted after the 90 s entry lifetime is not a new bell (tombstone). */
    @Test public void identicalRepostAfterExpiryNeverRingsAgain() {
        FilterStore.save(app,new FilterSettings(true,1000,0,0,0,0));
        org.robolectric.android.controller.ServiceController<OfferNotificationService> c=listener();
        try {
            long t0=System.currentTimeMillis();
            c.get().onNotificationPosted(post("NEW_ORDER","New delivery offer","$25.00 · 5.2 mi · 20 min",t0,null),null);assertTrue(audible(cards().get(0)));
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(91));org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertTrue("the expired card is gone",cards().isEmpty());
            c.get().onNotificationPosted(post("NEW_ORDER","New delivery offer","$25.00 · 5.2 mi · 20 min",t0,null),null);
            assertFalse("an identical re-post after expiry must not ring again",anyAudible());
            assertEquals("same incarnation, same history record",1,history().size());
        } finally { c.destroy(); }
    }
    /** H2-7: a listener reconnect (new instance, old process state lost) leaves exactly one card per DoorDash offer. */
    @Test public void reconnectLeavesNoOrphanDuplicateCards() {
        FilterStore.save(app,new FilterSettings(true,2000,0,0,0,0));
        org.robolectric.android.controller.ServiceController<OfferNotificationService> first=listener(),second=listener();
        try {
            StatusBarNotification src=post("NEW_ORDER","New Delivery!","New Order: Go to Chipotle");
            first.get().onNotificationPosted(src,null);assertEquals(1,Shadows.shadowOf(nm()).size());
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2));
            Shadows.shadowOf(second.get()).addActiveNotification(src);second.get().onListenerConnected();
            assertEquals("one Dasher offer has one review card",1,Shadows.shadowOf(nm()).size());assertFalse(anyAudible());
            OfferAlerts.notifyOffer(app,"offer-1-key",null,OfferRule.Result.REVIEW,"x",false);OfferAlerts.clear(app);
            assertEquals("clear(Context) removes tagged cards too",0,Shadows.shadowOf(nm()).size());
        } finally { second.destroy(); first.destroy(); }
    }
    /** E3: glanceable passing card; channel, small icon, public version and ring budget semantics unchanged. */
    @Test public void passingAlertIsGlanceableWithUnchangedRingSemantics() {
        FilterStore.save(app,new FilterSettings(true,800,0,0,0,0));
        org.robolectric.android.controller.ServiceController<OfferNotificationService> c=listener();
        try {
            c.get().onNotificationPosted(post("NEW_ORDER","$28.24 Flash offer available","5.4mi offer from Mr. Pollo. 21 min. Tap to view offer details."),null);
            Notification n=cards().get(0);assertEquals(OfferAlerts.CHANNEL_ID,n.getChannelId());assertTrue(audible(n));
            assertEquals("$28.24 · 5.4 mi · 21 min",n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
            assertEquals("Meets your rules · $5.23/mi · $81/hr · needed $8.00",n.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
            assertEquals("Mr. Pollo",n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString());
            assertEquals(R.drawable.ic_stat_offer,n.getSmallIcon().getResId());
            assertEquals("Offer meets your rules",n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
            assertEquals(Notification.VISIBILITY_PRIVATE,n.visibility);
            OfferRecord r=history().newestFirst().get(0);assertTrue(r.has(OfferRecord.PASS_BELL));assertEquals("Mr. Pollo",r.store);
            // Quiet (non-ringing) passing and review cards keep onlyAlertOnce + the quiet group.
            assertTrue(OfferAlerts.notifyOffer(app,"quiet",null,OfferRule.Result.KEEP,"Pass",false));
            Notification quiet=Shadows.shadowOf(nm()).getNotification("quiet",8241);assertTrue((quiet.flags & Notification.FLAG_ONLY_ALERT_ONCE)!=0);assertEquals(Notification.GROUP_ALERT_SUMMARY,quiet.getGroupAlertBehavior());
        } finally { c.destroy(); }
    }
    /** E3: review card says what is missing and never claims a decline. */
    @Test public void reviewCardNamesTheStoreAndMissingEvidence() {
        FilterStore.save(app,new FilterSettings(true,2000,0,0,0,0));
        org.robolectric.android.controller.ServiceController<OfferNotificationService> c=listener();
        try {
            c.get().onNotificationPosted(post("NEW_ORDER","New Delivery!","New Order: Go to Chick-fil-A"),null);
            Notification n=cards().get(0);assertEquals(OfferAlerts.REVIEW_CHANNEL_ID,n.getChannelId());
            assertEquals("Offer at Chick-fil-A — pay not shown",n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
            String text=n.extras.getCharSequence(Notification.EXTRA_TEXT).toString();assertTrue(text,text.contains("Tap to open in Dasher"));assertFalse(text.toLowerCase(java.util.Locale.US).contains("declined"));
            assertEquals("Offer needs review",n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
            OfferRecord r=history().newestFirst().get(0);assertTrue(r.has(OfferRecord.REVIEW_CARD));assertEquals(OfferRule.Code.PAY_MISSING,r.reasonCode);
            assertTrue(OfferAlerts.canNotifyReview(app));assertTrue(OfferAlerts.canNotify(app,OfferRule.Result.REVIEW));assertFalse(OfferAlerts.canNotify(app,OfferRule.Result.DECLINE));
        } finally { c.destroy(); }
    }
    /** D2: paused auto-decline records a notification shadow and never hides or requests a decline. */
    @Test public void pausedNotificationIsShadowOnly() {
        FilterStore.save(app,new FilterSettings(false,2000,0,0,0,0));
        org.robolectric.android.controller.ServiceController<OfferNotificationService> c=listener();
        try {
            StatusBarNotification source=post("NEW_ORDER","New delivery offer","$7.90 · 5.2 mi · 20 min");Shadows.shadowOf(c.get()).addActiveNotification(source);
            c.get().onNotificationPosted(source,null);
            assertEquals(1,c.get().getActiveNotifications().length);assertEquals(OfferAlerts.REVIEW_CHANNEL_ID,cards().get(0).getChannelId());
            OfferRecord r=history().newestFirst().get(0);assertTrue(r.has(OfferRecord.PAUSED_SHADOW));assertTrue(r.has(OfferRecord.REVIEW_CARD));assertFalse(r.declineRequestCounted());
            assertEquals(OfferRule.Result.DECLINE,r.verdict);
        } finally { c.destroy(); }
    }
    /** B7 on notifications: once the hourly limit is reached a failing notification is a silent review card, not a hide. */
    @Test public void declineBudgetAppliesToNotificationHides() {
        FilterStore.save(app,new FilterSettings(true,2000,0,0,0,0).withMaxDeclinesPerHour(1));
        org.robolectric.android.controller.ServiceController<OfferNotificationService> c=listener();
        try {
            StatusBarNotification a=post("A","New delivery offer","$7.90 · 5.2 mi · 20 min"),b=post("B","New delivery offer","$6.10 · 4.0 mi · 12 min");
            Shadows.shadowOf(c.get()).addActiveNotification(a);Shadows.shadowOf(c.get()).addActiveNotification(b);
            c.get().onNotificationPosted(a,null);assertEquals(1,c.get().getActiveNotifications().length);
            c.get().onNotificationPosted(b,null);assertEquals("limit reached: B is not hidden",1,c.get().getActiveNotifications().length);
            assertEquals(OfferRule.Code.DECLINE_LIMIT,history().newestFirst().get(0).reasonCode);assertEquals(OfferAlerts.REVIEW_CHANNEL_ID,cards().get(0).getChannelId());
        } finally { c.destroy(); }
    }
    /** E2/E4: the DoorDash offer channel's actual settings are stored for readiness UI, diagnostics on or off. */
    @Test public void doorDashChannelFactsArePersistedFromTheActualChannel() {
        assertFalse(DiagnosticLog.isEnabled(app));assertNull(DoorDashChannelFacts.load(app));
        NotificationChannel dd=new NotificationChannel("dasher_offers","New orders",NotificationManager.IMPORTANCE_HIGH);dd.enableVibration(true);
        Notification n=new Notification.Builder(app,"dasher_offers").setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle("New Delivery!").build();
        DoorDashChannelFacts.record(app,"dasher_offers",dd,n,1_700_000_000_000L);
        DoorDashChannelFacts f=DoorDashChannelFacts.load(app);assertNotNull(f);assertTrue(f.known());assertEquals(NotificationManager.IMPORTANCE_HIGH,f.importance);
        assertTrue(f.sound);assertTrue(f.vibration);assertFalse(f.silent());assertTrue(f.summary(),f.summary().contains("\"New orders\": importance high, sound on, vibration on"));
        dd.setSound(null,null);dd.enableVibration(false);DoorDashChannelFacts.record(app,"dasher_offers",dd,n,1_700_000_001_000L);assertTrue("a changed setting is written at once",DoorDashChannelFacts.load(app).silent());
        DoorDashChannelFacts.record(app,"dasher_offers",null,n,1_700_000_900_000L);assertTrue("an unreported channel never erases known settings",DoorDashChannelFacts.load(app).known());
        org.robolectric.android.controller.ServiceController<OfferNotificationService> c=listener();
        try {
            DoorDashChannelFacts.clear(app);c.get().onNotificationPosted(post("NEW_ORDER","New Delivery!","New Order: Go to Chipotle"),null);
            DoorDashChannelFacts seen=DoorDashChannelFacts.load(app);assertNotNull("every offer notification records the channel",seen);assertEquals("dasher_offers",seen.channelId);
            assertTrue(DiagnosticLog.report(app).contains("DoorDash offer channel"));
        } finally { c.destroy(); }
    }
    /** E4: readiness facts are read-only reports of Android state. */
    @Test public void readinessFactsReportAndroidStateWithoutChangingIt() {
        assertFalse(ReadinessFacts.accessibilityEnabledInSettings(app));assertFalse(ReadinessFacts.accessibilityConnected());assertFalse(ReadinessFacts.listenerConnected());
        assertTrue(ReadinessFacts.notificationsPermitted(app));assertTrue(ReadinessFacts.passingAlertAudible(app));
        assertEquals(NotificationManager.IMPORTANCE_LOW,ReadinessFacts.channelImportance(app,OfferAlerts.REVIEW_CHANNEL_ID));assertFalse(ReadinessFacts.channelSound(app,OfferAlerts.REVIEW_CHANNEL_ID));
        assertFalse(ReadinessFacts.dasherInstalled(app));assertFalse(ReadinessFacts.rulesReady(app));
        FilterStore.save(app,new FilterSettings(false,0,0,0,0,0).withMaxMilesHundredths(600));assertTrue(ReadinessFacts.rulesReady(app));assertFalse(ReadinessFacts.autoDeclineOn(app));
        assertNull(ReadinessFacts.doorDashChannel(app));assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
    /** H4.1 signal: only an explicit "Go to <store>" merchant proves the notification's store. */
    @Test public void onlyGoToMerchantIsAProvenOfferMerchant() {
        assertTrue(OfferNotificationService.provenOfferMerchant(java.util.Arrays.asList("New Delivery!","New Order: Go to Chick-fil-A"),"Chick-fil-A"));
        assertFalse(OfferNotificationService.provenOfferMerchant(java.util.Arrays.asList("$28.24 Flash offer available","3.2mi offer from Mr. Pollo."),"Mr. Pollo"));
        assertFalse(OfferNotificationService.provenOfferMerchant(java.util.Arrays.asList("New Delivery!"),""));
    }
    private View find(View v,String text) { if(v instanceof Button && text.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View out=find(g.getChildAt(i),text);if(out!=null)return out;}}return null; }
}
