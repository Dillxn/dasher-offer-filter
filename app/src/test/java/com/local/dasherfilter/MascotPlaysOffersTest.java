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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The mascot plays out only real offers (the user: the drip "doesn't 1:1 represent what's happening in the app"):
 * nothing while no offer comes; an offer decided while the page is up plays a moment later, as what became of it by
 * then (a decline confirmed, a takeover); offers already there when the page opened, old ones, and any while paused,
 * play nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class MascotPlaysOffersTest extends AndroidAdapterTestBase {
    @Test
    public void aNewOfferPlaysOutAsItWentOnceItsLineSettles() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DecisionLog.record(app, entry(System.currentTimeMillis() - 5_000, 900, OfferRule.Result.DECLINE,
                DecisionLog.Action.DECLINE_TAPPED));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            FilterHeroView hero = find(content, FilterHeroView.class);
            assertEquals(FilterHeroView.State.ON, hero.state());
            idle(2000);
            assertNull("an offer already there when the page opened plays nothing", hero.playing());

            // A failing offer: first Decline tapped, then taken over before its line settled.
            long at = System.currentTimeMillis();
            DecisionLog.Entry declined = entry(at, 1100, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED);
            DecisionLog.record(app, declined);
            idle(1000);
            assertNull("not until its line settles", hero.playing());
            DecisionLog.record(app, entry(at, 1100, OfferRule.Result.DECLINE, DecisionLog.Action.USER_TOOK_OVER));
            idle(1500);
            assertEquals("played as it went by then: left to the user", DecisionLog.Outcome.YOURS, hero.playing());
            idle(FilterHeroView.OFFER_MS);
            assertNull("then still again", hero.playing());

            // A passing one.
            DecisionLog.record(app, entry(System.currentTimeMillis(), 2600, OfferRule.Result.KEEP,
                    DecisionLog.Action.PASSES));
            idle(2500);
            assertEquals(DecisionLog.Outcome.PASSED, hero.playing());
        }
    }

    @Test
    public void anOldOfferAndAnyWhilePausedPlayNothing() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            FilterHeroView hero = find(content, FilterHeroView.class);
            idle(2000);
            // Recorded now, but decided half a minute ago (the page was away).
            DecisionLog.record(app, entry(System.currentTimeMillis() - 30_000, 900, OfferRule.Result.DECLINE,
                    DecisionLog.Action.DECLINE_TAPPED));
            idle(2500);
            assertNull("an old offer plays nothing", hero.playing());

            hero.performClick();
            assertEquals(FilterHeroView.State.PAUSED, hero.state());
            DecisionLog.record(app, entry(System.currentTimeMillis(), 900, OfferRule.Result.DECLINE,
                    DecisionLog.Action.PAUSED));
            idle(2500);
            assertNull("asleep, it plays nothing", hero.playing());
        }
    }

    private static DecisionLog.Entry entry(long at, int pay, OfferRule.Result result, DecisionLog.Action action) {
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, new OfferSnapshot(pay, 4.0, 15, 2), 2000,
                result, result == OfferRule.Result.KEEP ? "meets enabled rules" : "flat minimum", action,
                action != DecisionLog.Action.PAUSED, Collections.emptyList());
    }

    private static void idle(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }
}
