package com.local.dasherfilter;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OfferControlsTest {
    @Test
    public void recognizesExactActionsAndCountdownFormats() {
        for (String label : new String[] {"Accept", "ACCEPT OFFER", "Accept order",
                "Accept (30)", "Accept, 30 seconds", "Accept 30 secs",
                "Accept\u202f30\u00a0seconds remaining", "Accept 0:30"}) {
            assertTrue(label, OfferControls.isButton(label, "accept"));
        }
        assertTrue(OfferControls.isButton("Decline", "decline"));
        assertTrue(OfferControls.isButton("Decline offer", "decline"));
    }

    @Test
    public void doesNotTreatDialogPromptsOrUnrelatedTextAsActions() {
        for (String label : new String[] {"Accept this offer?", "Acceptance rate 30%",
                "Accepted", "Decline this order?", "Decline reason", "30 seconds"}) {
            assertFalse(OfferControls.isButton(label, "accept"));
            assertFalse(OfferControls.isButton(label, "decline"));
        }
    }
}
