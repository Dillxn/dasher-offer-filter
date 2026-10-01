package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Automatic update checks wait five minutes after the last; opening the app and the user's own checks do not. */
public class UpdateCadenceTest {
    private static final long NOW = 1_790_000_000_000L;

    @Test
    public void automaticChecksWaitFiveMinutesAfterTheLastAttempt() {
        for (UpdateCadence.Trigger trigger : new UpdateCadence.Trigger[] {UpdateCadence.Trigger.RESUMED,
                UpdateCadence.Trigger.CONNECTED, UpdateCadence.Trigger.PERIODIC, UpdateCadence.Trigger.AUTOMATIC}) {
            assertFalse(trigger.name(), UpdateCadence.mayStart(trigger, NOW, NOW - 90_000, 0));
            assertFalse(trigger.name(), UpdateCadence.mayStart(trigger, NOW, NOW - 299_999, 0));
            assertTrue(trigger.name(), UpdateCadence.mayStart(trigger, NOW, NOW - 300_000, 0));
        }
    }

    @Test
    public void openingTheAppAndTheUsersOwnChecksDoNotWait() {
        for (UpdateCadence.Trigger trigger : new UpdateCadence.Trigger[] {UpdateCadence.Trigger.OPENED,
                UpdateCadence.Trigger.TURNED_ON, UpdateCadence.Trigger.RETRY}) {
            assertTrue(trigger.name(), UpdateCadence.mayStart(trigger, NOW, NOW - 10_000, NOW - 1));
            // The cooldown after a check, and the backoff after a failure, still hold them.
            assertFalse(trigger.name(), UpdateCadence.mayStart(trigger, NOW, NOW - 10_000, NOW + 30_000));
        }
        assertTrue(UpdateCadence.mayStart(UpdateCadence.Trigger.MANUAL, NOW, NOW - 1, NOW + 30_000));
        assertTrue(UpdateCadence.Trigger.MANUAL.manual());
        assertFalse(UpdateCadence.Trigger.OPENED.manual());
    }

    @Test
    public void aClockMovedBackOrNoAttemptYetNeverHoldsChecksOff() {
        assertTrue(UpdateCadence.mayStart(UpdateCadence.Trigger.RESUMED, NOW, NOW + 60_000, 0));
        assertTrue(UpdateCadence.mayStart(UpdateCadence.Trigger.RESUMED, NOW, 0, 0));
    }

    @Test
    public void theRetryJobIsImmediateAndEveryOtherJobIsPeriodic() {
        assertEquals(UpdateCadence.Trigger.RETRY, UpdateCadence.forJob(7243, 7243));
        assertEquals(UpdateCadence.Trigger.PERIODIC, UpdateCadence.forJob(7241, 7243));
        assertEquals("periodic", UpdateCadence.Trigger.PERIODIC.label());
        assertEquals("turned on", UpdateCadence.Trigger.TURNED_ON.label());
    }
}
