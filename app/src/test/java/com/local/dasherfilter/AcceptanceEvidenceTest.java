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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * What the user does with an offer the app left alone, on screens shaped like Dasher's (Jetpack Compose: a clickable
 * node with no text holding its label, the countdown a label of its own). Accepted without a seen tap when it closes
 * with time left into a delivery screen (the user's decision); declined by hand when Dasher asks to confirm a decline
 * the app never made. Every step is a line under the offer in the history, and counts toward the acceptance rate;
 * nothing is learned from any of them (0.5.0: no rule changes). Simulated Android only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AcceptanceEvidenceTest {
    /**
     * The user's own kind of rules: $13, $3.85 a mile, $0.41 a minute ($24.60 an hour), at most 3 stops. Their $4.75 a
     * stop folds into minimum pay as 2 × $4.75 = $9.50, below the $13 already asked.
     */
    private static final FilterSettings RULES = FilterSettings.of(true, Math.max(1300, 2 * 475), 385, 41, 3);
    /** $7, $1.50 a mile: a $14.00 offer for 7.2 mi passes (it needs $10.80). */
    private static final FilterSettings PER_MILE = FilterSettings.of(true, 700, 150, 0, 0);
    /** $20 at least: a $7.90 offer fails, a $25.00 one passes. */
    private static final FilterSettings TWENTY = FilterSettings.of(true, 2000, 0, 0, 0);

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
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
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
        TestWindows.full(controller.get(), root);
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

    // ---- What became of an offer: steps on its own line ----

    /** The newest screen line of the offer with this pay, as the history has it now. */
    private DecisionLog.Entry line(int payCents) {
        DecisionLog.flush();
        for (DecisionLog.Entry entry : DecisionLog.recent(app, 20)) {
            if (entry.source == DecisionLog.Source.SCREEN && entry.facts.payCents != null
                    && entry.facts.payCents == payCents) {
                return entry;
            }
        }
        throw new AssertionError("no line for pay " + payCents + " in:\n" + history());
    }

    private static boolean has(DecisionLog.Entry line, DecisionLog.StepKind kind) {
        return DecisionLog.hasStep(line, kind);
    }

    /** The user accepted it: counted as accepted, as the user's (never the app's automatic request). */
    private void assertAcceptedByTheUser(int payCents) {
        DecisionLog.Entry line = line(payCents);
        assertTrue(history(), DecisionLog.accepted(line));
        assertEquals(DecisionLog.Outcome.ACCEPTED, DecisionLog.outcome(line));
        assertTrue(history(), has(line, DecisionLog.StepKind.ACCEPTED));
        assertFalse(history(), has(line, DecisionLog.StepKind.ACCEPTED_AUTOMATIC));
    }

    /** Nothing counted the offer as accepted. */
    private void assertNotAccepted(int payCents) {
        DecisionLog.Entry line = line(payCents);
        assertFalse(history(), DecisionLog.accepted(line));
        assertFalse(history(), has(line, DecisionLog.StepKind.ACCEPTED));
        assertFalse(history(), has(line, DecisionLog.StepKind.ACCEPTED_AUTOMATIC));
    }

    /** Nothing was counted as the user's Decline of the offer. */
    private void assertNoDeclineCounted(int payCents) {
        assertFalse(history(), has(line(payCents), DecisionLog.StepKind.DECLINE_COUNTED));
    }

    /** No outcome teaches anything: the minimums stay exactly as set. */
    private void assertRulesUnchanged(FilterSettings set) {
        FilterSettings now = FilterStore.load(app);
        assertArrayEquals(set.minimums(), now.minimums());
        assertEquals(set.maxStops, now.maxStops);
        assertEquals(set.minimumScalePercent, now.minimumScalePercent);
    }

    @Test
    public void anOfferThatClosesWithTimeLeftIntoADeliveryIsCountedWithoutATap() {
        // The user was waiting for offers as it came: positive evidence no delivery was under way.
        show(waiting());
        show(passing("0:35"));
        assertTrue("it passes", Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        later(4_000);
        show(passing("0:31"));
        later(1_000);
        show(delivery());

        assertAcceptedByTheUser(1675);
        contains(history(), "Accepted: it closed with about 0:30 left on its countdown, and Dasher showed a delivery "
                + "screen 0 s after it left");
        String log = DiagnosticLog.read(app);
        contains(log, "[accept] Accepted without a seen tap: Pay $16.75");
        assertFalse("nothing is learned from it", log.contains("Learned from"));
        FilterSettings saved = FilterStore.load(app);
        assertRulesUnchanged(RULES);
        // The steps stay on the phone: a report of the offer carries none of them, only its outcome category.
        String report = OfferReport.text(OfferReport.Problem.MISREAD, "test", 1, "Android test", saved,
                DecisionLog.recent(app, 1).get(0));
        assertFalse(report, report.contains("steps") || report.contains("it closed with about"));
        assertTrue(report, report.contains("\"outcome\": \"ACCEPTED\""));
    }

    @Test
    public void anOfferThatClosesIntoTheWaitForOffersIsNotAccepted() {
        show(passing("0:35"));
        later(3_000);
        show(screen("Sam T", "120 orders completed", "Home", "Schedule", "Account"));
        show(screen());
        later(2_000);
        show(waiting());

        assertNotAccepted(1675);
        assertTrue(has(line(1675), DecisionLog.StepKind.NOT_ACCEPTED));
        contains(history(), "Not accepted: Dasher went back to the wait for offers 2 s after it left");
        // The outcome still uses the raw transition; an unrecognized menu keeps none of its labels in diagnostics.
        String screens = DiagnosticLog.readScreens(app);
        contains(screens, PersonalText.UNKNOWN_NOT_KEPT);
        assertFalse(screens, screens.contains("Sam T") || screens.contains("120 orders completed"));
    }

    @Test
    public void anOfferThatMayHaveRunOutOrCameDuringADeliveryIsNotCounted() {
        show(waiting());
        show(passing("0:04"));
        later(2_000);
        show(delivery());
        assertNotAccepted(1675);
        contains(history(), "Not counted from what followed: it may have run out: its countdown showed 0:04, 2 s "
                + "before a read found it gone");

        // A delivery was under way when the next one came: the delivery screen after it proves nothing.
        later(5_000);
        show(offer("$18.00", "2 stops (4.0 mi) • 20 min", "0:40"));
        later(2_000);
        show(delivery());
        assertNotAccepted(1800);
        contains(history(), "Not counted from what followed: a delivery was already under way when it came, so the "
                + "delivery screen after it proves nothing");
    }

    @Test
    public void aNextScreenThatIsNeitherIsLoggedAndCountsNothing() {
        show(passing("0:35"));
        later(2_000);
        show(screen("Order details", "Store A", "Items 3"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        show(delivery());

        assertNotAccepted(1675);
        contains(history(), "Not counted from what followed: Dasher's next screen was neither a delivery nor the wait "
                + "for offers (its words are in the screens log)");
        String screens = DiagnosticLog.readScreens(app);
        contains(screens, "after an offer left, neither a delivery nor the wait for offers: labels=["
                + PersonalText.UNKNOWN_NOT_KEPT + "]");
        assertFalse(screens, screens.contains("Order details") || screens.contains("Store A")
                || screens.contains("Items 3"));
    }

    @Test
    public void dashersNewDeliveryHeadlineIsANewOfferNeverAnAcceptance() {
        show(passing("0:35"));
        later(2_000);
        // The user's note: "New Delivery!" / "New Order: Go to …" is a new order, not one they accepted.
        show(screen("New Delivery!", "New Order: Go to Store A", "Deliver by 9:45 PM"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));

        assertNotAccepted(1675);
        contains(history(), "Not counted from what followed: Dasher showed neither a delivery nor the wait for offers "
                + "within 60 s");
    }

    @Test
    public void dashersDeclineQuestionForAPassingOfferCountsAsTheUsersDeclineOnceDasherGoesOn() {
        FilterStore.save(app, PER_MILE);
        show(offer("$14.00", "2 stops (7.2 mi) • 21 min", "0:35"));
        assertTrue("it passes", Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        later(1_000);
        AccessibilityNodeInfo declineOffer = node("Decline offer", true);
        show(question(declineOffer));
        assertFalse("held until Dasher moves on", has(line(1400), DecisionLog.StepKind.DECLINE_COUNTED));
        later(1_000);
        show(screen("Finding offers"));
        later(5_000);
        show(offer("$20.00", "2 stops (7.2 mi) • 21 min", "0:40"));

        assertTrue("Dasher's question is never answered for the user",
                Shadows.shadowOf(declineOffer).getPerformedActions().isEmpty());
        DecisionLog.Entry declined = line(1400);
        assertTrue(history(), has(declined, DecisionLog.StepKind.DECLINE_QUESTION));
        assertTrue(history(), has(declined, DecisionLog.StepKind.DECLINE_COUNTED));
        String history = history();
        contains(history, "Dasher asked to confirm declining it; Offer Filter did not decline it");
        contains(history, "Counted as your Decline: Dasher went back to the wait for offers 1 s after it left");
        // Nothing is learned from it: the rules stay as set, and the next offer like it passes as before.
        assertFalse(history, history.contains("raised") || history.contains("learned"));
        assertRulesUnchanged(PER_MILE);
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        // Dasher's question showed its acceptance rate: kept as one number, and the offer it asked about marked as
        // free to decline (on the main thread, after its line).
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(50, AutopilotStore.reading(app, System.currentTimeMillis()).percent);
        assertTrue(history(), has(line(1400), DecisionLog.StepKind.AR_EXEMPT));
        assertFalse(history(), has(line(2000), DecisionLog.StepKind.AR_EXEMPT));
    }

    @Test
    public void goingBackToTheOfferFromDashersQuestionCountsNothing() {
        FilterStore.save(app, PER_MILE);
        show(offer("$14.00", "2 stops (7.2 mi) • 21 min", "0:35"));
        later(1_000);
        show(question(node("Decline offer", true)));
        later(2_000);
        show(offer("$14.00", "2 stops (7.2 mi) • 21 min", "0:31"));
        later(5_000);
        show(screen("Finding offers"));
        later(5_000);
        show(offer("$20.00", "2 stops (7.2 mi) • 21 min", "0:40"));

        assertNoDeclineCounted(1400);
        contains(history(), "Not counted as your Decline: you went back to the offer");
        assertRulesUnchanged(PER_MILE);
    }

    @Test
    public void offerFiltersOwnDeclineAndItsLateEchoAreNeverTheUsers() {
        FilterStore.save(app, TWENTY);
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

        assertNoDeclineCounted(790);
        assertFalse(has(line(790), DecisionLog.StepKind.DECLINE_TAPPED));
        String history = history();
        assertFalse(history, history.contains("Dasher asked to confirm declining it"));
        assertFalse(history, history.contains("Decline on it"));
        contains(DiagnosticLog.readScreens(app), "tap (Offer Filter's own) ");
    }

    @Test
    public void aComposeAcceptTapIsSeenAndCounted() {
        FilterStore.save(app, TWENTY);
        show(offer("$25.00", "2 stops (7.2 mi) • 21 min", null));
        later(3_000);
        clicked(accept);
        show(screen("Arrived at store"));

        assertAcceptedByTheUser(2500);
        assertRulesUnchanged(TWENTY);
        contains(DiagnosticLog.read(app), "Accept tap seen on Pay $25.00");
        String screens = DiagnosticLog.readScreens(app);
        contains(screens, "tap (not Offer Filter's) ");
        // The offer's own control identifies the tap; diagnostic clicks keep only structure/action categories.
        contains(screens, "target=accept");
        contains(screens, " -> accept");
        assertFalse(screens, screens.contains("above=") || screens.contains("below="));
        String history = history();
        contains(history, "You tapped Accept: waiting for a delivery screen");
        contains(history, "Accepted: you tapped Accept, and Dasher showed a delivery screen");
    }

    @Test
    public void aComposeDeclineTapOnAPassingOfferCountsAsTheUsersDecline() {
        FilterStore.save(app, PER_MILE);
        show(offer("$14.00", "2 stops (7.2 mi) • 21 min", null));
        later(3_000);
        clicked(decline);
        assertFalse("held until the dash goes on", has(line(1400), DecisionLog.StepKind.DECLINE_COUNTED));
        later(5_000);
        show(offer("$20.00", "2 stops (7.2 mi) • 21 min", null));

        assertTrue(history(), has(line(1400), DecisionLog.StepKind.DECLINE_COUNTED));
        contains(history(), "You tapped Decline on it: it counts once Dasher goes back to the wait for offers or another "
                + "offer comes");
        contains(history(), "Counted as your Decline: another offer came");
        assertRulesUnchanged(PER_MILE);
    }

    @Test
    public void aHeldDeclineIsDroppedByDashersHomeBeforeADash() {
        FilterStore.save(app, PER_MILE);
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

        assertNoDeclineCounted(1400);
        assertTrue(history(), has(line(1400), DecisionLog.StepKind.DECLINE_DROPPED));
        contains(history(), "Not counted as your Decline: the dash ended or paused");
    }

    @Test
    public void theReportCarriesTheRulesAndNoLearningOfAnyKind() {
        // 0.5.0 learns nothing from what the user does: the report has no learning times and no learned minimums.
        FilterStore.save(app, RULES.withEnabled(false));
        FilterStore.save(app, RULES);
        String report = DiagnosticLog.report(app);
        contains(report, "flat cents=1300");
        assertFalse(report, Pattern.compile("(?i)learning \\(|learning last turned|adaptive minimum|last reset=")
                .matcher(report).find());
    }

    @Test
    public void anOfferAfterARestartWithNothingReadBeforeItIsNotCounted() {
        // S1: the service starts mid-delivery; a stacked offer DoorDash pulls; Dasher back on its delivery screen.
        show(passing("0:35"));
        later(4_000);
        show(passing("0:31"));
        later(1_000);
        show(delivery());

        assertNotAccepted(1675);
        contains(history(), "Not counted from what followed: Dasher's wait for offers wasn't seen before it");
    }

    @Test
    public void aSeenDeclineThenBackToTheOfferThenAPickupCountsNothing() {
        // S5: the user taps Decline, cancels Dasher's question, then accepts (a tap Dasher does not report).
        FilterStore.save(app, PER_MILE);
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

        assertNoDeclineCounted(1400);
        assertNotAccepted(1400);
        String history = history();
        contains(history, "Not counted as your Decline: you went back to the offer");
        contains(history, "Not counted from what followed: you began to decline it, so the delivery screen after it "
                + "is no Accept");
        assertRulesUnchanged(PER_MILE);
    }

    @Test
    public void theWaitForOffersOrTheDashHomeEndsAStoredRoute() {
        show(waiting());
        show(passing("0:35"));
        later(4_000);
        show(passing("0:31"));
        later(1_000);
        show(delivery());
        assertAcceptedByTheUser(1675);
        assertNotNull("the accepted route is kept during its delivery", ActiveRouteStore.load(app));
        later(20 * 60_000L);
        // Back to the wait for offers (the dash's own screen, without "Finding offers"): that route is over.
        show(waiting());
        assertNull(ActiveRouteStore.load(app));

        // So the next offer can count again (with the route kept, the delivery screen after it proved nothing).
        later(60_000);
        show(offer("$18.00", "2 stops (4.0 mi) • 20 min", "0:40"));
        later(3_000);
        show(offer("$18.00", "2 stops (4.0 mi) • 20 min", "0:37"));
        later(1_000);
        show(delivery());
        assertAcceptedByTheUser(1800);

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
        FilterStore.save(app, TWENTY);
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
        FilterStore.save(app, TWENTY);
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

        assertNotAccepted(1675);
        contains(history(), "Not counted from what followed: a decline was requested through Dasher's notification");
    }

    @Test
    public void aNotificationDeclineIsMarkedBeforeItIsSent() {
        // Marked first: Dasher may react (and the screen reader read it) before send() returns. A send that fails
        // stays marked, which only means no delivery screen counts an offer as accepted for a minute.
        PendingIntent decline = PendingIntent.getBroadcast(app, 0, new Intent("test.decline"),
                PendingIntent.FLAG_IMMUTABLE);
        decline.cancel();
        assertThrows(PendingIntent.CanceledException.class, () -> OfferNotificationService.sendDecline(decline));
        assertTrue(OfferNotificationService.declineActionWithin(60_000));
    }
}
