package com.local.dasherfilter;

import org.junit.Test;

/** Existing action/update boundaries must remain unchanged while offer parsing is hardened. */
public final class PolicySafetyRegressionTest {
    @Test public void declineRecoveryKeepsItsIdentityFreshnessAndRetryCaps() { PolicySafetyCases.declineRecovery(); }
    @Test public void updateMetadataRemainsStrictAndRetriesRemainBounded() { PolicySafetyCases.updateMetadata(); }
    @Test public void everyUpdateAddressStaysOnItsTrustedOrigin() { PolicySafetyCases.updateOrigins(); }
}
