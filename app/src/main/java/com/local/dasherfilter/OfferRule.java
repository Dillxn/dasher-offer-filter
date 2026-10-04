package com.local.dasherfilter;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Applies saved rules to offer facts. A known failure declines even when other values are missing; otherwise a
 * missing value that an enabled rule needs yields REVIEW, never KEEP or DECLINE. A known ceiling on unknown pay (a
 * "+$" amount beside a total) declines an offer that misses the set rules even at that ceiling (the user's own rule
 * for that shape), and never passes one.
 *
 * <p>Strict (the default) holds a standalone offer to every minimum. Score by area (the user's choice) holds it to
 * its {@link AreaScore area score} instead: the selected cutoff (100% by default) passes, less declines; max stops
 * stays a hard limit, an active
 * minimum whose amount was not read leaves the offer to review (it could lift the score), and add-ons keep the
 * strict rules. Either way a decision carries the offer's score where it can be worked out.
 */
final class OfferRule {
    enum Result { DECLINE, KEEP, REVIEW }

    static final class Decision {
        final Result result;
        final long requiredCents;
        final String reason;
        /** The facts the requirement was compared with: the offer, an add-on's increment, or its combined route. */
        final OfferSnapshot basis;
        /** The offer's area score as a whole percent ({@link AreaScore#percent}), in either mode; -1 when none. */
        final int scorePercent;
        /** The area cutoff used for this decision; recorded fitness itself stays against the original baselines. */
        final int minimumScalePercent;

        Decision(Result result, long requiredCents, String reason) {
            this(result, requiredCents, reason, OfferSnapshot.UNKNOWN);
        }

        Decision(Result result, long requiredCents, String reason, OfferSnapshot basis) {
            this(result, requiredCents, reason, basis, -1);
        }

        Decision(Result result, long requiredCents, String reason, OfferSnapshot basis, int scorePercent) {
            this(result, requiredCents, reason, basis, scorePercent, 100);
        }

        Decision(Result result, long requiredCents, String reason, OfferSnapshot basis, int scorePercent,
                 int minimumScalePercent) {
            this.result = result;
            this.requiredCents = requiredCents;
            this.reason = reason;
            this.basis = basis;
            this.scorePercent = scorePercent;
            this.minimumScalePercent = minimumScalePercent;
        }

        String summary() {
            if (requiredCents == 0) return result + ": " + reason;
            if (reason.startsWith(SCORE_REASON)) {
                return String.format(Locale.US, "%s: %s; $%.2f would score %d%%", result, reason,
                        requiredCents / 100.0, minimumScalePercent);
            }
            return String.format(Locale.US, "%s: required at least $%.2f (%s)", result, requiredCents / 100.0, reason);
        }
    }

    /** How a reason by area score begins: "score 87% (needs 100%)". */
    static final String SCORE_REASON = "score ";

    static String scoreReason(int percent) {
        return scoreReason(percent, 100);
    }

    static String scoreReason(int percent, int minimumScalePercent) {
        return SCORE_REASON + percent + "% (needs " + minimumScalePercent + "%)";
    }

    /** The rules applied to a standalone offer, strictly or by area score as the user chose. */
    static Decision evaluate(OfferSnapshot offer, FilterSettings settings) {
        if (settings.scoreByArea) return byArea(offer, settings);
        Decision strict = strict(offer, settings);
        return new Decision(strict.result, strict.requiredCents, strict.reason, strict.basis,
                AreaScore.percent(settings, offer), settings.minimumScalePercent);
    }

    /**
     * Whether an unreadable-offer report would merely repeat the deliberately unavailable hotspot observation.
     * This does not change the decision or manual reporting. Re-evaluate without only that rule so another
     * unreadable active fact keeps its report; an unread payout is never attributed solely to hotspot data.
     */
    static boolean onlyHotspotMissing(OfferSnapshot offer, AddOnOffer addOn, FilterSettings settings) {
        if (settings.hotspotProximityHundredths <= 0) return false;
        OfferSnapshot relevant = addOn == null ? offer : addOn.combined;
        if (relevant == null || relevant.finalStopHotspotMiles != null || relevant.payCents == null) return false;
        if (addOn != null && addOn.incremental.payCents == null) return false;
        FilterSettings withoutHotspot = settings.withHotspotProximity(0);
        Decision remaining = addOn == null ? evaluate(offer, withoutHotspot) : evaluateAddOn(addOn, withoutHotspot);
        return remaining.result != Result.REVIEW;
    }

    /**
     * Score by area: max stops declines above it, as a hard limit; pay not read, or an active minimum's amount not
     * read, is review (no score from what is missing); otherwise the selected cutoff passes and less declines, with
     * the reason "score 96% (needs 97%)" and the least whole-cent pay reaching that cutoff. A "+$" ceiling on
     * unknown pay declines only when even that ceiling misses the cutoff on the set minimums alone (the adaptive
     * minimum never judges an add-on, so under that reading it would not fail), else review. With no minimum to score,
     * the strict rules apply (they have only max stops and an adaptive minimum with nothing learned yet left to ask).
     */
    private static Decision byArea(OfferSnapshot offer, FilterSettings settings) {
        int scale = settings.minimumScalePercent;
        AreaScore.Floors floors = AreaScore.floors(settings, offer);
        if (!floors.anyActive()) {
            Decision strict = strict(offer, settings);
            return new Decision(strict.result, strict.requiredCents, strict.reason, strict.basis, -1);
        }
        int percent = offer.payCents == null && floors.needsPay() ? -1
                : AreaScore.percent(floors, offer.payCents == null ? 0 : offer.payCents, scale);
        if (settings.maxStops > 0 && offer.stops != null && offer.stops > settings.maxStops) {
            return new Decision(Result.DECLINE, 0, offer.stops + " stops exceeds maximum " + settings.maxStops, offer,
                    percent, scale);
        }
        // A lone proximity spoke has no payout component: no amount of pay repairs a known distance failure.
        // The independent score may be known while pay is unread, but unread pay must still leave a pass to review.
        if (!floors.needsPay()) {
            if (!floors.readable()) return new Decision(Result.REVIEW, 0,
                    "final stop to nearest hotspot distance not found", offer);
            if (!AreaScore.reaches(floors, 0, scale)) {
                return new Decision(Result.DECLINE, 0, hotspotFailure(offer, settings), offer, percent, scale);
            }
            if (offer.payCents == null) return new Decision(Result.REVIEW, 0, "pay not found", offer, percent, scale);
            if (settings.maxStops > 0 && offer.stops == null) {
                return new Decision(Result.REVIEW, 0, "an enabled value was not found", offer, percent, scale);
            }
            return new Decision(Result.KEEP, 0, scoreReason(percent, scale), offer, percent, scale);
        }
        if (offer.payCents == null) {
            if (offer.payAtMostCents == null) return new Decision(Result.REVIEW, 0, "pay not found", offer);
            AreaScore.Floors set = AreaScore.floors(settings.withoutRisingBaseline(), offer);
            if (set.anyActive() && set.readable() && !AreaScore.reaches(set, offer.payAtMostCents, scale)) {
                return new Decision(Result.DECLINE, AreaScore.requiredPay(set, scale), "pay at most "
                        + DecisionLog.money(offer.payAtMostCents) + " with its +$ amount; "
                        + scoreReason(AreaScore.percent(set, offer.payAtMostCents, scale), scale), offer);
            }
            return new Decision(Result.REVIEW, AreaScore.requiredPay(floors, scale), "pay unclear beside a +$ amount", offer);
        }
        if (!floors.readable()) {
            // A declared shopping order has at least one item. Substituting exactly one is therefore an upper
            // bound on its area score: every larger real count only lengthens the item floor and lowers that spoke.
            // Use the bound only to prove a decline. It must never produce a pass, a learned observation or a
            // plotted score for the still-unknown count.
            if (floors.active[AreaScore.ITEM] && offer.items == null && offer.payCents != null) {
                AreaScore.Floors oneItem = AreaScore.floors(settings, offer.withItems(1, true));
                if (oneItem.readable() && !AreaScore.reaches(oneItem, offer.payCents, scale)) {
                    int upper = AreaScore.percent(oneItem, offer.payCents, scale);
                    return new Decision(Result.DECLINE, AreaScore.requiredPay(oneItem, scale),
                            "item count not found; even 1 item scores " + upper + "% (needs " + scale + "%)",
                            offer, -1, scale);
                }
            }
            return new Decision(Result.REVIEW, 0,
                    settings.hotspotProximityHundredths > 0 && offer.finalStopHotspotMiles == null
                            ? "final stop to nearest hotspot distance not found"
                            : floors.active[AreaScore.ITEM] && offer.items == null ? "item count not found"
                            : "an enabled value was not found", offer);
        }
        long required = AreaScore.requiredPay(floors, scale);
        if (!AreaScore.reaches(floors, offer.payCents, scale)) {
            return new Decision(Result.DECLINE, required, scoreReason(percent, scale), offer, percent, scale);
        }
        if (settings.maxStops > 0 && offer.stops == null) {
            return new Decision(Result.REVIEW, required, "an enabled value was not found", offer, percent, scale);
        }
        return new Decision(Result.KEEP, required, scoreReason(percent, scale), offer, percent, scale);
    }

    /**
     * The strict rules. Required pay is {@code max(flat, miles × rate, minutes × rate, stops × rate, items × rate)}.
     * The adaptive minimum raises it to one cent above the highest accepted standalone payout, and to at least the best accepted pay
     * per minute, per mile and per stop applied to this offer; each is a floor of its own, never added on top.
     * The global scale applies after each fixed or learned floor is resolved, with one final upward cent rounding
     * (a fixed mileage product stays decimal until then). Saved minimums and the learning records are not changed.
     */
    static Decision strict(OfferSnapshot offer, FilterSettings settings) {
        if (settings.maxStops > 0 && offer.stops != null && offer.stops > settings.maxStops) {
            String reason = offer.stops + " stops exceeds maximum " + settings.maxStops;
            return new Decision(Result.DECLINE, 0, reason, offer);
        }
        AreaScore.Floors floors = AreaScore.floors(settings, offer);
        // This spoke is independent of pay. A known failure remains a failure even when pay is unread.
        if (floors.active[AreaScore.HOTSPOT] && floors.hotspotDenominator != null
                && !floors.hotspotReaches(settings.minimumScalePercent)) {
            return new Decision(Result.DECLINE, 0, hotspotFailure(offer, settings), offer);
        }
        // A configured monetary rule still needs pay on a sparse notification, even before item evidence arrives.
        boolean needsPay = settings.flatCents > 0 || settings.hasMarginalRule() || settings.risingOffers
                || settings.hotspotProximityHundredths > 0;
        if (needsPay && offer.payCents == null && offer.payAtMostCents == null) {
            return new Decision(Result.REVIEW, 0, "pay not found", offer);
        }

        boolean missing = (settings.maxStops > 0 && offer.stops == null)
                || (settings.hotspotProximityHundredths > 0 && offer.finalStopHotspotMiles == null)
                || (floors.active[AreaScore.ITEM] && offer.items == null);
        // A standalone order is at least a pickup and a drop-off, so fewer stops is a misread ("1 stop"). Every
        // per-stop ask treats it as not found: priced as read, it would ask half as much and let the offer pass.
        Integer stops = offer.stops != null && offer.stops >= AcceptedBest.PLAUSIBLE_STOPS ? offer.stops : null;
        int scale = settings.minimumScalePercent;
        long required = floors.scaledCents(AreaScore.PAY, scale, true);
        String reason = "flat minimum";
        if (settings.perMileCents > 0) {
            if (offer.miles == null) {
                missing = true;
            } else {
                long byMiles = floors.scaledCents(AreaScore.MILE, scale, true);
                if (byMiles > required) {
                    required = byMiles;
                    reason = "dollars per mile";
                }
            }
        }
        if (settings.perMinuteCents > 0) {
            if (offer.minutes == null) {
                missing = true;
            } else {
                long byMinutes = floors.scaledCents(AreaScore.MINUTE, scale, true);
                if (byMinutes > required) {
                    required = byMinutes;
                    reason = "dollars per minute";
                }
            }
        }
        if (settings.perStopCents > 0) {
            if (stops == null) {
                missing = true;
            } else {
                long byStops = floors.scaledCents(AreaScore.STOP, scale, true);
                if (byStops > required) {
                    required = byStops;
                    reason = "dollars per stop";
                }
            }
        }
        AreaScore.Floors oneItem = floors.active[AreaScore.ITEM] && offer.items == null
                ? AreaScore.floors(settings, offer.withItems(1, true)) : null;
        if (floors.active[AreaScore.ITEM] && (offer.items != null
                || oneItem != null && oneItem.fixedCents[AreaScore.ITEM] != null)) {
            long byItems = (oneItem == null ? floors : oneItem)
                    .scaledCents(AreaScore.ITEM, scale, true);
            if (byItems > required) {
                required = byItems;
                reason = offer.items == null ? "dollars per item (at least 1 item)" : "dollars per item";
            }
        }
        // What the set rules alone ask, before the adaptive floors: all that a bound on unknown pay is judged by.
        long setRequired = required;
        String setReason = reason;
        if (settings.risingOffers && settings.lastAcceptedCents > 0
                && scaledCost(floors.acceptedCents[AreaScore.PAY].longValue(), scale) > required) {
            required = scaledCost(floors.acceptedCents[AreaScore.PAY].longValue(), scale);
            reason = String.format(Locale.US, "must beat highest accepted payout $%.2f",
                    settings.lastAcceptedCents / 100.0);
        }
        if (settings.risingOffers) {
            AcceptedBest best = settings.best;
            if (best.hasPerMinute()) {
                if (offer.minutes == null) {
                    missing = true;
                } else if (scaledCost(floors.acceptedCents[AreaScore.MINUTE].longValue(), scale) > required) {
                    required = scaledCost(floors.acceptedCents[AreaScore.MINUTE].longValue(), scale);
                    reason = "must match best accepted " + best.perMinuteLabel();
                }
            }
            if (best.hasPerMile()) {
                if (offer.miles == null) {
                    missing = true;
                } else if (scaledCost(floors.acceptedCents[AreaScore.MILE].longValue(), scale) > required) {
                    required = scaledCost(floors.acceptedCents[AreaScore.MILE].longValue(), scale);
                    reason = "must match best accepted " + best.perMileLabel();
                }
            }
            if (best.hasPerStop()) {
                if (stops == null) {
                    missing = true;
                } else if (scaledCost(floors.acceptedCents[AreaScore.STOP].longValue(), scale) > required) {
                    required = scaledCost(floors.acceptedCents[AreaScore.STOP].longValue(), scale);
                    reason = "must match best accepted " + best.perStopLabel();
                }
            }
            if (best.hasPerItem() && offer.itemCountApplicable) {
                if (offer.items == null) {
                    missing = true;
                    long byItems = oneItem == null ? 0
                            : oneItem.scaledCents(AreaScore.ITEM, scale, false);
                    if (byItems > required) {
                        required = byItems;
                        reason = "must match best accepted " + best.perItemLabel()
                                + " for at least 1 item";
                    }
                } else if (scaledCost(floors.acceptedCents[AreaScore.ITEM].longValue(), scale) > required) {
                    required = scaledCost(floors.acceptedCents[AreaScore.ITEM].longValue(), scale);
                    reason = "must match best accepted " + best.perItemLabel();
                }
            }
            // What offers declined by hand taught: each is a floor of its own, beaten by at least a cent.
            DeclinedFloor declined = settings.declined;
            if (declined.payCents > 0 && scaledCost(floors.declinedCents[AreaScore.PAY].longValue(), scale) > required) {
                required = scaledCost(floors.declinedCents[AreaScore.PAY].longValue(), scale);
                reason = "must beat declined payout " + DecisionLog.money(declined.payCents);
            }
            if (declined.rates.hasPerMinute()) {
                if (offer.minutes == null) {
                    missing = true;
                } else if (scaledCost(floors.declinedCents[AreaScore.MINUTE].longValue(), scale) > required) {
                    required = scaledCost(floors.declinedCents[AreaScore.MINUTE].longValue(), scale);
                    reason = "must beat declined " + declined.rates.perMinuteLabel();
                }
            }
            if (declined.rates.hasPerMile()) {
                if (offer.miles == null) {
                    missing = true;
                } else if (scaledCost(floors.declinedCents[AreaScore.MILE].longValue(), scale) > required) {
                    required = scaledCost(floors.declinedCents[AreaScore.MILE].longValue(), scale);
                    reason = "must beat declined " + declined.rates.perMileLabel();
                }
            }
            if (declined.rates.hasPerStop()) {
                if (stops == null) {
                    missing = true;
                } else if (scaledCost(floors.declinedCents[AreaScore.STOP].longValue(), scale) > required) {
                    required = scaledCost(floors.declinedCents[AreaScore.STOP].longValue(), scale);
                    reason = "must beat declined " + declined.rates.perStopLabel();
                }
            }
        }

        reason = scaledReason(reason, scale);
        setReason = scaledReason(setReason, scale);

        // Missing values can only raise the requirement, so a shortfall against the known part is already final.
        if (offer.payCents != null && offer.payCents < required) {
            return new Decision(Result.DECLINE, required, reason, offer);
        }
        if (needsPay && offer.payCents == null) {
            // Only the most it can pay is known. The user's own rule for this one shape (approved in chat): decline
            // when the offer as a whole, Y + X, misses the set rules, though an add-on reading might have passed the
            // add-on rules. The adaptive floors never judge an add-on, so they cannot count; anything else is unknown.
            if (offer.payAtMostCents < setRequired) {
                return new Decision(Result.DECLINE, setRequired, "pay at most "
                        + DecisionLog.money(offer.payAtMostCents) + " with its +$ amount; " + setReason, offer);
            }
            return new Decision(Result.REVIEW, required, "pay unclear beside a +$ amount", offer);
        }
        if (missing) return new Decision(Result.REVIEW, required,
                settings.hotspotProximityHundredths > 0 && offer.finalStopHotspotMiles == null
                        ? "final stop to nearest hotspot distance not found"
                        : floors.active[AreaScore.ITEM] && offer.items == null ? "item count not found"
                        : "an enabled value was not found", offer);
        return new Decision(Result.KEEP, required, scale == 100 ? "meets enabled rules"
                : "meets enabled rules at " + scale + "% minimum scale", offer);
    }

    /**
     * An add-on must keep the combined route within the flat, rate, and stop limits, and its own explicit added
     * pay must cover {@code max(added miles × rate, added minutes × rate, added stops × rate, added items × rate)}.
     * Only the amounts the add-on explicitly adds count: none is worked out from route totals. Always strict, score by area or not: an
     * add-on's increment has its own meaning, which an area score of a standalone offer does not capture.
     */
    static Decision evaluateAddOn(AddOnOffer addOn, FilterSettings settings) {
        Decision combined = strict(addOn.combined, settings.withoutRisingBaseline());
        if (combined.result == Result.DECLINE) {
            return new Decision(Result.DECLINE, combined.requiredCents, "combined route fails: " + combined.reason,
                    addOn.combined);
        }

        OfferSnapshot added = addOn.incremental;
        // Incremental and combined requirements are separate constraints, both using the same fixed cost resolver.
        AreaScore.Floors floors = AreaScore.incrementalFloors(settings, added);
        boolean missing = (settings.hasMarginalRule() || settings.risingOffers) && added.payCents == null;
        int scale = settings.minimumScalePercent;
        long marginalCost = 0;
        for (int axis : new int[] {AreaScore.MILE, AreaScore.MINUTE, AreaScore.STOP, AreaScore.ITEM}) {
            if (!floors.active[axis]) continue;
            if (floors.fixedCents[axis] == null) missing = true;
            else marginalCost = Math.max(marginalCost, floors.scaledCents(axis, scale, true));
        }

        if (added.payCents != null && added.payCents < marginalCost) {
            return new Decision(Result.DECLINE, marginalCost, scaledReason("add-on marginal economics", scale), added);
        }
        if (combined.result == Result.REVIEW || missing) {
            return new Decision(Result.REVIEW, marginalCost,
                    "add-on has missing or ambiguous incremental/route evidence", added);
        }
        return new Decision(Result.KEEP, marginalCost, "combined route and add-on meet enabled rules", added);
    }

    private static String hotspotFailure(OfferSnapshot offer, FilterSettings settings) {
        return "final stop is " + BigDecimal.valueOf(offer.finalStopHotspotMiles).stripTrailingZeros().toPlainString()
                + " mi from nearest hotspot; proximity below "
                + (settings.minimumScalePercent == 100 ? "" : settings.minimumScalePercent + "% of ")
                + FilterSettings.proximityLabel(settings.hotspotProximityHundredths);
    }

    /** Cents for {@code miles × rate}, rounded up; saturates rather than overflowing. */
    static long mileageCost(int rateCents, double miles) {
        return AreaScore.roundedCents(BigDecimal.valueOf(miles).multiply(BigDecimal.valueOf(rateCents)), 100);
    }

    /** Scale a resolved whole-cent floor; Long.MAX_VALUE remains the existing unreachable/saturated sentinel. */
    static long scaledCost(long cents, int minimumScalePercent) {
        if (minimumScalePercent == 100 || cents == Long.MAX_VALUE) return cents;
        return scaledCost(BigDecimal.valueOf(cents), minimumScalePercent);
    }

    /** Apply the scale before rounding a fixed route product, so fractions of a cent are not rounded twice. */
    private static long scaledCost(BigDecimal cents, int minimumScalePercent) {
        return AreaScore.roundedCents(cents, minimumScalePercent);
    }

    /** A buffer may fall below the learned offer: describe its baseline without claiming it must still be beaten. */
    private static String scaledReason(String reason, int minimumScalePercent) {
        if (minimumScalePercent == 100) return reason;
        return minimumScalePercent + "% of baseline: " + reason.replace("must beat ", "")
                .replace("must match ", "");
    }

    private OfferRule() {}
}
