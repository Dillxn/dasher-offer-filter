package com.local.dasherfilter;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OfferFilterService extends AccessibilityService {
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private final DeclineState declineState = new DeclineState();
    private String lastStatus = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null ||
                !DASHER_PACKAGE.contentEquals(event.getPackageName())) return;
        long now = SystemClock.uptimeMillis();

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null ||
                !DASHER_PACKAGE.contentEquals(root.getPackageName())) return;
        Scan scan = new Scan();
        scan(root, scan, 0);
        if (scan.truncated) {
            status("The offer screen could not be read completely. No action.");
            return;
        }
        FilterSettings settings = FilterStore.load(this);
        if (!settings.enabled) declineState.reset();

        if (settings.enabled && declineState.mayConfirm(now) &&
                scan.accept == null && scan.decline != null &&
                containsDeclinePrompt(scan.text)) {
            if (click(scan.decline)) {
                declineState.confirmationSent();
                status("Decline confirmation tapped.");
            }
            return;
        }
        if (scan.accept == null && scan.decline == null) declineState.offerGone();
        if (scan.accept == null || scan.decline == null) return;

        OfferSnapshot offer = OfferParser.parse(scan.text, scan.metricParts);
        OfferRule.Decision decision = OfferRule.evaluate(offer, settings);
        if (!settings.enabled || decision.result != OfferRule.Result.DECLINE) {
            status(offer.summary() + "\n" + decision.summary() +
                    (settings.enabled ? "" : "\nAuto-decline is off."));
            return;
        }
        String key = DeclineState.offerKey(offer, scan.text);
        if (!declineState.mayDecline(key)) return;
        // Act on this visible offer immediately. No timer, debounce, alarm, or vibration.
        if (click(scan.decline)) {
            declineState.declineSent(key, now);
            status("Decline tapped for " + offer.summary() + "\n" + decision.summary());
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
            if (current.isClickable() && current.isEnabled() && current.isVisibleToUser()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
        return false;
    }

    private static String scan(AccessibilityNodeInfo node, Scan result, int depth) {
        if (node == null) return "";
        if (depth > 60 || result.visited++ >= 1500) {
            result.truncated = true;
            return "";
        }
        String ownLabel = "";
        if (node.isVisibleToUser()) {
            addLabel(result, node, node.getText());
            addLabel(result, node, node.getContentDescription());
            ownLabel = node.getText() == null ? "" : node.getText().toString().trim();
            if (ownLabel.isEmpty() && node.getContentDescription() != null) {
                ownLabel = node.getContentDescription().toString().trim();
            }
        }
        List<String> siblings = new ArrayList<>();
        int children = node.getChildCount();
        // A container can be invisible while some of its children are visible.
        for (int i = 0; i < children; i++) {
            if (result.truncated) break;
            siblings.add(scan(node.getChild(i), result, depth + 1));
        }
        result.metricParts.addAll(OfferParser.joinMetricSiblings(siblings));
        // A single-child layout wrapper does not split the metric row.
        return ownLabel.isEmpty() && children == 1 && siblings.size() == 1
                ? siblings.get(0) : ownLabel;
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
    public void onInterrupt() { declineState.reset(); }

    private static final class Scan {
        final List<String> text = new ArrayList<>();
        final List<String> metricParts = new ArrayList<>();
        AccessibilityNodeInfo accept;
        AccessibilityNodeInfo decline;
        int visited;
        boolean truncated;
    }
}
