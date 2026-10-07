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

    /**
     * The ticket's bold line is the latest observed outcome: a decline by hand counted in 0.5.0 leads it, as an older
     * version's verdict on one did, and so does an offer seen not accepted; neither is left among the grey steps.
     */
    @Test public void aDeclineByHandCountedNowLeadsTheTicketAsAnOlderVersionsVerdictDid() {
        long at = System.currentTimeMillis() - 30_000;
        OfferSnapshot facts = new OfferSnapshot(1500, 4.0, 15, 2);
        String counted = DecisionLog.StepKind.DECLINE_COUNTED.label + ": another offer came";
        DecisionLog.record(app, new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, facts, 1000,
                OfferRule.Result.KEEP, "meets your minimums", DecisionLog.Action.PASSES, true,
                Collections.<String>emptyList())
                .withStep(new DecisionLog.Step(DecisionLog.StepKind.DECLINE_TAPPED, at + 2_000, ""))
                .withStep(new DecisionLog.Step(DecisionLog.StepKind.DECLINE_COUNTED, at + 4_000, "another offer came")));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            openTicket(content);
            android.widget.TextView lead = exactText(content, counted);
            assertNotNull("it leads the ticket, bold, as the 0.4.x verdict did", lead);
            assertEquals(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14,
                    app.getResources().getDisplayMetrics()), lead.getTextSize(), 0.01f);
            assertEquals("the other steps stay grey below", DecisionLog.StepKind.DECLINE_TAPPED.label,
                    exactText(content, DecisionLog.StepKind.DECLINE_TAPPED.label).getText().toString());
        }

        // An older version's verdict on a decline by hand still leads its ticket.
        DecisionLog.clear(app);
        DecisionLog.record(app, declinedEntry().withStep(new DecisionLog.Step(DecisionLog.StepKind.DECLINE_TAUGHT,
                System.currentTimeMillis(), "")));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            openTicket(content);
            assertNotNull(exactText(content, "Your Decline (older version)"));
        }

        // A passed offer seen not accepted: that outcome leads.
        DecisionLog.clear(app);
        String notAccepted = DecisionLog.StepKind.NOT_ACCEPTED.label + ": Dasher showed the wait for offers 3 s after it"
                + " left";
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() - 10_000, DecisionLog.Source.SCREEN,
                false, facts, 1000, OfferRule.Result.KEEP, "meets your minimums", DecisionLog.Action.PASSES, true,
                Collections.<String>emptyList()).withStep(new DecisionLog.Step(DecisionLog.StepKind.NOT_ACCEPTED,
                System.currentTimeMillis(), "Dasher showed the wait for offers 3 s after it left")));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            openTicket(content);
            assertNotNull(exactText(content, notAccepted));
        }
    }

    /** The shown line whose words are exactly {@code text}, or null. */
    private static android.widget.TextView exactText(View view, String text) {
        if (view instanceof android.widget.TextView && view.isShown()
                && ((android.widget.TextView) view).getText().toString().equals(text)) {
            return (android.widget.TextView) view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.TextView found = exactText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
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
