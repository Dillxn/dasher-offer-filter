package com.local.dasherfilter;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Synthetic supported shape; report #60 did not retain its exact post-offer money labels. */
public final class RetainedPayAcceptanceTest {
    private static final OfferSnapshot ORIGINAL = new OfferSnapshot(1675, 3.9, 30, 3).withItems(2, true);

    private static List<String> progress(String... extra) {
        List<String> labels = new ArrayList<>(Arrays.asList("Pick up by 7:52 PM", "Arrived at store", "$16.75"));
        labels.addAll(Arrays.asList(extra));
        return labels;
    }

    private static OfferSnapshot read(List<String> labels) {
        return OfferParser.parse(labels, OfferParser.joinMetricSiblings(labels));
    }

    private static AcceptedOfferTracker watched(OfferSnapshot original, String before, int seconds) {
        AcceptedOfferTracker tracker = new AcceptedOfferTracker();
        if (before != null) tracker.afterScreen(Arrays.asList(before), 500);
        tracker.observeOffer(original, original, false, original, seconds, false, 1_000);
        tracker.offerLeftAlone(original, original, original, false, true, seconds, false, 1_000);
        return tracker;
    }

    private static AcceptedOfferTracker watched() { return watched(ORIGINAL, "Finding offers", 35); }

    private static void show(AcceptedOfferTracker tracker, List<String> labels, boolean complete, long now) {
        OfferSnapshot read = read(labels);
        tracker.afterScreen(labels, AcceptedOfferTracker.offerFacts(read, labels), read, complete, now);
    }

    private static void noLesson(AcceptedOfferTracker tracker) {
        for (AcceptedOfferTracker.Note note : tracker.takeNotes()) assertNull(note.toString(), note.accepted);
    }

    @Test public void matchingRetainedPayAndItemsLearnOnlyTheOriginalOfferOnce() {
        for (List<String> labels : Arrays.asList(progress("2 items"), progress())) {
            AcceptedOfferTracker tracker = watched();
            assertTrue(AcceptedOfferTracker.offerFacts(read(labels), labels));
            assertEquals("global classification remains unchanged", AcceptedOfferTracker.After.OFFER_FACTS,
                    AcceptedOfferTracker.classify(labels));
            show(tracker, labels, true, 2_000);
            List<AcceptedOfferTracker.Note> notes = tracker.takeNotes();
            assertEquals(1, notes.size());
            assertEquals(DecisionLog.StepKind.ACCEPTED_LEARNED, notes.get(0).kind);
            assertSame(ORIGINAL, notes.get(0).accepted.acceptedOffer);
            assertSame(ORIGINAL, notes.get(0).accepted.routeAfter);
            assertFalse(notes.get(0).accepted.tapSeen);
            show(tracker, labels, true, 3_000);
            assertTrue(tracker.takeNotes().isEmpty());
        }
    }

    @Test public void aSeenManualTapUsesTheSameNarrowShapeAndKeepsItsOriginalFacts() {
        AcceptedOfferTracker tracker = watched();
        tracker.acceptClicked(1_500);
        tracker.takeNotes();
        List<String> labels = progress("2 items");
        AcceptedOfferTracker.Acceptance accepted = tracker.observeOtherScreen(labels, true, read(labels), true, 2_000);
        assertNotNull(accepted);
        assertSame(ORIGINAL, accepted.acceptedOffer);
        assertTrue(accepted.tapSeen);
        show(tracker, labels, true, 2_100);
        assertTrue(tracker.takeNotes().isEmpty());
    }

    @Test public void changedUnknownAndAdditionalOfferEvidenceCannotTeach() {
        List<List<String>> rejected = Arrays.asList(
                Arrays.asList("Arrived at store", "$9.99", "2 items"),
                progress("3 items"), progress("2-3 items"), progress("Shop & deliver"),
                progress("2 items", "3 stops"), progress("2 items", "3-4 stops"),
                progress("2 items", "3.9 mi"), progress("2 items", "30 min"),
                progress("2 items", "Turn left", "0.3 mi"), progress("2 items", "3.9", "mi"),
                progress("2 items", "40 mph", "500 ft"), progress("2 items", "500 feet"),
                progress("2 items", "Pick up 2 orders"),
                progress("2 items", "Accept"), progress("2 items", "Decline"),
                progress("2 items", "0:30"), progress("2 items", "1:30"),
                progress("2 items", "New Order: Go to Example Store"),
                progress("2 items", "Decline offer?"), progress("2 items", "End your current dash?"),
                progress("2 items", "Finding offers"), progress("2 items", "This dash so far"),
                progress("2 items", "Dash now"), progress("2 items", "Add to route"),
                Arrays.asList("Directions", "$16.75", "2 items"),
                Arrays.asList("Pick up by 7:52 PM", "$16.75", "2 items"));
        for (List<String> labels : rejected) {
            AcceptedOfferTracker tracker = watched();
            show(tracker, labels, true, 2_000);
            for (AcceptedOfferTracker.Note note : tracker.takeNotes()) assertNull(labels.toString(), note.accepted);
        }
        AcceptedOfferTracker incomplete = watched();
        show(incomplete, progress("2 items"), false, 2_000);
        noLesson(incomplete);
    }

    @Test public void everyMoneyLabelMustBeAnUnqualifiedConsistentAmount() {
        for (String money : Arrays.asList("Guaranteed $16.75", "$?", "$16.750", "+$1", "$16.75/hr",
                "$0.00", "$16.75 - $20.00", "Total pay $16.75", "up to", "per order", "each delivery",
                "Guaranteed", "Total pay", "Bonus")) {
            AcceptedOfferTracker tracker = watched();
            List<String> labels = progress("2 items", money);
            assertEquals(money, "ambiguous", tracker.retainedPayRelation(read(labels), labels));
            show(tracker, labels, true, 2_000);
            noLesson(tracker);
        }
    }

    @Test public void incompleteOriginalIdentityCannotBeFilledFromTheProgressScreen() {
        for (OfferSnapshot original : Arrays.asList(new OfferSnapshot(1675, null, 30, 3).withItems(2, true),
                new OfferSnapshot(1675, 3.9, null, 3).withItems(2, true),
                new OfferSnapshot(1675, 3.9, 30, null).withItems(2, true),
                ORIGINAL.withItems(null, true), ORIGINAL.withItems(null, false))) {
            AcceptedOfferTracker tracker = watched(original, "Finding offers", 35);
            show(tracker, progress("2 items"), true, 2_000);
            noLesson(tracker);
        }
    }

    @Test public void ambiguousMoneyStillBlocksWhenCountdownDisablesTheRetainedPayException() {
        for (List<String> labels : Arrays.asList(
                Arrays.asList("Arrived at store", "$16.750", "0:30"),
                progress("2 items", "$0.00", "0:30"))) {
            AcceptedOfferTracker tracker = watched();
            show(tracker, labels, false, 2_000);
            noLesson(tracker);
        }
    }

    @Test public void existingPriorRouteWaitingCountdownAndDeclineGuardsStillReject() {
        for (String before : Arrays.asList(null, "Arrived at store")) {
            AcceptedOfferTracker tracker = watched(ORIGINAL, before, 35);
            show(tracker, progress("2 items"), true, 2_000);
            noLesson(tracker);
        }
        for (int seconds : new int[]{-1, 2}) {
            AcceptedOfferTracker tracker = watched(ORIGINAL, "Finding offers", seconds);
            show(tracker, progress("2 items"), true, 2_000);
            noLesson(tracker);
        }
        AcceptedOfferTracker tracker = watched();
        tracker.declineTapped(1_500);
        show(tracker, progress("2 items"), true, 2_000);
        noLesson(tracker);
        tracker = watched();
        tracker.declineRequestedElsewhere();
        show(tracker, progress("2 items"), true, 2_000);
        noLesson(tracker);
    }

    @Test public void expiredWatchCannotWinARaceWithItsTimeoutRunnable() {
        for (long now : new long[]{62_000, 62_001}) {
            AcceptedOfferTracker tracker = watched();
            tracker.afterScreen(Arrays.asList("$16.75", "2 items"), 2_000);
            // Deliberately do not tick: a content event can be ahead of the timeout runnable in the queue.
            show(tracker, progress("2 items"), true, now);
            noLesson(tracker);
            assertTrue(tracker.expire(now));
            noLesson(tracker);
        }
    }

    @Test public void retainedPayCannotChangeAManualDeclineOutcome() {
        AcceptedOfferTracker tracker = watched(ORIGINAL, "Arrived at store", 35);
        tracker.declineTapped(1_500);
        tracker.takeNotes();
        show(tracker, progress("2 items"), true, 2_000);
        for (AcceptedOfferTracker.Note note : tracker.takeNotes()) {
            assertNull(note.accepted);
            assertNotEquals(DecisionLog.StepKind.DECLINE_COUNTED, note.kind);
        }
    }

    @Test public void expiredManualTapAndAutomaticRequestsDoNotUseThisException() {
        List<String> labels = progress("2 items");
        AcceptedOfferTracker tracker = watched();
        tracker.acceptClicked(1_500);
        tracker.takeNotes();
        assertNull(tracker.observeOtherScreen(labels, true, read(labels), true, 16_501));
        noLesson(tracker);
        for (boolean dispatched : new boolean[]{false, true}) {
            tracker = watched();
            tracker.automaticAcceptRequested(ORIGINAL, 1_500, dispatched);
            assertNull(tracker.observeOtherScreen(labels, true, read(labels), true, 2_000));
            show(tracker, labels, true, 2_000);
            noLesson(tracker);
        }
    }

    @Test public void outcomeDiagnosticsUseOnlyFixedCategories() {
        AcceptedOfferTracker tracker = watched();
        List<String> labels = progress("2 items");
        assertEquals("matched", tracker.retainedPayRelation(read(labels), labels));
        labels = Arrays.asList("Arrived at store", "$9.99");
        assertEquals("other", tracker.retainedPayRelation(read(labels), labels));
        labels = Arrays.asList("Arrived at store", "2 items");
        assertEquals("none", tracker.retainedPayRelation(read(labels), labels));
        assertEquals("arrived_at_store", AcceptedOfferTracker.progressKind(labels));
        assertEquals("none", AcceptedOfferTracker.progressKind(Arrays.asList("Personal arbitrary label")));
        assertEquals("multiple", AcceptedOfferTracker.progressKind(Arrays.asList("Arrived at store", "Confirm pickup")));
    }
}
