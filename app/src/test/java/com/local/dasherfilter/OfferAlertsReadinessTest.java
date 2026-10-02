package com.local.dasherfilter;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class OfferAlertsReadinessTest extends AndroidAdapterTestBase {
    @Test public void aBlockedReviewChannelIsNotReportedAsReady() {
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        assertTrue(OfferAlerts.canNotify(app));
        NotificationChannel review = manager.getNotificationChannel(OfferAlerts.REVIEW_CHANNEL_ID);
        review.setImportance(NotificationManager.IMPORTANCE_NONE);
        manager.createNotificationChannel(review);
        assertFalse(OfferAlerts.canNotify(app));
        assertTrue("the blocked review channel keeps the native original",
                !OfferAlerts.notifyOffer(app, "review", null, OfferRule.Result.REVIEW, "Check it", true));
        assertTrue("passing cards use their own available channel",
                OfferAlerts.notifyOffer(app, "pass", null, OfferRule.Result.KEEP, "Meets rules", false));
    }

    @Test public void aMissingNoticeChannelIsNormalButAnExistingBlockedOneNeedsFixing() {
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        assertNull(manager.getNotificationChannel(ConsentReminder.CHANNEL_ID));
        assertTrue(OfferAlerts.canNotify(app));
        manager.createNotificationChannel(new NotificationChannel(ConsentReminder.CHANNEL_ID, "Paused until opened",
                NotificationManager.IMPORTANCE_NONE));
        assertFalse(OfferAlerts.canNotify(app));
    }
}
