package com.local.dasherfilter;
import org.junit.Test;
import java.util.*;
import java.io.IOException;
import static org.junit.Assert.*;

public final class AdversarialSafetyTest {
    private OfferSnapshot p(String... labels) { return OfferParser.parse(Arrays.asList(labels)); }
    @Test public void malformedMoneyCannotBecomeAPartialValidAmount() { for(String s:Arrays.asList("$7.901","$10000.00","$1,234.56","-$7.90","−$7.90","$-7.90","$7-$9")) assertNull(s,p(s).payCents); }
    @Test public void malformedTimeNeverBecomesExactMinutes() { for(String s:Arrays.asList("Estimated 20-30 min","Estimated 3.5 min","Estimated -3 min","Estimated 2–4 minutes")) assertNull(s,p(s).minutes); }
    @Test public void countdownAndWaitingAreNotDeliveryDuration() { assertNull(p("Zone offer wait","1-2 min","Accept 0:45").minutes); assertNull(p("Estimated wait 5 min").minutes); }
    @Test public void noisyIdleAnimationsStillClearRouteContext() { assertTrue(OfferEvidence.isIdle(Arrays.asList("Finding offers.\u200c.\u200c"))); assertFalse(OfferEvidence.isIdle(Arrays.asList("Confirm pickup"))); }
    @Test public void customerMessageCannotImpersonateAnOfferByWording() { assertFalse(NotificationOffer.isLikelyOffer(Arrays.asList("New message from customer","Please add a new order for $25, 2 mi"))); }
    @Test public void NaNInfinityAndNegativeSnapshotsAreUnknown() { OfferSnapshot s=new OfferSnapshot(-1,Double.NaN,-1,-1); assertNull(s.payCents);assertNull(s.miles);assertNull(s.minutes);assertNull(s.stops);assertNull(new OfferSnapshot(1,Double.POSITIVE_INFINITY,1,1).miles); }
    @Test public void routeCostCannotOverflowAnIntegerAndPass() { OfferRule.Decision d=OfferRule.evaluate(new OfferSnapshot(100,1.0,100000,2),new FilterSettings(true,0,0,100000,0,0));assertEquals(10_000_000_000L,d.requiredCents);assertEquals(OfferRule.Result.DECLINE,d.result); }
    @Test public void snapshotInputIsBounded() { assertNull(p("$20.00",String.join("",Collections.nCopies(4097,"x"))).payCents);assertFalse(OfferEvidence.bounded(Collections.nCopies(257,"x"))); }
    @Test public void reviewAndReplayNeverRing() { OfferAlertState s=new OfferAlertState(1000,1000,1000);assertFalse(s.shouldRing(OfferRule.Result.REVIEW,false,false));assertFalse(s.shouldRing(OfferRule.Result.KEEP,false,true));assertFalse(s.shouldRing(OfferRule.Result.KEEP,true,false)); }
    @Test public void passingOfferRingsOnceDespiteUpdates() { OfferAlertState s=new OfferAlertState(1000,1000,1000);assertTrue(s.shouldRing(OfferRule.Result.KEEP,false,false));s.delivered("same",OfferRule.Result.KEEP,true);assertFalse(s.shouldRing(OfferRule.Result.KEEP,false,false));assertTrue(s.duplicate("same",OfferRule.Result.KEEP)); }
    @Test public void staleRemovalCannotClearNewerNotification() { OfferAlertState s=new OfferAlertState(1000,2000,1000);assertFalse(s.removalMatches(1999));assertTrue(s.removalMatches(2000)); }
    @Test public void notificationUpdatesDoNotExtendAuthority() { OfferAlertState s=new OfferAlertState(1000,1000,1000);s.postedAt=80000;assertTrue(s.expired(91000));assertTrue(s.expired(999)); }
    @Test public void staleAndFutureSourceNotificationsAreRejected() { assertTrue(OfferEvidence.fresh(100000,100100,90000));assertFalse(OfferEvidence.fresh(1,100000,90000));assertFalse(OfferEvidence.fresh(200000,100000,90000)); }
    @Test public void actualTransportAcceptsItsRealFeed() throws Exception { UpdateTransport.validateAddress(UpdatePolicy.FEED+"?t=123"); }
    @Test public void actualTransportRejectsRedirectAttackAddresses() {
        for(String url:Arrays.asList("http://dash-offer-filter-build.onrender.com/latest.json","https://u@dash-offer-filter-build.onrender.com/x","https://dash-offer-filter-build.onrender.com:8443/x","https://dash-offer-filter-build.onrender.com/a/../x","https://dash-offer-filter-build.onrender.com/%2e%2e/x","https://evil.onrender.com/x","https://dash-offer-filter-build.onrender.com/x#fragment")) assertThrows(url,IOException.class,()->UpdateTransport.validateAddress(url));
    }
    @Test public void backwardClockDoesNotDisableChecksIndefinitely() { assertFalse(UpdatePolicy.coolingDown(1000,99_999_999_999L));assertTrue(UpdatePolicy.coolingDown(1000,61000)); }
    @Test public void fractionalVersionAndPortCannotPassMetadataPolicy() { assertThrows(IllegalArgumentException.class,()->UpdatePolicy.validate(UpdatePolicy.PACKAGE,10,"https://dash-offer-filter-build.onrender.com:443/OfferFilter.apk","a".repeat(64),100)); }
}
