package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** What one of Dasher's click events says: the user's Accept or Decline, neither, or Offer Filter's own tap. */
public class ClickEvidenceTest {
    private static final List<String> NONE = Collections.emptyList();

    private static ClickEvidence click(List<String> text, boolean isAccept, boolean isDecline, boolean own,
                                       List<String> above, List<String> below) {
        return new ClickEvidence(text, "", true, "View", isAccept, isDecline, own, own ? 2500 : -1, above, below);
    }

    @Test
    public void aComposeButtonWhoseLabelIsBelowItCounts() {
        // Jetpack Compose: an empty event, a clickable node with no text, its label on a child.
        ClickEvidence accept = click(NONE, false, false, false, NONE, Arrays.asList("Accept", "0:35"));
        assertTrue(accept.accept());
        assertFalse(accept.decline());
        ClickEvidence decline = click(NONE, false, false, false, NONE, Collections.singletonList("Decline"));
        assertTrue(decline.decline());
        assertFalse(decline.accept());
    }

    @Test
    public void theControlTheLastOfferReadFoundCountsWithoutAnyWords() {
        assertTrue(click(NONE, true, false, false, NONE, NONE).accept());
        assertTrue(click(NONE, false, true, false, NONE, NONE).decline());
    }

    @Test
    public void aTapNamingBothIsNeither() {
        ClickEvidence container = click(NONE, false, false, false, NONE, Arrays.asList("Decline", "Accept"));
        assertFalse(container.accept());
        assertFalse(container.decline());
        assertEquals(ClickEvidence.Verdict.BOTH, container.verdict());
        // Holding the offer's pay too, it is the card itself: its labels name no control at all.
        ClickEvidence card = click(NONE, false, false, false, NONE, Arrays.asList("Decline", "$7.90", "Accept"));
        assertFalse(card.accept() || card.decline());
        ClickEvidence text = click(Arrays.asList("Accept", "Decline"), false, false, false, NONE, NONE);
        assertFalse(text.accept() || text.decline());
    }

    @Test
    public void anAndroidViewButtonStillCountsByItsText() {
        assertTrue(click(Collections.singletonList("Accept"), false, false, false, NONE, NONE).accept());
        assertTrue(click(NONE, false, false, false, Arrays.asList("", "Decline offer"), NONE).decline());
    }

    @Test
    public void offerFiltersOwnTapComingBackIsNeverTheUsers() {
        ClickEvidence echo = click(NONE, false, true, true, NONE, NONE);
        assertFalse(echo.decline());
        assertFalse(echo.accept());
        assertEquals(ClickEvidence.Verdict.OWN, echo.verdict());
    }

    @Test
    public void itsDescriptionKeepsTheVerdictAndCanLeaveTheWordsOut() {
        StringBuilder long_ = new StringBuilder();
        for (int i = 0; i < 100; i++) long_.append("Customer note ").append(i).append(' ');
        ClickEvidence tap = click(NONE, false, true, false, NONE, Arrays.asList(long_.toString(), "Decline"));
        String described = tap.describe(true);
        assertTrue(described, described.endsWith(" -> decline"));
        assertTrue(described, described.length() < 600);
        String shape = tap.describe(false);
        assertEquals("class=View source=yes target=decline since-own-tap=- -> decline", shape);
        assertFalse(shape.contains("Customer"));
    }

    @Test
    public void aTapOnAnOfferCardsBodyIsNoAccept() {
        // The card holds the whole offer, its Accept button included: the labels below it name no control.
        ClickEvidence card = click(NONE, false, false, false, NONE,
                Arrays.asList("$16.75", "incl. tips", "3 stops (3.9 mi) • 30 min", "Accept", "0:35"));
        assertFalse(card.accept());
        assertEquals(ClickEvidence.Verdict.OTHER, card.verdict());
        // Nor does a big subtree without facts.
        ClickEvidence big = click(NONE, false, false, false, NONE,
                Arrays.asList("Store A", "Store B", "Customer", "Pickup", "Drop-off", "Details", "Accept"));
        assertFalse(big.accept());
        // The tapped node itself, or the control the last offer read found, still counts.
        assertTrue(click(NONE, true, false, false, NONE,
                Arrays.asList("$16.75", "3 stops (3.9 mi) • 30 min", "Accept")).accept());
        assertTrue(click(Collections.singletonList("Accept"), false, false, false, NONE,
                Arrays.asList("$16.75", "3 stops (3.9 mi) • 30 min")).accept());
    }
}
