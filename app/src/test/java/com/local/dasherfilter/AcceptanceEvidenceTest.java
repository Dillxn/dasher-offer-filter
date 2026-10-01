package com.local.dasherfilter;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.Duration;
import java.util.Collections;
import java.util.regex.Pattern;
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
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Learning from what the user does with an offer the app left alone, on screens shaped like Dasher's (Jetpack
 * Compose: a clickable node with no text holding its label, the countdown a label of its own). Accepted without a
 * seen tap when it closes with time left into a delivery screen (the user's decision); declined by hand when Dasher
 * asks to confirm a decline the app never made. Every step is a line under the offer in the history. Only existing
 * API is used, so this compiles, and fails, on the code before these steps were kept. Simulated Android only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AcceptanceEvidenceTest {
    /** The user's own kind of rules: $13, $3.85 a mile, $0.41 a minute, $4.75 a stop, at most 3 stops. */
    private static final FilterSettings RULES = new FilterSettings(true, 1300, 385, 41, 475, 3, true, 0);

    private Application app;
    private ServiceController<OfferFilterService> controller;
    private AccessibilityNodeInfo accept;
    private AccessibilityNodeInfo decline;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        DiagnosticLog.setEnabled(app, true);
        DiagnosticLog.clear(app);
        FilterStore.save(app, RULES);
        FilterStore.resetAccepted(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        ReportOutbox.forgetCache();
        OfferSilencer.forgetCache();
        ActiveRouteStore.clear(app);
        OfferNotificationService.forgetDeclineAction();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void stop() {
        OfferNotificationService.forgetDeclineAction();
        controller.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.scanLooperForTests = null;
    }

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

    /** A Compose-style button: a clickable node without text, holding its label. */
    private AccessibilityNodeInfo button(String label) {
        AccessibilityNodeInfo button = node(null, true);
        Shadows.shadowOf(button).addChild(node(label, false));
        return button;
    }

    /** An offer screen as Dasher draws it: Decline, the pay, the route line, Accept, then its countdown. */
    private AccessibilityNodeInfo offer(String pay, String route, String countdown) {
        AccessibilityNodeInfo root = node(null, false);
        decline = button("Decline");
        accept = button("Accept");
        Shadows.shadowOf(root).addChild(decline);
        Shadows.shadowOf(root).addChild(node(pay, false));
        Shadows.shadowOf(root).addChild(node("incl. tips", false));
        Shadows.shadowOf(root).addChild(node(route, false));
        Shadows.shadowOf(root).addChild(accept);
        if (countdown != null) Shadows.shadowOf(root).addChild(node(countdown, false));
        return root;
    }

    /** $16.75 for 3 stops, 3.9 mi and 30 min: it passes those rules. */
    private AccessibilityNodeInfo passing(String countdown) {
        return offer("$16.75", "3 stops (3.9 mi) • 30 min", countdown);
    }

    private AccessibilityNodeInfo screen(String... labels) {
        AccessibilityNodeInfo root = node(null, false);
        for (String label : labels) Shadows.shadowOf(root).addChild(node(label, false));
        return root;
    }

    private AccessibilityNodeInfo delivery() {
        return screen("Deliver by 9:45 PM", "Delivery for Sam", "Complete delivery steps");
    }

    private AccessibilityNodeInfo waiting() {
        return screen("This dash", "You're in a good place to wait for offers", "Zone offer wait", "1-2 min");
    }

    /** Dasher's question after a Decline tap, as the user's report shows it. */
    private AccessibilityNodeInfo question(AccessibilityNodeInfo declineOffer) {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("Are you sure you want to decline this offer?", false));
        Shadows.shadowOf(root).addChild(node("Does not lower acceptance rate", false));
        Shadows.shadowOf(root).addChild(node("50%", false));
        Shadows.shadowOf(root).addChild(node("Accepting this offer will raise acceptance rate", false));
        Shadows.shadowOf(root).addChild(declineOffer);
        Shadows.shadowOf(root).addChild(node("0:24", false));
        Shadows.shadowOf(root).addChild(node("View offer details", true));
        return root;
    }

    private void show(AccessibilityNodeInfo root) {
        Shadows.shadowOf(controller.get()).setRootInActiveWindow(root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        controller.get().onAccessibilityEvent(event);
    }

    /** A click Dasher reports on {@code source}, with no text, as Jetpack Compose sends one. */
    private void clicked(AccessibilityNodeInfo source) {
        AccessibilityEvent tap = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        tap.setPackageName("com.doordash.driverapp");
        ((ShadowAccessibilityRecord) Shadow.extract(tap)).setSourceNode(source);
        controller.get().onAccessibilityEvent(tap);
    }

    private void later(long ms) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms));
    }

    private String history() {
        return DecisionLog.report(app, 20);
    }

    private static void contains(String text, String part) {
        assertTrue(part + " in:\n" + text, text.contains(part));
    }

    @Test
    public void anOfferThatClosesWithTimeLeftIntoADeliveryIsLearnedWithoutATap() {
        // The user was waiting for offers as it came: positive evidence no delivery was under way.
        show(waiting());
        show(passing("0:35"));
        assertTrue("it passes", Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        later(4_000);
        show(passing("0:31"));
        later(1_000);
        show(delivery());

        FilterSettings saved = FilterStore.load(app);
        assertEquals(1675, saved.lastAcceptedCents);
        String history = history();
        contains(history, "learning ");
        contains(history, "Accepted; the adaptive minimum learned from it: it closed with about 0:30 left on its "
                + "countdown, and Dasher showed a delivery screen 0 s after it left");
        String log = DiagnosticLog.read(app);
        contains(log, "[accept] Accepted without a seen tap: Pay $16.75");
        contains(log, "[accept] Learned from accepted Pay $16.75");
        // The steps stay on the phone: an automatic report carries none of them.
        ProblemReport report = ProblemReport.build(ProblemReport.Kind.UNREADABLE_OFFER, "test", saved,
                DecisionLog.recent(app, 1).get(0), Collections.<String>emptyList(), null, null,
                DecisionLog.recent(app, 10));
        assertFalse(report.body, report.body.contains("steps") || report.body.contains("Accepted;"));
    }

    @Test
    public void anOfferThatClosesIntoTheWaitForOffersTeachesNothing() {
        show(passing("0:35"));
        later(3_000);
        show(screen("Sam T", "120 orders completed", "Home", "Schedule", "Account"));
        show(screen());
        later(2_000);
        show(waiting());

        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        contains(history(), "Not accepted: Dasher went back to the wait for offers 2 s after it left");
        // What Dasher showed after it is kept for a report.
        contains(DiagnosticLog.readScreens(app), "after an offer left (0 s) labels=[Sam T");
    }

    @Test
    public void anOfferThatMayHaveRunOutOrCameDuringADeliveryTeachesNothing() {
        show(waiting());
        show(passing("0:04"));
        later(2_000);
        show(delivery());
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        contains(history(), "Not learned: it may have run out: its countdown showed 0:04, 2 s before a read found it "
                + "gone");

        // A delivery was under way when the next one came: the delivery screen after it proves nothing.
        later(5_000);
        show(offer("$18.00", "2 stops (4.0 mi) • 20 min", "0:40"));
        later(2_000);
        show(delivery());
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        contains(history(), "Not learned: a delivery was already under way when it came, so the delivery screen "
                + "after it proves nothing");
    }

    @Test
    public void aNextScreenThatIsNeitherIsLoggedAndTeachesNothing() {
        show(passing("0:35"));
        later(2_000);
        show(screen("Order details", "Store A", "Items 3"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        show(delivery());

        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        contains(history(), "Not learned: Dasher's next screen was neither a delivery nor the wait for offers (its "
                + "words are in the screens log)");
        contains(DiagnosticLog.readScreens(app),
                "after an offer left, neither a delivery nor the wait for offers: labels=[Order details, Store A");
    }

    @Test
    public void dashersNewDeliveryHeadlineIsANewOfferNeverAnAcceptance() {
        show(passing("0:35"));
        later(2_000);
        // The user's note: "New Delivery!" / "New Order: Go to …" is a new order, not one they accepted.
        show(screen("New Delivery!", "New Order: Go to Store A", "Deliver by 9:45 PM"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));

        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        contains(history(), "Not learned: Dasher showed neither a delivery nor the wait for offers within 60 s");
    }

    @Test
    public void dashersDeclineQuestionForAPassingOfferTeachesOnceTheNextOfferComes() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 0, 0, 0, true, 0));
        show(offer("$14.00", "2 stops (7.2 mi) • 21 min", "0:35"));
        assertTrue("it passes", Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        later(1_000);
        AccessibilityNodeInfo declineOffer = node("Decline offer", true);
        show(question(declineOffer));
        later(1_000);
        show(screen("Finding offers"));
        assertTrue("held until the dash goes on", FilterStore.load(app).declined.isEmpty());
        later(5_000);
        show(offer("$20.00", "2 stops (7.2 mi) • 21 min", "0:40"));

        assertTrue("Dasher's question is never answered for the user",
                Shadows.shadowOf(declineOffer).getPerformedActions().isEmpty());
        assertEquals("$1.94/mi", FilterStore.load(app).declined.rates.perMileLabel());
        String history = history();
        contains(history, "Dasher asked to confirm declining it; Offer Filter did not decline it");
        contains(history, "Counted as your Decline: Dasher went back to the wait for offers 1 s after it left; it "
                + "teaches once the next offer comes");
        contains(history, "Your Decline raised the adaptive minimum: learned from declines by hand: $1.94/mi");
    }

    @Test
    public void goingBackToTheOfferFromDashersQuestionCountsNothing() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 0, 0, 0, true, 0));
        show(offer("$14.00", "2 stops (7.2 mi) • 21 min", "0:35"));
        later(1_000);
        show(question(node("Decline offer", true)));
        later(2_000);
        show(offer("$14.00", "2 stops (7.2 mi) • 21 min", "0:31"));
        later(5_000);
        show(screen("Finding offers"));
        later(5_000);
        show(offer("$20.00", "2 stops (7.2 mi) • 21 min", "0:40"));

        assertTrue(FilterStore.load(app).declined.isEmpty());
        contains(history(), "Not counted as your Decline: you went back to the offer");
    }

    @Test
    public void offerFiltersOwnDeclineAndItsLateEchoAreNeverTheUsers() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0, true, 0));
        show(offer("$7.90", "2 stops (7.2 mi) • 21 min", "0:35"));
        AccessibilityNodeInfo ours = decline;
        assertEquals("the app declines it", 1, Shadows.shadowOf(ours).getPerformedActions().size());
        // Its click comes back late, after a slow read: still the app's own tap.
        later(2_500);
        clicked(ours);
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(question(confirm));
        assertEquals("the app confirms its own decline", 1, Shadows.shadowOf(confirm).getPerformedActions().size());
        later(1_500);
        show(screen("Finding offers"));
        later(5_000);
        show(offer("$25.00", "2 stops (7.2 mi) • 21 min", "0:40"));

        assertTrue(FilterStore.load(app).declined.isEmpty());
        String history = history();
        assertFalse(history, history.contains("Dasher asked to confirm declining it"));
        assertFalse(history, history.contains("Decline on it"));
        contains(DiagnosticLog.readScreens(app), "tap (Offer Filter's own) ");
    }

    @Test
    public void aComposeAcceptTapIsSeenAndLearned() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0, true, 0));
        show(offer("$25.00", "2 stops (7.2 mi) • 21 min", null));
        later(3_000);
        clicked(accept);
        show(screen("Arrived at store"));

        assertEquals(2500, FilterStore.load(app).lastAcceptedCents);
        contains(DiagnosticLog.read(app), "Accept tap seen on Pay $25.00");
        String screens = DiagnosticLog.readScreens(app);
        contains(screens, "tap (not Offer Filter's) ");
        // While the offer is up nothing around the tap is read (each node is a call into Dasher, whose UI thread is
        // drawing the offer): the tap is named by the offer's own Accept control, which the read found. Before, the
        // nodes below it were read too ("below=[Accept]").
        contains(screens, "target=accept");
        contains(screens, "above=[] below=[] -> accept");
        String history = history();
        contains(history, "You tapped Accept: waiting for a delivery screen");
        contains(history, "Accepted; the adaptive minimum learned from it: you tapped Accept, and Dasher showed a "
                + "delivery screen");
    }

    @Test
    public void aComposeDeclineTapOnAPassingOfferTeaches() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 0, 0, 0, true, 0));
        show(offer("$14.00", "2 stops (7.2 mi) • 21 min", null));
        later(3_000);
        clicked(decline);
        assertTrue("held until the dash goes on", FilterStore.load(app).declined.isEmpty());
        later(5_000);
        show(offer("$20.00", "2 stops (7.2 mi) • 21 min", null));

        assertEquals("$1.94/mi", FilterStore.load(app).declined.rates.perMileLabel());
        contains(history(), "You tapped Decline on it: it counts once Dasher goes back to the wait for offers or another "
                + "offer comes");
        contains(history(), "Counted as your Decline: another offer came; it teaches once the next offer comes");
    }

    @Test
    public void aHeldDeclineIsDroppedByDashersHomeBeforeADash() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 0, 0, 0, true, 0));
        show(offer("$14.00", "2 stops (7.2 mi) • 21 min", null));
        later(3_000);
        AccessibilityEvent tap = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        tap.setPackageName("com.doordash.driverapp");
        tap.getText().add("Decline");
        controller.get().onAccessibilityEvent(tap);
        later(2_000);
        // Dasher's home before a dash, as the user's report shows it: the decline was about stopping.
        show(screen("Side Menu", "This week", "Earnings Mode Switcher", "Time mode off", "Time mode on",
                "Safety tools", "Dash", "dx.home_screen.schedule", "Home", "Schedule", "Account"));
        later(60_000);
        show(offer("$20.00", "2 stops (7.2 mi) • 21 min", null));

        assertTrue(FilterStore.load(app).declined.isEmpty());
        contains(history(), "Not counted as your Decline: the dash ended or paused");
    }

    @Test
    public void theReportSaysWhenLearningWasOnAndWhenTheAdaptiveMinimumWasReset() {
        FilterStore.save(app, new FilterSettings(false, 1300, 0, 0, 0, 0, false, 0));
        FilterStore.save(app, RULES);
        FilterStore.resetAccepted(app);
        String report = DiagnosticLog.report(app);
        assertTrue(report, Pattern.compile("learning \\(auto-decline and Adaptive minimum both on\\) since=\\d{4}-")
                .matcher(report).find());
        contains(report, "learning last turned off=");
        assertTrue(report, Pattern.compile("adaptive minimums last reset=\\d{4}-").matcher(report).find());
    }

    @Test
    public void anOfferAfterARestartWithNothingReadBeforeItTeachesNothing() {
        // S1: the service starts mid-delivery; a stacked offer DoorDash pulls; Dasher back on its delivery screen.
        show(passing("0:35"));
        later(4_000);
        show(passing("0:31"));
        later(1_000);
        show(delivery());

        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        contains(history(), "Not learned: Dasher's wait for offers wasn't seen before it");
    }

    @Test
    public void aSeenDeclineThenBackToTheOfferThenAPickupTeachesNothing() {
        // S5: the user taps Decline, cancels Dasher's question, then accepts (a tap Dasher does not report).
        FilterStore.save(app, new FilterSettings(true, 700, 150, 0, 0, 0, true, 0));
        show(waiting());
        show(offer("$14.00", "2 stops (7.2 mi) • 21 min", "0:35"));
        later(1_000);
        clicked(decline);
        later(500);
        show(question(node("Decline offer", true)));
        later(1_500);
        show(offer("$14.00", "2 stops (7.2 mi) • 21 min", "0:31"));
        later(1_000);
        show(screen("Pick up by 9:30 PM", "Store A", "Directions"));
        later(5_000);
        show(offer("$20.00", "2 stops (7.2 mi) • 21 min", "0:40"));

        assertTrue("nothing learned from a decline the user backed out of", FilterStore.load(app).declined.isEmpty());
        String history = history();
        contains(history, "Not counted as your Decline: you went back to the offer");
        contains(history, "Not learned: you began to decline it, so the delivery screen after it is no Accept");
    }

    @Test
    public void theWaitForOffersOrTheDashHomeEndsAStoredRoute() {
        show(waiting());
        show(passing("0:35"));
        later(4_000);
        show(passing("0:31"));
        later(1_000);
        show(delivery());
        assertEquals(1675, FilterStore.load(app).lastAcceptedCents);
        assertNotNull("the accepted route is kept during its delivery", ActiveRouteStore.load(app));
        later(20 * 60_000L);
        // Back to the wait for offers (the dash's own screen, without "Finding offers"): that route is over.
        show(waiting());
        assertNull(ActiveRouteStore.load(app));

        // So the next offer can teach again (with the route kept, the delivery screen after it proved nothing).
        later(60_000);
        show(offer("$18.00", "2 stops (4.0 mi) • 20 min", "0:40"));
        later(3_000);
        show(offer("$18.00", "2 stops (4.0 mi) • 20 min", "0:37"));
        later(1_000);
        show(delivery());
        assertEquals(1800, FilterStore.load(app).lastAcceptedCents);

        // Dasher's home before a dash ends it too.
        assertNotNull(ActiveRouteStore.load(app));
        show(screen("Side Menu", "This week", "Earnings Mode Switcher", "Time mode off", "Dash", "Home",
                "Schedule", "Account"));
        assertNull(ActiveRouteStore.load(app));
    }

    @Test
    public void withNothingUpATapIsStillReadAroundAfterItsRead() {
        // A delivery under way: its stored route has travel the next add-on would be judged against.
        ActiveRouteStore.save(app, new OfferSnapshot(2500, 7.2, 21, 2));
        AccessibilityNodeInfo step = button("Complete delivery");
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("Deliver by 9:45 PM", false));
        Shadows.shadowOf(root).addChild(step);
        show(root);
        // A Compose button reports a click with no text: its label is below it, read once the click's read is done.
        clicked(step);

        OfferSnapshot route = ActiveRouteStore.load(app);
        assertEquals("pay kept", Integer.valueOf(2500), route.payCents);
        assertNull("delivery progress makes the old travel stale", route.miles);
        contains(DiagnosticLog.read(app), "delivery progress observed");
    }

    @Test
    public void aClickAfterTheOfferLeftIsNotOnItsControls() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0, true, 0));
        show(waiting());
        // Accept's label four levels below its clickable node: only the control the read found names a click on it.
        AccessibilityNodeInfo root = node(null, false);
        AccessibilityNodeInfo acceptButton = node(null, true);
        AccessibilityNodeInfo wrapper = acceptButton;
        for (int i = 0; i < 3; i++) {
            AccessibilityNodeInfo inner = node(null, false);
            Shadows.shadowOf(wrapper).addChild(inner);
            wrapper = inner;
        }
        Shadows.shadowOf(wrapper).addChild(node("Accept", false));
        decline = button("Decline");
        Shadows.shadowOf(root).addChild(decline);
        Shadows.shadowOf(root).addChild(node("$25.00", false));
        Shadows.shadowOf(root).addChild(node("2 stops (7.2 mi) • 21 min", false));
        Shadows.shadowOf(root).addChild(acceptButton);
        show(root);
        later(1_000);
        show(delivery());
        later(1_000);
        // A click on that node now (Dasher may reuse a node's identity on its next screen) is no Accept.
        clicked(acceptButton);

        String history = history();
        assertFalse(history, history.contains("You tapped Accept"));
        contains(DiagnosticLog.readScreens(app), "target=none");
    }

    @Test
    public void offerFiltersOwnTapCountedByWhenItHappenedNotWhenItsEventArrived() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0, true, 0));
        show(waiting());
        show(offer("$7.90", "2 stops (7.2 mi) • 21 min", "0:35"));
        long tappedAt = SystemClock.uptimeMillis();
        assertEquals("the app declines it", 1, Shadows.shadowOf(decline).getPerformedActions().size());
        later(500);
        show(offer("$25.00", "2 stops (7.2 mi) • 21 min", "0:40"));
        // The app's tap comes back 30 ms after it was made, but reaches the service 2 s later (a busy main thread),
        // naming Decline with no node: still the app's own, never the user's Decline of the offer now showing.
        later(1_500);
        AccessibilityEvent echo = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        echo.setPackageName("com.doordash.driverapp");
        echo.getText().add("Decline");
        echo.setEventTime(tappedAt + 30);
        controller.get().onAccessibilityEvent(echo);

        String history = history();
        assertFalse(history, history.contains("You tapped Decline on it"));
        contains(DiagnosticLog.readScreens(app), "tap (Offer Filter's own) ");
    }

    @Test
    public void aDeclineSentThroughDashersNotificationIsNoAcceptance() throws Exception {
        show(waiting());
        show(passing("0:35"));
        // The notification path declines this offer through Dasher's own action.
        OfferNotificationService.sendDecline(
                PendingIntent.getBroadcast(app, 0, new Intent("test.decline"), PendingIntent.FLAG_IMMUTABLE));
        later(4_000);
        show(passing("0:31"));
        later(1_000);
        show(delivery());

        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        contains(history(), "Not learned: a decline was requested through Dasher's notification");
    }

    @Test
    public void aNotificationDeclineIsMarkedBeforeItIsSent() {
        // Marked first: Dasher may react (and the screen reader read it) before send() returns. A send that fails
        // stays marked, which only means nothing is learned for a minute.
        PendingIntent decline = PendingIntent.getBroadcast(app, 0, new Intent("test.decline"),
                PendingIntent.FLAG_IMMUTABLE);
        decline.cancel();
        assertThrows(PendingIntent.CanceledException.class, () -> OfferNotificationService.sendDecline(decline));
        assertTrue(OfferNotificationService.declineActionWithin(60_000));
    }
}
