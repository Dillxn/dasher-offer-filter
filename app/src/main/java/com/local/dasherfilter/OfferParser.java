package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses explicit values only. Partial numbers, ranges, rates, pay components, increments, and lower bounds never become
 * exact offer facts; when the screen does not say which amount is the payout, pay is unknown (REVIEW).
 */
final class OfferParser {
    private static final Pattern MONEY = Pattern.compile("\\$\\s*(\\d{1,4}(?:[.,]\\d{1,2})?)(?![\\d.,])");
    private static final Pattern EXACT_MONEY = Pattern.compile("\\$\\s*(\\d{1,4}(?:[.,]\\d{1,2})?)");
    // Road names ("14 Mile Rd", Detroit's "8 Mile & Gratiot") and fraction parts ("4½" -> "41⁄2", "4 1/2") are not mileage.
    // DoorDash writes distances as "mi"/"miles"; a capitalized singular "Mile" is a place name, never a distance.
    private static final String ROAD = "(?!\\s*(?:rd|road|st|street|ave|avenue|blvd|hwy|highway|dr|ln|lane|way|pkwy|parkway|ct|court)\\b)";
    static final String NOT_A_PLACE = ROAD + "(?<!(?-i:Mile|MILE))";
    private static final String MILE_VALUE = "(?<![\\d.,+\\-⁄/])\\b(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:mi|miles?)\\b" + NOT_A_PLACE;
    private static final Pattern MILES = Pattern.compile("(?i)" + MILE_VALUE);
    private static final Pattern TOTAL_MILES = Pattern.compile("(?i)(?:total(?: trip)?(?: distance| mileage)?\\s*[:=]?\\s*)" + MILE_VALUE);
    private static final Pattern MILES_TOTAL = Pattern.compile("(?i)" + MILE_VALUE + "\\s*(?:in\\s+)?total\\b");
    private static final Pattern MILE_RANGE = Pattern.compile("(?i)\\d+(?:\\.\\d+)?\\s*(?:[-–—]|to)\\s*\\d+(?:\\.\\d+)?\\s*(?:mi|miles?)\\b");
    private static final String HOUR_UNIT = "(?:h|hrs?|hours?)";
    private static final String NO_NUMBER_BEFORE = "(?<![\\d.,+\\-⁄/:])\\b";
    private static final Pattern DURATION = Pattern.compile("(?i)" + NO_NUMBER_BEFORE + "(?:(\\d{1,2})\\s*" + HOUR_UNIT + "\\b\\s*(?:,\\s*|and\\s+)?)?(\\d{1,3})\\s*(?:mins?|minutes?)\\b");
    private static final Pattern ANY_HOURS = Pattern.compile("(?i)(?<![\\d.,⁄/:])\\d+(?:[.,]\\d+)?\\s*" + HOUR_UNIT + "\\b");
    private static final Pattern BARE_DURATION = Pattern.compile("(?i)(?:\\d{1,2}\\s*" + HOUR_UNIT + "\\s*(?:,\\s*|and\\s+)?)?\\d{1,3}\\s*(?:mins?|minutes?)");
    private static final Pattern STOPS = Pattern.compile("(?i)(?<![\\d.,+\\-])\\b(\\d{1,2})\\s+stops?\\b");
    private static final Pattern STOPS_FIRST = Pattern.compile("(?i)\\b(?:total\\s+)?stops?\\s*[:=]\\s*(\\d{1,2})\\b(?![.,]\\d)");
    private static final Pattern STOP_RANGE = Pattern.compile("(?i)(?:\\d+\\s*(?:[-–—]|to)\\s*\\d+\\s+stops?\\b|\\bstops?\\s*[:=]\\s*\\d+\\s*(?:[-–—]|to)\\s*\\d+)");
    private static final Pattern ROUTE_COUNTS = Pattern.compile("(?i)^(\\d{1,2})\\s+pick[ -]?ups?\\s*(?:[,•·+&/|]|and)\\s*(\\d{1,2})\\s+(?:customer\\s+)?drop[ -]?offs?$");
    private static final Pattern METRIC_NUMBER = Pattern.compile("\\d{1,3}(?:\\.\\d{1,2})?");
    private static final Pattern METRIC_UNIT = Pattern.compile("(?i)(?:mi\\.?|miles?|stops?|pick[ -]?ups?|(?:customer\\s+)?drop[ -]?offs?)[:=]?");
    // A rate unit directly after an amount ("$17.50/hr", "$25 an hour", "$1.21 per mi") makes that amount a rate, never pay.
    private static final String UNIT_WORD = "(?:active\\s+)?(?:hrs?|hours?|h|mi|miles?|mins?|minutes?)\\b";
    private static final String RATE_UNIT = "(?:/\\s*|per\\s+|an?\\s+|each\\s+)" + UNIT_WORD;
    private static final Pattern RATE_AFTER = Pattern.compile("(?i)^\\s*(?:" + RATE_UNIT + "|hourly\\b)");
    // …or later in the same clause ("$25.00 guaranteed per hour", "$25 guaranteed hourly", "$1.50 guaranteed /mi"). Only the
    // unambiguous "/unit", "per unit" and "hourly" count there; the clause ends at the next amount or metric separator, so
    // "$9.50 Guaranteed · Deliver within an hour" and "$9.50 • $1.21/mi" keep their payout.
    private static final Pattern RATE_LATER = Pattern.compile("(?i)^[^•·|;$]*?(?:(?:/\\s*|\\bper\\s+)" + UNIT_WORD + "|\\bhourly\\b)");
    // A separate node that is nothing but a rate unit ("/hr", "per hour", "an hour") makes the amount before it a rate…
    private static final Pattern RATE_LINE = Pattern.compile("(?i)^(?:" + RATE_UNIT + "\\.?|hourly)(?:\\s*\\+\\s*tips?)?$");
    // …and so does a money-less node that starts with one ("/hr • 4.1 mi", "per hour guaranteed", "hourly + tips").
    private static final Pattern RATE_LINE_START = Pattern.compile("(?i)^(?:(?:/\\s*|per\\s+)" + UNIT_WORD + "|hourly\\b)");
    private static final Pattern HOUR_WORD = Pattern.compile("(?i)(?:\\bh\\b|\\bhrs?\\b|\\bhours?\\b|\\bhourly\\b)");
    private static final String COMPONENT_WORD = "(?:includ\\w*|incl|boost\\w*|peak\\s*pay|bonus\\w*|tips?|promo\\w*|promotion\\w*|incentive\\w*|challenge\\w*)";
    private static final Pattern COMPONENT = Pattern.compile("(?i)\\b" + COMPONENT_WORD + "\\b");
    // A pay component named right before the amount ("Customer tip $4.00", "Includes a $15.00 …"); separators end the attribution.
    private static final Pattern COMPONENT_BEFORE = Pattern.compile("(?i)\\b" + COMPONENT_WORD + "\\b[^•·|;$\\d]{0,24}$");
    // …stated to be inside the total ("Includes a $15.00 Flash offer boost", "(incl. $2.00 tip)"): excluded, but not a sign the total is unknown.
    private static final Pattern INCLUDED_BEFORE = Pattern.compile("(?i)\\b(?:includ\\w*|incl)\\b[^•·|;$\\d]{0,24}$");
    // …or right after it ("$2.00 Peak Pay", "$15.00 Flash offer boost", "$7.50 in tips"). "with/before/plus tips" qualify the payout itself.
    private static final Pattern COMPONENT_AFTER = Pattern.compile("(?i)^\\s*(?:(?!(?:with|w|before|after|plus|excl\\w*|without|not|and|or|including|incl\\w*|includes?)\\b)[a-z]+\\s+){0,2}" +
            "(?:boost\\w*|peak\\s*pay|bonus\\w*|tips?|promo\\w*|promotion\\w*|incentive\\w*|challenge\\w*)\\b");
    // An extra amount on top of something else ("Earn an extra $3.00", "$2.00 more") is an increment, never the payout.
    private static final Pattern EXTRA_BEFORE = Pattern.compile("(?i)\\b(?:extra|additional|another)\\s*$");
    private static final Pattern EXTRA_AFTER = Pattern.compile("(?i)^\\s*(?:extra|additional|more)\\b");
    // "(incl. tips)" / "incl. tips" / "(tips included)" describes the adjacent payout; it is a pay label, not a component amount.
    private static final Pattern TIPS_DESCRIPTOR = Pattern.compile("(?i)\\(?\\s*\\b(?:(?:incl\\.?|including)\\s+(?:customer\\s+|the\\s+)?tips?|tips?\\s+included)\\b\\s*\\)?");
    // "$7.50 (incl. $2.00 tip)", "$28.24 Includes a $15.00 boost": a leading amount described as including components is the total.
    private static final Pattern INCLUSIVE_TOTAL = Pattern.compile("(?i)^\\$\\s*\\d{1,4}(?:[.,]\\d{1,2})?[\\s·•,:\\-–—(]*(?:incl\\.?|including|includes)(?![a-z])");
    // A node that starts "Includes …" / "(incl. …)" describes the amount right above it ("$9.25" / "Includes DoorDash pay and customer tip").
    private static final Pattern INCLUDES_START = Pattern.compile("(?i)^\\(?\\s*(?:includ\\w*|incl\\.?|including)(?![a-z])");
    // A deadline clause ("Deliver by 7:45 PM (in 32 min)") is never a duration; the rest of a merged line still is.
    private static final Pattern DEADLINE_CLAUSE = Pattern.compile("(?i)\\b(?:deliver|drop\\s*off|pick\\s*up|pickup|arrive)\\s+by\\b[^•·|;\\n]*");
    // A relative time ("Pickup in 5 min", "Ready for pickup in 12 min", "ETA 8 min") is not the trip duration.
    // Hours-only forms ("Pickup in 1 hr", "Ready for pickup in 1 hour", "Closes in 2 hours") are relative times too.
    private static final Pattern RELATIVE_CLAUSE = Pattern.compile("(?i)\\b(?:pick\\s*-?\\s*up|ready(?:\\s+for\\s+pick\\s*-?\\s*up)?|eta|arriv\\w*|prep(?:\\s+time)?|clos(?:es|ing))" +
            "\\s*(?:in|:)?\\s*(?:about\\s+|~\\s*)?(?:\\d{1,2}\\s*" + HOUR_UNIT + "\\b(?:\\s*(?:,\\s*|and\\s+)?\\d{1,3}\\s*(?:mins?|minutes?)\\b)?|\\d{1,3}\\s*(?:mins?|minutes?)\\b)");
    // An hours-only duration counts only as a whole metric element ("4.2 mi • 1 hr", "Estimated 1 hr"): never inside other
    // words ("CVS 24h", "Open 24 hours", "in 1 hr", "Ready by 1 hr") and never 10 hours or more.
    private static final Pattern WHOLE_HOUR_ELEMENT = Pattern.compile("(?i)^(?:(?:est(?:imated)?\\.?(?:\\s+(?:trip\\s+)?(?:time|duration))?|(?:total|trip)\\s+(?:time|duration)|duration|time)\\s*[:=]?\\s*)?" +
            "([1-9])\\s*" + HOUR_UNIT + "\\.?$");
    // A separate "+" / "plus" node before a tips node or another amount: the shown amount is a floor or a sum.
    private static final Pattern PLUS_NODE = Pattern.compile("(?i)^(?:\\+|plus)$");
    // A bare "35 min" next to one of these is a wait, countdown, prep/pickup time, or ETA, never the trip duration.
    private static final Pattern CONTEXT_WORDS = Pattern.compile("(?i)\\b(?:wait\\w*|remaining|left|ago|away|deliver\\s+by|accept\\s+by|pick\\s*up\\s+by|arrive\\s+by|eta|ready|prep\\w*|" +
            "clos(?:es|ing|ed)|arriv\\w*|drive\\s+to|pick\\s*-?\\s*up\\s+(?:in|at|time))\\b|\\bin\\s*$");

    static OfferSnapshot parse(List<String> visibleText) { return parse(visibleText, Collections.emptyList()); }
    static OfferSnapshot parse(List<String> visibleText, List<String> metricParts) {
        if (!OfferEvidence.bounded(visibleText) || !OfferEvidence.bounded(metricParts)) return new OfferSnapshot(null, null, null, null);
        List<String> lines = lines(visibleText);
        List<String> metrics = new ArrayList<>(lines);
        for (String part : metricParts) { String normalized = normalize(part); if (!normalized.isEmpty() && !metrics.contains(normalized)) metrics.add(normalized); }
        boolean hourly = isHourlyMode(lines);
        Integer pay = OfferEvidence.malformedMoney(lines) || hourly ? null : parsePay(lines);
        return new OfferSnapshot(pay, parseMiles(metrics), parseMinutes(metrics), parseStops(metrics), hourly);
    }

    /** Normalized, non-empty, de-duplicated lines in screen order (the same view parse() and isHourlyMode() use). */
    private static List<String> lines(List<String> labels) {
        List<String> lines = new ArrayList<>();
        for (String value : labels) { String trimmed = normalize(value); if (!trimmed.isEmpty() && !lines.contains(trimmed)) lines.add(trimmed); }
        return lines;
    }

    /**
     * DoorDash "Earn by Time": any active-hour marker makes the whole offer hourly; its rate is never a payout. A card whose
     * money is only hourly rates ("$15/hr + tips", "$15" + "/hr") shows no per-offer pay either and is hourly too, so no
     * other rule (max miles, max stops, avoid list) can decline it. The failure direction is REVIEW only.
     */
    static boolean isHourlyMode(List<String> labels) {
        if (labels == null) return false;
        for (String raw : labels) {
            String s = normalize(raw).toLowerCase(Locale.US).replaceAll("\\s*/\\s*", "/");
            if (s.contains("active hr") || s.contains("active hour") || s.contains("/active") || s.contains("per active") ||
                    s.contains("earn by time") || s.contains("from when you accept to when you complete")) return true;
        }
        if (!OfferEvidence.bounded(labels)) return false;   // callers already treat unreadable text as REVIEW
        boolean hourRate = false;
        for (List<int[]> line : classify(lines(labels)))
            for (int[] token : line) {
                if (token[KIND] == FREE) return false;
                hourRate |= token[KIND] == RATE_AMOUNT && token[HOUR] == 1;
            }
        return hourRate;
    }
    static boolean hasMileageToken(String label) { return MILES.matcher(normalize(label)).find(); }

    static List<String> joinMetricSiblings(List<String> siblings) {
        List<String> combined = new ArrayList<>(), units = new ArrayList<>();
        if (!OfferEvidence.bounded(siblings)) return combined;
        for (int i = 0; i < siblings.size(); i++) {
            String left = normalize(siblings.get(i)), right = i + 1 < siblings.size() ? normalize(siblings.get(i + 1)) : null, metric = "";
            if (right != null && METRIC_NUMBER.matcher(left).matches() && METRIC_UNIT.matcher(right).matches()) metric = left + " " + right.replaceAll("[:=]$", "");
            else if (right != null && METRIC_UNIT.matcher(left).matches() && METRIC_NUMBER.matcher(right).matches()) metric = right + " " + left.replaceAll("[:=]$", "");
            if (!metric.isEmpty()) {
                combined.add(metric); units.add(metric);
                if (i > 0 && normalize(siblings.get(i - 1)).matches("(?i)total(?: distance| mileage)?[:=]?")) combined.add("Total " + metric);
                i++; continue;
            }
            if (right != null && left.matches("(?i)total(?: distance| mileage)?[:=]?") && MILES.matcher(right).find()) combined.add("Total " + right);
            // Icons and separators between metric nodes do not break adjacency.
            if (!left.replaceAll("[\\p{Z}\\s•·|,/()\\-–—]", "").isEmpty()) units.add(left);
        }
        // A bare "21 min" node pairs only with an adjacent mileage/stop node in the same sibling group, and only when every
        // neighbour is such a metric (or the group edge): "Ready for pickup in" / "ETA" / "Prep time" beside it means another duration.
        for (int j = 0; j < units.size(); j++) {
            if (!BARE_DURATION.matcher(units.get(j)).matches()) continue;
            String before = j > 0 ? units.get(j - 1) : "", after = j + 1 < units.size() ? units.get(j + 1) : "";
            if ((!before.isEmpty() && !metricNeighbor(before)) || (!after.isEmpty() && !metricNeighbor(after))) continue;
            for (String neighbor : new String[]{before, after}) if (!neighbor.isEmpty()) combined.add(neighbor + " • " + units.get(j));
        }
        return combined;
    }
    private static boolean metricNeighbor(String s) {
        return !s.isEmpty() && (MILES.matcher(s).find() || STOPS.matcher(s).find() || ROUTE_COUNTS.matcher(s).matches()) &&
                !OfferEvidence.timeRange(s) && !CONTEXT_WORDS.matcher(s).find() && !OfferEvidence.hasMoneyToken(s);
    }
    private static String normalize(String text) { return OfferEvidence.normalize(text); }

    // How one money token relates to the payout. Only FREE amounts can be the payout; the others never can. Components and
    // increments also leave an unlabeled total unknown; included parts and rates do not.
    private static final int FREE = 0, COMPONENT_AMOUNT = 1, INCREMENT_AMOUNT = 2, RATE_AMOUNT = 3, INCLUDED_AMOUNT = 4;
    // Token layout: {cents, kind, 1 if the rate is per hour, 1 if a '+' follows the amount (a sum, not the payout)}.
    private static final int CENTS = 0, KIND = 1, HOUR = 2, PLUS = 3;

    /** The money tokens of lines[i], each attributed on its own (never per line). Lines must come from lines(). */
    private static List<int[]> tokens(List<String> lines, int i) {
        String line = lines.get(i);
        String clean = TIPS_DESCRIPTOR.matcher(line).find() ? TIPS_DESCRIPTOR.matcher(line).replaceAll(" ") : line;
        // A next node that is only a rate unit, or a money-less node that starts with one, makes an amount ending this line a
        // rate; so does one after a short money-less pay label node ("$8.00" / "Guaranteed" / "per hour").
        Matcher rateNext = rateLine(lines, i + 1);
        if (rateNext == null && i + 2 < lines.size() && isPayLabel(lines.get(i + 1)) && !MONEY.matcher(lines.get(i + 1)).find() && words(lines.get(i + 1)) <= 3)
            rateNext = rateLine(lines, i + 2);
        List<int[]> found = new ArrayList<>();
        Matcher matcher = MONEY.matcher(clean);
        int previousEnd = 0;
        while (matcher.find()) {
            String before = clean.substring(previousEnd, matcher.start()), rest = clean.substring(matcher.end());
            previousEnd = matcher.end();
            int kind = FREE, hour = 0;
            Matcher rate = RATE_AFTER.matcher(rest);
            if (!rate.find()) { rate = RATE_LATER.matcher(rest); if (!rate.find()) rate = null; }
            if (rate == null && rateNext != null && rest.trim().isEmpty()) rate = rateNext;
            if (rate != null) { kind = RATE_AMOUNT; hour = HOUR_WORD.matcher(rate.group()).find() ? 1 : 0; }
            else if (before.matches("(?s).*\\+\\s*$") || EXTRA_BEFORE.matcher(before).find() || EXTRA_AFTER.matcher(rest).find()) kind = INCREMENT_AMOUNT;
            else if (INCLUDED_BEFORE.matcher(before).find()) kind = INCLUDED_AMOUNT;
            else if (COMPONENT_BEFORE.matcher(before).find() || COMPONENT_AFTER.matcher(rest).find()) kind = COMPONENT_AMOUNT;
            found.add(new int[]{cents(matcher.group(1)), kind, hour, OfferEvidence.plusFollows(rest) ? 1 : 0});
        }
        return found;
    }

    /** A found rate-unit match when lines[j] is a rate node ("/hr", "per hour", "an hour", "/hr • 4.1 mi"), else null. */
    private static Matcher rateLine(List<String> lines, int j) {
        if (j >= lines.size()) return null;
        String line = lines.get(j);
        Matcher m = RATE_LINE.matcher(line);
        if (m.matches()) return m;
        if (MONEY.matcher(line).find()) return null;
        m = RATE_LINE_START.matcher(line);
        return m.find() ? m : null;
    }

    /** Every line's money tokens, including the attribution of a bare amount node to an adjacent component name node. */
    private static List<List<int[]>> classify(List<String> lines) {
        int n = lines.size();
        List<List<int[]>> tokens = new ArrayList<>();
        boolean[] componentLabel = new boolean[n];
        for (int i = 0; i < n; i++) {
            String line = lines.get(i);
            List<int[]> found = tokens(lines, i);
            tokens.add(found);
            // "Peak Pay" / "Customer tip" / "Flash offer boost": a short money-less node naming a component.
            componentLabel[i] = found.isEmpty() && !isPayLabel(line) && COMPONENT.matcher(line).find() && words(line) <= 3;
        }
        // A bare amount node next to a component name node is that component ("Peak Pay" / "$2.00"), in either order.
        for (int i = 0; i < n; i++)
            if (bare(lines, tokens, i) && tokens.get(i).get(0)[KIND] == FREE && ((i > 0 && componentLabel[i - 1]) || (i + 1 < n && componentLabel[i + 1])))
                tokens.get(i).get(0)[KIND] = COMPONENT_AMOUNT;
        return tokens;
    }
    private static boolean isPayLabel(String line) {
        String lower = line.toLowerCase(Locale.US);
        return lower.contains("guaranteed") || lower.contains("total pay") || TIPS_DESCRIPTOR.matcher(line).find() ||
                INCLUDES_START.matcher(line).find() || INCLUSIVE_TOTAL.matcher(line).find();
    }
    /** The line is exactly one amount ("$9.25"). */
    private static boolean bare(List<String> lines, List<List<int[]>> tokens, int i) {
        return tokens.get(i).size() == 1 && EXACT_MONEY.matcher(lines.get(i)).matches();
    }

    /**
     * The standalone payout, or null. Labeled amounts ("Guaranteed", "Total pay", "incl. tips", "Includes …") win; a label
     * without its own amount takes the bare amount on the line before it or the single free amount on the line after it,
     * and if those neighbours disagree the pay is unknown. Components, increments and rates are attributed per amount, never
     * per line. A component or increment that is not stated to be included blocks the unlabeled single-amount fallback:
     * whether the remaining amount already contains it is not shown.
     */
    private static Integer parsePay(List<String> lines) {
        int n = lines.size();
        boolean ambiguous = false;
        boolean[] payLabel = new boolean[n], describesAbove = new boolean[n];
        List<List<int[]>> tokens = classify(lines);
        for (int i = 0; i < n; i++) {
            String line = lines.get(i);
            describesAbove[i] = INCLUDES_START.matcher(line).find();
            payLabel[i] = isPayLabel(line);
            String clean = TIPS_DESCRIPTOR.matcher(line).find() ? TIPS_DESCRIPTOR.matcher(line).replaceAll(" ") : line;
            if (OfferEvidence.lowerBound(clean)) ambiguous = true;                      // "$7.50+", "+ tips", "before tips"
            if (PLUS_NODE.matcher(line).matches() && i + 1 < n && (lines.get(i + 1).toLowerCase(Locale.US).startsWith("tip") || OfferEvidence.hasMoneyToken(lines.get(i + 1)))) ambiguous = true;
            for (int[] token : tokens.get(i)) if (token[PLUS] == 1) ambiguous = true;  // "$7.50 + $2.00": a sum, not the payout
        }
        Set<Integer> labeled = new HashSet<>(), all = new HashSet<>();
        boolean blocked = false;
        for (int i = 0; i < n; i++) {
            boolean ownFree = false;
            for (int[] token : tokens.get(i)) {
                if (token[KIND] == FREE) { ownFree = true; if (payLabel[i]) labeled.add(token[CENTS]); else all.add(token[CENTS]); }
                else if (token[KIND] == COMPONENT_AMOUNT || token[KIND] == INCREMENT_AMOUNT) blocked = true;
            }
            if (!payLabel[i] || ownFree) continue;
            // A label without its own amount names the bare amount right before it (redesigned card) or the single free
            // amount right after it (original card). Neighbours that disagree leave the payout unknown.
            Integer before = i > 0 ? bareFree(lines, tokens, i - 1) : null;
            Set<Integer> after = new HashSet<>();
            if (!describesAbove[i] && i + 1 < n) for (int[] token : tokens.get(i + 1)) if (token[KIND] == FREE) after.add(token[CENTS]);
            if (before != null) {
                for (Integer value : after) if (!value.equals(before)) ambiguous = true;
                labeled.add(before);
            } else if (after.size() == 1) labeled.add(after.iterator().next());
        }
        if (ambiguous) return null;
        if (labeled.size() == 1) return labeled.iterator().next();
        if (!labeled.isEmpty() || blocked) return null;
        return all.size() == 1 ? all.iterator().next() : null;
    }
    private static Integer bareFree(List<String> lines, List<List<int[]>> tokens, int i) {
        return bare(lines, tokens, i) && tokens.get(i).get(0)[KIND] == FREE ? tokens.get(i).get(0)[CENTS] : null;
    }
    private static int words(String line) {
        String letters = line.replaceAll("[^\\p{L}\\p{N}\\s]", " ").trim();
        return letters.isEmpty() ? 0 : letters.split("\\s+").length;
    }
    private static Double parseMiles(List<String> lines) {
        Set<Double> found = new HashSet<>(), totals = new HashSet<>();
        for (String line : lines) {
            if (MILE_RANGE.matcher(line).find()) return null;
            Matcher matcher = MILES.matcher(line); while (matcher.find()) found.add(Double.parseDouble(matcher.group(1)));
            Matcher before = TOTAL_MILES.matcher(line); while (before.find()) totals.add(Double.parseDouble(before.group(1)));
            Matcher after = MILES_TOTAL.matcher(line); while (after.find()) totals.add(Double.parseDouble(after.group(1)));
        }
        if (!totals.isEmpty()) return totals.size() == 1 ? totals.iterator().next() : null;
        return found.size() == 1 ? found.iterator().next() : null;
    }
    private static Integer parseMinutes(List<String> lines) {
        Set<Integer> found = new HashSet<>();
        for (String line : lines) {
            line = RELATIVE_CLAUSE.matcher(DEADLINE_CLAUSE.matcher(line).replaceAll(" ")).replaceAll(" ");
            String lower = line.toLowerCase(Locale.US);
            if (lower.contains("wait") || lower.contains("accept by") || lower.contains("remaining")) continue;
            boolean explicitTime = lower.contains("estimated") || lower.contains("est.") || lower.contains("duration") || lower.contains("total time");
            boolean compactOfferMetric = MILES.matcher(line).find() || STOPS.matcher(line).find();
            if (!explicitTime && !compactOfferMetric) continue;
            if (OfferEvidence.timeRange(line)) return null;
            List<int[]> consumed = new ArrayList<>();
            Matcher matcher = DURATION.matcher(line);
            while (matcher.find()) {
                int hours = matcher.group(1) == null ? 0 : Integer.parseInt(matcher.group(1));
                found.add(hours * 60 + Integer.parseInt(matcher.group(2))); consumed.add(new int[]{matcher.start(), matcher.end()});
            }
            Matcher hours = ANY_HOURS.matcher(line);
            while (hours.find()) {
                boolean inside = false;
                for (int[] span : consumed) inside |= hours.start() >= span[0] && hours.end() <= span[1];
                if (inside) continue;
                // An hour token that is not part of one explicit "1 hr 5 min" duration makes minutes unknown, unless it is a
                // whole hours-only metric element on a line with no other duration.
                Integer whole = consumed.isEmpty() ? wholeHourElement(line, hours.start(), hours.end()) : null;
                if (whole == null) return null;
                found.add(whole * 60);
            }
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }
    /** Hours of an hours-only token that is its whole metric element (between separators or line edges), else null. */
    private static Integer wholeHourElement(String line, int start, int end) {
        int from = start, to = end;
        while (from > 0 && "•·|;,".indexOf(line.charAt(from - 1)) < 0) from--;
        while (to < line.length() && "•·|;,".indexOf(line.charAt(to)) < 0) to++;
        Matcher element = WHOLE_HOUR_ELEMENT.matcher(line.substring(from, to).trim());
        return element.matches() ? Integer.valueOf(element.group(1)) : null;
    }
    private static Integer parseStops(List<String> lines) {
        Set<Integer> found = new HashSet<>();
        for (String line : lines) {
            if (STOP_RANGE.matcher(line).find()) return null;
            Matcher matcher = STOPS.matcher(line); while (matcher.find()) found.add(Integer.parseInt(matcher.group(1)));
            Matcher first = STOPS_FIRST.matcher(line); while (first.find()) found.add(Integer.parseInt(first.group(1)));
            Matcher counts = ROUTE_COUNTS.matcher(line); if (counts.matches()) found.add(Integer.parseInt(counts.group(1)) + Integer.parseInt(counts.group(2)));
        }
        if (found.size() != 1) return null;
        int stops = found.iterator().next(); return stops > 0 ? stops : null;
    }
    private static int cents(String amount) {
        String normalized = amount.replace(',', '.'); int point = normalized.indexOf('.');
        if (point < 0) return Integer.parseInt(normalized) * 100;
        int whole = Integer.parseInt(normalized.substring(0, point)); String decimals = normalized.substring(point + 1);
        return whole * 100 + Integer.parseInt(decimals) * (decimals.length() == 1 ? 10 : 1);
    }
    private OfferParser() {}
}
