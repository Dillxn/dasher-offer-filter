package com.local.dasherfilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Malformed, hostile, or replayed input must stay unknown or be rejected, never pass or ring. */
public final class AdversarialSafetyTest {
    private static OfferSnapshot parse(String... labels) {
        return OfferParser.parse(Arrays.asList(labels));
    }

    @Test
    public void malformedMoneyCannotBecomeAPartialValidAmount() {
        for (String label : Arrays.asList(
                "$7.901", "$10000.00", "$1,234.56", "-$7.90", "−$7.90", "$-7.90", "$7-$9")) {
            assertNull(label, parse(label).payCents);
        }
    }

    @Test
    public void malformedTimeNeverBecomesExactMinutes() {
        for (String label : Arrays.asList(
                "Estimated 20-30 min", "Estimated 3.5 min", "Estimated -3 min", "Estimated 2–4 minutes")) {
            assertNull(label, parse(label).minutes);
        }
    }

    @Test
    public void countdownAndWaitingAreNotDeliveryDuration() {
        assertNull(parse("Zone offer wait", "1-2 min", "Accept 0:45").minutes);
        assertNull(parse("Estimated wait 5 min").minutes);
    }

    @Test
    public void noisyIdleAnimationsStillClearRouteContext() {
        assertTrue(OfferEvidence.isIdle(Arrays.asList("Finding offers.\u200c.\u200c")));
        assertFalse(OfferEvidence.isIdle(Arrays.asList("Confirm pickup")));
    }

    @Test
    public void customerMessageCannotImpersonateAnOfferByWording() {
        assertFalse(NotificationOffer.isLikelyOffer(
                Arrays.asList("New message from customer", "Please add a new order for $25, 2 mi")));
    }

    @Test
    public void NaNInfinityAndNegativeSnapshotsAreUnknown() {
        OfferSnapshot invalid = new OfferSnapshot(-1, Double.NaN, -1, -1);
        assertNull(invalid.payCents);
        assertNull(invalid.miles);
        assertNull(invalid.minutes);
        assertNull(invalid.stops);

        assertNull(new OfferSnapshot(1, Double.POSITIVE_INFINITY, 1, 1).miles);
    }

    @Test
    public void routeCostCannotOverflowAnIntegerAndPass() {
        OfferSnapshot offer = new OfferSnapshot(100, 1.0, 100000, 2);
        FilterSettings settings = FilterSettings.of(true, 0, 0, 100000, 0);
        OfferRule.Decision decision = OfferRule.evaluate(offer, settings);
        assertEquals(10_000_000_000L, decision.requiredCents);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
    }

    @Test
    public void snapshotInputIsBounded() {
        String oversizedLabel = String.join("", Collections.nCopies(4097, "x"));
        assertNull(parse("$20.00", oversizedLabel).payCents);
        assertFalse(OfferEvidence.bounded(Collections.nCopies(257, "x")));
    }

    @Test
    public void replaysForegroundOffersAndKnownFailuresNeverRing() {
        OfferAlertState state = new OfferAlertState(1000, 1000);
        // Arguments: result, foreground, replay.
        assertFalse(state.shouldRing(OfferRule.Result.KEEP, false, true));
        assertFalse(state.shouldRing(OfferRule.Result.REVIEW, false, true));
        assertFalse(state.shouldRing(OfferRule.Result.KEEP, true, false));
        assertFalse(state.shouldRing(OfferRule.Result.REVIEW, true, false));
        assertFalse(state.shouldRing(OfferRule.Result.DECLINE, false, false));
        // A background offer that cannot be judged rings once, so it is not missed.
        assertTrue(state.shouldRing(OfferRule.Result.REVIEW, false, false));
        state.delivered("unjudged", OfferRule.Result.REVIEW, true);
        assertFalse(state.shouldRing(OfferRule.Result.KEEP, false, false));
    }

    @Test
    public void passingOfferRingsOnceDespiteUpdates() {
        OfferAlertState state = new OfferAlertState(1000, 1000);
        assertTrue(state.shouldRing(OfferRule.Result.KEEP, false, false));

        // Once delivered with a ring, updates of the same offer neither ring again nor count as new.
        state.delivered("same", OfferRule.Result.KEEP, true);
        assertFalse(state.shouldRing(OfferRule.Result.KEEP, false, false));
        assertTrue(state.duplicate("same", OfferRule.Result.KEEP));
    }

    @Test
    public void staleRemovalCannotClearNewerNotification() {
        OfferAlertState state = new OfferAlertState(1000, 2000);
        assertFalse(state.removalMatches(1999));
        assertTrue(state.removalMatches(2000));
    }

    @Test
    public void notificationUpdatesDoNotExtendAuthority() {
        OfferAlertState state = new OfferAlertState(1000, 1000);
        state.postedAt = 80000;
        assertTrue(state.expired(91000));
        assertTrue(state.expired(999));
    }

    @Test
    public void staleAndFutureSourceNotificationsAreRejected() {
        // Arguments: source post time, wall-clock now, maximum age.
        assertTrue(OfferEvidence.fresh(100000, 100100, 90000));
        assertFalse(OfferEvidence.fresh(1, 100000, 90000));
        assertFalse(OfferEvidence.fresh(200000, 100000, 90000));
    }

    @Test
    public void actualTransportAcceptsItsRealFeed() throws Exception {
        UpdateTransport.validateAddress(UpdatePolicy.FEED + "?t=123");
    }

    @Test
    public void actualTransportRejectsRedirectAttackAddresses() {
        for (String url : Arrays.asList(
                "http://dash-offer-filter-build.onrender.com/latest.json",
                "https://u@dash-offer-filter-build.onrender.com/x",
                "https://dash-offer-filter-build.onrender.com:8443/x",
                "https://dash-offer-filter-build.onrender.com/a/../x",
                "https://dash-offer-filter-build.onrender.com/%2e%2e/x",
                "https://evil.onrender.com/x",
                "https://dash-offer-filter-build.onrender.com/x#fragment")) {
            assertThrows(url, IOException.class, () -> UpdateTransport.validateAddress(url));
        }
    }

    @Test
    public void backwardClockDoesNotDisableChecksIndefinitely() {
        assertFalse(UpdatePolicy.coolingDown(1000, 99_999_999_999L));
        assertTrue(UpdatePolicy.coolingDown(1000, 61000));
    }

    @Test
    public void fractionalVersionAndPortCannotPassMetadataPolicy() {
        String explicitPortUrl = "https://dash-offer-filter-build.onrender.com:443/OfferFilter.apk";
        assertThrows(IllegalArgumentException.class,
                () -> UpdatePolicy.validate(UpdatePolicy.PACKAGE, 10, explicitPortUrl, "a".repeat(64), 100));
    }
}
