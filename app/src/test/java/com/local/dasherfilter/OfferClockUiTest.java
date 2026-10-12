package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

/**
 * The offer clock (0.6.2; the owner, 12 October 2026: "we need how time is affecting their earnings to be something
 * the user is aware of"): it stands in the skyline caption's row while no older offer is selected, is heard in words
 * and opens what it means; an older offer tapped on the skyline takes the row back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class OfferClockUiTest extends AndroidAdapterTestBase {
    /** $10.00 minimum pay, $1.00 a mile and $12 an hour, at most 3 stops. */
    private static final FilterSettings RULES = FilterSettings.of(true, 1000, 100, 20, 3);

    private void record(int offers) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < offers; i++) {
            int pay = 900 + 100 * i;
            DecisionLog.record(app, new DecisionLog.Entry(now - (offers - i) * 1000L, DecisionLog.Source.SCREEN, false,
                    new OfferSnapshot(pay, 3.0, 15, 2), 1000, OfferRule.Result.KEEP, "meets your minimums",
                    DecisionLog.Action.PASSES, true, Collections.singletonList(DecisionLog.money(pay))));
        }
    }

    private static TextView caption(ActivityController<MainActivity> activity) {
        return ReflectionHelpers.getField(activity.get(), "offerCaption");
    }

    @Test public void theClockStandsInTheEmptyCaptionRowIsHeardAndOpensWhatItMeans() {
        FilterStore.save(app, RULES);
        record(12);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            TextView caption = caption(activity);
            assertEquals("the latest is said once, by the verdict at the top", "", caption.getText().toString());
            OfferClock.Face face = (OfferClock.Face) caption.getBackground();
            assertNotNull("the clock is drawn there", face.clock());
            assertEquals(12, face.clock().total);
            String said = String.valueOf(caption.getContentDescription());
            assertTrue(said, said.startsWith("Offers by time of day, as a percent of your minimums now. "));
            assertTrue(said, said.contains(", now. "));
            assertTrue(said, said.endsWith("History, not a promise."));
            assertTrue(caption.isClickable());
            assertTrue("a whole 48 dp row", caption.getHeight() >= new Ui(app).dp(48) - 1);
            caption.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertEquals(OfferClock.TITLE, Shadows.shadowOf(dialog).getTitle().toString());
            String details = Shadows.shadowOf(dialog).getMessage().toString();
            assertTrue(details, details.startsWith(OfferClock.DETAILS + "\n\n"));
            assertTrue("each slot on a line of its own", details.endsWith(face.clock().table()));
            assertTrue("nothing changes from a look at the clock", FilterStore.load(app).enabled);
        }
    }

    @Test public void anOlderOfferTakesTheRowBackAndTheLatestGivesItToTheClockAgain() {
        FilterStore.save(app, RULES);
        record(12);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            TextView caption = caption(activity);
            OfferClock.Face face = (OfferClock.Face) caption.getBackground();
            DecisionChartView chart = find(content, DecisionChartView.class);
            // Oldest first on the skyline: the twelfth, $20.00, is the latest.
            int latest = 11;
            assertEquals(Integer.valueOf(2000), chart.selectedEntry().facts.payCents);
            chart.select(latest - 1);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(caption.getText().toString(), caption.getText().toString().startsWith("Selected · $19.00"));
            assertNull("no clock behind an offer's words", face.clock());
            assertTrue(String.valueOf(caption.getContentDescription()).endsWith("Open offer details"));
            caption.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("its tap still opens the offer", find(content, MinimumsStarView.class).openedOffer() >= 0);
            activity.get().onBackPressed();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            chart.select(latest);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("", caption.getText().toString());
            assertNotNull("the clock is back", face.clock());
            assertTrue(String.valueOf(caption.getContentDescription()).startsWith(OfferClock.TITLE));
        }
    }

    @Test public void noClockBeforeTenOffersOrWithoutAMoneyMinimum() {
        FilterStore.save(app, RULES);
        record(9);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            TextView caption = caption(activity);
            assertNull(((OfferClock.Face) caption.getBackground()).clock());
            assertNull("nothing to hear", caption.getContentDescription());
            assertFalse("nothing to tap", caption.isClickable());
        }
        FilterStore.save(app, FilterStore.load(app), FilterSettings.of(true, 0, 0, 0, 3));
        record(3);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            assertNull("max stops alone scores nothing", ((OfferClock.Face) caption(activity).getBackground()).clock());
        }
    }
}
