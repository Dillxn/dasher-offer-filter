package com.local.dasherfilter;

/**
 * Facts around one evaluation that are not offer facts: wall clock (baseline freshness), add-on route context, the
 * notification-derived merchant, and how many decline requests the trailing hour already holds.
 */
final class EvaluationContext {
    final long nowWallMs;
    final boolean addOn;
    /** Accepted route context for add-ons; null when unknown. */
    final OfferSnapshot activeRoute;
    /** Merchant extracted from a notification ("" / null when unknown). Never raw screen labels. */
    final String merchant;
    final int declinesInLastHour;
    /**
     * The labels are a DoorDash offer notification proven by a strong offer marker (NotificationOffer.Kind.OFFER) whose merchant
     * came from an explicit offer phrase ("New Order: Go to <store>"). Only then may an avoid-list match on that merchant decline
     * (or hide) an offer that shows no pay/distance facts. Messages, promotions and review-only shapes never set it.
     */
    final boolean offerNotification;

    private EvaluationContext(long nowWallMs, boolean addOn, OfferSnapshot activeRoute, String merchant, int declinesInLastHour, boolean offerNotification) {
        this.nowWallMs = nowWallMs; this.addOn = addOn; this.activeRoute = activeRoute;
        this.merchant = merchant == null || merchant.trim().isEmpty() ? null : merchant.trim();
        this.declinesInLastHour = Math.max(0, declinesInLastHour);
        this.offerNotification = offerNotification;
    }
    static EvaluationContext at(long nowWallMs) { return new EvaluationContext(nowWallMs, false, null, null, 0, false); }
    static EvaluationContext now() { return at(System.currentTimeMillis()); }
    /** Evaluate as an add-on against this accepted route context (null = unknown route). */
    EvaluationContext withAddOn(OfferSnapshot route) { return new EvaluationContext(nowWallMs, true, route, merchant, declinesInLastHour, offerNotification); }
    EvaluationContext withMerchant(String m) { return new EvaluationContext(nowWallMs, addOn, activeRoute, m, declinesInLastHour, offerNotification); }
    EvaluationContext withDeclinesInLastHour(int n) { return new EvaluationContext(nowWallMs, addOn, activeRoute, merchant, n, offerNotification); }
    /** See offerNotification. Pass true only for a Kind.OFFER notification whose merchant came from "Go to <store>". */
    EvaluationContext withOfferNotification(boolean v) { return new EvaluationContext(nowWallMs, addOn, activeRoute, merchant, declinesInLastHour, v); }
    /** Uses the budget's trailing-hour count, excluding the offer being evaluated. budgetNow must use the budget's own clock. */
    EvaluationContext withBudget(DeclineBudget budget, long budgetNow, String offerKey) {
        return budget == null ? this : withDeclinesInLastHour(budget.count(budgetNow, offerKey));
    }
}
