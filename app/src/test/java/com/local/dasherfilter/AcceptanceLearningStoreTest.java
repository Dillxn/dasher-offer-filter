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
@Config(sdk = {26, 35}, instrumentedPackages = {"com.local.dasherfilter"})
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
        // The new high raised the saved minimums too (0.6.1), and a stale copy's save never puts the old ones back.
        assertArrayEquals(new int[] {1421, 237, 60, 0, 0, 0}, stored.minimums()); assertFalse(stored.enabled);
    }
    /** The owner, 11 October 2026: "new highs ... raise mins". Undo puts them back and forgets that lesson. */
    @Test public void aNewHighRaisesTheSavedMinimumsWithANoteAndAnUndoThatForgetsTheLesson() {
        assertEquals(FilterStore.AcceptedLesson.RAISED, learn(OFFER));
        assertArrayEquals(new int[] {1421, 237, 60, 0, 0, 0}, FilterStore.load(app).minimums());
        FilterStore.Adoption raise = FilterStore.learnedRaiseNote(app);
        assertNotNull(raise); assertTrue(raise.automatic);
        assertEquals("A new high you accepted raised them: $7.00 → $14.21 · $1.50/mi → $2.37/mi · $18/hr → $36/hr.",
                LearnedRaiseCard.line(raise));
        // OK closes the note; Undo stays.
        FilterStore.noteAdoption(app, raise.id);
        assertNull(FilterStore.learnedRaiseNote(app));
        assertNotNull(FilterStore.pendingAdoption(app));
        assertTrue(FilterStore.undoAdoption(app, raise.id));
        FilterSettings undone = FilterStore.load(app);
        assertArrayEquals(new int[] {700, 150, 30, 0, 0, 0}, undone.minimums());
        assertTrue("the lesson is forgotten too, or it would go on asking for the same", undone.accepted.isEmpty());
        assertTrue(undone.acceptanceLearning);
    }
    @Test public void aBestBelowTheSavedMinimumsRaisesNothing() {
        FilterStore.save(app, FilterStore.load(app).withMinimums(new int[] {5000, 5000, 5000, 0, 0, 0}));
        assertEquals(FilterStore.AcceptedLesson.SAVED, learn(OFFER));
        assertArrayEquals(new int[] {5000, 5000, 5000, 0, 0, 0}, FilterStore.load(app).minimums());
        assertNull(FilterStore.learnedRaiseNote(app));
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
        // The lesson raised the knobs already (0.6.1); dragged back down, Adopt raises them to the bests again.
        FilterSettings raised = FilterStore.load(app);
        assertArrayEquals(new int[] {3000, 237, 60, 0, 0, 0}, raised.minimums());
        FilterStore.save(app, raised, raised.withMinimums(new int[] {3000, 150, 30, 0, 0, 0}));
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
