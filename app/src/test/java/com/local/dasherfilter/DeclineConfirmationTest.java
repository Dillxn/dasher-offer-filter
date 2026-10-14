package com.local.dasherfilter;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

    /**
     * 0.6.18 (the owner's option A: confirm only decline wording seen in his own reports, pause on anything else): the
     * only question confirmed is Dasher's usual one, keyed on its prompt line alone. Recognising a decline sheet is
     * unchanged (the broad words above still find it); only the confirmation needs the known line.
     */
    @Test public void onlyDashersUsualQuestionIsKnown() {
        assertEquals(Collections.singletonList("are you sure you want to decline this offer"),
                DeclineConfirmation.KNOWN_PROMPTS);
        // Dasher's question as the owner's reports showed it, its statements and buttons beside it.
        assertTrue(DeclineConfirmation.knownPrompt(Arrays.asList("Are you sure you want to decline this offer?",
                "Does not lower acceptance rate", "9%", "Decline offer", "Go back")));
        assertTrue(DeclineConfirmation.knownPrompt(Arrays.asList("$7.90", "Are you sure you want to decline this "
                + "offer?", "Declining this offer may lower your acceptance rate", "37%", "Decline offer")));
        // Case, spacing and the final "?" or ellipsis are the label's, not the words'.
        for (String same : new String[] {"ARE YOU SURE YOU WANT TO DECLINE THIS OFFER?",
                "Are you sure you want to decline\u00a0this offer", "Are you sure you want to decline this offer…",
                "Are you sure you want to decline this offer...", " Are you sure  you want to decline this offer ? "}) {
            assertTrue(same, DeclineConfirmation.knownPrompt(Arrays.asList(same, "Decline offer")));
        }
        // Anything else is unknown: broad phrasings, the known line with words added, another question beside it, and
        // a sheet with no question words at all.
        for (List<String> other : Arrays.asList(
                Arrays.asList("Are you sure?", "Decline", "Cancel"),
                Arrays.asList("Decline offer?", "Decline"),
                Arrays.asList("Decline this offer?", "Cancel"),
                Arrays.asList("Are you sure you want to decline this offer? You can decline 1 offer per hour",
                        "Decline offer"),
                Arrays.asList("Are you sure you want to decline this offer?", "End your dash?", "Decline offer"),
                Arrays.asList("Are you sure you want to decline these offers?", "Decline offers"),
                Arrays.asList("Declining orders can lower your acceptance rate", "Decline"),
                Arrays.asList("Decline offer", "Cancel"))) {
            assertFalse(other.toString(), DeclineConfirmation.knownPrompt(other));
        }
        assertEquals("Are you sure?", DeclineConfirmation.unknownPrompt(Arrays.asList("Are you sure?", "Decline",
                "Cancel")));
        assertEquals("(no question words)", DeclineConfirmation.unknownPrompt(Arrays.asList("Decline offer",
                "Cancel")));
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

