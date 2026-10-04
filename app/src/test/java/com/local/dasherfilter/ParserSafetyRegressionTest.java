package com.local.dasherfilter;

import org.junit.Test;

/** The same deterministic cases run in the regular unit-test gate and the dependency-free local probe. */
public final class ParserSafetyRegressionTest {
    @Test public void ratesCannotBecomePayoutsOrIncrements() { ParserSafetyCases.rates(); }
    @Test public void siblingJoiningKeepsItemQualifiers() { ParserSafetyCases.itemFragments(); }
    @Test public void estimatedAndUniqueCountsStayUnknown() { ParserSafetyCases.itemMeaning(); }
    @Test public void boundedMalformedItemLabelsCannotCrash() { ParserSafetyCases.malformedItems(); }
    @Test public void rangesNeverBecomeExactStandaloneOrAddedMetrics() { ParserSafetyCases.ranges(); }
    @Test public void exactEvidenceAndExistingGuardsStayUnchanged() { ParserSafetyCases.unchangedEvidence(); }
}
