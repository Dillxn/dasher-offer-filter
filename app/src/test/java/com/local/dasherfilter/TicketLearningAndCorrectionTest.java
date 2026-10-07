package com.local.dasherfilter;

import android.os.Looper;
import android.view.View;
import java.time.Duration;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class TicketLearningAndCorrectionTest extends AndroidAdapterTestBase {
    @Test public void outcomeStepsAndTheScoreStayInsideTheSelectedTicket() {
        DecisionLog.Entry entry = declinedEntry().withScore(97).withStep(new DecisionLog.Step(
                DecisionLog.StepKind.NOT_LEARNED, System.currentTimeMillis(), "No confirmed acceptance"));
        DecisionLog.record(app, entry);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNull(shownTextContaining(content, "No confirmed acceptance"));
            assertNull(shownTextContaining(content, "Score 97%"));
            openTicket(content);
            assertNotNull("the score, as pay against the minimums",
                    shownTextContaining(content, "Score 97% of your minimums"));
            assertNull("no bar to name at exactly the minimums", shownTextContaining(content, " · bar "));
            assertNotNull(shownTextContaining(content, "Not counted from what followed: No confirmed acceptance"));
            assertNotNull(shownTextContaining(content, "Below your per-mile minimum"));
            for (String retired : new String[] {"Score reference", "Not learned", "learned", "Learned"}) {
                assertNull(retired, shownTextContaining(content, retired));
            }
        }
    }

    @Test public void howThisOfferWasJudgedStaysCollapsedInsideTheSelectedTicket() {
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 3));
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNull(shownTextContaining(content, AutopilotText.TICKET_KEY));
            openTicket(content);
            android.widget.TextView key = shownTextContaining(content, AutopilotText.TICKET_KEY);
            assertNotNull(key);
            assertEquals("Show how this offer was judged against your minimums", key.getContentDescription().toString());
            assertNull(shownTextContaining(content, "Your minimums · bar 100%"));
            key.performClick();
            // The declined $7.90 for 7.2 mi and 21 min, against the minimums as they are now.
            assertNotNull(shownTextContaining(content, "Your minimums · bar 100%"));
            assertNotNull(shownTextContaining(content, "Per mile — set $7.20 (7.2 mi × $1.00)"));
            assertNotNull(shownTextContaining(content, "Per hour — set $5.25 (21 min × $15/hr)"));
            assertEquals("Hide how this offer was judged against your minimums", key.getContentDescription().toString());
            assertNull("no learned column any more", shownTextContaining(content, "Learned"));
            key.performClick();
            assertNull(shownTextContaining(content, "Your minimums · bar 100%"));
        }
    }

    @Test public void laterEvidenceCorrectsTheSameFreshOffersAnimation() {
        FilterStore.save(app, FilterSettings.of(true, 1000, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            FilterHeroView hero = find(activity.get().findViewById(android.R.id.content), FilterHeroView.class);
            OfferSnapshot facts = new OfferSnapshot(1500, 4.0, 15, 2);
            DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN,
                    false, facts, 1000, OfferRule.Result.KEEP, "meets your minimums", DecisionLog.Action.PASSES,
                    true, Collections.emptyList()));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2300));
            assertEquals(DecisionLog.Outcome.PASSED, hero.playing());
            assertTrue(DecisionLog.markStep(app, facts, DecisionLog.StepKind.ACCEPTED,
                    "you tapped Accept, then Dasher showed a delivery", 60_000));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertEquals(DecisionLog.Outcome.ACCEPTED, hero.playing());
        }
    }
}
