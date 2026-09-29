package com.local.dasherfilter;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.*;

/**
 * Deterministic property test over generated label lists: nothing throws, no negative/NaN facts, unknown pay never
 * passes a pay rule, store rules never pass, Earn by Time is always review, and a pass always covers its requirement.
 */
public final class ParserFuzzTest {
    private static final String[] TOKENS = {
            "$7.50", "$28.24", "$8", "$0.00", "$1,234.56", "$10000.00", "$-3.00", "−$7.90", "$7.901", "$7-$9", "+$2.00", "+$5.00 Peak Pay", "$7.50+",
            "$15/active hr", "/active hr", "+ tips", "plus tips", "$1.21/mi", "$17.50/hr", "per hour", "an hour", "hourly", "$25 an hour",
            "4.2 mi", "4.2", "mi", "0.3 mi", "7.2 miles", "14 Mile Rd", "4½ mi", "4 1/2 mi", "3–5 miles", "999 mi", "+2.1 mi", "Total distance: 6.4 mi", "6.4 mi total",
            "21 min", "1 hr 5 min", "1h", "2 hrs", "1.5 hr", "Estimated time 24 min", "Estimated 20-30 min", "Estimated 1 hr • 5 min", "Wait 5 min", "+10 min", "1-2 min",
            "Deliver by 7:45 PM", "Accept by 7:45", "0:32", "32 secs", "Expires in 0:32", "remaining", "29",
            "Accept", "Accept 29", "Decline", "Decline offer", "Add to route", "Add to route 77", "Cancel", "Go back", "Are you sure you want to decline?",
            "Chick-fil-A", "Mr. Pollo", "Taco Bell", "Balance Bowls", "Guaranteed", "Guaranteed (incl. tips)", "incl. tips", "Total pay", "Total may be higher",
            "Includes a $15.00 Flash offer boost", "Includes DoorDash pay and customer tip", "Customer tip $4.00", "New total $30.00", "+$3.00",
            "2 stops", "3 stops (7.2 mi) • 21 min", "Stops: 2-4", "Stops: 3", "2 pickups • 2 drop-offs", "+1 stop", "99 stops", "0 stops",
            "From when you accept to when you complete this offer", "Earn by Time", "New Order", "New Order: Go to Chick-fil-A", "Flash offer available",
            "3.2mi offer from Mr. Pollo. Tap to view offer details.", "New message from customer", "Weekly earnings", "Add to your route",
            "", "   ", " ", "​", "½", "⁄", "𝟕", "$", "+", "/", "•", "(", ")"};
    private static final String[] JOINERS = {" ", " • ", "", " · ", "\n", ", "};

    @Test public void generatedLabelListsKeepEveryInvariant() {
        Random random = new Random(0x0FFE12L);
        EvaluationContext ctx = EvaluationContext.at(1_700_000_000_000L);
        int[] seen = new int[OfferRule.Result.values().length];
        int hourlyCount = 0, knownPay = 0, addOns = 0;
        for (int iteration = 0; iteration < 12_000; iteration++) {
            List<String> labels = new ArrayList<>();
            int n = random.nextInt(12);
            for (int i = 0; i < n; i++) {
                String label = TOKENS[random.nextInt(TOKENS.length)];
                if (random.nextInt(4) == 0) label = label + JOINERS[random.nextInt(JOINERS.length)] + TOKENS[random.nextInt(TOKENS.length)];
                labels.add(label);
            }
            String where = "#" + iteration + " " + labels;
            OfferSnapshot o = OfferParser.parse(labels, OfferParser.joinMetricSiblings(labels));
            assertFalse(where, o.payCents != null && o.payCents < 0);
            assertFalse(where, o.miles != null && (o.miles.isNaN() || o.miles.isInfinite() || o.miles < 0));
            assertFalse(where, o.minutes != null && o.minutes < 0);
            assertFalse(where, o.stops != null && o.stops <= 0);
            boolean hourly = OfferParser.isHourlyMode(labels);
            if (hourly) { assertNull(where, o.payCents); hourlyCount++; }
            if (o.payCents != null) knownPay++;
            if (AddOnOffer.isLikely(labels)) addOns++;

            FilterSettings payOnly = payRules(random);
            OfferRule.Decision standalone = OfferRule.evaluate(o, Collections.emptyList(), payOnly, ctx);
            OfferRule.Decision full = OfferRule.evaluate(o, labels, payOnly, ctx);
            for (OfferRule.Decision d : new OfferRule.Decision[]{standalone, full}) {
                assertNotNull(where, d.summary()); assertTrue(where, d.requiredCents >= 0);
                if (d.result == OfferRule.Result.KEEP) assertTrue(where, o.payCents != null || AddOnOffer.isLikely(labels));
            }
            seen[full.result.ordinal()]++;
            if (o.payCents == null) assertNotEquals(where, OfferRule.Result.KEEP, standalone.result);
            if (standalone.result == OfferRule.Result.KEEP) assertTrue(where, o.payCents >= standalone.requiredCents);
            if (hourly) assertEquals(where, OfferRule.Code.HOURLY_MODE, full.code);

            FilterSettings storesOnly = new FilterSettings(true, 0, 0, 0, 0, 0).withAvoidStores(java.util.Arrays.asList("Chick-fil-A", "Taco Bell"));
            assertNotEquals(where, OfferRule.Result.KEEP, OfferRule.evaluate(o, labels, storesOnly, ctx.withMerchant(NotificationOffer.merchant(labels))).result);

            AddOnOffer addOn = AddOnOffer.parse(new OfferSnapshot(2500, 5.0, 30, 2), o, labels);
            for (OfferSnapshot s : new OfferSnapshot[]{addOn.incremental, addOn.combined}) {
                assertFalse(where, s.payCents != null && s.payCents < 0);
                assertFalse(where, s.miles != null && (s.miles.isNaN() || s.miles < 0));
            }
            OfferRule.Decision addOnDecision = OfferRule.evaluateAddOn(addOn, labels, payOnly, ctx);
            if (addOnDecision.result == OfferRule.Result.KEEP) assertNotNull(where, addOn.incremental.payCents);

            boolean hasAccept = false; for (String label : labels) hasAccept |= OfferControls.isButton(label, "accept");
            DeclineConfirmation.select(labels, labels, hasAccept);
            assertNotNull(DeclineState.offerKey(o, labels));
            NotificationOffer.isLikelyOffer(labels);
            assertTrue(where, NotificationOffer.merchant(labels).length() <= 60);
            assertNotNull(OfferMetrics.summary(o));
        }
        // The generator must actually reach every verdict and shape, or the invariants above prove little.
        for (OfferRule.Result r : OfferRule.Result.values()) assertTrue(r + " " + seen[r.ordinal()], seen[r.ordinal()] > 50);
        assertTrue("hourly " + hourlyCount, hourlyCount > 200); assertTrue("pay " + knownPay, knownPay > 500); assertTrue("add-ons " + addOns, addOns > 500);
    }

    private static final String[] STORES = {"Chick-fil-A", "Mr. Pollo", "P.F. Chang's", "McDonald's", "Taco Bell", "Five Guys", "7-Eleven", "Café Rio"};
    private static final String[] SAFE_NOISE = {"Deliver by 7:45 PM", "0:32", "Accept 29", "32 secs", "Total may be higher", "Earn more with Peak Pay",
            "Customer may tip more", "+$2.00 Peak Pay", "Pickup", "Customer dropoff", "3 items", "Expires in 0:31"};

    /** Realistic cards in several layouts with random values: facts must be recovered exactly and the verdict must match an independent oracle. */
    @Test public void synthesizedCardsParseExactlyAndMatchTheArithmeticOracle() {
        Random random = new Random(0xD45E5L);
        EvaluationContext ctx = EvaluationContext.at(1_700_000_000_000L);
        int keeps = 0, declines = 0;
        for (int iteration = 0; iteration < 6_000; iteration++) {
            int pay = 200 + random.nextInt(5800), tenths = 3 + random.nextInt(200), minutes = 5 + random.nextInt(110), stops = 1 + random.nextInt(4);
            String money = "$" + pay / 100 + "." + String.format(java.util.Locale.US, "%02d", pay % 100), miles = tenths / 10 + "." + tenths % 10;
            String stopText = stops + (stops == 1 ? " stop" : " stops"), store = STORES[random.nextInt(STORES.length)];
            List<String> labels = new ArrayList<>();
            int layout = random.nextInt(5);
            switch (layout) {
                case 0: Collections.addAll(labels, money, "Guaranteed (incl. tips)", stopText + " (" + miles + " mi) • " + minutes + " min", store); break;
                case 1: Collections.addAll(labels, money + " Guaranteed", miles + " mi", "Estimated " + minutes + " min", stopText); break;
                case 2: Collections.addAll(labels, money, "Guaranteed", "Includes a $" + (1 + random.nextInt(20)) + ".00 Flash offer boost", stopText + " (" + miles + " mi) • " + minutes + " min"); break;
                case 3: Collections.addAll(labels, "Guaranteed", money, "Includes DoorDash pay and customer tip", miles + " mi • " + minutes + " min • " + stopText); break;
                default: Collections.addAll(labels, money + " incl. tips", miles + " mi", minutes / 60 > 0 ? "Estimated " + minutes / 60 + " hr " + minutes % 60 + " min" : "Estimated " + minutes + " min", stopText, store);
            }
            // Noise goes anywhere except between an amount and its own label (layouts 0, 2 and 3 show them as two adjacent nodes,
            // as in tree order). Separated from its label, the amount is unlabeled and an increment on screen makes it unknown.
            int blockStart = 0, blockLength = layout == 0 || layout == 2 || layout == 3 ? 2 : 1;
            for (int i = random.nextInt(3); i > 0; i--) {
                int at = random.nextInt(labels.size() + 1); String noise = SAFE_NOISE[random.nextInt(SAFE_NOISE.length)];
                if (at > blockStart && at < blockStart + blockLength) at = blockStart + blockLength;
                labels.add(at, noise);
                if (at <= blockStart) blockStart++;
            }
            labels.add("Accept"); labels.add("Decline");
            String where = "#" + iteration + " " + labels;
            OfferSnapshot o = OfferParser.parse(labels);
            assertEquals(where, Integer.valueOf(pay), o.payCents);
            assertEquals(where, tenths / 10.0, o.miles, 0.0);
            assertEquals(where, Integer.valueOf(minutes), o.minutes);
            assertEquals(where, Integer.valueOf(stops), o.stops);
            assertFalse(where, AddOnOffer.isLikely(labels));

            int flat = random.nextInt(4000), perMile = random.nextInt(250), perHour = random.nextInt(4000), extra = random.nextInt(300);
            FilterSettings s = new FilterSettings(true, flat, perMile, 0, perHour, extra, 0, 0, null, false, 0, 0L, 0);
            long byMiles = (perMile * (long) tenths + 9) / 10, byHour = (perHour * (long) minutes + 59) / 60;
            long required = Math.max(flat, Math.max(byMiles, byHour)) + (long) extra * Math.max(0, stops - 2);
            OfferRule.Decision d = OfferRule.evaluate(o, labels, s, ctx);
            if (!s.hasNonStoreRule()) { assertEquals(where, OfferRule.Result.REVIEW, d.result); continue; }
            assertEquals(where, required, d.requiredCents);
            assertEquals(where, pay >= required ? OfferRule.Result.KEEP : OfferRule.Result.DECLINE, d.result);
            if (d.result == OfferRule.Result.KEEP) keeps++; else declines++;
        }
        assertTrue(keeps + "/" + declines, keeps > 500 && declines > 500);
    }
    private static FilterSettings payRules(Random r) {
        int flat = r.nextBoolean() ? r.nextInt(3000) + 1 : 0, mile = r.nextInt(3) == 0 ? r.nextInt(300) : 0;
        int minute = r.nextInt(4) == 0 ? r.nextInt(60) : 0, hour = r.nextInt(3) == 0 ? r.nextInt(4000) : 0, stop = r.nextInt(4) == 0 ? r.nextInt(300) : 0;
        if (flat + mile + minute + hour + stop == 0) flat = 500;
        return new FilterSettings(true, flat, mile, minute, hour, stop, 0, 0, null, r.nextBoolean(), 2500, 1_700_000_000_000L - 60_000L, 0);
    }
}
