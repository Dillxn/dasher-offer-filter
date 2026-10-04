package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses explicit values only. Partial numbers and ranges never become exact offer facts. */
final class OfferParser {
    private static final Pattern MONEY = Pattern.compile("\\$\\s*(\\d{1,4}(?:[.,]\\d{1,2})?)(?![\\d.,])");
    private static final String MILE_VALUE =
            "(?<![\\d.,+\\-])\\b(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:mi|miles?)\\b";
    private static final Pattern MILES = Pattern.compile("(?i)" + MILE_VALUE);
    /** A hotspot distance is not the offer's travel, nor proof of the final stop's distance to it. */
    private static final Pattern HOTSPOT = Pattern.compile("(?i)\\bhot[ -]?spots?\\b");
    /** Only travel components can continue a hotspot heading; offer wording or stops end that context. */
    private static final Pattern TRAVEL_WORD = Pattern.compile("(?i)\\b(?:new|total|trip|distance|mileage|time|duration"
            + "|estimated|estimate|est|mi|miles?|min|mins|minutes?|hr|hrs|hours?|additional|extra|more|adds?|added)\\b");
    private static final Pattern TRAVEL_REMAINDER = Pattern.compile("[\\d\\s.,:;=+()•·/|\\-–—]*");
    private static final Pattern TOTAL_MILES = Pattern.compile(
            "(?i)(?:total(?: trip)?(?: distance| mileage)?\\s*[:=]?\\s*)" + MILE_VALUE);
    private static final Pattern MILES_TOTAL = Pattern.compile("(?i)" + MILE_VALUE + "\\s*(?:in\\s+)?total\\b");
    /** Hours alone are a duration too; the enclosing metric-line guard excludes waits and hourly rates. */
    private static final Pattern MINUTES = Pattern.compile(
            "(?i)(?<![\\d.,+\\-])\\b(?:(\\d{1,2})\\s*(?:hr|hour)s?(?:\\s*(\\d{1,3})\\s*(?:min|minute)s?)?"
                    + "(?!\\s*[+\\-]?(?:\\d|[.,]\\d))"
                    + "|(\\d{1,3})\\s*(?:min|minute)s?)\\b");
    private static final Pattern STOPS = Pattern.compile("(?i)(?<![\\d.,+\\-])\\b(\\d{1,2})\\s+stops?\\b");
    private static final Pattern STOPS_FIRST = Pattern.compile(
            "(?i)\\b(?:total\\s+)?stops?\\s*[:=]\\s*(\\d{1,2})\\b(?![.,]\\d)");
    private static final Pattern ROUTE_COUNTS = Pattern.compile(
            "(?i)^(\\d{1,2})\\s+pick[ -]?ups?\\s*(?:[,•·+&/|]|and)\\s*"
                    + "(\\d{1,2})\\s+(?:customer\\s+)?drop[ -]?offs?$");
    /** "Multiple dropoffs (2 stops)": a count of one kind of stop, not the route's total. */
    private static final Pattern STOP_BREAKDOWN = Pattern.compile(
            "(?i)(?:pick[ -]?ups?|drop[ -]?offs?)\\s*\\(\\s*\\d{1,2}\\s+stops?\\s*\\)");
    /** These complete labels establish all orders at one pickup, not shopping item quantities. */
    private static final Pattern PICKUP_ORDERS = Pattern.compile(
            "(?i)^pick[ -]?up\\s+([1-9]\\d?)\\s+orders?$");
    private static final Pattern DROPOFF_STOPS = Pattern.compile(
            "(?i)^(?:multiple\\s+)?(?:customer\\s+)?drop[ -]?offs?\\s*\\(\\s*([1-9]\\d?)\\s+stops?\\s*\\)$");
    private static final Pattern ORDER_WORD = Pattern.compile("(?i)\\borders?\\b");
    private static final Pattern PICKUP_WORD = Pattern.compile("(?i)\\bpick[ -]?ups?\\b");
    private static final Pattern DROPOFF_WORD = Pattern.compile("(?i)\\bdrop[ -]?offs?\\b");
    private static final Pattern METRIC_NUMBER = Pattern.compile("\\d{1,3}(?:\\.\\d{1,2})?");
    private static final Pattern METRIC_UNIT = Pattern.compile(
            "(?i)(?:mi\\.?|miles?|stops?|items?|pick[ -]?ups?|(?:customer\\s+)?drop[ -]?offs?)[:=]?");
    /** "+$2.00": an amount added to something else, never a total on its own. */
    private static final Pattern INCREMENT = Pattern.compile("\\+\\s*\\$");
    /** A label that is nothing but a "+$" amount, such as "+$1". */
    private static final Pattern BARE_PLUS_AMOUNT = Pattern.compile("\\+\\s*\\$\\s*(\\d{1,4}(?:[.,]\\d{1,2})?)");
    /** "+2 stops", "+12 min": travel added to a route already under way. */
    private static final Pattern ADDED_TRAVEL = Pattern.compile(
            "(?i)\\+\\s*\\d{1,3}(?:[.,]\\d{1,2})?\\s*(?:mi|miles?|mins?|minutes?|hrs?|hours?|stops?)\\b");
    /** Words that would make a "+$" amount count more than once ("per order", "each", "2x", "up to"). */
    private static final Pattern QUALIFIER = Pattern.compile("(?i)\\b(?:per|each|every|apiece|up\\s+to)\\b"
            + "|/\\s*(?:order|delivery|deliveries|stop|drop[ -]?off|pick[ -]?up)s?\\b|×|\\b\\d+\\s*x\\b|\\bx\\s*\\d");
    private static final Pattern TOTAL_LABEL = Pattern.compile("(?i)total(?: distance| mileage)?[:=]?");
    private static final String TRAILING_SEPARATOR = "[:=]$";
    /** Whitespace and common unit abbreviations do not turn a rate into an offer's total payout. */
    private static final Pattern PAY_RATE = Pattern.compile(
            "(?i)(?:/\\s*|\\bper\\s+)(?:h|hrs?|hours?|mi|miles?|mins?|minutes?|items?|stops?"
                    + "|orders?|deliver(?:y|ies)|pick[ -]?ups?|drop[ -]?offs?)\\b");

    static OfferSnapshot parse(List<String> visibleText) {
        return parse(visibleText, Collections.emptyList());
    }

    /**
     * @param visibleText normalized screen or notification labels; pay and duration are read only from these
     * @param metricParts distance/stop phrases re-assembled from sibling nodes by {@link #joinMetricSiblings}
     */
    static OfferSnapshot parse(List<String> visibleText, List<String> metricParts) {
        if (!OfferEvidence.bounded(visibleText) || !OfferEvidence.bounded(metricParts)) {
            return OfferSnapshot.UNKNOWN;
        }
        List<String> lines = distinctNormalized(visibleText, new ArrayList<>());
        List<String> routeLabels = routeMetricLabels(visibleText);
        List<String> routeLines = distinctNormalized(routeLabels, new ArrayList<>());
        // A metric may have been joined inside a nested subtree whose local siblings omit the hotspot heading.
        // The complete label sequence still identifies that context: do not let the joined copy put it back.
        Set<String> excluded = excludedRouteMetrics(visibleText, routeLabels);
        List<String> safeParts = routeMetricLabels(metricParts);
        safeParts.removeIf(excluded::contains);
        List<String> metrics = distinctNormalized(safeParts, new ArrayList<>(routeLines));
        boolean malformed = OfferEvidence.malformedMoney(lines);
        Integer pay = malformed ? null : parsePay(lines);
        Integer stops = parseStops(metrics);
        Integer payAtMost = malformed || pay != null ? null : payWithPlusAmount(lines, metrics, stops);
        // Item totals do not borrow travel context or add together counts from different labels/orders.
        ItemCount items = ItemCount.parse(distinctNormalized(metricParts, new ArrayList<>(lines)));
        return new OfferSnapshot(pay, parseMiles(metrics), parseMinutes(routeLines), stops, payAtMost)
                .withItems(items.count, items.applicable);
    }

    /**
     * Route-metric input only; never hotspot acquisition. Blank excluded entries preserve sibling boundaries.
     * A hotspot label owns immediately following bare travel components until distinct nonmetric wording or
     * explicit offer evidence (such as a compact stops line) establishes a different context.
     */
    static List<String> routeMetricLabels(List<String> labels) {
        List<String> safe = new ArrayList<>();
        boolean hotspot = false;
        for (String raw : labels) {
            String label = OfferEvidence.normalize(raw);
            if (HOTSPOT.matcher(label).find()) {
                hotspot = true;
                safe.add("");
                continue;
            }
            if (hotspot && TRAVEL_REMAINDER.matcher(TRAVEL_WORD.matcher(label).replaceAll("")).matches()) {
                safe.add("");
                continue;
            }
            if (!label.isEmpty()) hotspot = false;
            safe.add(label);
        }
        return safe;
    }

    private static Set<String> excludedRouteMetrics(List<String> labels, List<String> safe) {
        Set<String> excluded = new HashSet<>();
        List<String> blocked = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            String label = safe.get(i).isEmpty() ? OfferEvidence.normalize(labels.get(i)) : "";
            blocked.add(label);
            if (!label.isEmpty()) excluded.add(label);
        }
        excluded.addAll(joinRouteMetricSiblings(blocked));
        return excluded;
    }

    /**
     * Joins adjacent sibling labels such as {@code "4.2"}, {@code "mi"} into {@code "4.2 mi"}. Only a bare number
     * directly beside a bare unit is joined, so unrelated numbers elsewhere on the screen stay separate.
     */
    static List<String> joinMetricSiblings(List<String> siblings) {
        if (!OfferEvidence.bounded(siblings)) return new ArrayList<>();
        return joinRouteMetricSiblings(routeMetricLabels(siblings));
    }

    private static List<String> joinRouteMetricSiblings(List<String> siblings) {
        List<String> combined = new ArrayList<>();
        for (int i = 0; i + 1 < siblings.size(); i++) {
            String left = OfferEvidence.normalize(siblings.get(i));
            String right = OfferEvidence.normalize(siblings.get(i + 1));
            String metric = "";
            if (METRIC_NUMBER.matcher(left).matches() && METRIC_UNIT.matcher(right).matches()) {
                metric = left + " " + right.replaceAll(TRAILING_SEPARATOR, "");
            } else if (METRIC_UNIT.matcher(left).matches() && METRIC_NUMBER.matcher(right).matches()) {
                metric = right + " " + left.replaceAll(TRAILING_SEPARATOR, "");
            }
            // Separate qualifiers/signs still belong to an item label. Dropping them while joining would turn
            // a bound, estimate, increment, product count or progress reading into an exact offer total.
            if (metric.matches("(?i).*\\bitems?$") && ((i > 0
                    && itemCountQualifier(siblings, i - 1, -1)) || (i + 2 < siblings.size()
                    && itemCountQualifier(siblings, i + 2, 1)))) metric = "";
            if (!metric.isEmpty()) {
                combined.add(metric);
                if (i > 0 && TOTAL_LABEL.matcher(OfferEvidence.normalize(siblings.get(i - 1))).matches()) {
                    combined.add("Total " + metric);
                }
                i++; // The unit sibling has been consumed.
                continue;
            }
            if (TOTAL_LABEL.matcher(left).matches() && MILES.matcher(right).find()) combined.add("Total " + right);
        }
        return combined;
    }

    private static boolean itemCountQualifier(List<String> siblings, int at, int outside) {
        String normalized = OfferEvidence.normalize(siblings.get(at)).toLowerCase(Locale.US);
        // "30 min" belongs to duration, not a "min" item bound. Likewise, a separate travel number retains
        // its own adjacent unit: [12, items, 4.2, mi] is not an ambiguous [12, items, 4.2] basket count.
        if (MINUTES.matcher(normalized).matches()) return false;
        int unitAt = at + outside;
        if (METRIC_NUMBER.matcher(normalized).matches() && unitAt >= 0 && unitAt < siblings.size()
                && adjacentMetricUnit(siblings.get(unitAt))) return false;
        return ItemCount.orderComponent(normalized) || normalized.matches(
                ".*\\b(?:unique|distinct|remaining|found|collected|completed|of|to|up to|at least|at most"
                        + "|over|under|about|around|approx(?:imately)?|est(?:imate[ds]?)?|min(?:imum)?|max(?:imum)?|more than|less than|fewer than|per|each"
                        + "|adds?|added|additional|extra|more)\\b.*")
                || normalized.matches("[<>≤≥0-9\\s.,+\\-–—~≈/]+");
    }

    private static boolean adjacentMetricUnit(String label) {
        String unit = OfferEvidence.normalize(label).toLowerCase(Locale.US);
        return METRIC_UNIT.matcher(unit).matches()
                || unit.matches("(?:mins?|minutes?|hrs?|hours?)[:=]?");
    }

    private static List<String> distinctNormalized(List<String> values, List<String> into) {
        for (String value : values) {
            String normalized = OfferEvidence.normalize(value);
            if (!normalized.isEmpty() && !into.contains(normalized)) into.add(normalized);
        }
        return into;
    }

    /**
     * A single amount on a "Guaranteed"/"Total pay" line (or the line right after a bare label) wins. Otherwise
     * the screen must show exactly one amount and no "+$" increment. Rate figures ("/hr", "per mile", ...) and
     * increments are never pay; {@link #payWithPlusAmount} only bounds pay from above.
     */
    private static Integer parsePay(List<String> lines) {
        Set<Integer> labeled = new HashSet<>();
        Set<Integer> all = new HashSet<>();
        boolean sawIncrement = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String lower = line.toLowerCase(Locale.US);
            if (isRate(lower)) continue;
            boolean payLabel = lower.contains("guaranteed") || lower.contains("total pay");
            Matcher matcher = MONEY.matcher(line);
            boolean lineHasMoney = false;
            while (matcher.find()) {
                if (isIncrement(line, matcher.start())) {
                    sawIncrement = true;
                    continue;
                }
                lineHasMoney = true;
                int amount = cents(matcher.group(1));
                all.add(amount);
                if (payLabel) labeled.add(amount);
            }
            if (payLabel && !lineHasMoney && i + 1 < lines.size()) {
                Integer labelValue = soleTotalAmount(lines.get(i + 1));
                if (labelValue != null) labeled.add(labelValue);
            }
        }
        if (!labeled.isEmpty()) return onlyValue(labeled);
        // Whether an unlabeled amount already includes a "+$" bonus is unknown, so neither is taken as pay (the two
        // only bound it from above: payWithPlusAmount).
        return sawIncrement ? null : onlyValue(all);
    }

    /**
     * The most an offer can pay when its pay is unknown only because one bare "+$X" label sits directly beside its
     * one unlabeled total "$Y" (only the offer's Decline or Accept control, a countdown or the busy badge may come
     * between them, as on Dasher's screen: "Very busy, +$1, Decline, $5.75"). A legacy two-stop offer without order
     * counts uses Y + X. An explicitly counted single-pickup bundle uses Y + N*X: the bonus might apply to every
     * observed order. Orders sharing a dropoff still count separately. Either amount is only a decline ceiling,
     * never exact pay, a passing score, or evidence of an add-on's incremental economics. Explicit add-ons, added
     * travel, partial/conflicting counts, rates, labeled pay or multiplier qualifiers have no such ceiling.
     */
    private static Integer payWithPlusAmount(List<String> lines, List<String> metrics, Integer stops) {
        if (stops == null || AddOnOffer.isLikely(lines)) return null;
        for (String line : metrics) {
            if (ADDED_TRAVEL.matcher(line).find() || QUALIFIER.matcher(line).find()) {
                return null;
            }
        }
        Integer orders = bonusOrderCeiling(metrics, stops);
        if (orders == null) return null;
        Integer plus = null;
        Integer total = null;
        int plusAt = -1;
        int totalAt = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher money = MONEY.matcher(line);
            if (!money.find()) continue;
            String lower = line.toLowerCase(Locale.US);
            if (isRate(lower) || lower.contains("guaranteed") || lower.contains("total pay")) return null;
            Matcher bare = BARE_PLUS_AMOUNT.matcher(line);
            if (bare.matches()) {
                if (plus != null) return null;
                plus = cents(bare.group(1));
                plusAt = i;
                continue;
            }
            do {
                if (isIncrement(line, money.start())) return null;
                int amount = cents(money.group(1));
                if (total != null && total != amount) return null;
                total = amount;
                totalAt = i;
            } while (money.find());
        }
        if (plus == null || total == null || !besideEachOther(lines, plusAt, totalAt)) return null;
        long ceiling = (long) total + (long) plus * orders;
        return ceiling <= Integer.MAX_VALUE ? (int) ceiling : null;
    }

    /**
     * The total route must have exactly one pickup: S total stops = D observed dropoffs + 1. Its explicit pickup
     * count N includes all orders there, with D <= N because several orders may share a dropoff. Stops alone cannot
     * upper-bound per-order bonuses. Any partial/new count evidence also blocks the old two-stop fallback.
     */
    private static Integer bonusOrderCeiling(List<String> labels, int stops) {
        Set<Integer> orders = new HashSet<>();
        Set<Integer> dropoffs = new HashSet<>();
        boolean countEvidence = false;
        boolean separatePickup = false;
        for (String label : labels) {
            Matcher pickup = PICKUP_ORDERS.matcher(label);
            Matcher dropoff = DROPOFF_STOPS.matcher(label);
            if (pickup.matches()) {
                countEvidence = true;
                orders.add(Integer.parseInt(pickup.group(1)));
            } else if (dropoff.matches()) {
                countEvidence = true;
                dropoffs.add(Integer.parseInt(dropoff.group(1)));
            } else {
                boolean pickupWord = PICKUP_WORD.matcher(label).find();
                boolean dropoffWord = DROPOFF_WORD.matcher(label).find();
                // Unknown/ranged/spelled-out quantities cannot quietly become a single-order fallback. Ordinary
                // "Customer dropoff" or "Pick up" labels remain unchanged for the legacy two-stop shape.
                String lower = label.toLowerCase(Locale.US);
                if (ORDER_WORD.matcher(label).find()
                        || pickupWord && !lower.matches("pick[ -]?up")
                        || dropoffWord && !lower.matches("(?:customer\\s+)?drop[ -]?off")) return null;
                // A second generic pickup label cannot establish that this is the only pickup of a bundle.
                if (pickupWord) separatePickup = true;
            }
        }
        if (!countEvidence) return stops == 2 ? 1 : null;
        if (separatePickup) return null;
        Integer orderCount = onlyValue(orders);
        Integer dropoffCount = onlyValue(dropoffs);
        if (orderCount == null || dropoffCount == null || stops != dropoffCount + 1
                || dropoffCount > orderCount) return null;
        return orderCount;
    }

    /** A countdown as Dasher shows it beside an offer ("0:35"). */
    private static final Pattern COUNTDOWN = Pattern.compile("\\d{1,2}:[0-5]\\d");
    /** Dasher's short badge for a busy area, shown at the top of an offer ("Very busy"). */
    private static final List<String> BUSY_BADGES = java.util.Arrays.asList("busy", "very busy");

    /**
     * Whether the "+$" amount and the total sit side by side: next to each other, or with only offer chrome between
     * them (Dasher draws "+$1, Decline, $5.75"). Chrome is the offer's own Decline or Accept control, a countdown, or
     * the short busy badge; any other label between them (a word, a split digit, "incl. tips") keeps them apart.
     */
    private static boolean besideEachOther(List<String> lines, int plusAt, int totalAt) {
        if (plusAt < 0 || totalAt < 0 || plusAt == totalAt) return false;
        for (int i = Math.min(plusAt, totalAt) + 1; i < Math.max(plusAt, totalAt); i++) {
            if (!offerChrome(lines.get(i))) return false;
        }
        return true;
    }

    private static boolean offerChrome(String label) {
        String value = OfferControls.normalize(label);
        return OfferControls.isButton(value, "decline") || OfferControls.isButton(value, "accept")
                || COUNTDOWN.matcher(value).matches()
                || BUSY_BADGES.contains(value.replaceAll("[.!…]+$", ""));
    }

    /** True when the "$" at {@code dollarIndex} is written as "+$…": an amount added to something else. */
    private static boolean isIncrement(String line, int dollarIndex) {
        int i = dollarIndex - 1;
        while (i >= 0 && Character.isWhitespace(line.charAt(i))) i--;
        return i >= 0 && line.charAt(i) == '+';
    }

    /** The line's only amount, unless the line is a rate or an increment, which can never be a pay total. */
    private static Integer soleTotalAmount(String line) {
        if (isRate(line.toLowerCase(Locale.US)) || INCREMENT.matcher(line).find()) return null;
        Matcher matcher = MONEY.matcher(line);
        if (!matcher.find()) return null;
        int amount = cents(matcher.group(1));
        return matcher.find() ? null : amount;
    }

    /** Lowercase normalized label; shared by standalone and explicitly incremental pay readers. */
    static boolean isRate(String lower) {
        return PAY_RATE.matcher(lower).find();
    }

    /** An explicit total distance wins over leg distances; otherwise exactly one distance must be shown. */
    private static Double parseMiles(List<String> lines) {
        Set<Double> found = new HashSet<>();
        Set<Double> totals = new HashSet<>();
        for (String line : lines) {
            if (HOTSPOT.matcher(line).find()) continue;
            if (OfferEvidence.distanceRange(line)) return null;
            collectDoubles(MILES.matcher(line), found);
            collectDoubles(TOTAL_MILES.matcher(line), totals);
            collectDoubles(MILES_TOTAL.matcher(line), totals);
        }
        if (!totals.isEmpty()) return onlyValue(totals);
        return onlyValue(found);
    }

    /**
     * Duration counts only on lines that label it as the offer's time, or on compact metric lines such as
     * {@code "2 stops (7.2 mi) • 21 min"}. Wait times and countdowns are never delivery duration.
     */
    private static Integer parseMinutes(List<String> lines) {
        Set<Integer> found = new HashSet<>();
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.US);
            if (HOTSPOT.matcher(line).find()) continue;
            if (lower.contains("wait") || lower.contains("accept by") || lower.contains("remaining")) continue;
            boolean explicitTime = lower.contains("estimated") || lower.contains("est.")
                    || lower.contains("duration") || lower.contains("total time");
            boolean compactOfferMetric = MILES.matcher(line).find() || STOPS.matcher(line).find();
            if (!explicitTime && !compactOfferMetric) continue;
            if (OfferEvidence.timeRange(line)) return null;
            Matcher matcher = MINUTES.matcher(line);
            while (matcher.find()) {
                if (matcher.group(1) == null) {
                    found.add(Integer.parseInt(matcher.group(3)));
                } else {
                    int hours = Integer.parseInt(matcher.group(1));
                    found.add(hours * 60 + (matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2))));
                }
            }
        }
        return onlyValue(found);
    }

    private static Integer parseStops(List<String> lines) {
        Set<Integer> found = new HashSet<>();
        for (String line : lines) {
            if (OfferEvidence.stopRange(line)) return null;
            if (STOP_BREAKDOWN.matcher(line).find()) continue;
            collectIntegers(STOPS.matcher(line), found);
            collectIntegers(STOPS_FIRST.matcher(line), found);
            Matcher counts = ROUTE_COUNTS.matcher(line);
            if (counts.matches()) found.add(Integer.parseInt(counts.group(1)) + Integer.parseInt(counts.group(2)));
        }
        Integer stops = onlyValue(found);
        return stops != null && stops > 0 ? stops : null;
    }

    private static void collectDoubles(Matcher matcher, Set<Double> into) {
        while (matcher.find()) into.add(Double.parseDouble(matcher.group(1)));
    }

    private static void collectIntegers(Matcher matcher, Set<Integer> into) {
        while (matcher.find()) into.add(Integer.parseInt(matcher.group(1)));
    }

    /** The sole value, or null when the evidence is absent or conflicting. */
    private static <T> T onlyValue(Set<T> values) {
        return values.size() == 1 ? values.iterator().next() : null;
    }

    /** Converts a pre-validated {@code \d{1,4}([.,]\d{1,2})?} amount to cents. */
    private static int cents(String amount) {
        String normalized = amount.replace(',', '.');
        int point = normalized.indexOf('.');
        if (point < 0) return Integer.parseInt(normalized) * 100;
        int whole = Integer.parseInt(normalized.substring(0, point));
        String decimals = normalized.substring(point + 1);
        return whole * 100 + Integer.parseInt(decimals) * (decimals.length() == 1 ? 10 : 1);
    }

    private OfferParser() {}
}
