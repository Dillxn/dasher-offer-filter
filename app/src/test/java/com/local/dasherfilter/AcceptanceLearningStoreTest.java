package com.local.dasherfilter;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36}, instrumentedPackages = {"com.local.dasherfilter"})
@LooperMode(LooperMode.Mode.PAUSED)
public class AcceptanceLearningStoreTest {
    private Application app;
    private long now;
    private static final OfferSnapshot OFFER = new OfferSnapshot(1420, 6.0, 24, 2);
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        prefs().edit().clear().commit();
        app.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(Consent.ACCEPTED_VERSION, Consent.VERSION).putLong(Consent.ACCEPTED_AT, 100).commit();
        now = 1_791_000_000_000L; FilterStore.wallClock = () -> now;
        Updater.setEnabled(app, false);
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
        FilterStore.setAcceptanceLearning(app, true);
    }
    @After public void cleanup() { FilterStore.wallClock = System::currentTimeMillis; }
    private SharedPreferences prefs() { return app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE); }
    private FilterStore.AcceptedLesson learn(OfferSnapshot offer) {
        return FilterStore.recordConfirmedManualAcceptance(app, FilterStore.captureLearning(app, offer), offer);
    }
    @Test public void proofPersistsIndependentlyOfStaleRuleCopiesAndSavedKnobs() {
        FilterSettings old = FilterStore.load(app);
        assertEquals(FilterStore.AcceptedLesson.RAISED, learn(OFFER));
        FilterStore.save(app, old, old.withEnabled(false));
        Context fresh = app.createConfigurationContext(app.getResources().getConfiguration());
        FilterSettings stored = FilterStore.load(fresh);
        assertEquals(1420, stored.accepted.highestPayCents);
        assertEquals(1420, stored.accepted.milePay); assertEquals(6, stored.accepted.miles, 0);
        // The saved minimums stay as the user set them (the owner, 12 October 2026).
        assertArrayEquals(new int[] {700, 150, 30, 0, 0, 0}, stored.minimums()); assertFalse(stored.enabled);
    }
    /**
     * The owner, 11 and 12 October 2026: "new highs ... raise mins", but the saved ones never by themselves ("we have
     * the adopt mins feature for that"). The learned minimums rise, the note says so, and OK keeps everything.
     */
    @Test public void aNewHighRaisesTheLearnedMinimumsNotTheSavedOnes() {
        assertEquals(FilterStore.AcceptedLesson.RAISED, learn(OFFER));
        FilterSettings after = FilterStore.load(app);
        assertArrayEquals(new int[] {700, 150, 30, 0, 0, 0}, after.minimums());
        assertEquals(1420, after.accepted.highestPayCents);
        FilterStore.LearnedRaise raise = FilterStore.learnedRaise(app);
        assertNotNull(raise);
        assertEquals(1421, raise.flat); assertEquals(237, raise.mile); assertEquals(60, raise.minute);
        assertEquals("$14.21/stop · $2.37/mi · $36/hr · yours $7.00/stop · $1.50/mi · $18/hr", LearnedRaiseCard.line(raise));
        assertNull("nothing was adopted", FilterStore.pendingAdoption(app));
        // OK closes the note; the learned minimums still apply and the saved ones stay.
        FilterStore.closeLearnedRaise(app, raise.id);
        assertNull(FilterStore.learnedRaise(app));
        assertArrayEquals(new int[] {700, 150, 30, 0, 0, 0}, FilterStore.load(app).minimums());
        assertEquals(1420, FilterStore.load(app).accepted.highestPayCents);
    }
    /** Adopt on the note makes the learned minimums the user's own, with Learned minimums' Undo. */
    @Test public void adoptFromTheNoteRaisesOnlyLowerSavedMinimumsWithUndo() {
        FilterStore.save(app, FilterStore.load(app).withMinimums(new int[] {2000, 150, 30, 0, 0, 0}));
        assertEquals(FilterStore.AcceptedLesson.RAISED, learn(OFFER));
        FilterStore.LearnedRaise raise = FilterStore.learnedRaise(app);
        assertNotNull(raise);
        assertEquals("pay asks no more than yours", 0, raise.flat);
        assertEquals("$2.37/mi · $36/hr · yours $1.50/mi · $18/hr", LearnedRaiseCard.line(raise));
        assertTrue(FilterStore.adoptAccepted(app));
        assertArrayEquals(new int[] {2000, 237, 60, 0, 0, 0}, FilterStore.load(app).minimums());
        assertNull("adopted, nothing is left to note", FilterStore.learnedRaise(app));
        FilterStore.Adoption a = FilterStore.pendingAdoption(app);
        assertNotNull(a);
        assertTrue(FilterStore.undoAdoption(app, a.id));
        FilterSettings undone = FilterStore.load(app);
        assertArrayEquals(new int[] {2000, 150, 30, 0, 0, 0}, undone.minimums());
        assertEquals("Undo of an adoption keeps the bests", 1420, undone.accepted.highestPayCents);
    }
    /** The note goes once its lesson's bests change, and hides while learning is off. */
    @Test public void theNoteFollowsItsLessonAndTheLearner() {
        learn(OFFER);
        assertNotNull(FilterStore.learnedRaise(app));
        FilterStore.setAcceptanceLearning(app, false);
        assertNull("learned minimums off: nothing rose", FilterStore.learnedRaise(app));
        FilterStore.setAcceptanceLearning(app, true);
        assertNotNull("the bests still stand", FilterStore.learnedRaise(app));
        FilterStore.save(app, FilterStore.load(app).withMinimums(new int[] {5000, 5000, 5000, 0, 0, 0}));
        assertNull("no learned minimum asks more than yours", FilterStore.learnedRaise(app));
        FilterStore.save(app, FilterStore.load(app).withMinimums(new int[] {700, 150, 30, 0, 0, 0}));
        assertNull("forgotten once it could not show", FilterStore.learnedRaise(app));
        learn(new OfferSnapshot(2000, 6.0, 24, 2));
        FilterStore.LearnedRaise next = FilterStore.learnedRaise(app);
        assertNotNull(next); assertEquals(2001, next.flat);
        FilterStore.resetAccepted(app);
        assertNull(FilterStore.learnedRaise(app));
    }
    /**
     * The owner's 11 October 7:02 PM offer: he stepped the knobs while it showed. His own change keeps its lesson
     * valid; Adopt (the learned minimums made his) still starts lessons afresh.
     */
    @Test public void theUsersKnobChangeKeepsAnOfferOnScreensLessonButAdoptDoesNot() {
        FilterStore.LearningPermit onScreen = FilterStore.captureLearning(app, OFFER);
        FilterSettings before = FilterStore.load(app);
        FilterStore.save(app, before, before.withMinimums(new int[] {900, 150, 30, 0, 0, 0}));
        FilterStore.save(app, FilterStore.load(app).withMaxStops(2));
        assertEquals(FilterStore.AcceptedLesson.RAISED,
                FilterStore.recordConfirmedManualAcceptance(app, onScreen, OFFER));
        OfferSnapshot next = new OfferSnapshot(3000, 6.0, 24, 2);
        FilterStore.LearningPermit later = FilterStore.captureLearning(app, next);
        assertTrue(FilterStore.adoptAccepted(app));
        assertEquals(FilterStore.AcceptedLesson.STALE, FilterStore.recordConfirmedManualAcceptance(app, later, next));
        assertEquals(1420, FilterStore.load(app).accepted.highestPayCents);
    }
    @Test public void aBestBelowTheSavedMinimumsRaisesNothing() {
        FilterStore.save(app, FilterStore.load(app).withMinimums(new int[] {5000, 5000, 5000, 0, 0, 0}));
        assertEquals(FilterStore.AcceptedLesson.SAVED, learn(OFFER));
        assertArrayEquals(new int[] {5000, 5000, 5000, 0, 0, 0}, FilterStore.load(app).minimums());
        assertNull(FilterStore.learnedRaise(app));
    }
    @Test public void learningOffOrPausedRaisesNoMinimum() {
        FilterStore.save(app, FilterStore.load(app).withEnabled(false));
        assertEquals(FilterStore.AcceptedLesson.OFF, learn(OFFER));
        assertArrayEquals(new int[] {700, 150, 30, 0, 0, 0}, FilterStore.load(app).minimums());
        assertNull(FilterStore.pendingAdoption(app));
    }
    @Test public void bestStoredBelowFixedMinimumIsDistinctFromRaisedAndUnchanged() {
        FilterStore.save(app, FilterStore.load(app).withMinimums(new int[] {5000, 5000, 5000, 0, 0, 0}));
        assertEquals(FilterStore.AcceptedLesson.SAVED, learn(OFFER));
        assertEquals(FilterStore.AcceptedLesson.UNCHANGED, learn(OFFER));
        assertEquals(FilterStore.AcceptedLesson.RAISED, learn(new OfferSnapshot(6000, 6.0, 24, 2)));
    }
    @Test public void invalidatingTransitionsCannotResurrectACapturedOutcome() {
        for (int transition = 0; transition < 5; transition++) {
            FilterStore.setAcceptanceLearning(app, true);
            FilterStore.save(app, FilterStore.load(app).withEnabled(true));
            FilterStore.LearningPermit pending = FilterStore.captureLearning(app, OFFER);
            assertNotNull(pending);
            switch (transition) {
                case 0: FilterStore.setAcceptanceLearning(app, false); FilterStore.setAcceptanceLearning(app, true); break;
                case 1: FilterStore.save(app, FilterStore.load(app).withEnabled(false));
                    FilterStore.save(app, FilterStore.load(app).withEnabled(true)); break;
                case 2: FilterStore.resetAccepted(app); break;
                case 3: FilterStore.clearLearningHistory(app); break;
                default: FilterStore.invalidateLearningEvidence(app); break;
            }
            assertEquals("transition " + transition, FilterStore.AcceptedLesson.STALE,
                    FilterStore.recordConfirmedManualAcceptance(app, pending, OFFER));
            assertTrue(FilterStore.load(app).accepted.isEmpty());
        }
    }
    @Test public void duplicateAndContradictoryEvidenceCannotTeachAgain() {
        FilterStore.LearningPermit pending = FilterStore.captureLearning(app, OFFER);
        assertEquals(FilterStore.AcceptedLesson.STALE, FilterStore.recordConfirmedManualAcceptance(app, pending,
                new OfferSnapshot(8000, 6.0, 24, 2)));
        assertEquals(FilterStore.AcceptedLesson.RAISED, FilterStore.recordConfirmedManualAcceptance(app, pending, OFFER));
        assertEquals(FilterStore.AcceptedLesson.STALE, FilterStore.recordConfirmedManualAcceptance(app, pending, OFFER));
        FilterStore.resetAccepted(app);
        assertEquals(FilterStore.AcceptedLesson.STALE, FilterStore.recordConfirmedManualAcceptance(app, pending, OFFER));
        assertTrue(FilterStore.load(app).accepted.isEmpty());
    }
    @Test public void currentConsentAndCapturedConsentBothGateTheWrite() {
        FilterStore.LearningPermit pending = FilterStore.captureLearning(app, OFFER);
        SharedPreferences consent = app.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE);
        consent.edit().putInt(Consent.ACCEPTED_VERSION, 0).commit();
        assertEquals(FilterStore.AcceptedLesson.OFF, FilterStore.recordConfirmedManualAcceptance(app, pending, OFFER));
        consent.edit().putInt(Consent.ACCEPTED_VERSION, Consent.VERSION).putLong(Consent.ACCEPTED_AT, 200).commit();
        assertEquals(FilterStore.AcceptedLesson.STALE, FilterStore.recordConfirmedManualAcceptance(app, pending, OFFER));
    }
    @Test public void offPreservesBestsButCollectsNothingAndResetKeepsSettings() {
        learn(OFFER);
        FilterStore.setAcceptanceLearning(app, false);
        assertNull(FilterStore.captureLearning(app, new OfferSnapshot(9000, 4.0, 20, 2)));
        assertEquals(1420, FilterStore.load(app).accepted.highestPayCents);
        FilterSettings off = FilterStore.load(app);
        FilterStore.resetAccepted(app);
        FilterSettings reset = FilterStore.load(app);
        assertTrue(reset.accepted.isEmpty()); assertFalse(reset.acceptanceLearning);
        assertArrayEquals(off.minimums(), reset.minimums()); assertEquals(off.maxStops, reset.maxStops);
    }
    @Test public void adoptionIsSelectivePersistentAndItsUndoDoesNotChangeLearningOrBar() {
        FilterStore.save(app, FilterStore.load(app).withMinimums(new int[] {3000, 150, 30, 0, 0, 0}));
        learn(OFFER); FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 112));
        // The lesson left the knobs as they were; Adopt raises the lower ones to the bests.
        assertArrayEquals(new int[] {3000, 150, 30, 0, 0, 0}, FilterStore.load(app).minimums());
        assertTrue(FilterStore.adoptAccepted(app));
        FilterStore.Adoption a = FilterStore.pendingAdoption(app);
        assertNotNull(a); assertEquals(3000, a.flatBefore); assertEquals(3000, a.flatAfter);
        assertEquals(237, a.mileAfter); assertEquals(60, a.minuteAfter);
        Context fresh = app.createConfigurationContext(app.getResources().getConfiguration());
        assertEquals(a.id, FilterStore.pendingAdoption(fresh).id);
        assertTrue(FilterStore.undoAdoption(fresh, a.id));
        FilterSettings undone = FilterStore.load(app);
        assertArrayEquals(new int[] {3000, 150, 30, 0, 0, 0}, undone.minimums());
        assertEquals(112, undone.minimumScalePercent); assertEquals(1420, undone.accepted.highestPayCents);
        assertNull(FilterStore.pendingAdoption(app)); assertFalse(FilterStore.undoAdoption(app, a.id));
    }
    @Test public void anInterveningSameMillisecondMoneyEditInvalidatesUndoEvenIfValuesReturn() {
        learn(OFFER); FilterStore.adoptAccepted(app); FilterStore.Adoption a = FilterStore.pendingAdoption(app);
        FilterSettings adopted = FilterStore.load(app);
        FilterStore.save(app, adopted, adopted.withMinimums(new int[] {2000, 237, 60, 0, 0, 0}));
        FilterStore.save(app, FilterStore.load(app), adopted);
        assertNull(FilterStore.pendingAdoption(app)); assertFalse(FilterStore.undoAdoption(app, a.id));
        assertEquals(1421, FilterStore.load(app).flatCents);
    }
    @Test public void clearHistoryDeletesDerivedBestsAndUndoButKeepsAdoptedSettings() {
        learn(OFFER); FilterStore.adoptAccepted(app); int[] adopted = FilterStore.load(app).minimums();
        DecisionLog.clear(app);
        assertTrue(FilterStore.load(app).accepted.isEmpty()); assertNull(FilterStore.pendingAdoption(app));
        assertArrayEquals(adopted, FilterStore.load(app).minimums());
    }
    @Test public void oldRetiredRemnantsDoNotReconstructLearnerEvidenceOrEnableModes() {
        prefs().edit().clear().putInt("rules_model", 2).putBoolean("enabled", true).putInt("flat", 800)
                .putBoolean("autopilot_on", true).putInt("autopilot_bar_percent", 115)
                .putBoolean("score_by_area", true).putBoolean("rising_offers", true)
                .putInt("last_accepted", 9000).putInt("best_mile_pay", 9000)
                .putLong("best_miles_bits", Double.doubleToLongBits(2)).commit();
        FilterSettings next = FilterStore.load(app);
        // Learning is on by default from 0.6.1; remnants still restore no evidence and no area mode.
        assertTrue(next.accepted.isEmpty()); assertTrue(next.acceptanceLearning); assertFalse(next.scoreByArea);
        assertEquals(800, next.flatCents); assertTrue(next.autopilot); assertEquals(115, next.minimumScalePercent);
        assertFalse(prefs().contains("last_accepted"));
        FilterStore.setAcceptanceLearning(app, true); learn(OFFER);
        assertEquals(1420, FilterStore.load(app).accepted.highestPayCents);
    }
}
