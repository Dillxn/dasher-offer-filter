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

    private EvaluationContext(long nowWallMs, boolean addOn, OfferSnapshot activeRoute, String merchant, int declinesInLastHour) {
        this.nowWallMs = nowWallMs; this.addOn = addOn; this.activeRoute = activeRoute;
        this.merchant = merchant == null || merchant.trim().isEmpty() ? null : merchant.trim();
        this.declinesInLastHour = Math.max(0, declinesInLastHour);
    }
    static EvaluationContext at(long nowWallMs) { return new EvaluationContext(nowWallMs, false, null, null, 0); }
    static EvaluationContext now() { return at(System.currentTimeMillis()); }
    /** Evaluate as an add-on against this accepted route context (null = unknown route). */
    EvaluationContext withAddOn(OfferSnapshot route) { return new EvaluationContext(nowWallMs, true, route, merchant, declinesInLastHour); }
    EvaluationContext withMerchant(String m) { return new EvaluationContext(nowWallMs, addOn, activeRoute, m, declinesInLastHour); }
    EvaluationContext withDeclinesInLastHour(int n) { return new EvaluationContext(nowWallMs, addOn, activeRoute, merchant, n); }
    /** Uses the budget's trailing-hour count, excluding the offer being evaluated. budgetNow must use the budget's own clock. */
    EvaluationContext withBudget(DeclineBudget budget, long budgetNow, String offerKey) {
        return budget == null ? this : withDeclinesInLastHour(budget.count(budgetNow, offerKey));
    }
}
