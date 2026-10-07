package com.local.dasherfilter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertTrue;

/**
 * The toasts that guide the user over Android's own pages (setup, split screen) and the driving strip keep to two
 * lines: from Android 12 a text toast shows at most two lines of a 300 dp toast and cuts the rest with an ellipsis
 * (SystemUI's text_toast.xml), so a long toast loses its last words, often the action itself ("…then turn it off and on
 * again"). Each keeps to {@link SetupChecklist#TOAST_MOST} characters, with the action in it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class ToastWordsTest {
    @Test
    public void everyGuidingToastFitsTwoLines() {
        String[] toasts = {
                SetupChecklist.FIND_IN_LIST, SetupChecklist.RESTART_ON_PAGE, SetupChecklist.RESTART_IN_LIST,
                SetupChecklist.NOW_ON_PAGE, SetupChecklist.FIND_LISTENER, SetupChecklist.NOW_LISTENER,
                SetupChecklist.RECONNECT_ON_PAGE, SetupChecklist.RECONNECT_IN_LIST, SetupChecklist.ALLOW_SOURCE,
                SetupChecklist.ALLOW_NOTIFICATIONS, DasherSplit.hint("pixel"), DasherSplit.hint("samsung"),
                DasherSplit.hint("other"), DasherSplit.FLOATING_HINT, DasherSplit.SCREEN_READING_FIRST,
                DasherSplit.RECENTS_REFUSED, MainActivity.KNOBS_HINT, MainActivity.KNOBS_BEYOND_STRIP,
                MainActivity.FIRST_RULE, LocationRationale.PATH, "Not allowed. " + LocationRationale.PATH,
        };
        for (String toast : toasts) {
            assertTrue(toast.length() + " characters: " + toast, toast.length() <= SetupChecklist.TOAST_MOST);
        }
    }

    @Test
    public void theirWordsStillSayWhereToGoAndWhatToDo() {
        // AGENTS: the list "saying where to look ("Downloaded apps (or Installed apps) → Offer Filter")"; the split
        // hints in the phone's own words (Pixel: the icon, Split screen, then Dasher; Samsung: Open in split screen view).
        assertTrue(SetupChecklist.FIND_IN_LIST.startsWith("Downloaded apps (or Installed apps) → Offer Filter"));
        assertTrue(SetupChecklist.FIND_IN_LIST.endsWith("turn it on"));
        assertTrue(SetupChecklist.RESTART_IN_LIST.startsWith("Downloaded apps (or Installed apps) → Offer Filter"));
        assertTrue(SetupChecklist.RESTART_IN_LIST.endsWith("turn it off and on"));
        assertTrue(DasherSplit.hint("pixel").contains("Offer Filter's icon")
                && DasherSplit.hint("pixel").contains("Split screen, then Dasher"));
        assertTrue(DasherSplit.hint("samsung").contains("Open in split screen view"));
        assertTrue(DasherSplit.RECENTS_REFUSED.contains("recent apps"));
    }
}
