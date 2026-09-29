package com.local.dasherfilter;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public final class DeclineConfirmationTest {
    @Test public void selectsSecondButtonEvenWithBackgroundAcceptPresent() {
        assertEquals(1, DeclineConfirmation.select(
                Arrays.asList("$3.00", "Accept 29", "Decline", "Cancel", "Decline offer"),
                Arrays.asList("Decline", "Decline offer")));
    }

    @Test public void prefersOverlayWhenIdenticallyNamedActionsRemainVisible() {
        assertEquals(1, DeclineConfirmation.select(
                Arrays.asList("Accept", "Decline offer", "Cancel"),
                Arrays.asList("Decline offer", "Decline offer")));
    }

    @Test public void doesNotMistakeAnOrdinaryOfferForConfirmation() {
        assertFalse(DeclineConfirmation.isSurface(Arrays.asList("$30.00", "Accept 29", "Decline offer")));
        assertEquals(-1, DeclineConfirmation.select(
                Arrays.asList("$30.00", "Accept 29", "Decline offer"),
                Arrays.asList("Decline offer")));
    }

    @Test public void allowsPlainDeclineWhenDialogPromptIdentifiesConfirmation() {
        assertEquals(0, DeclineConfirmation.select(
                Arrays.asList("Are you sure you want to decline this offer?", "Go Back", "Decline"),
                Arrays.asList("Decline")));
    }

    @Test public void waitsForAnActionableButtonRatherThanSelectingTheHeading() {
        assertTrue(DeclineConfirmation.isSurface(Arrays.asList("Decline offer", "Cancel")));
        assertEquals(-1, DeclineConfirmation.select(
                Arrays.asList("Decline offer", "Cancel"), Collections.emptyList()));
        assertEquals(0, DeclineConfirmation.select(
                Arrays.asList("Decline offer", "Cancel"), Arrays.asList("Decline offer")));
    }

    @Test public void neverSelectsCancelBackOrAccept() {
        assertEquals(-1, DeclineConfirmation.select(
                Arrays.asList("Decline this offer?", "Cancel", "Go Back", "Accept"),
                Arrays.asList("Cancel", "Go Back", "Accept")));
    }

    @Test public void normalizesConfirmationLabels() {
        assertEquals(0, DeclineConfirmation.select(
                Arrays.asList("DECLINE\u202fOFFER", "Cancel"),
                Arrays.asList("DECLINE\u202fOFFER")));
    }

    @Test public void loneDeclineButtonIsNotAConfirmation() {
        // A new offer drawn before its pay and Accept button looks exactly like this.
        assertFalse(DeclineConfirmation.isSurface(Arrays.asList("Decline")));
        assertEquals(-1, DeclineConfirmation.select(Arrays.asList("Decline"), Arrays.asList("Decline")));
    }

    @Test public void questionTitleIdentifiesAConfirmationSheet() {
        assertTrue(DeclineConfirmation.isSurface(Arrays.asList("Decline offer?", "Decline")));
        assertEquals(0, DeclineConfirmation.select(
                Arrays.asList("Decline offer?", "Decline"), Arrays.asList("Decline")));
    }

    @Test public void dialogButtonDrawnOverTheOfferWinsWhateverItsWording() {
        // The offer's own "Decline offer" comes first in the tree; the sheet's bare "Decline" is drawn over it.
        assertEquals(1, DeclineConfirmation.select(
                Arrays.asList("$7.90", "Decline offer", "Are you sure?", "Decline", "Cancel"),
                Arrays.asList("Decline offer", "Decline")));
    }

    @Test public void recognizesOtherBackOutWordings() {
        for (String back : new String[] {"Keep order", "Never mind", "No thanks", "Not now"}) {
            assertTrue(back, DeclineConfirmation.isSurface(Arrays.asList("Decline order", back)));
        }
        assertTrue(DeclineConfirmation.isSurface(
                Arrays.asList("Declining orders can lower your acceptance rate", "Decline")));
    }

    @Test public void explicitPromptIsDistinguishedFromABackButton() {
        assertTrue(DeclineConfirmation.hasPrompt(Arrays.asList("Are you sure you want to decline this offer?")));
        assertFalse(DeclineConfirmation.hasPrompt(Arrays.asList("Back", "Decline", "Accept")));
    }
}

