package com.local.dasherfilter;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Existing standalone-rule cases retained; add-on fixtures now explicitly identify added values. */
public final class OfferRuleTest {
    private OfferSnapshot p(String... text) { return OfferParser.parse(Arrays.asList(text)); }
    @Test public void parsesAnOfferWithExplicitValues() {
        OfferSnapshot o = p("$8.50 Guaranteed", "4.2 mi", "Estimated time 24 min", "3 stops");
        assertEquals(Integer.valueOf(850), o.payCents); assertEquals(4.2, o.miles, .001); assertEquals(Integer.valueOf(24), o.minutes); assertEquals(Integer.valueOf(3), o.stops);
    }
    @Test public void declinesWhenKnownFloorFailsEvenIfTimeIsMissing() { assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(p("$5.00 Guaranteed", "4 mi"), new FilterSettings(true,600,150,30,100,0)).result); }
    @Test public void leavesAmbiguousOffersForReview() {
        FilterSettings s = new FilterSettings(true,600,150,30,100,0); OfferSnapshot o=p("$8.00","$9.00","3 mi"); assertNull(o.payCents);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(o,s).result); assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(p("$10.00 Guaranteed","3 mi"),s).result);
    }
    @Test public void ignoresAnUnrelatedEarningsAmountWhenPayIsGuaranteed() { assertEquals(Integer.valueOf(850),p("$100.00 Weekly earnings","Guaranteed","$8.50","4 mi").payCents); }
    @Test public void chargesOnlyForStopsBeyondPickupAndDropoff() {
        OfferRule.Decision d=OfferRule.evaluate(p("$7.00 Guaranteed","1 mi","3 stops"),new FilterSettings(true,600,100,0,200,0)); assertEquals(OfferRule.Result.DECLINE,d.result); assertEquals(800L,d.requiredCents);
    }
    @Test public void readsMileageAndStopCountsSplitAcrossSiblingNodes() {
        OfferSnapshot o=OfferParser.parse(Arrays.asList("$8.50","4.2","mi","3","stops"),OfferParser.joinMetricSiblings(Arrays.asList("4.2","mi","3","stops"))); assertEquals(4.2,o.miles,.001); assertEquals(Integer.valueOf(3),o.stops);
    }
    @Test public void doesNotJoinUnrelatedNumbersAcrossTheScreen() { assertNull(p("$8.50","4.2","mi").miles); assertEquals(0,OfferParser.joinMetricSiblings(Arrays.asList("4.2","Restaurant","mi")).size()); }
    @Test public void readsUnicodeSpacingAndSingularMile() { assertEquals(4.2,p("$8.50","4.2\u202fmi").miles,.001); assertEquals(1.0,p("$8.50","1\u00a0mile").miles,.001); }
    @Test public void usesExplicitTotalDistanceWhenOtherDistancesArePresent() {
        assertEquals(6.4,p("$8.50","1 mi","Total distance: 6.4 mi").miles,.001); assertEquals(6.4,p("$8.50","1 mi","6.4 mi total").miles,.001);
        assertNull(p("$8.50","Total 4.2 mi","Total 6.4 mi").miles); assertNull(p("$8.50","4.2 mi","6.4 mi").miles);
    }
    @Test public void joinsSplitTotalLabelsWithoutChangingPay() {
        OfferSnapshot o=OfferParser.parse(Arrays.asList("$8.50","1 mi","Total distance","4.2","mi"),OfferParser.joinMetricSiblings(Arrays.asList("Total distance","4.2","mi"))); assertEquals(Integer.valueOf(850),o.payCents); assertEquals(4.2,o.miles,.001);
    }
    @Test public void doesNotTreatAMileageRangeAsAnExactDistance() { assertNull(p("$8.50","3–5 miles").miles); assertNull(p("$8.50","3 - 5 mi").miles); }
    @Test public void readsExplicitRouteCountsWithoutGuessingFromItemsOrOrders() { assertEquals(Integer.valueOf(4),p("$8.50","2 pickups • 2 drop-offs").stops); assertEquals(Integer.valueOf(3),p("$8.50","Stops: 3").stops); assertNull(p("$8.50","3 items","2 orders","Pickup","Customer dropoff").stops); }
    @Test public void twentyDollarRuleDoesNotDependOnMileageOrStops() {
        FilterSettings s=new FilterSettings(true,2000,0,0,0,0); assertEquals(OfferRule.Result.DECLINE,OfferRule.evaluate(new OfferSnapshot(1999,null,null,null),s).result); assertEquals(OfferRule.Result.KEEP,OfferRule.evaluate(new OfferSnapshot(2000,null,null,null),s).result); assertEquals(OfferRule.Result.KEEP,OfferRule.evaluate(new OfferSnapshot(2001,null,null,null),s).result);
    }
    @Test public void threeDollarOfferFailsTwentyTwoDollarFloorWithOtherValuesMissing() {
        OfferSnapshot o=p("$3.00 Guaranteed","Accept, 30 seconds","Decline"); assertEquals(Integer.valueOf(300),o.payCents); OfferRule.Decision d=OfferRule.evaluate(o,new FilterSettings(true,2200,150,30,100,2)); assertEquals(OfferRule.Result.DECLINE,d.result); assertEquals(2200L,d.requiredCents);
    }
    @Test public void risingRuleRequiresStrictlyMoreThanLastAcceptedPay() {
        FilterSettings s=new FilterSettings(true,2200,0,0,0,0,true,2500); assertEquals(OfferRule.Result.DECLINE,OfferRule.evaluate(new OfferSnapshot(2499,null,null,null),s).result); assertEquals(OfferRule.Result.DECLINE,OfferRule.evaluate(new OfferSnapshot(2500,null,null,null),s).result); assertEquals(OfferRule.Result.KEEP,OfferRule.evaluate(new OfferSnapshot(2501,null,null,null),s).result); assertEquals(2501L,OfferRule.evaluate(new OfferSnapshot(2500,null,null,null),s).requiredCents);
    }
    @Test public void risingRuleStartsWithNormalRulesAndCanBeDisabled() {
        FilterSettings s=new FilterSettings(true,2200,0,0,0,0,true,0); assertEquals(OfferRule.Result.KEEP,OfferRule.evaluate(new OfferSnapshot(2200,null,null,null),s).result); assertEquals(OfferRule.Result.DECLINE,OfferRule.evaluate(new OfferSnapshot(2199,null,null,null),s).result); assertEquals(OfferRule.Result.KEEP,OfferRule.evaluate(new OfferSnapshot(2200,null,null,null),new FilterSettings(true,2200,0,0,0,0,false,2500)).result);
    }
    @Test public void risingRuleCombinesWithPriceAndStopRulesWithoutDoubleCharging() {
        FilterSettings s=new FilterSettings(true,2200,0,0,100,3,true,2500); assertEquals(2501L,OfferRule.evaluate(new OfferSnapshot(2501,null,null,3),s).requiredCents); assertEquals(OfferRule.Result.KEEP,OfferRule.evaluate(new OfferSnapshot(2501,null,null,3),s).result); assertEquals(OfferRule.Result.DECLINE,OfferRule.evaluate(new OfferSnapshot(9900,null,null,4),s).result); assertEquals(OfferRule.Result.REVIEW,OfferRule.evaluate(new OfferSnapshot(null,null,null,3),s).result); assertEquals(OfferRule.Result.DECLINE,OfferRule.evaluate(new OfferSnapshot(300,null,null,null),s).result); assertEquals(OfferRule.Result.DECLINE,OfferRule.evaluate(new OfferSnapshot(2600,null,null,null),new FilterSettings(true,3000,0,0,0,0,true,2500)).result);
    }
    @Test public void maximumStopsIsInclusiveAndWorksWithoutPay() {
        FilterSettings s=new FilterSettings(true,0,0,0,0,3); assertEquals(OfferRule.Result.KEEP,OfferRule.evaluate(new OfferSnapshot(null,null,null,2),s).result); assertEquals(OfferRule.Result.KEEP,OfferRule.evaluate(new OfferSnapshot(null,null,null,3),s).result); OfferRule.Decision d=OfferRule.evaluate(new OfferSnapshot(null,null,null,4),s); assertEquals(OfferRule.Result.DECLINE,d.result); assertEquals("DECLINE: 4 stops exceeds maximum 3",d.summary());
    }
    @Test public void zeroDisablesMaximumStops() { FilterSettings s=new FilterSettings(true,2000,0,0,0,0); assertEquals(OfferRule.Result.KEEP,OfferRule.evaluate(new OfferSnapshot(2000,null,null,10),s).result); assertEquals(OfferRule.Result.KEEP,OfferRule.evaluate(new OfferSnapshot(2000,null,null,null),s).result); }
    @Test public void unknownStopsNeedReviewUnlessPriceAlreadyFails() {
        assertEquals(OfferRule.Result.REVIEW,OfferRule.evaluate(new OfferSnapshot(null,null,null,null),new FilterSettings(true,0,0,0,0,2)).result); FilterSettings s=new FilterSettings(true,2000,0,0,0,2); assertEquals(OfferRule.Result.REVIEW,OfferRule.evaluate(new OfferSnapshot(2000,null,null,null),s).result); assertEquals(OfferRule.Result.DECLINE,OfferRule.evaluate(new OfferSnapshot(1999,null,null,null),s).result);
    }
    @Test public void eitherPriceOrStopLimitCanDeclineAnOffer() {
        FilterSettings s=new FilterSettings(true,2000,0,0,0,2); assertEquals(OfferRule.Result.DECLINE,OfferRule.evaluate(new OfferSnapshot(9999,null,null,3),s).result); assertEquals(OfferRule.Result.DECLINE,OfferRule.evaluate(new OfferSnapshot(null,null,null,3),s).result); assertEquals(OfferRule.Result.REVIEW,OfferRule.evaluate(new OfferSnapshot(null,null,null,2),s).result); assertEquals(OfferRule.Result.DECLINE,OfferRule.evaluate(new OfferSnapshot(1999,null,null,2),s).result); assertEquals(OfferRule.Result.KEEP,OfferRule.evaluate(new OfferSnapshot(2000,null,null,2),s).result);
    }
    @Test public void ambiguousStopCountsDoNotTriggerTheCeiling() {
        FilterSettings s=new FilterSettings(true,0,0,0,0,2);
        for(String[] labels:new String[][]{{"$25.00","2 stops","4 stops"},{"$25.00","2–4 stops"},{"$25.00","Stops: 2-4"},{"$25.00","2.5 stops"},{"$25.00","Stops: 2.5"},{"$25.00","0 stops"}}){OfferSnapshot o=OfferParser.parse(Arrays.asList(labels));assertNull(o.stops);assertEquals(OfferRule.Result.REVIEW,OfferRule.evaluate(o,s).result);}
    }
    @Test public void addOnUsesMarginalEconomicsAndCombinedRoute() {
        FilterSettings s=new FilterSettings(true,2000,150,0,0,0); OfferSnapshot active=new OfferSnapshot(2500,10.0,null,2);
        AddOnOffer good=AddOnOffer.parse(active,p("$3.00","1 mi"),Arrays.asList("Add to route","+$3.00","+1 mi")); assertEquals(OfferRule.Result.KEEP,OfferRule.evaluateAddOn(good,s).result);
        AddOnOffer bad=AddOnOffer.parse(active,p("$3.00","4 mi"),Arrays.asList("Add to route","+$3.00","+4 mi")); OfferRule.Decision d=OfferRule.evaluateAddOn(bad,s); assertEquals(OfferRule.Result.DECLINE,d.result);assertEquals(600L,d.requiredCents);
    }
    @Test public void addOnFlatMinimumAppliesToCombinedRouteNotTwice() { AddOnOffer a=AddOnOffer.parse(new OfferSnapshot(2200,null,null,null),p("$3.00"),Arrays.asList("Add to route","+$3.00"));assertEquals(OfferRule.Result.KEEP,OfferRule.evaluateAddOn(a,new FilterSettings(true,2200,0,0,0,0)).result); }
    @Test public void addOnUsesCombinedStopCeilingAndMarginalStopFee() {
        AddOnOffer a=AddOnOffer.parse(new OfferSnapshot(2500,null,null,2),p("$3.00","2 stops"),Arrays.asList("Add to route","+$3.00","+2 stops"));assertEquals(OfferRule.Result.DECLINE,OfferRule.evaluateAddOn(a,new FilterSettings(true,0,0,0,0,3)).result); OfferRule.Decision d=OfferRule.evaluateAddOn(a,new FilterSettings(true,0,0,0,200,0));assertEquals(OfferRule.Result.DECLINE,d.result);assertEquals(400L,d.requiredCents);
    }
    @Test public void addOnDoesNotRequireMarginalPayToBeatPriorFullOrder() {
        AddOnOffer a=AddOnOffer.parse(new OfferSnapshot(2500,10.0,null,2),p("$3.00","1 mi"),Arrays.asList("Add to route","+$3.00","+1 mi"));assertEquals(OfferRule.Result.KEEP,OfferRule.evaluateAddOn(a,new FilterSettings(true,2000,100,0,0,0,true,2500)).result);
    }
    @Test public void addOnMissingEnabledMarginalMetricRequiresReviewUnlessKnownFailure() {
        FilterSettings s=new FilterSettings(true,0,150,30,0,0);OfferSnapshot active=new OfferSnapshot(2500,10.0,50,2);
        assertEquals(OfferRule.Result.REVIEW,OfferRule.evaluateAddOn(AddOnOffer.parse(active,p("$5.00","2 mi"),Arrays.asList("Add to route","+$5.00","+2 mi")),s).result);
        assertEquals(OfferRule.Result.DECLINE,OfferRule.evaluateAddOn(AddOnOffer.parse(active,p("$2.00","3 mi"),Arrays.asList("Add to route","+$2.00","+3 mi")),s).result);
    }
}
