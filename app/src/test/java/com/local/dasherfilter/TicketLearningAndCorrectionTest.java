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
    @Test public void learningAndStrictReferenceStayInsideTheSelectedTicket() {
        DecisionLog.Entry entry = declinedEntry().withScore(97).withStep(new DecisionLog.Step(
                DecisionLog.StepKind.NOT_LEARNED, System.currentTimeMillis(), "No confirmed acceptance"));
        DecisionLog.record(app, entry);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNull(shownTextContaining(content, "No confirmed acceptance"));
            openTicket(content);
            assertNotNull(shownTextContaining(content, "Score reference · 97%"));
            assertNotNull(shownTextContaining(content, "Not learned: No confirmed acceptance"));
            assertNotNull(shownTextContaining(content, "Below your per-mile rate"));
        }
    }

    @Test public void laterEvidenceCorrectsTheSameFreshOffersAnimation() {
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            FilterHeroView hero = find(activity.get().findViewById(android.R.id.content), FilterHeroView.class);
            OfferSnapshot facts = new OfferSnapshot(1500, 4.0, 15, 2);
            DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN,
                    false, facts, 1000, OfferRule.Result.KEEP, "meets enabled rules", DecisionLog.Action.PASSES,
                    true, Collections.emptyList()));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2300));
            assertEquals(DecisionLog.Outcome.PASSED, hero.playing());
            assertTrue(DecisionLog.markStep(app, facts, DecisionLog.StepKind.ACCEPTED_OBSERVED,
                    "confirmed delivery", 60_000));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertEquals(DecisionLog.Outcome.ACCEPTED, hero.playing());
        }
    }
}
