package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.robolectric.shadows.ShadowAccessibilityRecord;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowWindowManagerImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The user's 0.4.42 report: the first Decline was tapped and Dasher asked "Are you sure you want to decline this
 * offer?", Offer Filter did not manage to tap its "Decline offer", the user tapped "View offer details", and Offer
 * Filter tapped Decline on that same offer again. A decline is the user's from the moment they act on it: going back
 * to the offer from its question, any tap of theirs on Dasher, or a touch that is not the echo of one of the app's own
 * taps. Most tests read on the main looper with simulated time; the last ones run the service's own scanner thread.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class DeclineHandBackTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final Rect BOTTOM_HALF = new Rect(0, 1040, 1080, 2040);
    private static final String TOOK_OVER_TOAST = "Offer Filter stopped tapping this offer";

    private Application app;
    private ServiceController<OfferFilterService> controller;
    private AccessibilityNodeInfo accept;
    private AccessibilityNodeInfo decline;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Updater.setEnabled(app, false);
        OfferAlerts.ensureChannel(app);
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        DiagnosticLog.clear(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        AreaMap.forgetCache();
        OfferSilencer.forgetCache();
        OfferFilterService.scanLooperForTests = null;
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void tearDown() {
        if (controller != null) controller.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.takeoverBeginsForTests = null;
        OfferFilterService.nodeFetchForTests = null;
    }

    // ---- Dasher's screens, as the user's reports show them ----

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
        accept = button("Accept");
        Shadows.shadowOf(root).addChild(decline);
        Shadows.shadowOf(root).addChild(node(pay, false));
        Shadows.shadowOf(root).addChild(node("incl. tips", false));
        Shadows.shadowOf(root).addChild(node("2 stops (7.2 mi) • 21 min", false));
        Shadows.shadowOf(root).addChild(accept);
        Shadows.shadowOf(root).addChild(node(countdown, false));
        return root;
    }

    /** Dasher's question after a Decline tap, as the user's report shows it. */
    private AccessibilityNodeInfo question(AccessibilityNodeInfo declineOffer, AccessibilityNodeInfo... more) {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("Are you sure you want to decline this offer?", false));
        Shadows.shadowOf(root).addChild(node("Does not lower acceptance rate", false));
        Shadows.shadowOf(root).addChild(node("50%", false));
        Shadows.shadowOf(root).addChild(node("Accepting this offer will raise acceptance rate", false));
        Shadows.shadowOf(root).addChild(declineOffer);
        Shadows.shadowOf(root).addChild(node("0:24", false));
        for (AccessibilityNodeInfo control : more) Shadows.shadowOf(root).addChild(control);
        return root;
    }

    private AccessibilityNodeInfo idle() {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("Finding offers", false));
        return root;
    }

    /** Records when (uptime) {@code button} is tapped, and answers each tap as Android would: taken or refused. */
    private static List<Long> taps(AccessibilityNodeInfo button, boolean taken) {
        List<Long> at = new CopyOnWriteArrayList<>();
        Shadows.shadowOf(button).setOnPerformActionListener((action, args) -> {
            at.add(SystemClock.uptimeMillis());
            return taken;
        });
        return at;
    }

    private static AccessibilityWindowInfo window(AccessibilityNodeInfo root, boolean active, Rect bounds, int layer) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadow.setRoot(root);
        shadow.setActive(active);
        shadow.setBoundsInScreen(bounds);
        shadow.setLayer(layer);
        return window;
    }

    private static AccessibilityEvent event(int type) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName("com.doordash.driverapp");
        return event;
    }

    // ---- The service, reading on the main looper with simulated time ----

    private OfferFilterService service() {
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        OfferFilterService service = controller.get();
        service.onServiceConnected();
        idle(service);
        return service;
    }

    private void idle(OfferFilterService service) {
        if (service.scanLooper() != Looper.getMainLooper()) Shadows.shadowOf(service.scanLooper()).idle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Dasher fills the screen showing {@code root} and says so with a window change. */
    private void show(OfferFilterService service, AccessibilityNodeInfo root) {
        dasherShows(service, root);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        idle(service);
    }

    private void dasherShows(OfferFilterService service, AccessibilityNodeInfo root) {
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window(root, true, SCREEN, 0)));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
    }

    /** Dasher's countdown ticks: a content change. */
    private void tick(OfferFilterService service) {
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        idle(service);
    }

    /** Lets {@code ms} pass on the main looper, running what falls due on the way (the poll, retries, rechecks). */
    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    /** The user taps {@code control}: Dasher reports the click, stamped now, with its node and no text (Compose). */
    private void userClicks(OfferFilterService service, AccessibilityNodeInfo control) {
        AccessibilityEvent tap = event(AccessibilityEvent.TYPE_VIEW_CLICKED);
        tap.setEventTime(SystemClock.uptimeMillis());
        ((ShadowAccessibilityRecord) Shadow.extract(tap)).setSourceNode(control);
        service.onAccessibilityEvent(tap);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private List<View> touchWatches() {
        ShadowWindowManagerImpl windows = Shadow.extract(app.getSystemService(WindowManager.class));
        List<View> watches = new ArrayList<>();
        for (View view : windows.getViews()) {
            if (!(view instanceof DasherTab) && !(view instanceof DasherGuide) && !(view instanceof BackToMapChip)) {
                watches.add(view);
            }
        }
        return watches;
    }

    /** A finger lands now; Android reports it to the touch watch with its own time. */
    private void touchNow() {
        long at = SystemClock.uptimeMillis();
        assertEquals("one touch watch while declining", 1, touchWatches().size());
        touchWatches().get(0).dispatchTouchEvent(MotionEvent.obtain(at, at, MotionEvent.ACTION_OUTSIDE, 0, 0, 0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private AudioManager audio() {
        return app.getSystemService(AudioManager.class);
    }

    /** Dasher's offer ring on the alarm stream, at volume 5. */
    private void ringing() {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        ShadowAudioManager shadow = Shadows.shadowOf(audio());
        shadow.setActivePlaybackConfigurationsFor(Collections.singletonList(
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()), false);
    }

    private int alarmFloor() {
        return Build.VERSION.SDK_INT >= 28 ? audio().getStreamMinVolume(AudioManager.STREAM_ALARM) : 0;
    }

    private DecisionLog.Action lastAction() {
        return DecisionLog.recent(app, 1).get(0).action;
    }

    private static void contains(String text, String part) {
        assertTrue(part + " in:\n" + text, text.contains(part));
    }

    // ---- 1. Going back to the offer from its question hands it back ----

    @Test
    public void goingBackToTheOfferFromItsQuestionHandsItBack() {
        OfferFilterService service = service();
        ringing();
        AccessibilityNodeInfo first = offer("$7.90", "0:35");
        List<Long> firstDecline = taps(decline, true);
        show(service, first);
        assertEquals("declined at once", 1, firstDecline.size());
        assertEquals("its ring turned down", alarmFloor(), audio().getStreamVolume(AudioManager.STREAM_ALARM));

        // Dasher asks; its "Decline offer" is not enabled yet, so Offer Filter cannot tap it.
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        confirm.setEnabled(false);
        List<Long> confirmTaps = taps(confirm, true);
        show(service, question(confirm, button("View offer details")));
        pass(1_500);

        // The user taps "View offer details". Android reports neither the touch nor the click: only the same offer,
        // back on screen, its countdown ticking.
        AccessibilityNodeInfo back = offer("$7.90", "0:31");
        List<Long> again = taps(decline, true);
        show(service, back);
        String said = FilterStore.lastStatus(app);
        pass(500);
        tick(service);

        // Before, it was declined again at once (the same offer may be retried up to 4 times).
        assertTrue("never declined again", again.isEmpty());
        assertTrue(confirmTaps.isEmpty());
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, lastAction());
        assertEquals(TOOK_OVER_TOAST, ShadowToast.getTextOfLatestToast());
        assertEquals("the sound is back", 5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
        contains(said, "You went back to the offer, so auto-decline stopped for it.");
        contains(FilterStore.lastStatus(app), "You took over this offer; no automatic action.");
        contains(DiagnosticLog.read(app), "went back to the offer from Dasher's question");
        assertTrue("the touch watch is down", touchWatches().isEmpty());
        assertTrue(OfferFilterService.userHasOffer(new OfferSnapshot(790, 7.2, 21, 2)));

        // It stays the user's until the takeover ends as before: Dasher's wait for offers.
        pass(30_000);
        // The same instance's clock continues down; a fresh 0:31 would now mean a re-offer.
        show(service, offer("$7.90", "0:01"));
        assertTrue(again.isEmpty());
        show(service, idle());
        AccessibilityNodeInfo next = offer("$7.90", "0:40");
        List<Long> nextDecline = taps(decline, true);
        show(service, next);
        assertEquals("the next offer, with the same pay, is a new offer", 1, nextDecline.size());
    }

    @Test
    public void aFreshCountdownReofferEndsTheEarlierTakeover() {
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:25"));
        pass(300);
        userClicks(service, accept);
        show(service, offer("$7.90", "0:24"));
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, lastAction());
        AccessibilityNodeInfo fresh = offer("$7.90", "0:47");
        List<Long> at = taps(decline, true);
        show(service, fresh);
        assertEquals("same facts with a reset countdown is a new instance", 1, at.size());
        contains(DiagnosticLog.read(app), "[takeover] ended: new instance (countdown)");
        List<DecisionLog.Entry> history = DecisionLog.recent(app, 10);
        assertEquals("same facts from two offer instances remain separate", 2, history.size());
        assertEquals(DecisionLog.Action.DECLINE_TAPPED, history.get(0).action);
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, history.get(1).action);
        show(service, offer("$7.90", "0:46"));
        assertEquals("ordinary countdown progress never splits the history", 2, DecisionLog.recent(app, 10).size());
    }

    @Test
    public void takeoverFollowedByDeliveryShowsAcceptedForDisplayOnly() {
        FilterSettings before = FilterStore.load(app);
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:35"));
        pass(300);
        userClicks(service, button("View offer details"));
        show(service, node("Complete delivery steps", false));
        DecisionLog.Entry entry = DecisionLog.recent(app, 1).get(0);
        assertEquals(DecisionLog.Outcome.ACCEPTED, DecisionLog.outcome(entry));
        assertTrue(entry.steps.stream().anyMatch(step -> step.kind == DecisionLog.StepKind.ACCEPTED_OBSERVED));
        contains(DecisionLog.report(app, 1), "delivery screen followed the offer you took over; display only");
        // The user's acceptance of the offer they took over: never the app's automatic one, and no rule changes.
        assertTrue(entry.steps.stream().noneMatch(step -> step.kind == DecisionLog.StepKind.ACCEPTED_AUTOMATIC));
        FilterSettings after = FilterStore.load(app);
        assertTrue(Arrays.equals(before.minimums(), after.minimums()));
        assertEquals(before.maxStops, after.maxStops);
        assertEquals(before.minimumScalePercent, after.minimumScalePercent);
    }

    @Test
    public void returningToAnExistingRouteAfterTakeoverDoesNotInventAnAcceptance() {
        ActiveRouteStore.save(app, new OfferSnapshot(2000, 8.0, 30, 2));
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:35"));
        pass(300);
        userClicks(service, button("View offer details"));
        show(service, node("Complete delivery steps", false));
        DecisionLog.Entry entry = DecisionLog.recent(app, 1).get(0);
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(entry));
        assertTrue(entry.steps.stream().noneMatch(step -> step.kind == DecisionLog.StepKind.ACCEPTED_OBSERVED));
    }

    // ---- 2. A user's click on Dasher during a decline hands it back ----

    @Test
    public void theUsersClickOnAnyOfDashersControlsDuringADeclineHandsItBack() {
        OfferFilterService service = service();
        String[] questionControls = {"View offer details", "Go back", "Cancel"};
        int cents = 710;
        for (String label : questionControls) {
            show(service, idle());
            ringing();
            AccessibilityNodeInfo shown = offer("$" + cents / 100 + "." + cents % 100, "0:35");
            List<Long> first = taps(decline, true);
            show(service, shown);
            assertEquals(label, 1, first.size());

            // The question; Android refuses Offer Filter's tap on its Decline.
            pass(300);
            AccessibilityNodeInfo confirm = button("Decline offer");
            List<Long> confirmTaps = taps(confirm, false);
            AccessibilityNodeInfo control = button(label);
            show(service, question(confirm, control));
            assertEquals(label, 1, confirmTaps.size());

            // 100 ms later the user taps one of its other controls. Before, any click within 1.5 s of Offer Filter's
            // tap counted as its own, and no click ever handed an offer back.
            pass(100);
            userClicks(service, control);
            assertTrue(label, OfferFilterService.userHasOffer(new OfferSnapshot(cents, 7.2, 21, 2)));
            pass(400);
            tick(service);

            assertEquals(label + ": nothing more tapped", 1, confirmTaps.size());
            assertEquals(label, 1, first.size());
            assertEquals(label, DecisionLog.Action.USER_TOOK_OVER, lastAction());
            assertEquals(label, TOOK_OVER_TOAST, ShadowToast.getTextOfLatestToast());
            assertEquals(label, 5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
            cents += 10;
        }
        contains(DiagnosticLog.read(app), "your tap on Dasher");
    }

    @Test
    public void theUsersClickOnTheOfferCardOrAnyOtherControlRightAfterTheFirstDeclineHandsItBack() {
        OfferFilterService service = service();
        int cents = 760;
        for (String what : new String[] {"card", "Help"}) {
            show(service, idle());
            AccessibilityNodeInfo shown = offer("$" + cents / 100 + "." + cents % 100, "0:35");
            AccessibilityNodeInfo control = what.equals("card") ? node(null, true) : button(what);
            if (what.equals("card")) Shadows.shadowOf(control).addChild(node("Taco Bell", false));
            Shadows.shadowOf(shown).addChild(control);
            List<Long> first = taps(decline, true);
            show(service, shown);
            assertEquals(what, 1, first.size());

            // 80 ms after Offer Filter's Decline tap, the user taps the offer card (or another control).
            pass(80);
            userClicks(service, control);
            pass(220);
            AccessibilityNodeInfo confirm = button("Decline offer");
            List<Long> confirmTaps = taps(confirm, true);
            show(service, question(confirm, button("View offer details")));

            assertTrue(what + ": its question is left to the user", confirmTaps.isEmpty());
            assertEquals(what, DecisionLog.Action.USER_TOOK_OVER, lastAction());
            cents += 10;
        }
    }

    /**
     * PRIVACY.md: "Click diagnostics retain the control shape and action category, never its text labels." The click
     * that hands a decline back is no exception: Dasher's click event can carry a View-based control's words (a card's
     * merged text, a customer's name among them) or the clicked node can hold its own, and none of them reaches the
     * log, a shared report or the summary after a dash. Only the node's class and a fixed category (Accept, Decline).
     */
    @Test
    public void theHandBackLineNamesYourTapOnDasherByItsShapeNeverItsWords() throws Exception {
        OfferFilterService service = service();
        String store = "Chipotle Mexican Grill - Short Vine";
        String[][] eventWords = {
                {"Go back"},
                {store, "Deliver to Robin Q", "Customer note: ring twice", "Clifton Heights"},
                {},
                {"Accept"},
        };
        int cents = 760;
        long firstClick = 0;
        for (int i = 0; i < eventWords.length; i++) {
            show(service, idle());
            pass(2_000);
            AccessibilityNodeInfo shown = offer("$" + cents / 100 + "." + cents % 100, "0:35");
            // The third holds its own words: a clickable text, as a Compose Text with a click handler is.
            AccessibilityNodeInfo control = node(i == 2 ? store : null, true);
            Shadows.shadowOf(shown).addChild(control);
            List<Long> first = taps(decline, true);
            show(service, shown);
            assertEquals(1, first.size());

            pass(120);
            AccessibilityEvent tap = event(AccessibilityEvent.TYPE_VIEW_CLICKED);
            tap.setEventTime(SystemClock.uptimeMillis());
            for (String word : eventWords[i]) tap.getText().add(word);
            ((ShadowAccessibilityRecord) Shadow.extract(tap)).setSourceNode(control);
            if (firstClick == 0) firstClick = System.currentTimeMillis();
            service.onAccessibilityEvent(tap);
            idle(service);
            pass(500);
            cents += 10;
        }

        List<String> handBacks = new ArrayList<>();
        for (String line : DiagnosticLog.read(app).split("\n")) {
            if (line.contains("your tap on Dasher")) handBacks.add(line);
        }
        assertEquals(handBacks.toString(), eventWords.length, handBacks.size());
        for (String line : handBacks) noLabels(line);
        // The node's class, as Android names it ("a control" when it names none), then the fixed category if any.
        assertTrue(handBacks.get(0), handBacks.get(0).matches(".*\\((a control|\\w+)\\); automatic decline stopped"));
        assertTrue(handBacks.get(3),
                handBacks.get(3).matches(".*\\((a control|\\w+), Accept\\); automatic decline stopped"));

        // A shared report, and the summary after a dash with a counted problem 5 s after the first click.
        int reported = 0;
        for (String line : DiagnosticLog.fullReport(app).split("\n")) {
            if (!line.contains("your tap on Dasher")) continue;
            reported++;
            noLabels(line);
        }
        assertEquals(eventWords.length, reported);
        org.json.JSONObject model = new org.json.JSONObject();
        model.put("counts", new org.json.JSONObject());
        model.put("anomalies", new org.json.JSONArray().put(new org.json.JSONObject().put("t", firstClick + 5_000)
                .put("what", "decline still showing (question seen; not confirmed)")));
        String summary = DashSummary.build(app, firstClick - 60_000, firstClick + 60_000, DashSummary.End.DASH_OVER,
                model);
        int summarized = 0;
        for (String line : summary.split("\n")) {
            if (!line.contains("your tap on Dasher")) continue;
            summarized++;
            noLabels(line);
        }
        assertTrue("the summary's excerpt around the problem carries the line: " + summary, summarized > 0);
    }

    private static void noLabels(String line) {
        for (String word : new String[] {"Go back", "Chipotle", "Short Vine", "Clifton", "Customer", "[name]",
                "[instructions]", "\""}) {
            assertFalse(word + " in: " + line, line.contains(word));
        }
    }

    // ---- 3. The echo window does not swallow a real touch ----

    @Test
    public void refusedRetriesNoLongerKeepTheEchoWindowOpenOverTheUsersTouch() {
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:35"));
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirmTaps = taps(confirm, false);
        show(service, question(confirm, button("View offer details")));
        // Dasher's countdown ticks every 100 ms meanwhile. Before, every read retried the refused tap, and each retry
        // opened the 150 ms echo window again, so no touch could ever count.
        for (int i = 0; i < 4; i++) {
            pass(100);
            tick(service);
        }
        int tries = confirmTaps.size();

        // The user's touch, 180 ms after the last retry (and 80 ms after a read that did not retry).
        pass(80);
        touchNow();
        pass(500);
        tick(service);

        String log = DiagnosticLog.read(app);
        contains(log, "touch during decline: the user's, 180 ms after Offer Filter's last tap");
        assertEquals("nothing tapped after the touch", tries, confirmTaps.size());
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, lastAction());
        assertEquals("retried 250-400 ms apart: " + confirmTaps, 2, tries);
        assertEquals(300, confirmTaps.get(1) - confirmTaps.get(0));
    }

    @Test
    public void aTouchInsideAnEchoWindowIsStillTheUsersWhenDasherReportsTheirClick() {
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:35"));
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirmTaps = taps(confirm, false);
        AccessibilityNodeInfo details = button("View offer details");
        show(service, question(confirm, details));
        pass(300);
        tick(service);
        int tries = confirmTaps.size();

        // The user's finger lands 20 ms after that retry: inside its echo window. Dasher reports the click on "View
        // offer details", a node Offer Filter never tapped: that decides it.
        pass(20);
        touchNow();
        userClicks(service, details);
        pass(400);
        tick(service);

        contains(DiagnosticLog.read(app), "touch ignored: own-action echo, 20 ms after Offer Filter's tap");
        assertEquals("nothing tapped after the click", tries, confirmTaps.size());
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, lastAction());
        assertEquals(TOOK_OVER_TOAST, ShadowToast.getTextOfLatestToast());
        assertEquals(2, tries);
    }

    // ---- 4. Tries at the question: at most two retries, 250-400 ms apart, then left to the user ----

    @Test
    public void aRefusedConfirmationIsRetriedTwiceThenLeftToTheUser() {
        OfferFilterService service = service();
        AccessibilityNodeInfo first = offer("$7.90", "0:35");
        List<Long> firstDecline = taps(decline, true);
        show(service, first);
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirmTaps = taps(confirm, false);
        show(service, question(confirm, button("View offer details")));
        // No event of Dasher's comes meanwhile. Before, nothing read the question again during the poll.
        for (int i = 0; i < 40; i++) pass(50);

        assertEquals("the first try and two retries: " + confirmTaps, 3, confirmTaps.size());
        for (int i = 1; i < confirmTaps.size(); i++) {
            long gap = confirmTaps.get(i) - confirmTaps.get(i - 1);
            assertTrue("retry " + i + " " + gap + " ms after", gap >= 250 && gap <= 400);
        }
        String log = DiagnosticLog.read(app);
        contains(log, "confirmation try 1/3");
        contains(log, "found in the active window");
        contains(log, "refused");
        contains(log, "confirmation not tapped");
        contains(FilterStore.lastStatus(app), "left to you");

        // The user goes back to the offer: no first-step Decline again.
        AccessibilityNodeInfo back = offer("$7.90", "0:30");
        List<Long> again = taps(decline, true);
        show(service, back);
        pass(1_000);
        tick(service);
        assertTrue(again.isEmpty());
        assertEquals(1, firstDecline.size());
    }

    @Test
    public void aConfirmationDasherDoesNotActOnIsRetriedTwiceThenLeftToTheUser() {
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:35"));
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirmTaps = taps(confirm, true);
        show(service, question(confirm, button("View offer details")));
        for (int i = 0; i < 130; i++) pass(50);

        // Taken taps wait for Dasher to close its question; refused taps still retry promptly.
        assertEquals("the first try and two retries: " + confirmTaps, 3, confirmTaps.size());
        for (int i = 1; i < confirmTaps.size(); i++) {
            long gap = confirmTaps.get(i) - confirmTaps.get(i - 1);
            assertTrue("retry " + i + " " + gap + " ms after", gap >= 2_000 && gap <= 3_000);
        }
        contains(DiagnosticLog.read(app), "confirmation not tapped");
    }

    @Test
    public void thePollFindsTheQuestionInAWindowOfItsOwnBesideABigOfferWindow() {
        OfferFilterService service = service();
        AccessibilityNodeInfo big = offer("$7.90", "0:35");
        for (int i = 0; i < 250; i++) Shadows.shadowOf(big).addChild(node("Map pin " + i, false));
        show(service, big);

        // Dasher's question opens in a window of its own, above the offer's, which stays the active one; no event.
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirmTaps = taps(confirm, true);
        AccessibilityNodeInfo asked = question(confirm, button("View offer details"));
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(big, true, SCREEN, 0), window(asked, false, BOTTOM_HALF, 1)));
        long appeared = SystemClock.uptimeMillis();
        pass(100);

        // Before, the poll's read of the offer's window stopped at 200 nodes and looked nowhere else.
        assertEquals(1, confirmTaps.size());
        assertTrue(confirmTaps.get(0) - appeared <= OfferFilterService.CONFIRM_POLL_MS);
        String log = DiagnosticLog.read(app);
        contains(log, "found in another window of Dasher's");
        contains(log, "requested");
    }

    // ---- 5. No second first-step Decline once its question was seen, except one after Dasher's glitch ----

    @Test
    public void aConfirmedQuestionThatClosesOnTheOfferIsDeclinedOnceMoreOnlyAfterTwoSeconds() {
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:35"));
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirmTaps = taps(confirm, true);
        show(service, question(confirm, button("View offer details")));
        assertEquals(1, confirmTaps.size());

        // Dasher closes its question but still shows the offer: no tap of the user's was seen.
        pass(200);
        AccessibilityNodeInfo still = offer("$7.90", "0:34");
        List<Long> again = taps(decline, true);
        show(service, still);
        for (int i = 0; i < 3; i++) {
            pass(600);
            tick(service);
        }
        // Before, the offer was declined again at once.
        assertTrue("not before 2 s: " + again, again.isEmpty());
        assertEquals("Dasher acted on the first try", 1, confirmTaps.size());
        pass(300);
        tick(service);
        assertEquals("one more Decline after 2 s", 1, again.size());
        contains(DiagnosticLog.read(app), "Dasher glitch");

        // The same again: no third time.
        pass(300);
        AccessibilityNodeInfo confirm2 = button("Decline offer");
        List<Long> confirmTaps2 = taps(confirm2, true);
        show(service, question(confirm2, button("View offer details")));
        assertEquals(1, confirmTaps2.size());
        pass(200);
        AccessibilityNodeInfo stillAgain = offer("$7.90", "0:30");
        List<Long> third = taps(decline, true);
        show(service, stillAgain);
        for (int i = 0; i < 8; i++) {
            pass(500);
            tick(service);
        }
        assertTrue(third.isEmpty());
        contains(DiagnosticLog.read(app), "confirmation not tapped");
    }

    @Test
    public void afterTheWaitForOffersTheSameOfferAgainIsANewOffer() {
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:35"));
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirmTaps = taps(confirm, true);
        show(service, question(confirm, button("View offer details")));
        assertEquals(1, confirmTaps.size());
        pass(500);
        // Dasher's wait for offers, as the user's reports show it (no "Finding offers").
        AccessibilityNodeInfo waiting = node(null, false);
        for (String label : new String[] {"This dash", "You're in a good place to wait for offers", "Zone offer wait"}) {
            Shadows.shadowOf(waiting).addChild(node(label, false));
        }
        show(service, waiting);

        // Dasher offers the same order again a minute later: declined at once, as any new offer.
        pass(60_000);
        AccessibilityNodeInfo same = offer("$7.90", "0:40");
        List<Long> again = taps(decline, true);
        show(service, same);
        assertEquals(1, again.size());
    }

    // ---- The user's report, start to finish ----

    @Test
    public void theReportedDeclineIsNeverTappedAgainAfterTheUserTapsViewOfferDetails() {
        OfferFilterService service = service();
        AccessibilityNodeInfo first = offer("$7.90", "0:35");
        List<Long> firstDecline = taps(decline, true);
        show(service, first);
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirmTaps = taps(confirm, false);
        AccessibilityNodeInfo details = button("View offer details");
        show(service, question(confirm, details));
        for (int i = 0; i < 4; i++) {
            pass(100);
            tick(service);
        }
        // The user taps "View offer details" 20 ms later: the watch reports the touch and Dasher the click, and Dasher
        // goes back to the offer.
        pass(20);
        touchNow();
        userClicks(service, details);
        pass(40);
        AccessibilityNodeInfo back = offer("$7.90", "0:31");
        List<Long> again = taps(decline, true);
        show(service, back);
        for (int i = 0; i < 6; i++) {
            pass(500);
            tick(service);
        }

        assertTrue("never declined again: " + again, again.isEmpty());
        assertEquals(1, firstDecline.size());
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, lastAction());
        assertEquals(TOOK_OVER_TOAST, ShadowToast.getTextOfLatestToast());
    }

    // ---- On the service's own scanner thread ----

    @Test
    public void aClickIsTheUsersFromTheMomentItLandsEvenWhileAReadIsUnderWay() throws Exception {
        OfferFilterService.scanLooperForTests = null;
        controller = Robolectric.buildService(OfferFilterService.class).create();
        OfferFilterService service = controller.get();
        service.onServiceConnected();
        idle(service);
        assertTrue(service.scanLooper() != Looper.getMainLooper());
        show(service, offer("$7.90", "0:35"));
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());

        // Dasher's question comes up, and the phone is slow to read it.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        service.windowSource = () -> {
            entered.countDown();
            try {
                gate.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return service.getWindows();
        };
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirmTaps = taps(confirm, true);
        AccessibilityNodeInfo details = button("View offer details");
        dasherShows(service, question(confirm, details));
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        // The user taps "View offer details" while that read is under way: theirs from the moment Dasher reports it.
        ShadowSystemClock.advanceBy(Duration.ofMillis(400));
        userClicks(service, details);
        assertTrue(OfferFilterService.userHasOffer(new OfferSnapshot(790, 7.2, 21, 2)));
        gate.countDown();
        idle(service);
        idle(service);

        assertTrue("the read in flight taps nothing", confirmTaps.isEmpty());
        assertEquals(TOOK_OVER_TOAST, ShadowToast.getTextOfLatestToast());
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, lastAction());
    }
}
