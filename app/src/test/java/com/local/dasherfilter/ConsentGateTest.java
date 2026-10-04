package com.local.dasherfilter;

import android.app.Notification;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowWindowManagerImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The first-run notice and its consent, through real Android adapters: until it is accepted the screen reader
 * declines nothing and the notification path posts nothing, and the homepage is only the notice; explicit acceptance is kept,
 * Not now closes the app, an existing install sees it once, and the bundled texts open from the notice and Settings.
 * Every other test runs with the notice accepted (ConsentedTestApp); these start from a phone that has not seen it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class ConsentGateTest extends AndroidAdapterTestBase {
    @Before
    public void notYetAccepted() {
        ConsentedTestApp.forget(app);
    }

    // ---- Nothing acts before the notice is accepted ----

    @Test
    public void theScreenReaderReadsDecidesAndTapsNothingUntilTheNoticeIsAccepted() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        ServiceController<OfferFilterService> controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        try {
            OfferFilterService service = controller.get();
            service.onServiceConnected();
            idle();
            AccessibilityNodeInfo[] buttons = new AccessibilityNodeInfo[2];
            show(service, offer("$7.90", buttons));
            // A notification's or a rules change's request for a read finds nothing to do either.
            OfferFilterService.requestCheckFromNotification();
            idle();

            assertTrue("no Decline tap", Shadows.shadowOf(buttons[1]).getPerformedActions().isEmpty());
            assertTrue("no Accept tap", Shadows.shadowOf(buttons[0]).getPerformedActions().isEmpty());
            assertTrue("nothing decided", DecisionLog.recent(app, 10).isEmpty());
            assertEquals("no read at all", 0, service.rootFetches);
            assertTrue("no tab over Dasher", windows(service).isEmpty());
            assertFalse("nothing captured", DiagnosticLog.read(app).contains("$7.90"));
            assertFalse(DiagnosticLog.readScreens(app).contains("$7.90"));

            // Accepted: the very next offer is declined at once, as always.
            Consent.accept(app);
            AccessibilityNodeInfo[] next = new AccessibilityNodeInfo[2];
            show(service, offer("$7.80", next));
            assertEquals(1, Shadows.shadowOf(next[1]).getPerformedActions().size());
            assertTrue(Shadows.shadowOf(next[0]).getPerformedActions().isEmpty());
            assertEquals(DecisionLog.Action.DECLINE_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void theNotificationPathPostsHidesAndDeclinesNothingUntilTheNoticeIsAccepted() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        ServiceController<OfferNotificationService> controller =
                Robolectric.buildService(OfferNotificationService.class).create();
        try {
            controller.get().onListenerConnected();
            // One that would ring "Offers to check", and one below the minimum that would be filtered.
            controller.get().onNotificationPosted(doorDashOffer("New Order: Go to Chick-fil-A"), null);
            controller.get().onNotificationPosted(offerNotification("NEW_ORDER_2", "$3.50 · 2 stops (6.2 mi) • 21 min"),
                    null);
            idle();

            // No card: only the one reminder that the app is paused (ConsentReminderTest).
            assertEquals("no card", 1, notifications().size());
            assertNotNull(notifications().getNotification(ConsentReminder.NOTIFICATION_ID));
            assertTrue("nothing decided, hidden or declined", DecisionLog.recent(app, 10).isEmpty());
            assertFalse(OfferNotificationService.hasActiveOffer());
            assertFalse(OfferNotificationService.declineActionWithin(60_000));
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());

            // Accepted: a new offer gets its card as before.
            Consent.accept(app);
            controller.get().onNotificationPosted(offerNotification("NEW_ORDER_3", "New Order: Go to Taco Bell"),
                    null);
            idle();
            assertEquals(1, notifications().size());
            assertEquals("CHECK_BELL", DecisionLog.recent(app, 1).get(0).action.name());
        } finally {
            controller.destroy();
        }
    }

    // ---- The homepage is only the notice ----

    @Test
    public void dataNoticeDistinguishesLongerLivedCopiesAndNamesReportRecipients() {
        String data = null;
        for (String[] point : Consent.POINTS) if ("Your data.".equals(point[0])) data = point[1];
        assertNotNull(data);
        for (String fact : new String[] {"rolling 24-hour window", "latest 200 offer decisions",
                "no age-based expiry", "Up to 30 queued reports", "notes you type in reports are not masked",
                "developer's private GitHub repository", "Anthropic's Claude", "OpenAI's ChatGPT/Codex",
                "does not erase sent copies"}) {
            assertTrue(fact, data.contains(fact));
        }
        assertFalse(data.contains("Offer and dash text stays masked on this phone for up to 24 hours"));
        assertFalse(data.contains("cleared by this update"));
    }

    @Test
    public void acceptanceRateRiskIsTheFirstPointAndRequiresExplicitAcknowledgement() {
        assertEquals("Acceptance rate.", Consent.POINTS[0][0]);
        assertEquals("Automatic declines may dramatically lower your DoorDash acceptance rate. "
                + "Only continue if you understand and accept that risk.", Consent.POINTS[0][1]);
        assertEquals("I understand and accept", Consent.ACCEPT);
        assertTrue(Consent.AGREEMENT.contains("acknowledge this acceptance-rate risk"));
        assertTrue(Consent.AGREEMENT.contains("at your own risk"));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            TextView risk = shownTextContaining(content, Consent.POINTS[0][1]);
            assertNotNull("the actual warning is shown, not only its heading", risk);
            LinearLayout body = (LinearLayout) risk.getParent();
            assertEquals("the warning precedes every other notice point", 0, body.indexOfChild(risk));
            assertNotNull(shownTextContaining(content, Consent.AGREEMENT));
            assertFalse("opening or reading the warning accepts nothing", Consent.accepted(app));
            shownButton(content, "I understand and accept").performClick();
            idle();
            assertTrue(Consent.accepted(app));
            assertHomepageShown(content);
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h400dp-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void explicitAcknowledgementAndNotNowRemainReadableInShortLargeTextWindows() {
        RuntimeEnvironment.setFontScale(2f);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            int width = Math.round(320 * app.getResources().getDisplayMetrics().density);
            int height = Math.round(400 * app.getResources().getDisplayMetrics().density);
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            content.layout(0, 0, width, height);
            Button accept = shownButton(content, Consent.ACCEPT);
            Button notNow = shownButton(content, Consent.NOT_NOW);
            assertEquals("both actions are in the scrolling notice", accept.getParent(), notNow.getParent());
            LinearLayout body = (LinearLayout) accept.getParent();
            assertEquals(LinearLayout.VERTICAL, body.getOrientation());
            assertTrue("the actions never overlap", accept.getTop() >= notNow.getBottom());
            for (Button action : new Button[] {notNow, accept}) {
                assertEquals("each label gets the full body width", body.getWidth() - body.getPaddingLeft()
                        - body.getPaddingRight(), action.getWidth());
                assertNotNull(action.getLayout());
                for (int line = 0; line < action.getLayout().getLineCount(); line++) {
                    assertEquals("no acknowledgement words are ellipsized", 0,
                            action.getLayout().getEllipsisCount(line));
                }
                assertTrue("all lines fit vertically", action.getLayout().getHeight()
                        <= action.getHeight() - action.getCompoundPaddingTop() - action.getCompoundPaddingBottom());
            }
            ScrollView scroll = (ScrollView) ((View) body.getParent()).getParent();
            assertNotNull(scroll);
            assertTrue("the long notice can scroll to its actions", scroll.getChildAt(0).getHeight() > scroll.getHeight());
            assertFalse("layout and scrolling never imply consent", Consent.accepted(app));
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    @Test
    public void theHomepageShowsOnlyTheNoticeUntilIUnderstandWhichIsKept() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNoticeShown(content);
            assertTrue("an update waits while the notice is read", activity.get().midTask());

            shownButton(content, Consent.ACCEPT).performClick();
            idle();

            assertTrue(Consent.accepted(app));
            assertHomepageShown(content);
            assertFalse(activity.get().midTask());
        }
        SharedPreferences kept = app.getSharedPreferences(Consent.PREFS, android.content.Context.MODE_PRIVATE);
        assertEquals("the notice's version is kept", Consent.VERSION, kept.getInt(Consent.ACCEPTED_VERSION, 0));
        assertTrue(kept.getLong(Consent.ACCEPTED_AT, 0) > 0);
        assertTrue(DiagnosticLog.read(app).contains("notice " + Consent.VERSION + " accepted"));

        // Opened again, and recreated (a rotation, day and night): straight to the homepage.
        try (ActivityController<MainActivity> again = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertHomepageShown(again.get().findViewById(android.R.id.content));
            again.recreate();
            assertHomepageShown(again.get().findViewById(android.R.id.content));
        }
    }

    @Test
    public void notNowClosesTheAppAndTheNoticeComesAgain() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            shownButton(content, Consent.NOT_NOW).performClick();
            assertTrue("Not now closes the app", activity.get().isFinishing());
            assertFalse(Consent.accepted(app));
            assertNoAcceptanceStored();
        }
        // Back from the notice is Not now too, and it comes again each time until accepted.
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNoticeShown(activity.get().findViewById(android.R.id.content));
            activity.get().onBackPressed();
            assertTrue(activity.get().isFinishing());
            assertFalse(Consent.accepted(app));
            assertNoAcceptanceStored();
        }
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNoticeShown(activity.get().findViewById(android.R.id.content));
        }
    }

    @Test
    public void anExistingInstallSeesTheNoticeOnceAfterThisUpdate() {
        // A phone in use before the notice existed: rules on, offers in its history, the map and sound settings.
        FilterStore.save(app, new FilterSettings(true, 2000, 150, 0, 0, 3));
        FilterStore.setSilenceWhileDeclining(app, true);
        DecisionLog.record(app, declinedEntry());
        // Version 12 did not distinguish outbox/history retention or fully explain report recipients.
        assertEquals(13, Consent.VERSION);
        app.getSharedPreferences(Consent.PREFS, android.content.Context.MODE_PRIVATE).edit()
                .putInt(Consent.ACCEPTED_VERSION, 12).commit();
        assertFalse("the old notice cannot authorize filtering after this update", Consent.accepted(app));

        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNoticeShown(content);
            assertNull("the history waits behind it", find(content, OfferCardView.class));
            shownButton(content, Consent.ACCEPT).performClick();
            idle();
            assertHomepageShown(content);
        }
        assertEquals("its rules are untouched", 2000, FilterStore.load(app).flatCents);
        assertTrue(FilterStore.load(app).enabled);
        for (int open = 0; open < 2; open++) {
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                assertHomepageShown(activity.get().findViewById(android.R.id.content));
            }
        }
    }

    // ---- The bundled texts ----

    @Test
    public void theNoticeLinksOpenTheTermsAndThePrivacyText() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNotNull(shownTextContaining(content, Consent.AGREEMENT));
            for (LegalTexts.Doc doc : new LegalTexts.Doc[] {LegalTexts.Doc.TERMS, LegalTexts.Doc.PRIVACY}) {
                shownButton(content, doc.title).performClick();
                assertOpens(doc);
            }
            assertFalse("reading them accepts nothing", Consent.accepted(app));
            assertNoticeShown(content);
        }
    }

    @Test
    public void theSettingsFooterLinksOpenTheBundledTexts() {
        Consent.accept(app);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            iconButton(content, "Settings").performClick();
            idle();
            assertNotNull(shownTextContaining(content, "Not a DoorDash app."));
            for (LegalTexts.Doc doc : LegalTexts.Doc.values()) {
                Button link = shownButton(content, doc.title);
                assertNotNull(doc.title, link);
                link.performClick();
                assertOpens(doc);
            }
        }
    }

    // ---- Helpers ----

    private void assertNoAcceptanceStored() {
        SharedPreferences prefs = app.getSharedPreferences(Consent.PREFS, android.content.Context.MODE_PRIVATE);
        assertFalse(prefs.contains(Consent.ACCEPTED_VERSION));
        assertFalse(prefs.contains(Consent.ACCEPTED_AT));
    }

    /** The page the last tap opened is {@code doc}'s, and it shows that text with no connection. */
    private void assertOpens(LegalTexts.Doc doc) {
        Intent started = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull(doc.title + " opens a page", started);
        assertEquals(LegalActivity.class.getName(), started.getComponent().getClassName());
        assertEquals(doc.name(), started.getStringExtra(LegalActivity.DOC));
        try (ActivityController<LegalActivity> page = Robolectric.buildActivity(LegalActivity.class, started).setup()) {
            View shown = page.get().findViewById(android.R.id.content);
            assertNotNull(findText(shown, doc.title));
            if (doc != LegalTexts.Doc.LICENSE) {
                assertNotNull(shownTextContaining(shown, "Not legal advice; have a lawyer review before public release."));
            }
            String line = doc == LegalTexts.Doc.TERMS ? "Auto-accept starts off."
                    : doc == LegalTexts.Doc.PRIVACY ? "positions rounded to about half a kilometre"
                    : "Permission is hereby granted, free of charge";
            assertNotNull(doc.title + ": " + line, shownTextContaining(shown, line));
            iconButton(shown, "Back").performClick();
            assertTrue("Back closes it", page.get().isFinishing());
        }
    }

    private void assertNoticeShown(View content) {
        assertNotNull(shownTextContaining(content, Consent.TITLE));
        for (String[] point : Consent.POINTS) assertNotNull(point[0], shownTextContaining(content, point[0]));
        assertNotNull(shownButton(content, Consent.ACCEPT));
        assertNotNull(shownButton(content, Consent.NOT_NOW));
        assertNull("nothing of the homepage", shownIcon(content, "Settings"));
        assertNull(shownIcon(content, Appearance.resolve(app).description()));
        assertFalse(find(content, FilterHeroView.class).isShown());
        assertFalse(find(content, MinimumsStarView.class).isShown());
    }

    private void assertHomepageShown(View content) {
        assertNull(shownTextContaining(content, Consent.TITLE));
        assertNull(shownButton(content, Consent.ACCEPT));
        assertNotNull(shownIcon(content, "Settings"));
        assertTrue(find(content, FilterHeroView.class).isShown());
    }

    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName("com.doordash.driverapp");
        node.setText(text);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        node.setClickable(clickable);
        if (clickable) {
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(node).setOnPerformActionListener((action, args) -> true);
        }
        return node;
    }

    /** An offer screen showing {@code money}; {@code buttons} gets its Accept and Decline. */
    private AccessibilityNodeInfo offer(String money, AccessibilityNodeInfo[] buttons) {
        AccessibilityNodeInfo root = node("", false);
        buttons[0] = node("Accept", true);
        buttons[1] = node("Decline", true);
        Shadows.shadowOf(root).addChild(node(money, false));
        Shadows.shadowOf(root).addChild(node("2 stops (7.2 mi) • 21 min", false));
        Shadows.shadowOf(root).addChild(buttons[0]);
        Shadows.shadowOf(root).addChild(buttons[1]);
        return root;
    }

    private static void show(OfferFilterService service, AccessibilityNodeInfo root) {
        TestWindows.full(service, root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        service.onAccessibilityEvent(event);
        idle();
    }

    private static java.util.List<View> windows(OfferFilterService service) {
        ShadowWindowManagerImpl windows = Shadow.extract(service.getSystemService(WindowManager.class));
        return windows.getViews();
    }

    /** A DoorDash offer notification on its own key, posted now. */
    private StatusBarNotification offerNotification(String tag, String text) {
        Notification payload = new Notification.Builder(app, "source")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText(text)
                .build();
        return new StatusBarNotification("com.doordash.driverapp", "com.doordash.driverapp", tag.hashCode(), tag,
                10001, 0, 0, payload, android.os.Process.myUserHandle(), System.currentTimeMillis());
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
    }
}
