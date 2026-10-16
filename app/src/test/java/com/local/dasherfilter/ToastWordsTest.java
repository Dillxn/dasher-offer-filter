package com.local.dasherfilter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The toasts that guide the user over Android's own pages (setup, split screen) and in a window too short for the
 * knobs keep to two lines: from Android 12 a text toast shows at most two lines of a 300 dp toast and cuts the rest
 * with an ellipsis (SystemUI's text_toast.xml), so a long toast loses its last words, often the action itself ("…then
 * turn it off and on again"). Each keeps to {@link SetupFlow#TOAST_MOST} characters, with the action in it. From
 * 0.6.26 setup's own page says each step before Android's page opens, so its toasts only name the path (the owner,
 * 16 October 2026: "toast instructions too long to read").
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class ToastWordsTest {
    @Test
    public void everyGuidingToastFitsTwoLines() {
        String[] toasts = {
                SetupFlow.inAccessibilityList(false), SetupFlow.inAccessibilityList(true), SetupFlow.IN_APP_INFO,
                SetupFlow.OFF_AND_ON_HERE, SetupFlow.LISTENER_IN_LIST, SetupFlow.LISTENER_OFF_AND_ON,
                SetupFlow.REBINDING, SetupFlow.ALLOW_SOURCE, SetupFlow.ALLOW_NOTIFICATIONS,
                DasherSplit.hintWithDasher("pixel"),
                DasherSplit.hintWithDasher("samsung"), DasherSplit.hintWithDasher("other"), DasherSplit.FLOATING_HINT,
                DasherSplit.SCREEN_READING_FIRST, DasherSplit.RECENTS_REFUSED, DasherSplit.PICK_DASHER_CARD,
                DasherSplit.PICK_DASHER_CARD_REFUSED, MainActivity.KNOBS_HINT, MainActivity.KNOBS_NEED_ROOM,
                MainActivity.FIRST_RULE, LocationRationale.PATH, "Not allowed. " + LocationRationale.PATH,
        };
        for (String toast : toasts) {
            assertTrue(toast.length() + " characters: " + toast, toast.length() <= SetupFlow.TOAST_MOST);
        }
        ShadowBuild.setManufacturer("samsung");
        for (boolean restart : new boolean[] {false, true}) {
            String toast = SetupFlow.inAccessibilityList(restart);
            assertTrue(toast.length() + " characters: " + toast, toast.length() <= SetupFlow.TOAST_MOST);
        }
    }

    @Test
    public void theirWordsStillSayWhereToGoAndWhatToDo() {
        // Setup's toasts: the path in Android's own words, a few of them. A Galaxy's Accessibility lists downloaded
        // apps under Installed apps (the owner's screenshot, 16 October 2026); stock Android under Downloaded apps.
        ShadowBuild.setManufacturer("Google");
        assertEquals("Downloaded apps → Offer Filter", SetupFlow.inAccessibilityList(false));
        assertEquals("Downloaded apps → Offer Filter: off, then on", SetupFlow.inAccessibilityList(true));
        ShadowBuild.setManufacturer("samsung");
        assertEquals("Installed apps → Offer Filter", SetupFlow.inAccessibilityList(false));
        assertEquals("⋮ → Allow restricted settings", SetupFlow.IN_APP_INFO);
        assertTrue("the listener by the name its list shows", SetupFlow.LISTENER_IN_LIST.endsWith(
                "Offer Filter background offers"));
        assertTrue(SetupFlow.LISTENER_OFF_AND_ON.endsWith("off, then on"));
        // The split hints in the phone's own words (Pixel: the icon, Split screen, then Dasher; Samsung: Open in split
        // screen view, then Dasher).
        assertTrue(DasherSplit.hintWithDasher("pixel").contains("Offer Filter's icon")
                && DasherSplit.hintWithDasher("pixel").contains("Split screen, then Dasher"));
        assertTrue(DasherSplit.hintWithDasher("samsung").contains("Offer Filter's icon")
                && DasherSplit.hintWithDasher("samsung").contains("Open in split screen view"));
        // Recent apps open only for the user to split there: nothing is started into the other half, so every phone's
        // words end with Dasher's own card.
        for (String phone : new String[] {"pixel", "samsung", "other"}) {
            assertTrue(DasherSplit.hintWithDasher(phone), DasherSplit.hintWithDasher(phone).endsWith("then Dasher"));
        }
        assertTrue(DasherSplit.RECENTS_REFUSED.contains("recent apps"));
        assertTrue(DasherSplit.RECENTS_REFUSED, DasherSplit.RECENTS_REFUSED.endsWith("then Dasher"));
        // The launch-screen line's tap: recent apps, where the user picks Dasher's card with the dash.
        assertTrue(DasherSplit.PICK_DASHER_CARD.contains("Dasher card"));
        assertTrue(DasherSplit.PICK_DASHER_CARD_REFUSED.startsWith("Open recent apps"));
        // The homepage line is one short line beside its Recent apps button.
        assertTrue(DasherSplit.LAUNCH_SCREEN_LINE, DasherSplit.LAUNCH_SCREEN_LINE.length() <= 40);
        assertTrue(DasherSplit.LAUNCH_SCREEN_SPOKEN.endsWith(DasherSplit.LAUNCH_SCREEN_ACTION));
    }
}
