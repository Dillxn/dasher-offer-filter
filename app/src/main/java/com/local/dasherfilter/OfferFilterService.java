package com.local.dasherfilter;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OfferFilterService extends AccessibilityService {
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastScanAt;
    private long lastAttemptAt;
    private long confirmationUntil;
    private String lastAttemptKey = "";
    private String pendingKey = "";
    private String lastStatus = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null ||
                !DASHER_PACKAGE.contentEquals(event.getPackageName())) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastScanAt < 250) return;
        lastScanAt = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null ||
                !DASHER_PACKAGE.contentEquals(root.getPackageName())) return;
        Scan scan = new Scan();
        scan(root, scan, 0);

        if (confirmationUntil > now && scan.accept == null && scan.decline != null &&
                containsDeclinePrompt(scan.text)) {
            if (click(scan.decline)) status("Decline confirmation tapped.");
            confirmationUntil = 0;
            return;
        }
        if (scan.accept == null || scan.decline == null) return;

        OfferSnapshot offer = OfferParser.parse(scan.text);
        if (offer.payCents == null) {
            status("Offer visible, but payout could not be read safely. No action.");
            return;
        }
        FilterSettings settings = FilterStore.load(this);
        OfferRule.Decision decision = OfferRule.evaluate(offer, settings);
        status(offer.summary() + "\n" + decision.summary() +
                (settings.enabled ? "" : "\nAuto-decline is off."));
        if (!settings.enabled || decision.result != OfferRule.Result.DECLINE) return;

        String key = offer.fingerprint();
        if (key.equals(pendingKey) ||
                (key.equals(lastAttemptKey) && now - lastAttemptAt < 30000)) return;
        pendingKey = key;
        handler.postDelayed(() -> recheckAndDecline(key), 700);
    }

    private void recheckAndDecline(String key) {
        pendingKey = "";
        FilterSettings settings = FilterStore.load(this);
        if (!settings.enabled) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null ||
                !DASHER_PACKAGE.contentEquals(root.getPackageName())) return;
        Scan scan = new Scan();
        scan(root, scan, 0);
        if (scan.accept == null || scan.decline == null) return;
        OfferSnapshot offer = OfferParser.parse(scan.text);
        if (!key.equals(offer.fingerprint()) ||
                OfferRule.evaluate(offer, settings).result != OfferRule.Result.DECLINE) return;
        if (click(scan.decline)) {
            lastAttemptKey = key;
            lastAttemptAt = SystemClock.uptimeMillis();
            confirmationUntil = lastAttemptAt + 3000;
            status("Decline tapped for " + offer.summary());
        } else {
            status("Could not tap Decline. No further action.");
        }
    }

    private static boolean containsDeclinePrompt(List<String> text) {
        for (String line : text) {
            String lower = line.toLowerCase(Locale.US);
            if (lower.contains("decline this") || lower.contains("decline offer") ||
                    lower.contains("decline order") || lower.contains("are you sure")) return true;
        }
        return false;
    }

    private static boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 4 && current != null; i++, current = current.getParent()) {
            if (current.isClickable()) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    private static void scan(AccessibilityNodeInfo node, Scan result, int depth) {
        if (node == null || depth > 25 || result.visited++ > 250) return;
        if (node.isVisibleToUser()) {
            addLabel(result, node, node.getText());
            addLabel(result, node, node.getContentDescription());
            for (int i = 0; i < node.getChildCount(); i++) {
                scan(node.getChild(i), result, depth + 1);
            }
        }
    }

    private static void addLabel(Scan result, AccessibilityNodeInfo node, CharSequence value) {
        if (value == null) return;
        String label = value.toString().trim();
        if (label.isEmpty()) return;
        if (!result.text.contains(label)) result.text.add(label);
        if (result.accept == null && isButton(label, "accept")) result.accept = node;
        if (result.decline == null && isButton(label, "decline")) result.decline = node;
    }

    private static boolean isButton(String label, String verb) {
        String normalized = label.trim().toLowerCase(Locale.US);
        if (normalized.equals(verb) || normalized.equals(verb + " offer") ||
                normalized.equals(verb + " order")) return true;
        // Dasher may append the offer countdown to the Accept label.
        return verb.equals("accept") && normalized.matches(
                "accept(?: offer| order)?\\s*[(:·]?\\s*(?:\\d{1,2}|\\d{1,2}:\\d{2})" +
                        "\\s*(?:s|sec|seconds)?\\s*\\)?");
    }

    private void status(String message) {
        if (!message.equals(lastStatus)) {
            lastStatus = message;
            FilterStore.setLastStatus(this, message);
        }
    }

    @Override
    public void onInterrupt() { }

    private static final class Scan {
        final List<String> text = new ArrayList<>();
        AccessibilityNodeInfo accept;
        AccessibilityNodeInfo decline;
        int visited;
    }
}
