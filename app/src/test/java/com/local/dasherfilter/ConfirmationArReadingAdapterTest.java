package com.local.dasherfilter;

import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Dasher's decline question shows the acceptance rate ("Are you sure you want to decline this offer?", "Does not lower
 * acceptance rate", "50%"). The screen reader hands a copy of the labels it already read to Autopilot
 * ({@link AutopilotRuntime#confirmationSeen}), which parses them on its own thread: the confirmation tap never waits
 * for it, one number is kept per offer and question, and Dasher's mark that declining an offer does not lower the rate
 * goes on that offer's own line. Through the real screen reader, on the main looper with simulated time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public final class ConfirmationArReadingAdapterTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    /** $20 at least: a $7.90 offer fails, a $25.00 one passes. */
    private static final FilterSettings TWENTY = FilterSettings.of(true, 2000, 0, 0, 0);
    private static final String QUESTION = "Are you sure you want to decline this offer?";

    private Application app;
    private ServiceController<OfferFilterService> controller;
    private AccessibilityNodeInfo decline;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        DiagnosticLog.setEnabled(app, true);
        FilterStore.save(app, TWENTY);
        OfferSilencer.forgetCache();
        ActiveRouteStore.clear(app);
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        forgetEverything();
    }

    @After public void tearDown() {
        if (controller != null) controller.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.scanLooperForTests = null;
        AutopilotRuntime.executorForTests = Runnable::run;
    }

    /** No history, no reading and no log: as a phone that has seen nothing yet. */
    private void forgetEverything() {
        DiagnosticLog.clear(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        AutopilotStore.clear(app);
        AutopilotRuntime.forgetCache();
    }

    // ---- The app's own decline: the tap never waits ----

    @Test public void theAppsOwnQuestionIsReadWithoutDelayingItsConfirmationTap() {
        // The baseline: the question's labels go nowhere, as with no hook at all.
        List<Long> baseline = declineAndConfirm(task -> { }, null);
        assertNull("nothing was parsed", reading());
        assertTrue(readings().isEmpty());

        // Parsed at once, on the reading thread itself: the most a parse could hold the tap up.
        List<Long> inline = declineAndConfirm(Runnable::run, null);
        assertEquals("the same tap uptimes as the baseline", baseline, inline);
        assertEquals(72, reading().percent);
        assertEquals(line(790).facts.fingerprint(), reading().fingerprint);
        assertEquals(Collections.singletonList("ar reading 72% (exempt no)"), readings());

        // Parsed later, on Autopilot's own thread, as on a phone: the tap goes first.
        List<Runnable> held = new CopyOnWriteArrayList<>();
        boolean[] readAtTap = {true};
        int[] handedAtTap = {-1};
        List<Long> deferred = declineAndConfirm(held::add, () -> {
            readAtTap[0] = reading() != null;
            handedAtTap[0] = held.size();
        });
        assertEquals("the same tap uptimes as the baseline", baseline, deferred);
        assertEquals("the question was handed over in the read that tapped, before the tap", 1, handedAtTap[0]);
        assertFalse("tapped without waiting for the parse", readAtTap[0]);
        assertNull(reading());
        for (Runnable task : held) task.run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(72, reading().percent);
        assertEquals(line(790).facts.fingerprint(), reading().fingerprint);
        assertEquals("one reading however often the question was read",
                Collections.singletonList("ar reading 72% (exempt no)"), readings());
    }

    /**
     * From a phone that has seen nothing, ten minutes after anything before: an offer that fails is declined, Dasher
     * asks, and the app confirms; Autopilot's parse runs on {@code parser}. {@code atConfirmTap} runs as the tap lands.
     *
     * @return the uptimes of the Decline tap and of the confirmation tap, from when the offer showed
     */
    private List<Long> declineAndConfirm(Executor parser, Runnable atConfirmTap) {
        if (controller != null) {
            controller.destroy();
            controller = null;
        }
        pass(600_000);
        forgetEverything();
        AutopilotRuntime.executorForTests = parser;
        OfferFilterService service = service();
        show(service, waiting());
        pass(2_000);
        long shownAt = SystemClock.uptimeMillis();
        AccessibilityNodeInfo shown = offer("$7.90", "0:35");
        List<Long> declines = taps(decline, true, null);
        show(service, shown);
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirms = taps(confirm, true, atConfirmTap);
        show(service, question(confirm, "Your acceptance rate", "72%"));
        pass(400);
        show(service, waiting());
        pass(1_000);
        assertEquals("declined at once", 1, declines.size());
        assertEquals("its question confirmed once", 1, confirms.size());
        assertEquals(DecisionLog.Action.CONFIRMATION_TAPPED, line(790).action);
        return Arrays.asList(declines.get(0) - shownAt, confirms.get(0) - shownAt);
    }

    // ---- The user's own decline ----

    @Test public void theUsersOwnQuestionIsReadWithTheOfferTheyAreDeclining() {
        OfferFilterService service = service();
        show(service, waiting());
        show(service, offer("$25.00", "0:35"));
        assertTrue("it passes: the user's to take or decline", performed(decline).isEmpty());
        pass(1_000);
        // The user taps Decline themselves: Dasher asks them.
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirms = taps(confirm, true, null);
        show(service, question(confirm, "Does not lower acceptance rate", "64%"));
        pass(400);

        assertTrue("Dasher's question is never answered for the user", confirms.isEmpty());
        DecisionLog.Entry line = line(2500);
        AutopilotStore.Reading reading = reading();
        assertNotNull(reading);
        assertEquals(64, reading.percent);
        assertEquals("kept with the offer the user is declining", line.facts.fingerprint(), reading.fingerprint);
        assertTrue(DecisionLog.report(app, 5), DecisionLog.hasStep(line, DecisionLog.StepKind.AR_EXEMPT));
        assertEquals(Collections.singletonList("ar reading 64% (exempt yes)"), readings());
        for (String logged : autopilotLog()) {
            assertFalse("fixed words and numbers only: " + logged, logged.contains("lower")
                    || logged.contains("decline this offer") || logged.contains("acceptance rate"));
        }
    }

    @Test public void aQuestionLongAfterAnyOfferIsKeptWithoutOneAndMarksNoLine() {
        OfferFilterService service = service();
        show(service, waiting());
        show(service, offer("$25.00", "0:35"));
        pass(1_000);
        show(service, waiting());
        pass(15_000);
        // Dasher asks about an offer this reader never saw (the earlier one left 15 s before).
        show(service, question(button("Decline offer"), "Does not lower acceptance rate", "64%"));
        pass(400);

        AutopilotStore.Reading reading = reading();
        assertNotNull(reading);
        assertEquals(64, reading.percent);
        assertEquals("about no offer it can name", "", reading.fingerprint);
        assertEquals(0, marks());
        assertEquals(Collections.singletonList("ar reading 64% (exempt yes)"), readings());
    }

    // ---- The mark on the right line ----

    @Test public void anExemptMarkLandsOnTheLineOfTheOfferItWasShownFor() {
        OfferFilterService service = service();
        show(service, waiting());
        // An offer that passes; the user brings up Dasher's question for it, which shows no mark, then goes back to
        // the wait for offers.
        show(service, offer("$25.00", "0:35"));
        pass(1_000);
        show(service, question(button("Decline offer"), "Your acceptance rate", "64%"));
        pass(1_000);
        show(service, waiting());
        pass(30_000);
        // An offer that fails: the app declines it, and Dasher says declining it does not lower the rate.
        AccessibilityNodeInfo failing = offer("$7.90", "0:35");
        List<Long> declines = taps(decline, true, null);
        show(service, failing);
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirms = taps(confirm, true, null);
        show(service, question(confirm, "Does not lower acceptance rate", "64%"));
        pass(400);
        show(service, waiting());
        pass(1_000);

        assertEquals(1, declines.size());
        assertEquals(1, confirms.size());
        String history = DecisionLog.report(app, 5);
        assertTrue(history, DecisionLog.hasStep(line(790), DecisionLog.StepKind.AR_EXEMPT));
        assertFalse(history, DecisionLog.hasStep(line(2500), DecisionLog.StepKind.AR_EXEMPT));
        assertEquals(history, 1, marks());
        assertTrue(history, history.contains(DecisionLog.StepKind.AR_EXEMPT.label));
        assertEquals(line(790).facts.fingerprint(), reading().fingerprint);
        assertEquals(Arrays.asList("ar reading 64% (exempt no)", "ar reading 64% (exempt yes)"), readings());
    }

    // ---- One reading per question ----

    @Test public void repeatedPollsOfOneQuestionRecordOneReading() {
        AtomicInteger handed = new AtomicInteger();
        AutopilotRuntime.executorForTests = task -> {
            handed.incrementAndGet();
            task.run();
        };
        OfferFilterService service = service();
        show(service, waiting());
        show(service, offer("$7.90", "0:35"));
        pass(300);
        // Android refuses the app's tap on the question's Decline: the question stays up, read on every tick.
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirms = taps(confirm, false, null);
        show(service, question(confirm, "Does not lower acceptance rate", "50%"));
        for (int i = 0; i < 4; i++) {
            pass(100);
            tick(service);
        }
        pass(400);

        assertTrue("the question was read again and again: " + handed.get(), handed.get() >= 3);
        assertTrue(confirms.size() >= 1);
        assertEquals(Collections.singletonList("ar reading 50% (exempt yes)"), readings());
        assertEquals(50, reading().percent);
        assertEquals(line(790).facts.fingerprint(), reading().fingerprint);
        int onItsLine = 0;
        for (DecisionLog.Step step : line(790).steps) if (step.kind == DecisionLog.StepKind.AR_EXEMPT) onItsLine++;
        assertEquals("one mark, however often the question was read", 1, onItsLine);
        assertEquals(1, marks());
    }

    // ---- Dasher's screens ----

    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName("com.doordash.driverapp");
        if (text != null) node.setText(text);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        node.setClickable(clickable);
        if (clickable) {
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(node).setOnPerformActionListener((action, args) -> true);
        }
        return node;
    }

    /** A Compose button: a clickable node without text, holding its label. */
    private AccessibilityNodeInfo button(String label) {
        AccessibilityNodeInfo button = node(null, true);
        Shadows.shadowOf(button).addChild(node(label, false));
        return button;
    }

    /** An offer as Dasher draws it: Decline, the pay, the route line, Accept, then its countdown. */
    private AccessibilityNodeInfo offer(String pay, String countdown) {
        AccessibilityNodeInfo root = node(null, false);
        decline = button("Decline");
        Shadows.shadowOf(root).addChild(decline);
        Shadows.shadowOf(root).addChild(node(pay, false));
        Shadows.shadowOf(root).addChild(node("incl. tips", false));
        Shadows.shadowOf(root).addChild(node("2 stops (7.2 mi) • 21 min", false));
        Shadows.shadowOf(root).addChild(button("Accept"));
        Shadows.shadowOf(root).addChild(node(countdown, false));
        return root;
    }

    /** Dasher's question after a Decline tap, with what it says of the acceptance rate. */
    private AccessibilityNodeInfo question(AccessibilityNodeInfo declineOffer, String... rate) {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node(QUESTION, false));
        for (String label : rate) Shadows.shadowOf(root).addChild(node(label, false));
        Shadows.shadowOf(root).addChild(declineOffer);
        Shadows.shadowOf(root).addChild(node("0:24", false));
        Shadows.shadowOf(root).addChild(button("View offer details"));
        return root;
    }

    private AccessibilityNodeInfo waiting() {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("Finding offers", false));
        return root;
    }

    /** Records when (uptime) {@code button} is tapped, running {@code atTap} then; Android takes or refuses it. */
    private static List<Long> taps(AccessibilityNodeInfo button, boolean taken, Runnable atTap) {
        List<Long> at = new CopyOnWriteArrayList<>();
        Shadows.shadowOf(button).setOnPerformActionListener((action, args) -> {
            at.add(SystemClock.uptimeMillis());
            if (atTap != null) atTap.run();
            return taken;
        });
        return at;
    }

    private static List<Integer> performed(AccessibilityNodeInfo button) {
        return Shadows.shadowOf(button).getPerformedActions();
    }

    // ---- The service, reading on the main looper ----

    private OfferFilterService service() {
        controller = Robolectric.buildService(OfferFilterService.class).create();
        OfferFilterService service = controller.get();
        service.onServiceConnected();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return service;
    }

    /** Dasher fills the screen showing {@code root} and says so with a window change. */
    private void show(OfferFilterService service, AccessibilityNodeInfo root) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadow.setRoot(root);
        shadow.setActive(true);
        shadow.setBoundsInScreen(SCREEN);
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Dasher's countdown ticks: a content change. */
    private void tick(OfferFilterService service) {
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static AccessibilityEvent event(int type) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName("com.doordash.driverapp");
        event.setEventTime(SystemClock.uptimeMillis());
        return event;
    }

    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    // ---- What was kept ----

    private AutopilotStore.Reading reading() {
        return AutopilotStore.reading(app, System.currentTimeMillis());
    }

    /** Autopilot's log lines, without their time and category. */
    private List<String> autopilotLog() {
        List<String> lines = new ArrayList<>();
        for (String line : DiagnosticLog.read(app).split("\n")) {
            int at = line.indexOf("[autopilot] ");
            if (at >= 0) lines.add(line.substring(at + "[autopilot] ".length()));
        }
        return lines;
    }

    /** Autopilot's acceptance-rate readings as it logged them. */
    private List<String> readings() {
        List<String> found = new ArrayList<>();
        for (String line : autopilotLog()) if (line.startsWith("ar reading")) found.add(line);
        return found;
    }

    /** The newest screen line of the offer with this pay. */
    private DecisionLog.Entry line(int payCents) {
        DecisionLog.flush();
        for (DecisionLog.Entry entry : DecisionLog.recent(app, 20)) {
            if (entry.source == DecisionLog.Source.SCREEN && entry.facts.payCents != null
                    && entry.facts.payCents == payCents) {
                return entry;
            }
        }
        throw new AssertionError("no line for pay " + payCents + " in:\n" + DecisionLog.report(app, 20));
    }

    /** How many lines in the history carry Dasher's mark that declining did not lower the rate. */
    private int marks() {
        int count = 0;
        for (DecisionLog.Entry entry : DecisionLog.recent(app, 20)) {
            for (DecisionLog.Step step : entry.steps) if (step.kind == DecisionLog.StepKind.AR_EXEMPT) count++;
        }
        return count;
    }
}
