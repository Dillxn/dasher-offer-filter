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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Reads only the visible Dasher window and may request Decline on a readable, known-failing offer. A notification
 * is never authority to click another offer, and a successful click is a request, not a confirmed decline.
 */
public final class OfferFilterService extends AccessibilityService {
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private static final long RECHECK_WINDOW_MS = 3000;
    private static final long RECHECK_INTERVAL_MS = 200;
    private static final int MAX_SCAN_DEPTH = 60;
    private static final int MAX_SCAN_NODES = 1500;
    private static final int MAX_CLICK_TARGET_ANCESTORS = 8;
    private static final int MAX_ACCEPT_LABEL_ANCESTORS = 4;
    /** Taps that prove delivery progress, making stored travel estimates stale. */
    private static final List<String> PROGRESS_TAPS = Arrays.asList(
            "confirm pickup", "complete pickup", "complete delivery", "confirm dropoff");

    private static volatile OfferFilterService active;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DeclineState declineState = new DeclineState();
    private final AcceptedOfferTracker acceptedTracker = new AcceptedOfferTracker();
    private long recheckUntil;
    /** Notification generation when the last decline was requested; a newer offer revokes confirmation. */
    private long declineGeneration;
    private String lastStatus = "";
    private String lastDiagnosticSignature = "";

    /** Re-scans while a screen is still settling or a decline confirmation is pending. */
    private final Runnable recheck = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            boolean more = checkOffer();
            if (more && (now < recheckUntil || declineState.hasPendingConfirmation(now))) {
                handler.postDelayed(this, RECHECK_INTERVAL_MS);
            }
        }
    };

    static boolean isConnected() {
        return active != null;
    }

    static boolean isDasherForeground() {
        OfferFilterService service = active;
        if (service == null) return false;
        try {
            return isDasher(service.getRootInActiveWindow());
        } catch (RuntimeException error) {
            return false;
        }
    }

    /** Asks for a fresh screen read, e.g. after a notification or a rules change. Never opens Dasher. */
    static void requestCheckFromNotification() {
        OfferFilterService service = active;
        if (service == null) return;
        service.handler.post(() -> {
            service.handler.removeCallbacks(service.recheck);
            service.recheckUntil = SystemClock.uptimeMillis() + RECHECK_WINDOW_MS;
            service.handler.post(service.recheck);
        });
    }

    @Override protected void onServiceConnected() {
        active = this;
        Updater.schedule(this);
        DiagnosticLog.log(this, "accessibility", "connected; no automatic activity launches");
        status("Accessibility connected. Only visible offer screens can be fully evaluated.");
        Updater.check(this, false, null);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        boolean windowsChanged = event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        if (!windowsChanged && !isDasherPackage(event.getPackageName())) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) observeClick(event);
        handler.removeCallbacks(recheck);
        recheckUntil = SystemClock.uptimeMillis() + RECHECK_WINDOW_MS;
        if (checkOffer()) handler.postDelayed(recheck, RECHECK_INTERVAL_MS);
    }

    @Override public void onInterrupt() {
        handler.removeCallbacks(recheck);
        declineState.reset();
        acceptedTracker.reset();
        status("Accessibility interrupted; waiting for a new readable offer.");
    }

    @Override public boolean onUnbind(Intent intent) {
        stop();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        stop();
        super.onDestroy();
    }

    private void stop() {
        if (active == this) active = null;
        handler.removeCallbacksAndMessages(null);
        declineState.reset();
        acceptedTracker.reset();
    }

    private void observeClick(AccessibilityEvent event) {
        if (isAcceptClick(event)) acceptedTracker.acceptClicked(SystemClock.uptimeMillis());
        for (CharSequence label : event.getText()) {
            String text = OfferEvidence.normalize(label == null ? null : label.toString()).toLowerCase(Locale.US);
            if (PROGRESS_TAPS.contains(text)) ActiveRouteStore.invalidateTravel(this);
        }
    }

    /** @return true when another check should follow shortly */
    private boolean checkOffer() {
        try {
            return checkReadableOffer();
        } catch (RuntimeException error) {
            declineState.reset();
            DiagnosticLog.log(this, "accessibility",
                    "scan rejected; no further action: " + error.getClass().getSimpleName());
            status("Screen read failed. No automatic action until a new readable screen.");
            return false;
        }
    }

    private boolean checkReadableOffer() {
        long now = SystemClock.uptimeMillis();
        FilterSettings settings = FilterStore.load(this);
        if (!settings.enabled) declineState.reset();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return settings.enabled;
        if (!isDasher(root)) {
            declineState.reset();
            acceptedTracker.reset();
            return false;
        }
        Scan scan = Scan.of(root);
        if (scan.truncated) {
            status("Offer screen exceeded safe read limits; no automatic action.");
            return false;
        }

        Scan confirmation = confirmationScan(scan, declineState.hasPendingConfirmation(now));
        if (confirmation != null) return handleConfirmation(confirmation, settings, now);

        OfferSnapshot offer = OfferParser.parse(scan.text, scan.metricParts);
        if (scan.accept == null || scan.decline == null) return handleOtherScreen(scan, offer, settings, now);
        if (scan.accept.equals(scan.decline)) {
            status("Ambiguous shared button target; no action.");
            return false;
        }
        return handleOffer(scan, offer, settings, now);
    }

    /** Second step of a decline this service requested. Pausing or a newer offer revokes the authority. */
    private boolean handleConfirmation(Scan confirmation, FilterSettings settings, long now) {
        diagnostic("confirmation", confirmation, null, null);
        if (!settings.enabled || !declineState.hasPendingConfirmation(now)) return false;
        if (declineGeneration != OfferNotificationService.generation()) {
            declineState.reset();
            status("A new notification arrived; old decline confirmation authority revoked.");
            return false;
        }
        int selected = DeclineConfirmation.select(
                confirmation.text, confirmation.declineLabels, confirmation.hasAcceptLabel);
        if (selected >= 0 && declineState.mayConfirm(now) && click(confirmation.declineTargets.get(selected))) {
            declineState.confirmationSent(now);
            status("Decline confirmation requested; waiting for Dasher to close the offer.");
        }
        return true;
    }

    /** A Dasher screen without both offer controls: delivery progress, idle, or an offer still loading. */
    private boolean handleOtherScreen(Scan scan, OfferSnapshot offer, FilterSettings settings, long now) {
        AcceptedOfferTracker.Acceptance accepted = acceptedTracker.observeOtherScreen(scan.text, now);
        if (accepted != null) {
            recordAcceptance(accepted);
            return false;
        }
        if (OfferEvidence.isIdle(scan.text)) {
            boolean pending = declineState.hasPendingConfirmation(now);
            ActiveRouteStore.clear(this);
            declineState.reset();
            status(pending
                    ? "Dasher returned to the idle screen after decline; no active offer visible."
                    : "Dasher is finding offers.");
            return false;
        }
        if (scan.accept == null && scan.decline == null) {
            declineState.offerGone();
            return declineState.hasPendingConfirmation(now);
        }
        diagnostic("incomplete-controls", scan, offer, null);
        status(offer.summary() + "\nBoth offer controls are not yet readable; no action.");
        return settings.enabled;
    }

    private void recordAcceptance(AcceptedOfferTracker.Acceptance accepted) {
        if (!accepted.addOn && accepted.acceptedOffer.payCents != null) {
            FilterStore.recordAccepted(this, accepted.acceptedOffer.payCents);
        }
        ActiveRouteStore.save(this, accepted.routeAfter);
        status("Acceptance observed. " + (accepted.addOn
                ? "Add-on route updated; standalone baseline unchanged."
                : "Standalone payout baseline updated."));
    }

    /** A readable offer with distinct Accept and Decline targets. Only a known failure is declined, at once. */
    private boolean handleOffer(Scan scan, OfferSnapshot offer, FilterSettings settings, long now) {
        boolean isAddOn = AddOnOffer.isLikely(scan.text);
        AddOnOffer addOn = isAddOn ? AddOnOffer.parse(ActiveRouteStore.load(this), scan.text) : null;
        acceptedTracker.observeOffer(offer, isAddOn ? addOn.combined : offer, isAddOn, now);
        OfferRule.Decision decision = isAddOn
                ? OfferRule.evaluateAddOn(addOn, settings) : OfferRule.evaluate(offer, settings);
        diagnostic(isAddOn ? "add-on" : "offer", scan, offer, decision);
        String detail = isAddOn ? addOn.summary() : offer.summary();

        if (!settings.enabled || decision.result != OfferRule.Result.DECLINE) {
            declineState.reset();
            status(detail + "\n" + decision.summary() + (settings.enabled ? "" : "\nAuto-decline is off."));
            return settings.enabled && decision.result == OfferRule.Result.REVIEW;
        }
        String key = DeclineState.offerKey(offer, scan.text);
        if (!declineState.mayDecline(key, now)) return declineState.hasPendingConfirmation(now);
        // Re-check the active window just before acting: the screen can change while it is being read.
        if (isDasher(getRootInActiveWindow()) && click(scan.decline)) {
            declineState.declineSent(key, now);
            declineGeneration = OfferNotificationService.generation();
            DiagnosticLog.log(this, "accessibility", "first-step Decline REQUESTED: " + detail);
            status("Decline requested: " + detail + "\n" + decision.summary());
        } else {
            status("Decline click was not accepted by Android. No completion claimed.");
        }
        return true;
    }

    /** A confirmation surface in any Dasher window while a decline is pending, else in the active window. */
    private Scan confirmationScan(Scan primary, boolean pending) {
        if (pending) {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (!isDasher(root)) continue;
                Scan candidate = Scan.of(root);
                if (!candidate.truncated && DeclineConfirmation.isSurface(candidate.text, candidate.hasAcceptLabel)) {
                    return candidate;
                }
            }
        }
        return DeclineConfirmation.isSurface(primary.text, primary.hasAcceptLabel) ? primary : null;
    }

    private static boolean isAcceptClick(AccessibilityEvent event) {
        for (CharSequence label : event.getText()) {
            if (isAcceptLabel(label)) return true;
        }
        if (isAcceptLabel(event.getContentDescription())) return true;
        AccessibilityNodeInfo node = event.getSource();
        for (int depth = 0; depth < MAX_ACCEPT_LABEL_ANCESTORS && node != null; depth++, node = node.getParent()) {
            if (isAcceptLabel(node.getText()) || isAcceptLabel(node.getContentDescription())) return true;
        }
        return false;
    }

    private static boolean isAcceptLabel(CharSequence label) {
        return label != null && OfferControls.isButton(label.toString(), "accept");
    }

    private static boolean isDasherPackage(CharSequence packageName) {
        return packageName != null && DASHER_PACKAGE.contentEquals(packageName);
    }

    private static boolean isDasher(AccessibilityNodeInfo node) {
        return node != null && isDasherPackage(node.getPackageName());
    }

    private static boolean hasClickAction(AccessibilityNodeInfo node) {
        return node.isClickable()
                || node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
    }

    /** The nearest visible, enabled, clickable node at or above a label. */
    private static AccessibilityNodeInfo clickTarget(AccessibilityNodeInfo node) {
        for (int depth = 0; depth < MAX_CLICK_TARGET_ANCESTORS && node != null; depth++, node = node.getParent()) {
            if (node.isVisibleToUser() && node.isEnabled() && hasClickAction(node)) return node;
        }
        return null;
    }

    /** @return whether Android accepted the click request; not whether Dasher acted on it */
    private static boolean click(AccessibilityNodeInfo node) {
        return node != null && isDasher(node) && node.isEnabled() && node.isVisibleToUser() && hasClickAction(node)
                && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private void diagnostic(String phase, Scan scan, OfferSnapshot offer, OfferRule.Decision decision) {
        if (!DiagnosticLog.isEnabled(this)) return;
        String signature = phase
                + "|" + (offer == null ? "" : offer.fingerprint())
                + "|" + (decision == null ? "" : decision.summary())
                + "|" + (scan.accept != null)
                + "|" + (scan.decline != null);
        if (signature.equals(lastDiagnosticSignature)) return;
        lastDiagnosticSignature = signature;
        DiagnosticLog.log(this, "screen", signature + " labels=" + scan.text + " metricParts=" + scan.metricParts);
    }

    private void status(String message) {
        if (message.equals(lastStatus)) return;
        lastStatus = message;
        FilterStore.setLastStatus(this, message);
        DiagnosticLog.log(this, "status", message);
    }

    /** One bounded read of a window: distinct labels, joined metric siblings, and offer control targets. */
    private static final class Scan {
        final List<String> text = new ArrayList<>();
        final List<String> metricParts = new ArrayList<>();
        final List<String> declineLabels = new ArrayList<>();
        final List<AccessibilityNodeInfo> declineTargets = new ArrayList<>();
        AccessibilityNodeInfo accept;
        AccessibilityNodeInfo decline;
        boolean hasAcceptLabel;
        boolean truncated;
        int visited;

        static Scan of(AccessibilityNodeInfo root) {
            Scan scan = new Scan();
            scan.visit(root, 0);
            return scan;
        }

        /** @return the node's own label, or its only child's, so a parent can join split metric siblings */
        private String visit(AccessibilityNodeInfo node, int depth) {
            if (node == null) return "";
            if (depth > MAX_SCAN_DEPTH || visited++ >= MAX_SCAN_NODES) {
                truncated = true;
                return "";
            }
            String own = "";
            if (node.isVisibleToUser()) {
                addLabel(node, node.getText());
                addLabel(node, node.getContentDescription());
                own = OfferEvidence.normalize(node.getText() == null ? null : node.getText().toString());
                if (own.isEmpty() && node.getContentDescription() != null) {
                    own = OfferEvidence.normalize(node.getContentDescription().toString());
                }
            }
            List<String> childLabels = new ArrayList<>();
            int children = node.getChildCount();
            for (int i = 0; i < children && !truncated; i++) childLabels.add(visit(node.getChild(i), depth + 1));
            metricParts.addAll(OfferParser.joinMetricSiblings(childLabels));
            return own.isEmpty() && children == 1 && childLabels.size() == 1 ? childLabels.get(0) : own;
        }

        private void addLabel(AccessibilityNodeInfo node, CharSequence value) {
            if (value == null) return;
            String label = OfferEvidence.normalize(value.toString());
            if (label.isEmpty()) return;
            if (label.length() > OfferEvidence.MAX_LABEL_LENGTH || text.size() >= OfferEvidence.MAX_LABELS) {
                truncated = true;
                return;
            }
            if (!text.contains(label)) text.add(label);
            if (OfferControls.isButton(label, "accept")) {
                hasAcceptLabel = true;
                if (accept == null) accept = clickTarget(node);
            }
            if (OfferControls.isButton(label, "decline")) {
                AccessibilityNodeInfo target = clickTarget(node);
                if (target != null) {
                    if (decline == null) decline = target;
                    declineLabels.add(label);
                    declineTargets.add(target);
                }
            }
        }
    }
}
