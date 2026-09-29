package com.local.dasherfilter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** H4.1: EvaluationContext.withOfferNotification lets an avoid-list match decline a merchant-only offer notification, nothing else. */
public final class NotificationStoreRuleTest {
    private static final EvaluationContext CTX = EvaluationContext.at(1_700_000_000_000L);
    private static final FilterSettings AVOID = new FilterSettings(true, 2000, 0, 0, 0, 0).withAvoidStores(Arrays.asList("Chick-fil-A"));
    private static final List<String> REAL = Arrays.asList("New Delivery!", "New Order: Go to Chick-fil-A");
    private static OfferRule.Decision eval(List<String> labels, FilterSettings s, EvaluationContext ctx) { return OfferRule.evaluate(OfferParser.parse(labels), labels, s, ctx); }

    @Test public void provenOfferMerchantOnTheAvoidListDeclines() {
        OfferRule.Decision d = eval(REAL, AVOID, CTX.withMerchant(NotificationOffer.merchant(REAL)).withOfferNotification(true));
        assertEquals(OfferRule.Result.DECLINE, d.result); assertEquals(OfferRule.Code.AVOIDED_STORE, d.code); assertEquals("chick fil a", d.avoidedStore);
    }
    @Test public void withoutTheSignalAMerchantOnlyMatchStaysReview() {
        OfferRule.Decision d = eval(REAL, AVOID, CTX.withMerchant(NotificationOffer.merchant(REAL)));
        assertEquals(OfferRule.Result.REVIEW, d.result); assertEquals(OfferRule.Code.AVOIDED_STORE, d.code);
    }
    @Test public void aMatchOutsideTheProvenMerchantNeverDeclines() {
        List<String> labels = Arrays.asList("New Delivery!", "New Order: Go to Taco Bell", "Next to Chick-fil-A");
        OfferRule.Decision d = eval(labels, AVOID, CTX.withMerchant("Taco Bell").withOfferNotification(true));
        assertNotEquals(OfferRule.Result.DECLINE, d.result);
    }
    @Test public void theSignalNeverCreatesAPassOrOverridesEarnByTimeOrTheLimit() {
        FilterSettings storesOnly = new FilterSettings(true, 0, 0, 0, 0, 0).withAvoidStores(Arrays.asList("Chick-fil-A"));
        List<String> other = Arrays.asList("New Delivery!", "New Order: Go to Chipotle");
        assertEquals(OfferRule.Code.NO_PAY_RULE, eval(other, storesOnly, CTX.withMerchant("Chipotle").withOfferNotification(true)).code);
        List<String> hourly = Arrays.asList("New Delivery!", "New Order: Go to Chick-fil-A", "$15/active hr + tips");
        assertEquals(OfferRule.Code.HOURLY_MODE, eval(hourly, AVOID, CTX.withMerchant("Chick-fil-A").withOfferNotification(true)).code);
        OfferRule.Decision limited = eval(REAL, AVOID.withMaxDeclinesPerHour(2), CTX.withMerchant("Chick-fil-A").withOfferNotification(true).withDeclinesInLastHour(2));
        assertEquals(OfferRule.Result.REVIEW, limited.result); assertEquals(OfferRule.Code.DECLINE_LIMIT, limited.code);
    }
    @Test public void addOnNotificationFromAProvenAvoidedMerchantDeclines() {
        List<String> addOn = Arrays.asList("New Order: Go to Chick-fil-A", "Add to your route");
        OfferRule.Decision d = OfferRule.evaluate(OfferParser.parse(addOn), addOn, AVOID, CTX.withAddOn(null).withMerchant("Chick-fil-A").withOfferNotification(true));
        assertEquals(OfferRule.Result.DECLINE, d.result); assertEquals(OfferRule.Code.AVOIDED_STORE, d.code);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(OfferParser.parse(addOn), addOn, AVOID, CTX.withAddOn(null).withMerchant("Chick-fil-A")).result);
    }
    @Test public void contextCopiesKeepTheSignal() {
        EvaluationContext c = CTX.withOfferNotification(true).withMerchant("X").withAddOn(null).withDeclinesInLastHour(3);
        assertTrue(c.offerNotification); assertFalse(CTX.offerNotification); assertEquals(3, c.declinesInLastHour);
    }
}
