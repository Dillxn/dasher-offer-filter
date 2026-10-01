package com.local.dasherfilter;

import android.app.Application;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

/** Area scoring may compensate a weak spoke; the history must not claim an unchanged floor rose. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AreaDeclineLessonTest {
    private Application app;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        FilterStore.resetAccepted(app);
    }

    @Test public void areaPassingOfferBelowOneSetMinimumDoesNotClaimThatFloorRose() {
        FilterSettings rules = new FilterSettings(true, 1000, 100, 0, 0, 0, true, 0).withScoreByArea(true);
        OfferSnapshot offer = new OfferSnapshot(900, 4.0, 20, 2);
        FilterStore.save(app, rules);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(offer, FilterStore.load(app)).result);
        int score = AreaScore.percent(FilterStore.load(app), offer);
        assertEquals(FilterStore.DeclineLesson.NOTHING_NEW, FilterStore.learnFromDecline(app, offer));
        assertEquals(score, AreaScore.percent(FilterStore.load(app), offer));
        // Learning itself stays as the user chose: only the truthful lesson status changed.
        assertEquals(900, FilterStore.load(app).declined.payCents);
    }

    @Test public void aRealFloorIncreaseStillReportsTaughtAndSwitchesStillGateLearning() {
        FilterStore.save(app, new FilterSettings(true, 1000, 100, 0, 0, 0, true, 0).withScoreByArea(true));
        OfferSnapshot offer = new OfferSnapshot(1200, 4.0, 20, 2);
        assertEquals(FilterStore.DeclineLesson.TAUGHT, FilterStore.learnFromDecline(app, offer));
        assertEquals(1200, FilterStore.load(app).declined.payCents);
        FilterStore.save(app, FilterStore.load(app).withAdaptive(false));
        assertEquals(FilterStore.DeclineLesson.SWITCHES_OFF,
                FilterStore.learnFromDecline(app, new OfferSnapshot(1500, 4.0, 20, 2)));
    }
}
