package com.local.dasherfilter;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public final class DeclineConfirmationTest {
    @Test public void selectsSecondButtonEvenWithBackgroundAcceptPresent() {
        assertEquals(1, DeclineConfirmation.select(
                Arrays.asList("$3.00", "Accept 29", "Decline", "Cancel", "Decline offer"),
                Arrays.asList("Decline", "Decline offer"), true));
    }

    @Test public void prefersOverlayWhenIdenticallyNamedActionsRemainVisible() {
        assertEquals(1, DeclineConfirmation.select(
                Arrays.asList("Accept", "Decline offer", "Cancel"),
                Arrays.asList("Decline offer", "Decline offer"), true));
    }

    @Test public void doesNotMistakeAnOrdinaryOfferForConfirmation() {
        assertFalse(DeclineConfirmation.isSurface(Arrays.asList("$30.00", "Accept 29", "Decline offer"), true));
        assertEquals(-1, DeclineConfirmation.select(
                Arrays.asList("$30.00", "Accept 29", "Decline offer"),
                Arrays.asList("Decline offer"), true));
    }

    @Test public void allowsPlainDeclineWhenDialogPromptIdentifiesConfirmation() {
        assertEquals(0, DeclineConfirmation.select(
                Arrays.asList("Are you sure you want to decline this offer?", "Go Back", "Decline"),
                Arrays.asList("Decline"), false));
    }

    @Test public void waitsForAnActionableButtonRatherThanSelectingTheHeading() {
        assertTrue(DeclineConfirmation.isSurface(Arrays.asList("Decline offer", "Cancel"), false));
        assertEquals(-1, DeclineConfirmation.select(
                Arrays.asList("Decline offer", "Cancel"), Collections.emptyList(), false));
        assertEquals(0, DeclineConfirmation.select(
                Arrays.asList("Decline offer", "Cancel"), Arrays.asList("Decline offer"), false));
    }

    @Test public void neverSelectsCancelBackOrAccept() {
        assertEquals(-1, DeclineConfirmation.select(
                Arrays.asList("Decline this offer?", "Cancel", "Go Back", "Accept"),
                Arrays.asList("Cancel", "Go Back", "Accept"), true));
    }

    @Test public void normalizesConfirmationLabels() {
        assertEquals(0, DeclineConfirmation.select(
                Arrays.asList("DECLINE\u202fOFFER", "Cancel"),
                Arrays.asList("DECLINE\u202fOFFER"), true));
    }
}
