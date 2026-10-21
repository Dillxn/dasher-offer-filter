package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;

/**
 * The owner's reports of 20 October 2026 carried no screen text: Dasher draws a full-screen offer above its own
 * screen, and a payment, account or earnings word there, below the offer's Accept, made every such offer read as an
 * account screen, kept nowhere. Which word Dasher shows is not known yet; these screens use one of the marker words
 * ("Fast Pay"). The offer's own lines, down to its Accept, are now kept, masked as ever, with a fixed note naming the
 * word in place of the rest; nothing below the Accept is kept, and a word on the offer itself keeps only the note.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class FullScreenOfferEvidenceTest {
    private static final String BELOW_NOTE = "screen below the offer not kept (account word: f-a-s-t p-a-y)";
    private static final String OFFER_NOTE = "offer text not kept (account word: f-a-s-t p-a-y)";
    /** Dasher's home under a full-screen offer: a name, the lifetime count, the word, the tabs. */
    private static final List<String> HOME = Arrays.asList("0:13", "Pat", "312 orders completed", "Fast Pay",
            "Home", "Schedule", "Account");

    private Application app;
    private ServiceController<OfferFilterService> controller;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        FilterStore.save(app, FilterSettings.of(true, 450, 1620, 70, 0).withScoreByArea(true));
        ActiveRouteStore.clear(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        DiagnosticLog.forgetCache();
        DiagnosticLog.clear(app);
        DiagnosticLog.setEnabled(app, true);
        OfferSilencer.forgetCache();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void stop() {
        controller.destroy();
        ActiveRouteStore.clear(app);
        DiagnosticLog.forgetCache();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.scanLooperForTests = null;
    }

    @Test
    public void aFullScreenOfferKeepsItsOwnLinesWithANoteForTheScreenBelowIt() throws Exception {
        AtomicInteger declines = new AtomicInteger();
        show(fullScreen(declines, "$7.90", "Guaranteed (incl. tips)", "2 stops (7.2 mi) • 21 min",
                "Chick-fil-A", "123 Main St, Apt 4", "Accept"));
        DecisionLog.Entry line = newest();
        assertEquals("read and judged as before", Integer.valueOf(790), line.facts.payCents);
        assertEquals(Arrays.asList("$7.90", "Guaranteed (incl. tips)", "2 stops (7.2 mi) • 21 min", BELOW_NOTE),
                line.evidence);
        // Kept as it is through a restart, a report and the history's own read line, and never on the ticket.
        DecisionLog.forgetCache();
        DecisionLog.Entry reloaded = DecisionLog.recent(app, 1).get(0);
        assertEquals(line.evidence, reloaded.evidence);
        JSONArray sent = OfferReport.redacted(reloaded).getJSONArray("evidence");
        assertEquals(4, sent.length());
        assertEquals("$7.90", sent.getString(0));
        assertEquals(BELOW_NOTE, sent.getString(3));
        String history = DecisionLog.report(app, 1);
        assertTrue(history, history.contains("read: [$7.90, Guaranteed (incl. tips), 2 stops (7.2 mi) • 21 min, "
                + BELOW_NOTE + "]"));
        assertEquals(Arrays.asList("$7.90", "Guaranteed (incl. tips)", "2 stops (7.2 mi) • 21 min"),
                PersonalText.withoutNotKeptNotes(reloaded.evidence));
        // The screens log keeps the offer down to its Accept, masked, and the note; nothing of the screen below.
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("labels=[Decline, $7.90, Guaranteed (incl. tips), 2 stops (7.2 mi) • 21 min, "
                + "Chick-fil-A, "));
        assertTrue(log, log.contains(", Accept, " + BELOW_NOTE + "] metricParts=[]"));
        String everything = log + DiagnosticLog.readScreens(app) + history + sent;
        for (String below : new String[] {"Pat", "312 orders", "Fast Pay", "Schedule", "123 Main"}) {
            assertFalse(below, everything.contains(below));
        }
    }

    @Test
    public void aWordOnTheOfferItselfKeepsOnlyTheNote() {
        List<String> labels = labels("$7.90", "Fast Pay", "2 stops (7.2 mi) • 21 min", "Accept");
        assertEquals(Collections.singletonList(OFFER_NOTE), DecisionLog.offerEvidence(labels));
        assertEquals(Collections.singletonList(OFFER_NOTE), DashSummary.readLines(labels));
        // Through the service too: the history keeps the note, the screens log none of the screen's labels (the
        // app's own lines say what it decided, as ever).
        show(fullScreen(new AtomicInteger(), "$7.90", "Fast Pay", "2 stops (7.2 mi) • 21 min", "Accept"));
        assertEquals(Collections.singletonList(OFFER_NOTE), newest().evidence);
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[screen] " + DiagnosticLog.NOT_KEPT));
        assertFalse(log, log.contains("labels="));
        assertFalse(log, log.contains("Fast Pay"));
    }

    @Test
    public void aPaymentScreenWithNoAcceptStillKeepsNothing() {
        List<String> wallet = Arrays.asList("Card details", "$123.45", "4111 1111 1111 1111", "Decline");
        assertTrue(DecisionLog.offerEvidence(wallet).isEmpty());
        assertEquals(Collections.singletonList(PersonalText.UNKNOWN_NOT_KEPT), DashSummary.readLines(wallet));
        assertTrue(OfferReport.redact(wallet).isEmpty());
    }

    @Test
    public void theSummaryKeepsTheOfferDownToItsAcceptWhereverItsDeclineIs() {
        List<String> declineFirst = labels("Decline", "$7.90", "2 stops (7.2 mi) • 21 min", "Accept");
        assertEquals(Arrays.asList("Decline", "$7.90", "2 stops (7.2 mi) • 21 min", "Accept", BELOW_NOTE),
                DashSummary.readLines(declineFirst));
        List<String> declineAfter = new ArrayList<>(Arrays.asList("$7.90", "2 stops (7.2 mi) • 21 min", "Accept",
                "Decline", "Fast Pay"));
        assertEquals(Arrays.asList("$7.90", "2 stops (7.2 mi) • 21 min", "Accept", BELOW_NOTE),
                DashSummary.readLines(declineAfter));
        // A screen with Accept but no Decline anywhere is no offer this can tell: nothing of it.
        assertEquals(Collections.singletonList(PersonalText.UNKNOWN_NOT_KEPT),
                DashSummary.readLines(Arrays.asList("$7.90", "Accept", "Fast Pay")));
    }

    @Test
    public void everyNotePassesEveryMaskAndIsNeverTakenForAnAccountScreen() {
        List<String> words = new ArrayList<>();
        for (String marker : PersonalText.ACCOUNT_MARKERS) words.add(marker.toLowerCase(Locale.US));
        words.addAll(Arrays.asList("dasher direct", "exp date", "valid through", "taxes", "balances", "payouts",
                "wallets", "earnings"));
        for (String word : words) {
            for (boolean below : new boolean[] {true, false}) {
                String note = PersonalText.notKeptNote(word, below);
                assertFalse(note, note.toLowerCase(Locale.US).contains("(account word: other)"));
                assertTrue(note, PersonalText.isNotKeptNote(note));
                assertFalse(note, PersonalText.accountText(note));
                assertEquals(note, PersonalText.mask(note));
                assertEquals(Collections.singletonList(note), PersonalText.mask(Arrays.asList("Dropoff instructions:",
                        note)).subList(1, 2));
                assertEquals(note, OfferReport.redact(note));
                String logged = " [screen] head labels=[$7.90, Accept, " + note + "] metricParts=[]";
                assertEquals(note, logged, PersonalText.maskLine(logged));
            }
        }
        // The word as each label spells it, never the label around it; one not listed is "other".
        assertEquals("fast pay", PersonalText.accountWord(Arrays.asList("Try Fast  Pay today")));
        assertEquals("1099", PersonalText.accountWord(Arrays.asList("Your 1099-NEC")));
        assertEquals(PersonalText.notKeptNote("other", true), PersonalText.notKeptNote("Pat's balance", true));
        assertTrue(PersonalText.notKeptNote(null, true).endsWith("(account word: o-t-h-e-r)"));
        // Text shaped like a note but naming anything else is screen text, masked and redacted as ever.
        String forged = "screen below the offer not kept (account word: p-a-t)";
        assertFalse(PersonalText.isNotKeptNote(forged));
        assertFalse(PersonalText.isNotKeptNote("Deliver to Sam P " + BELOW_NOTE));
        assertEquals(Collections.singletonList(BELOW_NOTE),
                PersonalText.notKeptNotes(Arrays.asList("Sam P", forged, BELOW_NOTE)));
        assertEquals(Arrays.asList("Sam P", forged),
                PersonalText.withoutNotKeptNotes(Arrays.asList("Sam P", forged, BELOW_NOTE)));
    }

    // ---- Dasher's screens ----

    /** The offer's labels, then Dasher's home under it ({@link #HOME}). */
    private static List<String> labels(String... offer) {
        List<String> labels = new ArrayList<>(Arrays.asList(offer));
        labels.addAll(HOME);
        return labels;
    }

    /** A full-screen offer: its Decline at the top, the given labels (Accept among them), then Dasher's home. */
    private AccessibilityNodeInfo fullScreen(AtomicInteger declines, String... offer) {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(button("Decline", declines));
        for (String label : offer) {
            Shadows.shadowOf(root).addChild(label.equals("Accept") ? button(label, new AtomicInteger())
                    : node(label, false));
        }
        for (String label : HOME) Shadows.shadowOf(root).addChild(node(label, false));
        return root;
    }

    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName("com.doordash.driverapp");
        if (text != null) node.setText(text);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        node.setClickable(clickable);
        if (clickable) node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        return node;
    }

    /** A clickable control labelled {@code label} whose clicks are counted. */
    private AccessibilityNodeInfo button(String label, AtomicInteger clicks) {
        AccessibilityNodeInfo button = node(label, true);
        Shadows.shadowOf(button).setOnPerformActionListener((action, args) -> {
            clicks.incrementAndGet();
            return true;
        });
        return button;
    }

    private void show(AccessibilityNodeInfo root) {
        TestWindows.full(controller.get(), root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        controller.get().onAccessibilityEvent(event);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private DecisionLog.Entry newest() {
        DecisionLog.flush();
        return DecisionLog.recent(app, 1).get(0);
    }
}
