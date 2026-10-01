package com.local.dasherfilter;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NotificationOfferTest {
    @Test public void explicitHeadlineWinsOverWordsInsideMerchantNames() {
        assertTrue(NotificationOffer.isLikelyOffer(Arrays.asList("New Order: Go to Balance Bowls")));
        assertTrue(NotificationOffer.isLikelyOffer(Arrays.asList("New Delivery!", "Promotion Pizza")));
        assertTrue(NotificationOffer.isLikelyOffer(Arrays.asList("New Order", "Deposit Diner")));
        assertFalse(NotificationOffer.isLikelyOffer(Arrays.asList("New message from customer", "My new order is late")));
        assertFalse(NotificationOffer.isLikelyOffer(Arrays.asList("New orders promotion", "$5.00")));
        assertFalse(NotificationOffer.isLikelyOffer(Arrays.asList("Balance", "$100.00")));
    }

    @Test public void recognizesCommonOfferWording() {
        assertTrue(NotificationOffer.isLikelyOffer(Arrays.asList("New Order!", "$8.50", "4.2 mi")));
        assertTrue(NotificationOffer.isLikelyOffer(Arrays.asList("Delivery opportunity", "3 stops")));
        assertTrue(NotificationOffer.isLikelyOffer(Arrays.asList("$12.00", "6.1 mi")));
    }

    @Test public void rejectsUnrelatedDoorDashNotifications() {
        assertFalse(NotificationOffer.isLikelyOffer(Arrays.asList("Weekly earnings", "$125.00")));
        assertFalse(NotificationOffer.isLikelyOffer(Arrays.asList("New message from customer", "Where are you?")));
        assertFalse(NotificationOffer.isLikelyOffer(Arrays.asList("Scheduled dash starts soon")));
    }
}
