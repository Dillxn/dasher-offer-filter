package com.local.dasherfilter;

import java.math.BigDecimal;

/** On-demand explanation of the current minimums for the selected offer's actual observed amounts. */
final class MinimumsDetails {
    private static final int[] MONEY_AXES = {
            AreaScore.PAY, AreaScore.MILE, AreaScore.MINUTE, AreaScore.STOP, AreaScore.ITEM
    };
    private static final String[] NAMES = {"Payout", "Pay/mile", "Pay/min", "Pay/stop", "Hotspot", "Pay/item"};

    private MinimumsDetails() {}

    static String describe(FilterSettings rules, OfferSnapshot offer) {
        OfferSnapshot facts = offer == null ? OfferSnapshot.UNKNOWN : offer;
        AreaScore.Floors floors = AreaScore.floors(rules, facts);
        StringBuilder text = new StringBuilder("Current minimums · Scale ")
                .append(rules.minimumScalePercent).append("%\n");
        int[] minimums = rules.minimums();
        for (int axis : MONEY_AXES) {
            text.append(NAMES[axis]).append(" — Saved ")
                    .append(saved(axis, minimums[axis], facts, floors))
                    .append(" · Learned ").append(learned(axis, rules, floors))
                    .append(" · Used ").append(used(axis, rules, facts, floors)).append('\n');
        }
        text.append("\nBlue is Saved; purple is Learned. Values are payout-dollar requirements for this offer, "
                + "not per-unit rates. Used includes the percentage buffer; stored minimums stay unchanged. "
                + "≈ means rounded up to the next cent.\n\n");
        if (!rules.risingOffers) text.append("Adaptive is off: learned minimums are kept, not applied. ");
        if (rules.scoreByArea) {
            text.append("In area mode, 100% rescales to the current minimums: increased learning need not expand "
                    + "the purple outline. Stronger spokes can compensate for weaker ones. ");
        } else {
            text.append("In strict mode, every active Used amount must be met; the area score is only a reference. ");
        }
        text.append("Only confirmed manual choices train Payout, Pay/mile, Pay/min and Pay/stop. "
                + "Automatic accepts do not. Pay/item is fixed only.\n\n");
        appendHotspot(text, rules, facts);
        return text.toString();
    }

    private static String saved(int axis, int minimum, OfferSnapshot offer, AreaScore.Floors floors) {
        if (minimum <= 0) return "off";
        if (axis == AreaScore.ITEM && !offer.itemCountApplicable) return "not applicable";
        return amount(floors.fixedCents[axis]);
    }

    private static String learned(int axis, FilterSettings rules, AreaScore.Floors floors) {
        if (axis == AreaScore.ITEM) return "none (fixed only)";
        if (!hasLearned(axis, rules)) return "off";
        BigDecimal accepted = floors.acceptedCents[axis];
        BigDecimal declined = floors.declinedCents[axis];
        BigDecimal higher = accepted == null ? declined : declined == null ? accepted : accepted.max(declined);
        return amount(higher) + (rules.risingOffers ? "" : " (not applied)");
    }

    private static String used(int axis, FilterSettings rules, OfferSnapshot offer, AreaScore.Floors floors) {
        if (axis == AreaScore.ITEM && rules.perItemCents > 0 && !offer.itemCountApplicable) {
            return "not applicable";
        }
        if (!floors.active[axis]) return "off";
        // Area cannot score a zero route, while strict retains its known zero-cost policy.
        if (rules.scoreByArea && floors.cents[axis] == null) return "unavailable";
        if (floors.fixedCents[axis] == null && floors.acceptedCents[axis] == null
                && floors.declinedCents[axis] == null) return "unavailable";
        BigDecimal exact = floors.effectiveCents(axis).multiply(BigDecimal.valueOf(rules.minimumScalePercent))
                .movePointLeft(2);
        return amount(exact, floors.scaledCents(axis, rules.minimumScalePercent, false));
    }

    private static boolean hasLearned(int axis, FilterSettings rules) {
        switch (axis) {
            case AreaScore.PAY: return rules.lastAcceptedCents > 0 || rules.declined.payCents > 0;
            case AreaScore.MILE: return rules.best.hasPerMile() || rules.declined.rates.hasPerMile();
            case AreaScore.MINUTE: return rules.best.hasPerMinute() || rules.declined.rates.hasPerMinute();
            case AreaScore.STOP: return rules.best.hasPerStop() || rules.declined.rates.hasPerStop();
            default: return false;
        }
    }

    private static String amount(BigDecimal exactCents) {
        return exactCents == null ? "unavailable" : amount(exactCents, AreaScore.roundedCents(exactCents, 100));
    }

    private static String amount(BigDecimal exactCents, long roundedCents) {
        if (roundedCents == Long.MAX_VALUE) return "out of range";
        String approximation = exactCents.compareTo(BigDecimal.valueOf(roundedCents)) == 0 ? "" : "≈";
        return approximation + "$" + BigDecimal.valueOf(roundedCents, 2).toPlainString();
    }

    private static void appendHotspot(StringBuilder text, FilterSettings rules, OfferSnapshot offer) {
        text.append("Hotspot — ");
        if (offer.finalStopHotspotMiles == null) {
            text.append("distance unavailable; needs the actual distance from this offer's final stop "
                    + "to a current Dasher hotspot. Automatic measurement is unavailable. ");
        } else {
            text.append(BigDecimal.valueOf(offer.finalStopHotspotMiles).stripTrailingZeros().toPlainString())
                    .append(" mi from this offer's final stop to its nearest actual Dasher hotspot. ");
        }
        text.append("Independent of payout, fixed only; ");
        if (rules.hotspotProximityHundredths <= 0) {
            text.append("rule off.");
        } else {
            text.append("Saved ").append(FilterSettings.proximityLabel(rules.hotspotProximityHundredths))
                    .append("; Used ")
                    .append(BigDecimal.valueOf(rules.hotspotProximityHundredths)
                            .multiply(BigDecimal.valueOf(rules.minimumScalePercent)).movePointLeft(4)
                            .stripTrailingZeros().toPlainString())
                    .append(" /mi reciprocal proximity.");
        }
    }
}
