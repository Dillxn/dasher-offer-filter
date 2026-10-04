package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Synthetic, dependency-free cases shared by JUnit and the local JDK-only parser audit. */
public final class ParserSafetyCases {
    private static final OfferSnapshot ACTIVE = new OfferSnapshot(1200, 4.0, 20, 2).withItems(12, true);

    public static int rates() {
        Checks c = new Checks("rates");
        for (String unit : Arrays.asList("h", "hr", "hrs", "hour", "hours", "mi", "mile", "miles",
                "min", "mins", "minute", "minutes", "item", "items", "stop", "stops", "order", "orders",
                "delivery", "deliveries", "pickup", "pick-ups", "dropoff", "drop-offs")) {
            for (String separator : Arrays.asList("/", " / ", "/\u00a0", " per ", " PER ")) {
                String rate = "$2" + separator + unit;
                c.eq(null, parse(rate).payCents, "standalone " + rate);
                c.eq(null, parse("Guaranteed", rate).payCents, "labeled rate " + rate);
                c.eq(Integer.valueOf(1200), parse("$12.00", "Guaranteed", rate).payCents,
                        "real payout beside rate " + rate);
                AddOnOffer increment = add("+" + rate);
                c.eq(null, increment.incremental.payCents, "rate is not an increment " + rate);
                c.eq(null, increment.combined.payCents, "rate cannot compose route payout " + rate);
                AddOnOffer total = add("New total " + rate);
                c.eq(null, total.incremental.payCents, "rate cannot be differenced " + rate);
                c.eq(null, total.combined.payCents, "rate is not a total " + rate);
                c.eq(null, parse("+$1", "Decline", rate, "2 stops").payAtMostCents,
                        "rate cannot create a decline ceiling " + rate);
            }
        }
        for (String label : Arrays.asList("$12.00", "$12,00", "$12 Guaranteed", "Total pay $12",
                "$12 Pickup", "$12 Stoplight Market", "$12 from Milk Market", "$12 periwinkle")) {
            c.eq(Integer.valueOf(1200), parse(label).payCents, "exact payout " + label);
        }
        c.eq(Integer.valueOf(600), add("+$6.00").incremental.payCents, "exact increment");
        c.eq(Integer.valueOf(1800), add("+$6.00").combined.payCents, "exact composed payout");
        return c.finish();
    }

    public static int itemFragments() {
        Checks c = new Checks("item-fragments");
        for (String qualifier : Arrays.asList("unique", "distinct", "remaining", "found", "collected",
                "completed", "of", "to", "up to", "at least", "at most", "over", "under", "about", "around",
                "approx", "approximately", "estimate", "estimated", "more than", "less than", "per", "each",
                "add", "adds", "added", "additional", "extra", "more", "+", "-", "\u2212", "–", "—", "~",
                "≈", "/", "<", ">", "≤", "≥", "est", "Est.", "min", "minimum", "max", "maximum", "fewer than", "3", "3 to", "3 /", "First order", "Order #2")) {
            for (List<String> labels : Arrays.asList(Arrays.asList(qualifier, "12", "items"),
                    Arrays.asList("12", "items", qualifier), Arrays.asList(qualifier, "Items:", "12"),
                    Arrays.asList("Items:", "12", qualifier))) {
                OfferSnapshot offer = OfferParser.parse(labels, OfferParser.joinMetricSiblings(labels));
                c.eq(null, offer.items, "qualified fragments " + labels);
                c.eq(true, offer.itemCountApplicable, "declaration survives " + labels);
            }
        }
        for (List<String> labels : Arrays.asList(Arrays.asList("12", "items"), Arrays.asList("Items:", "12"),
                Arrays.asList("Total", "12", "items"), Arrays.asList("Shop & deliver", "12", "items"))) {
            c.eq(Integer.valueOf(12), OfferParser.parse(labels, OfferParser.joinMetricSiblings(labels)).items,
                    "unqualified fragments " + labels);
        }
        for (String duration : Arrays.asList("30 min", "21 minutes", "1 hr 20 min")) {
            for (List<String> labels : Arrays.asList(Arrays.asList("12", "items", duration),
                    Arrays.asList(duration, "Items:", "12"))) {
                c.eq(Integer.valueOf(12), OfferParser.parse(labels, OfferParser.joinMetricSiblings(labels)).items,
                        "complete adjacent duration is not an item bound " + labels);
            }
        }
        for (String unit : Arrays.asList("mi", "miles", "stops", "min", "minutes", "hr", "pickups", "dropoffs")) {
            for (List<String> labels : Arrays.asList(Arrays.asList("12", "items", "3", unit),
                    Arrays.asList("Items:", "12", "3", unit), Arrays.asList(unit, "3", "12", "items"),
                    Arrays.asList(unit, "3", "Items:", "12"))) {
                c.eq(Integer.valueOf(12), OfferParser.parse(labels, OfferParser.joinMetricSiblings(labels)).items,
                        "separate adjacent travel unit remains separate " + labels);
            }
        }
        for (List<String> labels : Arrays.asList(Arrays.asList("12", "items", "3", "items"),
                Arrays.asList("12", "items", "3", "items", "4", "mi"),
                Arrays.asList("3", "items", "of", "12", "items"))) {
            c.eq(null, OfferParser.parse(labels, OfferParser.joinMetricSiblings(labels)).items,
                    "conflicting or progress item pairs cannot lose contradictory evidence " + labels);
        }
        List<String> duplicateItems = Arrays.asList("12", "items", "12", "items");
        c.eq(Integer.valueOf(12), OfferParser.parse(duplicateItems,
                OfferParser.joinMetricSiblings(duplicateItems)).items, "identical explicit pairs remain duplicate readings");
        List<String> route = Arrays.asList("Total", "4.2", "mi", "2", "stops");
        OfferSnapshot travel = OfferParser.parse(route, OfferParser.joinMetricSiblings(route));
        c.eq(Double.valueOf(4.2), travel.miles, "distance siblings unchanged");
        c.eq(Integer.valueOf(2), travel.stops, "stop siblings unchanged");
        return c.finish();
    }

    public static int itemMeaning() {
        Checks c = new Checks("item-meaning");
        for (String label : Arrays.asList("Estimated 12 items", "Estimate: 12 items", "Approx. 12 items", "Max 12 items", "Minimum 12 items", "Fewer than 12 items", "<12 items", "≤12 items",
                "Approximately 12 items", "Estimated total 12 items", "About total 12 items",
                "12 items total (estimated)", "12 items in total, approximately", "12 items additional (estimated)",
                "+12 items total", "12 additional items total", "Unique total items 8", "Distinct total items 8",
                "Unique total 8 items", "Unique total items: 8", "Estimated items: 12", "Estimated total items 12")) {
            c.eq(null, parse(label).items, "ambiguous total " + label);
            AddOnOffer addon = add(label);
            c.eq(null, addon.incremental.items, "ambiguous increment " + label);
            c.eq(null, addon.combined.items, "ambiguous combined count " + label);
        }
        for (int n : new int[]{1, 2, 9, 12, 99, 999, 9999}) {
            for (String label : Arrays.asList(n + " items", "Items: " + n, "Total items " + n,
                    n + " total items", n + " items in total")) {
                c.eq(Integer.valueOf(n), parse(label).items, "explicit total " + label);
            }
            for (String label : Arrays.asList("+" + n + " items", "+ " + n + " items", "Adds " + n + " items",
                    n + " additional items", n + " items more", "Additional items: " + n)) {
                AddOnOffer addon = add(label);
                c.eq(Integer.valueOf(n), addon.incremental.items, "explicit increment " + label);
                c.eq(Integer.valueOf(n + 12), addon.combined.items, "explicit composed items " + label);
                c.eq(null, parse(label).items, "increment is not standalone total " + label);
            }
        }
        c.eq(Integer.valueOf(12), parse("12 items (8 unique)", "8 unique items").items, "units beside products");
        c.eq(Integer.valueOf(12), parse("12 items", "Unique total items 8").items, "ignore unique-only companion");
        c.eq(null, parse("First order: 5 items", "Second order: 5 items").items, "equal subtotals are not total");
        c.eq(Integer.valueOf(10), parse("First order: 5 items", "Second order: 5 items", "Total items: 10").items,
                "explicit route total resolves components");
        return c.finish();
    }

    public static int malformedItems() {
        Checks c = new Checks("malformed-items");
        for (int repeats : new int[]{1, 2, 10, 100, 400, 1000, 1800, 2040}) {
            for (String separator : Arrays.asList(",", ".")) {
                String number = repeat("1" + separator, repeats) + "1";
                for (String label : Arrays.asList("Items: " + number, number + " items", "Total items " + number)) {
                    // Both the direct item reader and the complete parser must survive the same bounded label.
                    try {
                        c.eq(null, ItemCount.parse(Arrays.asList(label)).count, "direct malformed length=" + label.length());
                        c.eq(null, parse(label).items, "integrated malformed length=" + label.length());
                    } catch (StackOverflowError overflow) {
                        c.fail("regex stack overflow, label length=" + label.length());
                    }
                }
            }
        }
        for (String label : Arrays.asList(repeat("9", 4090) + " items", "Items: " + repeat("9", 4089),
                "Items: " + repeat("9", 4090), "0 items", "-2 items", "1.5 items", "10000 items", "Items: 3/5",
                "8 unique items", "12+ items", "10–12 items", "10 to 12 items")) {
            c.eq(null, parse(label).items, "unknown malformed count length=" + label.length());
        }
        c.eq(Integer.valueOf(12), parse("１２\u00a0items").items, "NFKC exact item count");
        c.eq(null, OfferParser.parse(null).items, "missing input");
        c.eq(null, OfferParser.parse(Arrays.asList(null, "")).items, "empty labels");
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 257; i++) tooMany.add("12 items");
        c.eq(null, OfferParser.parse(tooMany).items, "label count bound");
        List<String> tooLong = new ArrayList<>();
        for (int i = 0; i < 9; i++) tooLong.add(repeat("x", 4096));
        c.eq(null, OfferParser.parse(tooLong).items, "total length bound");
        return c.finish();
    }

    public static int ranges() {
        Checks c = new Checks("ranges");
        for (String separator : Arrays.asList("-", "–", "—", " to ", " TO ")) {
            String interval = "3" + separator + "5";
            OfferSnapshot miles = parse("$12", interval + " mi");
            c.eq(null, miles.miles, "mileage interval " + interval);
            c.eq(Integer.valueOf(1200), miles.payCents, "independent payout survives " + interval);
            c.eq(null, parse("Estimated " + interval + " min").minutes, "time interval " + interval);
            c.eq(null, parse(interval + " stops").stops, "stop interval " + interval);
            c.eq(null, parse("Stops: " + interval).stops, "label-first interval " + interval);
            c.eq(null, parse("Guaranteed $3" + separator + "5").payCents, "money interval " + interval);
            AddOnOffer addon = add(interval + " mi extra", interval + " min extra", interval + " stops extra");
            unknownTravel(c, addon, "ranged increment " + interval);
            AddOnOffer conflictingTotal = add("+2 mi", "+2 min", "+2 stops", "Total distance " + interval + " mi",
                    "Total time " + interval + " min", "Total stops " + interval + " stops");
            unknownTravel(c, conflictingTotal, "ranged total cannot be composed " + interval);
            AddOnOffer pay = add("$3" + separator + "5 extra");
            c.eq(null, pay.incremental.payCents, "ranged addon pay " + interval);
            c.eq(null, pay.combined.payCents, "ranged addon total pay " + interval);
        }
        AddOnOffer exact = add("+$6", "+2 mi", "+10 min", "+1 stop", "+3 items");
        c.eq(Integer.valueOf(600), exact.incremental.payCents, "exact added pay");
        c.eq(Double.valueOf(2), exact.incremental.miles, "exact added mileage");
        c.eq(Integer.valueOf(10), exact.incremental.minutes, "exact added time");
        c.eq(Integer.valueOf(1), exact.incremental.stops, "exact added stop");
        c.eq(Integer.valueOf(3), exact.incremental.items, "exact added items");
        c.eq(Double.valueOf(6), exact.combined.miles, "composed mileage");
        c.eq(Integer.valueOf(30), exact.combined.minutes, "composed duration");
        c.eq(Integer.valueOf(3), exact.combined.stops, "composed stops");
        return c.finish();
    }

    public static int unchangedEvidence() {
        Checks c = new Checks("unchanged-evidence");
        OfferSnapshot ordinary = parse("$12.00", "2 stops (7.2 mi) • 21 min");
        c.eq(Integer.valueOf(1200), ordinary.payCents, "ordinary payout");
        c.eq(Double.valueOf(7.2), ordinary.miles, "ordinary mileage");
        c.eq(Integer.valueOf(21), ordinary.minutes, "ordinary duration");
        c.eq(Integer.valueOf(2), ordinary.stops, "ordinary stops");
        c.eq(false, ordinary.itemCountApplicable, "no inferred item applicability");
        OfferSnapshot hotspot = parse("Nearest hotspot", "2.5 mi", "30 min", "$12", "2 stops (7 mi) • 21 min");
        c.eq(Double.valueOf(7), hotspot.miles, "hotspot is not route distance");
        c.eq(Integer.valueOf(21), hotspot.minutes, "hotspot is not route time");
        OfferSnapshot bonus = parse("+$1", "Decline", "$5.75", "2 stops");
        c.eq(null, bonus.payCents, "bonus ceiling not exact pay");
        c.eq(Integer.valueOf(675), bonus.payAtMostCents, "two-stop ceiling preserved");
        OfferSnapshot stack = parse("+$1", "Decline", "$5.75", "Pick up 2 orders", "Multiple dropoffs (2 stops)", "3 stops");
        c.eq(null, stack.payCents, "stack bonus not exact pay");
        c.eq(Integer.valueOf(775), stack.payAtMostCents, "stack ceiling preserved");
        c.eq(null, parse("Add to route", "+$1", "$5.75", "2 stops").payAtMostCents, "no standalone addon ceiling");
        c.eq(null, parse("$12", "$14").payCents, "conflicting pay unknown");
        c.eq(null, parse("6 items", "7 items").items, "conflicting item readings unknown");
        c.eq(Integer.valueOf(12), parse("12 items", "12 items", "Shop for 12 items").items, "no repeated-label sum");
        OfferSnapshot bounded = new OfferSnapshot(null, 4.0, 20, 2, 1500).withItems(12, true);
        c.eq(null, bounded.withoutPayBound().payAtMostCents, "discarded bound");
        c.eq(Integer.valueOf(12), bounded.withoutPayBound().items, "item evidence survives copy");
        c.eq(true, ACTIVE.contradicts(ACTIVE.withItems(13, true)), "item mismatch blocks identity");
        c.eq(false, OfferSnapshot.UNKNOWN.withItems(12, true).agreesWith(ACTIVE), "items alone grant no authority");
        return c.finish();
    }

    private static void unknownTravel(Checks c, AddOnOffer offer, String label) {
        c.eq(null, offer.incremental.miles, label + " miles");
        c.eq(null, offer.combined.miles, label + " combined miles");
        c.eq(null, offer.incremental.minutes, label + " minutes");
        c.eq(null, offer.combined.minutes, label + " combined minutes");
        c.eq(null, offer.incremental.stops, label + " stops");
        c.eq(null, offer.combined.stops, label + " combined stops");
    }

    private static OfferSnapshot parse(String... labels) { return OfferParser.parse(Arrays.asList(labels)); }
    private static AddOnOffer add(String... labels) {
        List<String> full = new ArrayList<>();
        full.add("Add to route");
        full.addAll(Arrays.asList(labels));
        return AddOnOffer.parse(ACTIVE, full);
    }
    private static String repeat(String text, int n) {
        StringBuilder out = new StringBuilder(text.length() * n);
        for (int i = 0; i < n; i++) out.append(text);
        return out.toString();
    }

    private static final class Checks {
        final String group;
        final List<String> failures = new ArrayList<>();
        int count;
        Checks(String group) { this.group = group; }
        void eq(Object expected, Object actual, String context) {
            count++;
            if (!Objects.equals(expected, actual)) failures.add(context + ": expected=" + expected + " actual=" + actual);
        }
        void fail(String message) { count++; failures.add(message); }
        int finish() {
            if (!failures.isEmpty()) throw new AssertionError(group + ": " + failures.size() + "/" + count
                    + " failed; " + failures.subList(0, Math.min(6, failures.size())));
            return count;
        }
    }

    public static void main(String[] args) {
        String[] names = {"rates", "item-fragments", "item-meaning", "malformed-items", "ranges", "unchanged-evidence"};
        java.util.function.IntSupplier[] groups = {ParserSafetyCases::rates, ParserSafetyCases::itemFragments,
                ParserSafetyCases::itemMeaning, ParserSafetyCases::malformedItems, ParserSafetyCases::ranges,
                ParserSafetyCases::unchangedEvidence};
        int checks = 0, failures = 0;
        for (int i = 0; i < groups.length; i++) {
            try {
                int passed = groups[i].getAsInt();
                checks += passed;
                System.out.printf(Locale.US, "PASS %s: %d checks%n", names[i], passed);
            } catch (AssertionError failure) {
                failures++;
                System.out.println("FAIL " + failure.getMessage());
            }
        }
        System.out.printf(Locale.US, "RESULT groups=%d failed_groups=%d passed_checks=%d%n", groups.length, failures, checks);
        if (failures != 0) System.exit(1);
    }

    private ParserSafetyCases() {}
}
