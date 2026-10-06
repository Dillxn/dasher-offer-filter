package com.local.dasherfilter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Reading Dasher's acceptance rate from its decline question (finalSpec.autopilot.arGuard): one unambiguous "N%"
 * beside acceptance-rate wording, the "Does not lower acceptance rate" mark unless other wording says declining does
 * lower it, and nothing at all from a screen that is not a decline question or that shows an account.
 */
public final class AcceptanceRateTest {
    private static final String QUESTION = "Are you sure you want to decline this offer?";

    private static AcceptanceRate.Parsed parse(String... labels) {
        return AcceptanceRate.parse(Arrays.asList(labels));
    }

    private static void assertParsed(String what, int percent, boolean exempt, AcceptanceRate.Parsed parsed) {
        assertEquals(what + ": percent", percent, parsed.percent);
        assertEquals(what + ": exempt", exempt, parsed.exempt);
    }

    @Test
    public void theOwnersQuestionReadsNinePercentAndFree() {
        assertParsed("owner's capture", 9, true,
                parse(QUESTION, "Does not lower acceptance rate", "9%", "Maintaining a hi…"));
        assertTrue(parse(QUESTION, "Does not lower acceptance rate", "9%").hasPercent());
    }

    @Test
    public void theQuestionInTheConfirmationTestsReadsFiftyAndFree() {
        // ConfirmationLeftToYouTest's question as the screen reader reads it, the offer behind it included.
        assertParsed("ConfirmationLeftToYouTest fixture", 50, true, parse(QUESTION,
                "Does not lower acceptance rate", "50%", "Accepting this offer will raise acceptance rate", "Decline",
                "$9.25", "incl. tips", "2 stops (7.2 mi) • 21 min", "Accept", "0:24", "View offer details"));
    }

    @Test
    public void wordingThatDecliningLowersTheRateIsNotFree() {
        assertParsed("may lower", 62, false,
                parse("Declining this offer may lower your acceptance rate", "62%"));
        assertParsed("will lower beside the mark", 9, false, parse(QUESTION, "Does not lower acceptance rate",
                "Declining will lower your acceptance rate", "9%"));
        assertParsed("can lower", 9, false, parse(QUESTION, "Does not lower acceptance rate",
                "Declines can lower your acceptance rate", "9%"));
        assertParsed("lowers your acceptance", 9, false, parse(QUESTION, "Does not lower acceptance rate",
                "Declining lowers your acceptance rate", "9%"));
        assertParsed("raise is not lower", 9, true, parse(QUESTION, "Does not lower acceptance rate",
                "Accepting this offer will raise acceptance rate", "9%"));
    }

    @Test
    public void theMarkMustBeTheWholeLabel() {
        assertParsed("with 'your' and a full stop", 40, true,
                parse(QUESTION, "Does not lower your acceptance rate.", "40%"));
        assertParsed("an exclamation mark", 40, true, parse(QUESTION, "Does NOT lower acceptance rate!", "40%"));
        assertParsed("inside a longer sentence", 40, false,
                parse(QUESTION, "Declining this does not lower acceptance rate today", "40%"));
        assertParsed("no mark", 40, false, parse(QUESTION, "Your acceptance rate", "40%"));
    }

    @Test
    public void onlyOneUnambiguousPercentCounts() {
        assertParsed("two different percents", -1, true,
                parse(QUESTION, "Does not lower acceptance rate", "50%", "9%"));
        assertParsed("the same percent twice", 9, true,
                parse(QUESTION, "Does not lower acceptance rate", "9%", "9%"));
        assertParsed("no acceptance-rate wording", -1, false, parse(QUESTION, "9%"));
        assertParsed("over 100", -1, false, parse(QUESTION, "Your acceptance rate", "101%"));
        assertParsed("100", 100, false, parse(QUESTION, "Your acceptance rate", "100%"));
        assertParsed("0", 0, false, parse(QUESTION, "Your acceptance rate", "0%"));
        assertParsed("a space before the sign", 7, false, parse(QUESTION, "Your acceptance rate", "7 %"));
        assertParsed("a no-break space and a full-width sign", 7, false,
                parse(QUESTION, "Your acceptance rate", "7 ％"));
        assertParsed("a percent inside a sentence is not a label of its own", -1, false,
                parse(QUESTION, "Your acceptance rate is 9%"));
        assertParsed("four digits never match", 9, false, parse(QUESTION, "Your acceptance rate", "1000%", "9%"));
        assertParsed("no percent at all", -1, true, parse(QUESTION, "Does not lower acceptance rate"));
        assertFalse(parse(QUESTION, "Does not lower acceptance rate").hasPercent());
    }

    @Test
    public void onlyADeclineQuestionIsRead() {
        assertNull("no question", parse("Does not lower acceptance rate", "9%"));
        assertNull("an offer", parse("$9.25", "2 stops (7.2 mi) • 21 min", "Accept", "Decline", "9%"));
        assertNull("an account screen", parse(QUESTION, "Available balance", "Your acceptance rate", "9%"));
        assertNull("an earnings page", parse(QUESTION, "Earnings history", "Does not lower acceptance rate", "9%"));
        assertNull(AcceptanceRate.parse(null));
        assertParsed("Dasher's other question shape", 31, false,
                parse("Decline offer?", "Your acceptance rate may go down", "31%"));
    }

    @Test
    public void missingLabelsAreSkipped() {
        List<String> labels = Arrays.asList(null, QUESTION, null, "Does not lower acceptance rate", "9%");
        assertParsed("null labels", 9, true, AcceptanceRate.parse(labels));
    }
}
