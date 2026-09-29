package com.local.dasherfilter;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OfferFilterService extends AccessibilityService {
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private static boolean connected;
    private static volatile OfferFilterService active;
    private final DeclineState declineState = new DeclineState();
    private final AcceptedOfferTracker acceptedTracker = new AcceptedOfferTracker();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long recheckUntil;
    private final Runnable recheck = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            boolean keepChecking = checkOffer();
            boolean notificationWake = OfferNotificationService.hasRecentPendingWake(now);
            if ((now < recheckUntil || declineState.hasPendingConfirmation(now) || notificationWake) &&
                    (keepChecking || notificationWake)) {
                handler.postDelayed(this, 200);
            }
        }
    };
    private String lastStatus = "";
    private String lastDiagnosticSignature = "";

    static boolean isConnected() { return connected; }

    static boolean isDasherForeground() {
        OfferFilterService service = active;
        if (service == null) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return root != null && root.getPackageName() != null &&
                DASHER_PACKAGE.contentEquals(root.getPackageName());
    }

    static void requestCheckFromNotification() {
        OfferFilterService service = active;
        if (service == null) return;
        service.handler.post(() -> {
            service.handler.removeCallbacks(service.recheck);
            service.recheckUntil = Math.max(service.recheckUntil, SystemClock.uptimeMillis() + 5000);
            service.handler.post(service.recheck);
        });
    }

    @Override
    protected void onServiceConnected() {
        connected = true;
        active = this;
        Updater.schedule(this);
        Updater.check(this, false, null);
        DiagnosticLog.log(this, "accessibility", "service connected");
        status("Accessibility connected. Waiting for a Dasher offer.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        boolean windowChange = event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        if (!windowChange && (event.getPackageName() == null ||
                !DASHER_PACKAGE.contentEquals(event.getPackageName()))) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED && isAcceptClick(event)) {
            acceptedTracker.acceptClicked(SystemClock.uptimeMillis());
            DiagnosticLog.log(this, "accessibility", "observed Accept click");
        }
        handler.removeCallbacks(recheck);
        recheckUntil = SystemClock.uptimeMillis() + 3000;
        if (checkOffer()) handler.postDelayed(recheck, 200);
    }

    private boolean checkOffer() {
        long now = SystemClock.uptimeMillis();
        FilterSettings settings = FilterStore.load(this);
        if (!settings.enabled) declineState.reset();

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            status("Dasher reported a change, but the screen is not readable yet.");
            return settings.enabled;
        }
        if (root.getPackageName() == null || !DASHER_PACKAGE.contentEquals(root.getPackageName())) {
            declineState.reset();
            acceptedTracker.reset();
            return false;
        }
        Scan scan = new Scan();
        scan(root, scan, 0);
        if (scan.truncated) {
            diagnosticScan("truncated", scan, null, null);
            status("The offer screen could not be read completely. No action.");
            return settings.enabled;
        }

        Scan confirmation = confirmationScan(scan, declineState.hasPendingConfirmation(now));
        if (confirmation != null) {
            diagnosticScan("confirmation", confirmation, null, null);
            if (!settings.enabled || !declineState.hasPendingConfirmation(now)) {
                status("Decline confirmation visible, but no recent automatic decline is pending. No action.");
                return false;
            }
            int selected = DeclineConfirmation.select(confirmation.text, confirmation.declineLabels,
                    confirmation.hasAcceptLabel);
            if (selected < 0) {
                status("Decline confirmation visible, but its action button is not clickable yet. Retrying.");
            } else if (declineState.mayConfirm(now)) {
                if (click(confirmation.declineTargets.get(selected))) {
                    declineState.confirmationSent(now);
                    DiagnosticLog.log(this, "accessibility", "second-step Decline offer click requested");
                    status("Second-step Decline offer requested; checking whether the confirmation closes.");
                } else {
                    status("Could not tap the second-step Decline offer button. Retrying while it is visible.");
                }
            }
            return true;
        }
        OfferSnapshot offer = OfferParser.parse(scan.text, scan.metricParts);
        if (scan.accept == null || scan.decline == null) {
            diagnosticScan("screen-without-both-controls", scan, offer, null);
            AcceptedOfferTracker.Acceptance accepted = acceptedTracker.observeOtherScreen(scan.text, now);
            if (accepted != null) {
                Integer baseline = accepted.baselinePay();
                if (baseline != null) FilterStore.recordAccepted(this, baseline);
                ActiveRouteStore.save(this, accepted.routeAfter);
                status((accepted.addOn ? "Accepted add-on detected. Active route updated: " :
                        "Accepted offer detected. Active route: ") +
                        (accepted.routeAfter == null ? "unknown" : accepted.routeAfter.summary()) +
                        (baseline == null ? "" : String.format(Locale.US,
                                "\nRising rule baseline: $%.2f.", baseline / 100.0)));
                return false;
            }
            if (ActiveRouteStore.isIdleScreen(scan.text)) {
                ActiveRouteStore.clear(this);
                DiagnosticLog.log(this, "route", "active route cleared on idle Dasher screen");
            }
            if (scan.accept == null && scan.decline == null) declineState.offerGone();
            if (scan.accept != null || scan.decline != null || offer.payCents != null) {
                status(offer.summary() + "\nNo action: " +
                        (scan.accept == null ? "Accept" : "") +
                        (scan.accept == null && scan.decline == null ? " and " : "") +
                        (scan.decline == null ? "Decline" : "") + " control not found.");
            }
            return settings.enabled;
        }

        OfferSnapshot activeRoute = ActiveRouteStore.load(this);
        AddOnOffer addOn = activeRoute != null && AddOnOffer.isLikely(scan.text)
                ? AddOnOffer.parse(activeRoute, offer, scan.text) : null;
        acceptedTracker.observeOffer(offer, addOn == null ? offer : addOn.combined,
                addOn != null, now);

        OfferRule.Decision decision = addOn == null
                ? OfferRule.evaluate(offer, settings)
                : OfferRule.evaluateAddOn(addOn, settings);
        diagnosticScan(addOn == null ? "offer" : "add-on", scan,
                addOn == null ? offer : addOn.incremental, decision);
        String evaluated = addOn == null ? offer.summary() : addOn.summary();
        OfferNotificationService.screenResolved(this, decision.result,
                evaluated + "; " + decision.summary());
        if (!settings.enabled || decision.result != OfferRule.Result.DECLINE) {
            status((addOn == null ? offer.summary() : addOn.summary()) + "\n" + decision.summary() +
                    (settings.enabled ? "" : "\nAuto-decline is off."));
            return settings.enabled && decision.result == OfferRule.Result.REVIEW;
        }
        String key = DeclineState.offerKey(offer, scan.text);
        if (!declineState.mayDecline(key, now)) return true;
        // First attempt is immediate. Rechecks always read the current screen and saved rules.
        if (click(scan.decline)) {
            declineState.declineSent(key, now);
            String declined = addOn == null ? offer.summary() : addOn.summary();
            OfferNotificationService.cancelPendingFiltered(this, declined);
            DiagnosticLog.log(this, "accessibility", "first-step Decline click requested for " + declined);
            status("Decline requested for " + declined + "\n" + decision.summary() +
                    "\nChecking whether the offer leaves the screen.");
        } else {
            status((addOn == null ? offer.summary() : addOn.summary()) + "\n" + decision.summary() +
                    "\nCould not tap Decline. Retrying while this offer is visible.");
        }
        return true;
    }

    private Scan confirmationScan(Scan primary, boolean pending) {
        if (pending) {
            // Android lists interactive windows from top to bottom. Search the popup first.
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null || root.getPackageName() == null ||
                        !DASHER_PACKAGE.contentEquals(root.getPackageName())) continue;
                Scan candidate = new Scan();
                scan(root, candidate, 0);
                if (!candidate.truncated && DeclineConfirmation.isSurface(candidate.text, candidate.hasAcceptLabel)) {
                    return candidate;
                }
            }
        }
        return DeclineConfirmation.isSurface(primary.text, primary.hasAcceptLabel) ? primary : null;
    }

    private static boolean isAcceptClick(AccessibilityEvent event) {
        for (CharSequence label : event.getText()) {
            if (label != null && OfferControls.isButton(label.toString(), "accept")) return true;
        }
        if (event.getContentDescription() != null &&
                OfferControls.isButton(event.getContentDescription().toString(), "accept")) return true;
        AccessibilityNodeInfo node = event.getSource();
        for (int depth = 0; depth < 4 && node != null; depth++, node = node.getParent()) {
            if (node.getText() != null && OfferControls.isButton(node.getText().toString(), "accept")) return true;
            if (node.getContentDescription() != null &&
                    OfferControls.isButton(node.getContentDescription().toString(), "accept")) return true;
        }
        return false;
    }

    private static boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 8 && current != null; i++, current = current.getParent()) {
            if (hasClickAction(current) && current.isEnabled() && current.isVisibleToUser()) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
        }
        return false;
    }

    private static boolean hasClickAction(AccessibilityNodeInfo node) {
        return node.isClickable() || node.getActionList().contains(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
    }

    private static AccessibilityNodeInfo clickTarget(AccessibilityNodeInfo node) {
        for (int depth = 0; depth < 8 && node != null; depth++, node = node.getParent()) {
            if (node.isVisibleToUser() && node.isEnabled() && hasClickAction(node)) return node;
        }
        return null;
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
        if (OfferControls.isButton(label, "accept")) {
            result.hasAcceptLabel = true;
            AccessibilityNodeInfo target = clickTarget(node);
            if (result.accept == null && target != null) result.accept = target;
        }
        if (OfferControls.isButton(label, "decline")) {
            AccessibilityNodeInfo target = clickTarget(node);
            if (target != null) {
                if (result.decline == null) result.decline = target;
                result.declineLabels.add(label);
                result.declineTargets.add(target);
            }
        }
    }

    private void diagnosticScan(String phase, Scan scan, OfferSnapshot offer, OfferRule.Decision decision) {
        if (!DiagnosticLog.isEnabled(this)) return;
        String message = phase + " labels=" + scan.text + " metricParts=" + scan.metricParts +
                " accept=" + (scan.accept != null) + " decline=" + (scan.decline != null) +
                (offer == null ? "" : " parsed={" + offer.summary() + "}") +
                (decision == null ? "" : " decision={" + decision.summary() + "}");
        if (message.equals(lastDiagnosticSignature)) return;
        lastDiagnosticSignature = message;
        DiagnosticLog.log(this, "screen", message);
    }

    private void status(String message) {
        if (!message.equals(lastStatus)) {
            lastStatus = message;
            FilterStore.setLastStatus(this, message);
            DiagnosticLog.log(this, "status", message);
        }
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(recheck);
        declineState.reset();
        acceptedTracker.reset();
        DiagnosticLog.log(this, "accessibility", "service interrupted");
        status("Android interrupted Offer Filter. Waiting for the next Dasher screen change.");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        active = null;
        handler.removeCallbacks(recheck);
        declineState.reset();
        DiagnosticLog.log(this, "accessibility", "service unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        active = null;
        handler.removeCallbacks(recheck);
        declineState.reset();
        DiagnosticLog.log(this, "accessibility", "service destroyed");
        super.onDestroy();
    }

    private static final class Scan {
        final List<String> text = new ArrayList<>();
        final List<String> metricParts = new ArrayList<>();
        AccessibilityNodeInfo accept;
        boolean hasAcceptLabel;
        AccessibilityNodeInfo decline;
        final List<String> declineLabels = new ArrayList<>();
        final List<AccessibilityNodeInfo> declineTargets = new ArrayList<>();
        int visited;
        boolean truncated;
    }
}
