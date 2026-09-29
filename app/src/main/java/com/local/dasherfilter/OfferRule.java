package com.local.dasherfilter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Production rules. Unknown is REVIEW, never KEEP or DECLINE; a separate known failure may still decline. Earn by Time
 * offers are always REVIEW. Store rules can only decline, never pass. The opt-in decline budget only reduces automation.
 */
final class OfferRule {
    enum Result { DECLINE, KEEP, REVIEW }
    /** Short machine reason for history, stats and UI. */
    enum Code {
        PAY_MISSING, MILES_MISSING, MINUTES_MISSING, STOPS_MISSING, ADDON_AMBIGUOUS,
        FLOOR, PER_MILE, PER_MINUTE, PER_HOUR, EXTRA_STOPS, MAX_STOPS, MAX_MILES, AVOIDED_STORE, RISING,
        HOURLY_MODE, DECLINE_LIMIT, NO_PAY_RULE, MEETS, PAUSED, UNREADABLE, UNSPECIFIED;
        /** Decisions stated as a required payout ("required at least $X (…)"). */
        boolean statesRequirement() {
            return this != MAX_STOPS && this != MAX_MILES && this != AVOIDED_STORE && this != HOURLY_MODE &&
                    this != DECLINE_LIMIT && this != NO_PAY_RULE && this != PAUSED && this != UNREADABLE;
        }
        boolean missingEvidence() { return this == PAY_MISSING || this == MILES_MISSING || this == MINUTES_MISSING || this == STOPS_MISSING || this == ADDON_AMBIGUOUS; }
        static Code parse(String name) { try { return valueOf(name); } catch (RuntimeException error) { return UNSPECIFIED; } }
    }
    static final String HOURLY_REASON = "Earn by Time offer — auto-decline disabled (DoorDash ends the dash after more than one decline per hour)";
    static final String PAUSED_REASON = "Auto-decline is off; inspect this offer manually.";

    static final class Decision {
        final Result result;
        final long requiredCents;
        final String reason;
        final Code code;
        /** Human explanation, one line per rule ("7.2 mi × $1.50/mi = $10.80"). */
        final List<String> breakdown;
        /** The avoid-list term that matched, or null. */
        final String avoidedStore;
        Decision(Result result, long requiredCents, String reason) {
            this(result, requiredCents, reason, result == Result.KEEP ? Code.MEETS : Code.UNSPECIFIED, null, null);
        }
        Decision(Result result, long requiredCents, String reason, Code code, List<String> breakdown, String avoidedStore) {
            this.result = result; this.requiredCents = requiredCents; this.reason = reason; this.code = code == null ? Code.UNSPECIFIED : code;
            this.breakdown = breakdown == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(breakdown));
            this.avoidedStore = avoidedStore;
        }
        String summary() {
            if (requiredCents == 0 || !code.statesRequirement()) return result + ": " + reason;
            return String.format(Locale.US, "%s: required at least %s (%s)", result, OfferMetrics.money(requiredCents), reason);
        }
        String explanation() { return String.join("\n", breakdown); }
        /** Auto-decline is off: never a decline, never a passing bell. */
        static Decision paused() { return new Decision(Result.REVIEW, 0, PAUSED_REASON, Code.PAUSED, null, null); }
    }

    /**
     * Facts-only entry point (0.4.x signature): current wall clock, no store labels, no merchant, no decline budget. An Earn by
     * Time snapshot from OfferParser is still REVIEW here. Adapters should pass labels and an EvaluationContext instead so the
     * avoid list and the opt-in decline limit apply.
     */
    static Decision evaluate(OfferSnapshot offer, FilterSettings settings) {
        return evaluate(offer, Collections.emptyList(), settings, EvaluationContext.now());
    }
    /** Facts-only add-on entry point (0.4.x signature); an Earn by Time add-on is still REVIEW. */
    static Decision evaluateAddOn(AddOnOffer addOn, FilterSettings settings) {
        return evaluateAddOn(addOn, Collections.emptyList(), settings, EvaluationContext.now());
    }

    /**
     * Evaluates one offer from its visible labels. Add-on wording/controls/increments (or ctx.addOn) route to the add-on
     * path with ctx.activeRoute; standalone screens match avoid terms against visible non-control labels.
     */
    static Decision evaluate(OfferSnapshot offer, List<String> labels, FilterSettings s, EvaluationContext ctx) {
        List<String> visible = labels == null ? Collections.emptyList() : labels;
        EvaluationContext c = ctx == null ? EvaluationContext.now() : ctx;
        if (c.addOn || AddOnOffer.isLikely(visible)) return evaluateAddOn(AddOnOffer.parse(c.activeRoute, offer, visible), visible, s, c);
        Decision guard = guard(visible, offer.hourly);
        if (guard != null) return guard;
        String avoided = StoreMatcher.firstMatch(s.avoidStores, c.merchant, visible);
        return limit(standalone(offer, null, s, s.baselineActive(c.nowWallMs), avoided, offer.hasFacts() || hasOfferControls(visible)), s, c);
    }

    /**
     * Add-ons: the combined route must pass on its displayed totals and the explicit increment must cover its own marginal
     * miles/time/stops. A route total derived from the stored route plus the increment is unknown for every rule (it is
     * shown, marked "not used"): the stored route may be partly driven or stale, so it never declines, and never passes, an
     * add-on. Only the stop count keeps the 0.4.5 derived ceiling (accepted stops + explicit "+N stops"). Avoid terms apply
     * only to a notification-derived merchant.
     */
    static Decision evaluateAddOn(AddOnOffer addOn, List<String> labels, FilterSettings s, EvaluationContext ctx) {
        List<String> visible = labels == null ? Collections.emptyList() : labels;
        EvaluationContext c = ctx == null ? EvaluationContext.now() : ctx;
        Decision guard = guard(visible, addOn.hourly);
        if (guard != null) return guard;
        String avoided = StoreMatcher.firstMatch(s.avoidStores, c.merchant, Collections.emptyList());
        FilterSettings routeRules = s.withRisingOffers(false);
        boolean storeMayDecline = addOn.incremental.hasFacts() || addOn.combined.hasFacts() || hasOfferControls(visible);
        Decision combined = standalone(addOn.shownRoute(), addOn.combined, routeRules, false, avoided, storeMayDecline);
        List<String> lines = new ArrayList<>();
        for (String line : combined.breakdown) lines.add("Route after adding: " + line);
        if (combined.result == Result.DECLINE) {
            String reason = combined.code == Code.AVOIDED_STORE ? combined.reason : "combined route fails: " + combined.reason;
            return limit(new Decision(Result.DECLINE, combined.requiredCents, reason, combined.code, lines, avoided), s, c);
        }
        if (combined.code == Code.AVOIDED_STORE) return new Decision(Result.REVIEW, 0, combined.reason, Code.AVOIDED_STORE, lines, avoided);
        if (combined.code == Code.NO_PAY_RULE)   // rising alone does not apply to add-ons
            return new Decision(Result.REVIEW, 0, s.hasNonStoreRule() ? "the rising rule does not apply to add-ons; no other pay rule set" : combined.reason, Code.NO_PAY_RULE, lines, null);
        OfferSnapshot delta = addOn.incremental;
        // A derived increment ("New total" minus the stored route pay) depends on stored context; it never decides an add-on.
        boolean payShown = delta.payCents != null && addOn.explicitIncrementPay;
        boolean missing = !payShown;
        lines.add(delta.payCents == null ? "Added pay not shown" : payShown ? "Added pay " + OfferMetrics.money(delta.payCents)
                : "Added pay not shown (new total minus stored route pay = " + OfferMetrics.money(delta.payCents) + " is not used)");
        long base = 0; Code baseCode = null;
        if (s.perMileCents > 0) {
            if (delta.miles == null) { missing = true; lines.add("Added miles not shown"); }
            else {
                long byMiles = mileage(s.perMileCents, delta.miles);
                lines.add("+" + OfferMetrics.miles(delta.miles) + " mi × " + OfferMetrics.perMile(s.perMileCents) + " = " + OfferMetrics.money(byMiles));
                if (byMiles > base) { base = byMiles; baseCode = Code.PER_MILE; }
            }
        }
        if (s.perMinuteCents > 0 || s.perHourCents > 0) {
            if (delta.minutes == null) { missing = true; lines.add("Added minutes not shown"); }
            else {
                if (s.perMinuteCents > 0) {
                    long byMinutes = (long) s.perMinuteCents * delta.minutes;
                    lines.add("+" + delta.minutes + " min × " + OfferMetrics.money(s.perMinuteCents) + "/min = " + OfferMetrics.money(byMinutes));
                    if (byMinutes > base) { base = byMinutes; baseCode = Code.PER_MINUTE; }
                }
                if (s.perHourCents > 0) {
                    long byHour = byHour(s.perHourCents, delta.minutes);
                    lines.add("+" + delta.minutes + " min at " + OfferMetrics.perHour(s.perHourCents) + " = " + OfferMetrics.money(byHour));
                    if (byHour > base) { base = byHour; baseCode = Code.PER_HOUR; }
                }
            }
        }
        long variable = base, fee = 0;
        if (s.extraStopCents > 0) {
            if (delta.stops == null || addOn.active.stops == null) { missing = true; lines.add("Added or current stops not shown"); }
            else {
                long before = Math.max(0L, (long) addOn.active.stops - 2);
                long after = Math.max(0L, (long) addOn.active.stops + delta.stops - 2);
                fee = mul(s.extraStopCents, after - before);
                variable = add(variable, fee);
                lines.add("+ " + OfferMetrics.money(fee) + " for " + (after - before) + " added extra stop" + (after - before == 1 ? "" : "s"));
            }
        }
        if (payShown && delta.payCents < variable) {
            Code code = baseCode == null || (fee > 0 && delta.payCents >= base) ? Code.EXTRA_STOPS : baseCode;
            lines.add("Added pay " + OfferMetrics.money(delta.payCents) + " vs needed " + OfferMetrics.money(variable));
            return limit(new Decision(Result.DECLINE, variable, "add-on marginal economics", code, lines, null), s, c);
        }
        if (combined.result == Result.REVIEW || missing)
            return new Decision(Result.REVIEW, variable, "add-on has missing or ambiguous incremental/route evidence", Code.ADDON_AMBIGUOUS, lines, null);
        return new Decision(Result.KEEP, variable, "explicit add-on and combined route meet rules", Code.MEETS, lines, null);
    }

    /** Unreadable text or Earn by Time (from labels or the parsed snapshot): always REVIEW, whatever another rule would say. */
    private static Decision guard(List<String> labels, boolean hourly) {
        if (!OfferEvidence.bounded(labels)) return new Decision(Result.REVIEW, 0, "screen text exceeded safe read limits", Code.UNREADABLE, null, null);
        if (hourly || OfferParser.isHourlyMode(labels))
            return new Decision(Result.REVIEW, 0, HOURLY_REASON, Code.HOURLY_MODE, Collections.singletonList("Earn by Time pays by active time; this app never auto-declines it"), null);
        return null;
    }

    /** Opt-in runaway guard: once the trailing hour holds the limit, a would-be decline becomes review (no click, no hide). */
    private static Decision limit(Decision d, FilterSettings s, EvaluationContext c) {
        if (d.result != Result.DECLINE || s.maxDeclinesPerHour <= 0 || c.declinesInLastHour < s.maxDeclinesPerHour) return d;
        List<String> lines = new ArrayList<>(d.breakdown);
        lines.add("Would request decline (" + d.reason + "), but " + c.declinesInLastHour + " decline requests already happened in the last hour");
        return new Decision(Result.REVIEW, d.requiredCents, "decline limit reached (" + s.maxDeclinesPerHour + " per hour)", Code.DECLINE_LIMIT, lines, d.avoidedStore);
    }

    /** Both offer controls are visible: the labels are a real offer card, not a notification or message. */
    private static boolean hasOfferControls(List<String> labels) {
        boolean accept = false, decline = false;
        for (String label : labels) { if (label == null) continue; accept |= OfferControls.isButton(label, "accept"); decline |= OfferControls.isButton(label, "decline"); }
        return accept && decline;
    }

    /**
     * storeMayDecline: the offer shows at least one explicit fact or both offer controls. A store match on text with neither
     * (a merchant-only notification) is REVIEW, so a misread message or promotion can never be declined or hidden by the avoid list.
     * derived: for an add-on route, the combined values including derived sums, used only to explain why a value is unknown.
     */
    private static Decision standalone(OfferSnapshot offer, OfferSnapshot derived, FilterSettings s, boolean risingActive, String avoided, boolean storeMayDecline) {
        List<String> lines = new ArrayList<>();
        Code failure = null; String failureReason = null;
        List<Code> missing = new ArrayList<>();
        // A rule that cannot evaluate (rising with no fresh baseline) never passes an offer on its own.
        boolean risingApplies = s.risingOffers && risingActive && s.lastAcceptedCents > 0;
        boolean payRule = s.flatCents > 0 || s.perMileCents > 0 || s.perMinuteCents > 0 || s.perHourCents > 0 || s.extraStopCents > 0 || risingApplies;
        boolean anyRule = payRule || s.maxStops > 0 || s.maxMilesHundredths > 0;
        String milesUnknown = derived != null && derived.miles != null ? "route total not shown (derived " + OfferMetrics.miles(derived.miles) + " mi is not used)" : "miles not shown";
        String minutesUnknown = derived != null && derived.minutes != null ? "route total not shown (derived " + derived.minutes + " min is not used)" : "minutes not shown";
        if (s.maxStops > 0) {
            if (offer.stops == null) { missing.add(Code.STOPS_MISSING); lines.add("Max " + s.maxStops + " stops: stops not shown"); }
            else if (offer.stops > s.maxStops) { failure = Code.MAX_STOPS; failureReason = offer.stops + " stops exceeds maximum " + s.maxStops; lines.add("Max " + s.maxStops + " stops: " + offer.stops + " is over"); }
            else lines.add("Max " + s.maxStops + " stops: " + offer.stops + " OK");
        }
        if (s.maxMilesHundredths > 0) {
            String max = OfferMetrics.milesHundredths(s.maxMilesHundredths);
            if (offer.miles == null) {
                missing.add(Code.MILES_MISSING);
                lines.add("Max " + max + " mi: " + milesUnknown);
            } else if (BigDecimal.valueOf(offer.miles).compareTo(BigDecimal.valueOf(s.maxMilesHundredths, 2)) > 0) {
                if (failure == null) { failure = Code.MAX_MILES; failureReason = OfferMetrics.miles(offer.miles) + " mi exceeds your " + max + " mi maximum"; }
                lines.add("Max " + max + " mi: " + OfferMetrics.miles(offer.miles) + " mi is over");
            } else lines.add("Max " + max + " mi: " + OfferMetrics.miles(offer.miles) + " mi OK");
        }
        boolean storeReview = false;
        if (avoided != null) {
            if (storeMayDecline) {
                if (failure == null) { failure = Code.AVOIDED_STORE; failureReason = "avoided store: " + avoided; }
                lines.add("Avoided store: " + avoided);
            } else { storeReview = true; lines.add("Avoided store: " + avoided + " (no offer details shown, so it is not declined)"); }
        }
        long required = 0; Code binding = Code.FLOOR; String reason = "flat minimum";
        if (s.flatCents > 0) { required = s.flatCents; lines.add("Flat minimum " + OfferMetrics.money(s.flatCents)); }
        if (s.perMileCents > 0) {
            if (offer.miles == null) { missing.add(Code.MILES_MISSING); lines.add(OfferMetrics.perMile(s.perMileCents) + ": " + milesUnknown); }
            else {
                long byMiles = mileage(s.perMileCents, offer.miles);
                lines.add(OfferMetrics.miles(offer.miles) + " mi × " + OfferMetrics.perMile(s.perMileCents) + " = " + OfferMetrics.money(byMiles));
                if (byMiles > required) { required = byMiles; binding = Code.PER_MILE; reason = "dollars per mile"; }
            }
        }
        if (s.perMinuteCents > 0 || s.perHourCents > 0) {
            if (offer.minutes == null) { missing.add(Code.MINUTES_MISSING); lines.add("Time rate: " + minutesUnknown); }
            else {
                if (s.perMinuteCents > 0) {
                    long byMinutes = (long) s.perMinuteCents * offer.minutes;
                    lines.add(offer.minutes + " min × " + OfferMetrics.money(s.perMinuteCents) + "/min = " + OfferMetrics.money(byMinutes));
                    if (byMinutes > required) { required = byMinutes; binding = Code.PER_MINUTE; reason = "dollars per minute"; }
                }
                if (s.perHourCents > 0) {
                    long byHour = byHour(s.perHourCents, offer.minutes);
                    lines.add(offer.minutes + " min at " + OfferMetrics.perHour(s.perHourCents) + " = " + OfferMetrics.money(byHour));
                    if (byHour > required) { required = byHour; binding = Code.PER_HOUR; reason = "dollars per hour"; }
                }
            }
        }
        long base = required;
        if (s.extraStopCents > 0) {
            if (offer.stops == null) { missing.add(Code.STOPS_MISSING); lines.add("Extra-stop fee: stops not shown"); }
            else {
                int extra = Math.max(0, offer.stops - 2);
                long fee = mul(s.extraStopCents, extra);
                required = add(required, fee);
                if (extra > 0) { reason += " and extra stops"; lines.add("+ " + OfferMetrics.money(fee) + " for " + extra + " extra stop" + (extra == 1 ? "" : "s")); }
                else lines.add("No extra-stop fee for " + OfferMetrics.stops(offer.stops));
            }
        }
        if (s.risingOffers && s.lastAcceptedCents > 0) {
            if (risingActive) {
                lines.add("Must beat last accepted " + OfferMetrics.money(s.lastAcceptedCents));
                if ((long) s.lastAcceptedCents + 1 > required) {
                    required = (long) s.lastAcceptedCents + 1; binding = Code.RISING;
                    reason = String.format(Locale.US, "must beat last accepted payout $%.2f", s.lastAcceptedCents / 100.0);
                }
            } else lines.add("Last accepted " + OfferMetrics.money(s.lastAcceptedCents) + " not applied (older than 8 h or undated)");
        }
        // While the rising rule is on, pay stays required even when its baseline is not active (0.4.x needsPay): unknown pay
        // is REVIEW, never a pass on the remaining non-pay rules. Rising alone with no active baseline is NO_PAY_RULE below.
        if (payRule || s.risingOffers) {
            if (offer.payCents == null) {
                missing.add(0, Code.PAY_MISSING);
                lines.add(derived != null && derived.payCents != null ? "Pay total not shown (derived " + OfferMetrics.money(derived.payCents) + " is not used)"
                        : payRule ? "Pay not shown" : "Pay not shown (required while the rising rule is on)");
            } else if (payRule) {
                lines.add("Pay " + OfferMetrics.money(offer.payCents) + " vs needed " + OfferMetrics.money(required));
                if (offer.payCents < required && failure == null) {
                    failure = binding != Code.RISING && offer.payCents >= base ? Code.EXTRA_STOPS : binding;
                    failureReason = reason;
                }
            }
        }
        if (failure != null) return new Decision(Result.DECLINE, required, failureReason, failure, lines, avoided);
        if (storeReview) return new Decision(Result.REVIEW, 0, "avoided store: " + avoided + "; no offer details shown", Code.AVOIDED_STORE, lines, avoided);
        if (!anyRule) {
            boolean rising = s.risingOffers;   // the only rule, and its baseline is not active
            lines.add(rising ? "Rising rule has no fresh accepted payout yet; it never passes an offer on its own" : "No pay rule set; avoided stores alone never pass an offer");
            return new Decision(Result.REVIEW, 0, rising ? "rising baseline not active; no other pay rule set" : "no pay rule set", Code.NO_PAY_RULE, lines, null);
        }
        if (!missing.isEmpty()) return new Decision(Result.REVIEW, required, missingReason(missing), firstMissing(missing), lines, null);
        return new Decision(Result.KEEP, required, "meets enabled rules", Code.MEETS, lines, null);
    }
    private static Code firstMissing(List<Code> missing) { Code best = null; for (Code c : missing) if (best == null || c.ordinal() < best.ordinal()) best = c; return best; }
    private static String missingReason(List<Code> missing) {
        List<String> names = new ArrayList<>();
        for (Code c : Code.values()) if (missing.contains(c)) names.add(c == Code.PAY_MISSING ? "pay" : c == Code.MILES_MISSING ? "miles" : c == Code.MINUTES_MISSING ? "minutes" : "stops");
        String joined = names.size() == 1 ? names.get(0) : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
        return joined + " not shown";
    }
    /** ceil(minutes × hourly rate / 60), exact in long arithmetic. */
    static long byHour(int perHourCents, int minutes) {
        long product = (long) perHourCents * minutes;
        return product / 60 + (product % 60 == 0 ? 0 : 1);
    }
    private static long mileage(int rate, double miles) {
        try { return BigDecimal.valueOf(miles).multiply(BigDecimal.valueOf(rate)).setScale(0, RoundingMode.CEILING).longValueExact(); }
        catch (ArithmeticException error) { return Long.MAX_VALUE; }
    }
    private static long mul(long a, long b) { return b != 0 && a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b; }
    private static long add(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
    private OfferRule() {}
}
