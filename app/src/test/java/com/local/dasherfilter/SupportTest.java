package com.local.dasherfilter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class SupportTest {
    @Test public void eachServiceOpensItsOwnPaymentPageForTheAuthor() {
        assertEquals("https://cash.app/$OfferFilterDev", Support.link(Support.Method.CASH_APP, "OfferFilterDev"));
        assertEquals("https://venmo.com/Offer-Filter?txn=pay&note=Offer%20Filter%20tip",
                Support.link(Support.Method.VENMO, "Offer-Filter"));
        assertEquals("https://paypal.me/offer_filter", Support.link(Support.Method.PAYPAL, "offer_filter"));
    }

    @Test public void aNameCanNeverChangeWhichAddressIsOpened() {
        for (String name : new String[] {"", " ", "$tag", "a/b", "a?b", "a#b", "a@evil.test", "a.b", "a b",
                "x".repeat(31), null}) {
            assertFalse(String.valueOf(name), Support.validHandle(name));
            assertThrows(String.valueOf(name), IllegalArgumentException.class,
                    () -> Support.link(Support.Method.CASH_APP, name));
        }
        assertTrue(Support.validHandle("x".repeat(30)));
    }

    @Test public void onlyServicesWithANameAreOffered() {
        String cashApp = Support.cashApp;
        String venmo = Support.venmo;
        String payPal = Support.payPal;
        try {
            Support.cashApp = "";
            Support.venmo = "";
            Support.payPal = "";
            assertTrue(Support.methods().isEmpty());
            Support.payPal = "offer_filter";
            Support.cashApp = "bad/name";
            assertEquals(java.util.Collections.singletonList(Support.Method.PAYPAL), Support.methods());
        } finally {
            Support.cashApp = cashApp;
            Support.venmo = venmo;
            Support.payPal = payPal;
        }
    }

    @Test public void theShippedNamesAreUsable() {
        for (String name : new String[] {Support.CASH_APP, Support.VENMO, Support.PAYPAL}) {
            assertTrue("empty, or a usable name: " + name, name.isEmpty() || Support.validHandle(name));
        }
    }
}
