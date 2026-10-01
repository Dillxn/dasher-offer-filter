package com.local.dasherfilter;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Screen text is masked as it is kept (the two logs, the decision history's read lines) and in the shared report,
 * while the screen reader still decides from the raw labels. Drives OfferFilterService with invented Dasher screens.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class MaskedCaptureAdapterTest {
    private Application app;
    private ServiceController<OfferFilterService> controller;
    private AccessibilityNodeInfo decline;
    private AccessibilityNodeInfo accept;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        DiagnosticLog.setEnabled(app, true);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DecisionLog.forgetCache();
        ReportOutbox.forgetCache();
        OfferSilencer.forgetCache();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void stop() {
        controller.destroy();
        OfferFilterService.scanLooperForTests = null;
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

    private AccessibilityNodeInfo screen(List<String> labels) {
        AccessibilityNodeInfo root = node("", false);
        for (String label : labels) Shadows.shadowOf(root).addChild(node(label, false));
        return root;
    }

    private void show(AccessibilityNodeInfo root) {
        Shadows.shadowOf(controller.get()).setRootInActiveWindow(root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        controller.get().onAccessibilityEvent(event);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void assertNone(String kept, String... raw) {
        for (String text : raw) assertFalse("\"" + text + "\" was kept: " + kept, kept.contains(text));
    }

    @Test
    public void anOfferIsDeclinedFromItsRawLabelsButKeptMasked() {
        List<String> labels = Arrays.asList("Deliver to Sam P", "$7.90", "2 stops (7.2 mi) • 21 min",
                "100 Example St", "Springfield, OH 45000", "McDonald's (32059-SOMEWHERE)");
        AccessibilityNodeInfo root = screen(labels);
        accept = node("Accept", true);
        decline = node("Decline", true);
        Shadows.shadowOf(root).addChild(accept);
        Shadows.shadowOf(root).addChild(decline);

        show(root);

        // The decision read the screen as it was: $7.90 over 7.2 mi misses the $20 minimum.
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertTrue(Shadows.shadowOf(accept).getPerformedActions().isEmpty());
        DecisionLog.Entry entry = DecisionLog.recent(app, 1).get(0);
        assertEquals(OfferRule.Result.DECLINE, entry.result);
        assertEquals(Integer.valueOf(790), entry.facts.payCents);
        assertEquals(Double.valueOf(7.2), entry.facts.miles);
        assertEquals(OfferRule.evaluate(OfferParser.parse(labels, Collections.<String>emptyList()),
                FilterStore.load(app)).result, entry.result);

        // What was kept of it is masked; the store and the offer's figures stay.
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("Deliver to [name]"));
        assertTrue(log, log.contains("[address], [address]"));
        assertTrue(log, log.contains("McDonald's (32059-SOMEWHERE)"));
        assertTrue(log, log.contains("$7.90") && log.contains("2 stops (7.2 mi) • 21 min"));
        assertNone(log, "Sam P", "Example St", "Springfield", "45000");
    }

    @Test
    public void dashersOtherScreensAreKeptMasked() {
        show(screen(Arrays.asList("Deliver to Sam P", "by 2:39 AM", "Call", "Message", "100 Example St",
                "Springfield, OH 45000", "Leave it at the door", "\"100 Example St ..... pls leave it at the desk\"",
                "McDonald's (32059-SOMEWHERE) (#e34ffd29)")));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        show(screen(Arrays.asList("Sam P", "651 orders completed", "[icon] Pro Shopper", "Home")));

        String screens = DiagnosticLog.readScreens(app);
        assertTrue(screens, screens.contains("[Deliver to [name], by 2:39 AM, Call, Message, [address], [address], "
                + "Leave it at the door, [instructions], McDonald's (32059-SOMEWHERE) (#e34ffd29)]"));
        assertTrue(screens, screens.contains("[[name], 651 orders completed, [icon] Pro Shopper, Home]"));
        assertNone(screens, "Sam P", "Example", "pls leave", "45000");
    }

    @Test
    public void theDecisionHistoryKeepsItsReadLinesMasked() {
        assertEquals(Arrays.asList("Deliver to [name] · $7.90", "$7.90", "[name]'s order · 2.1 mi"),
                DecisionLog.evidence(Arrays.asList("Deliver to Sam P · $7.90", "$7.90", "Tiaunna's order · 2.1 mi",
                        "100 Example St")));

        // A line kept before masking (written as an older version did) is masked when the history is read.
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(910, 3.0, 15, 2), 2000, OfferRule.Result.DECLINE, "below the minimum",
                DecisionLog.Action.DECLINE_TAPPED, true, Arrays.asList("Deliver to Noel G · $9.10")));
        DecisionLog.flush();
        DecisionLog.forgetCache();
        assertEquals(Collections.singletonList("Deliver to [name] · $9.10"),
                DecisionLog.recent(app, 1).get(0).evidence);
    }

    @Test
    public void theSharedReportIsMaskedEvenForLinesKeptBeforeMasking() throws IOException {
        // Lines an older version wrote raw, kept up to a day.
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.US).format(new Date());
        try (OutputStream out = new FileOutputStream(new File(app.getFilesDir(), "offer-filter-diagnostics.log"))) {
            out.write((now + " [screen] other labels=[Deliver to Noel G, 4201 Example Parkway, Call (513) 555-0100,"
                    + " Leave at my door: gate code 1234] metricParts=[]\n").getBytes(StandardCharsets.UTF_8));
        }
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(910, 3.0, 15, 2), 2000, OfferRule.Result.DECLINE, "below the minimum",
                DecisionLog.Action.DECLINE_TAPPED, true, Arrays.asList("Deliver to Noel G · $9.10")));
        FilterStore.setLastStatus(app, "Deliver to Noel G · $9.10");

        String report = DiagnosticLog.report(app);

        assertTrue(report, report.contains("Screen text is masked on the phone"));
        assertTrue(report, report.contains("other labels=[Deliver to [name], [address], Call [phone], "
                + "Leave at my door: [instructions]]"));
        assertTrue(report, report.contains("read: [Deliver to [name] · $9.10]"));
        assertNone(report, "Noel", "Example Parkway", "555-0100", "gate code");
    }
}
