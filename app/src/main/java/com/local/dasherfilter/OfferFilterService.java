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

/** Reads only the visible Dasher window. A notification is not authority to click another offer. */
public final class OfferFilterService extends AccessibilityService {
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private static volatile OfferFilterService active;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DeclineState declineState = new DeclineState();
    private final AcceptedOfferTracker acceptedTracker = new AcceptedOfferTracker();
    private long recheckUntil, declineGeneration;
    private String lastStatus = "", lastDiagnosticSignature = "";
    private final Runnable recheck = new Runnable() {
        @Override public void run() { long now = SystemClock.uptimeMillis(); boolean more = checkOffer(); if (more && (now < recheckUntil || declineState.hasPendingConfirmation(now))) handler.postDelayed(this, 200); }
    };
    static boolean isConnected() { return active != null; }
    static boolean isDasherForeground() {
        OfferFilterService s = active; if (s == null) return false;
        try { return isDasher(s.getRootInActiveWindow()); } catch (RuntimeException error) { return false; }
    }
    private static boolean isDasher(AccessibilityNodeInfo node) { return node != null && node.getPackageName() != null && DASHER_PACKAGE.contentEquals(node.getPackageName()); }
    static void requestCheckFromNotification() {
        OfferFilterService s = active;
        if (s != null) s.handler.post(() -> { s.handler.removeCallbacks(s.recheck); s.recheckUntil = SystemClock.uptimeMillis() + 3000; s.handler.post(s.recheck); });
    }
    @Override protected void onServiceConnected() {
        active = this; Updater.schedule(this); DiagnosticLog.log(this, "accessibility", "connected; no automatic activity launches");
        status("Accessibility connected. Only visible offer screens can be fully evaluated."); Updater.check(this, false, null);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOWS_CHANGED && (event.getPackageName() == null || !DASHER_PACKAGE.contentEquals(event.getPackageName()))) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (isAcceptClick(event)) acceptedTracker.acceptClicked(SystemClock.uptimeMillis());
            for (CharSequence label : event.getText()) {
                String text = OfferEvidence.normalize(label == null ? null : label.toString()).toLowerCase(java.util.Locale.US);
                if (text.equals("confirm pickup") || text.equals("complete pickup") || text.equals("complete delivery") || text.equals("confirm dropoff")) ActiveRouteStore.invalidateTravel(this);
            }
        }
        handler.removeCallbacks(recheck); recheckUntil = SystemClock.uptimeMillis() + 3000; if (checkOffer()) handler.postDelayed(recheck, 200);
    }
    private boolean checkOffer() {
        try { return checkReadableOffer(); }
        catch (RuntimeException error) { declineState.reset(); DiagnosticLog.log(this, "accessibility", "scan rejected; no further action: " + error.getClass().getSimpleName()); status("Screen read failed. No automatic action until a new readable screen."); return false; }
    }
    private boolean checkReadableOffer() {
        long now = SystemClock.uptimeMillis(); FilterSettings settings = FilterStore.load(this); if (!settings.enabled) declineState.reset();
        AccessibilityNodeInfo root = getRootInActiveWindow(); if (root == null) return settings.enabled;
        if (!isDasher(root)) { declineState.reset(); acceptedTracker.reset(); return false; }
        Scan scan = new Scan(); scan(root, scan, 0);
        if (scan.truncated) { status("Offer screen exceeded safe read limits; no automatic action."); return false; }
        Scan confirmation = confirmationScan(scan, declineState.hasPendingConfirmation(now));
        if (confirmation != null) {
            diagnostic("confirmation", confirmation, null, null);
            if (!settings.enabled || !declineState.hasPendingConfirmation(now)) return false;
            if (declineGeneration != OfferNotificationService.generation()) { declineState.reset(); status("A new notification arrived; old decline confirmation authority revoked."); return false; }
            int selected = DeclineConfirmation.select(confirmation.text, confirmation.declineLabels, confirmation.hasAcceptLabel);
            if (selected >= 0 && declineState.mayConfirm(now) && click(confirmation.declineTargets.get(selected))) { declineState.confirmationSent(now); status("Decline confirmation requested; waiting for Dasher to close the offer."); }
            return true;
        }
        OfferSnapshot offer = OfferParser.parse(scan.text, scan.metricParts);
        if (scan.accept == null || scan.decline == null) {
            AcceptedOfferTracker.Acceptance accepted = acceptedTracker.observeOtherScreen(scan.text, now);
            if (accepted != null) {
                if (!accepted.addOn && accepted.acceptedOffer.payCents != null) FilterStore.recordAccepted(this, accepted.acceptedOffer.payCents);
                ActiveRouteStore.save(this, accepted.routeAfter);
                status("Acceptance observed. " + (accepted.addOn ? "Add-on route updated; standalone baseline unchanged." : "Standalone payout baseline updated.")); return false;
            }
            if (OfferEvidence.isIdle(scan.text)) {
                boolean pending = declineState.hasPendingConfirmation(now); ActiveRouteStore.clear(this); declineState.reset();
                status(pending ? "Dasher returned to the idle screen after decline; no active offer visible." : "Dasher is finding offers."); return false;
            }
            if (scan.accept == null && scan.decline == null) declineState.offerGone();
            if (scan.accept != null || scan.decline != null) { diagnostic("incomplete-controls", scan, offer, null); status(offer.summary() + "\nBoth offer controls are not yet readable; no action."); return settings.enabled; }
            return declineState.hasPendingConfirmation(now);
        }
        if (scan.accept.equals(scan.decline)) { status("Ambiguous shared button target; no action."); return false; }
        boolean isAddOn = AddOnOffer.isLikely(scan.text);
        AddOnOffer addOn = isAddOn ? AddOnOffer.parse(ActiveRouteStore.load(this), offer, scan.text) : null;
        acceptedTracker.observeOffer(offer, isAddOn ? addOn.combined : offer, isAddOn, now);
        OfferRule.Decision decision = isAddOn ? OfferRule.evaluateAddOn(addOn, settings) : OfferRule.evaluate(offer, settings);
        diagnostic(isAddOn ? "add-on" : "offer", scan, offer, decision); String detail = isAddOn ? addOn.summary() : offer.summary();
        if (!settings.enabled || decision.result != OfferRule.Result.DECLINE) {
            declineState.reset(); status(detail + "\n" + decision.summary() + (settings.enabled ? "" : "\nAuto-decline is off.")); return settings.enabled && decision.result == OfferRule.Result.REVIEW;
        }
        String key = DeclineState.offerKey(offer, scan.text); if (!declineState.mayDecline(key, now)) return declineState.hasPendingConfirmation(now);
        if (isDasher(getRootInActiveWindow()) && click(scan.decline)) {
            declineState.declineSent(key, now); declineGeneration = OfferNotificationService.generation();
            DiagnosticLog.log(this, "accessibility", "first-step Decline REQUESTED: " + detail); status("Decline requested: " + detail + "\n" + decision.summary());
        } else status("Decline click was not accepted by Android. No completion claimed.");
        return true;
    }
    private Scan confirmationScan(Scan primary, boolean pending) {
        if (pending) for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = window.getRoot(); if (!isDasher(root)) continue;
            Scan candidate = new Scan(); scan(root, candidate, 0);
            if (!candidate.truncated && DeclineConfirmation.isSurface(candidate.text, candidate.hasAcceptLabel)) return candidate;
        }
        return DeclineConfirmation.isSurface(primary.text, primary.hasAcceptLabel) ? primary : null;
    }
    private static boolean isAcceptClick(AccessibilityEvent e) {
        for (CharSequence label : e.getText()) if (label != null && OfferControls.isButton(label.toString(), "accept")) return true;
        if (e.getContentDescription() != null && OfferControls.isButton(e.getContentDescription().toString(), "accept")) return true;
        AccessibilityNodeInfo n = e.getSource();
        for (int depth = 0; depth < 4 && n != null; depth++, n = n.getParent()) {
            if (n.getText() != null && OfferControls.isButton(n.getText().toString(), "accept")) return true;
            if (n.getContentDescription() != null && OfferControls.isButton(n.getContentDescription().toString(), "accept")) return true;
        }
        return false;
    }
    private static boolean hasClickAction(AccessibilityNodeInfo n) { return n.isClickable() || n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK); }
    private static AccessibilityNodeInfo clickTarget(AccessibilityNodeInfo n) { for (int depth = 0; depth < 8 && n != null; depth++, n = n.getParent()) if (n.isVisibleToUser() && n.isEnabled() && hasClickAction(n)) return n; return null; }
    private static boolean click(AccessibilityNodeInfo n) { return n != null && isDasher(n) && n.isEnabled() && n.isVisibleToUser() && hasClickAction(n) && n.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
    private static String scan(AccessibilityNodeInfo n, Scan result, int depth) {
        if (n == null) return "";
        if (depth > 60 || result.visited++ >= 1500) { result.truncated = true; return ""; }
        String own = "";
        if (n.isVisibleToUser()) {
            addLabel(result, n, n.getText()); addLabel(result, n, n.getContentDescription());
            own = OfferEvidence.normalize(n.getText() == null ? null : n.getText().toString());
            if (own.isEmpty() && n.getContentDescription() != null) own = OfferEvidence.normalize(n.getContentDescription().toString());
        }
        List<String> siblings = new ArrayList<>(); int children = n.getChildCount();
        for (int i = 0; i < children && !result.truncated; i++) siblings.add(scan(n.getChild(i), result, depth + 1));
        result.metricParts.addAll(OfferParser.joinMetricSiblings(siblings)); return own.isEmpty() && children == 1 && siblings.size() == 1 ? siblings.get(0) : own;
    }
    private static void addLabel(Scan s, AccessibilityNodeInfo n, CharSequence value) {
        if (value == null) return; String label = OfferEvidence.normalize(value.toString()); if (label.isEmpty()) return;
        if (label.length() > 4096 || s.text.size() >= 256) { s.truncated = true; return; }
        if (!s.text.contains(label)) s.text.add(label);
        if (OfferControls.isButton(label, "accept")) { s.hasAcceptLabel = true; if (s.accept == null) s.accept = clickTarget(n); }
        if (OfferControls.isButton(label, "decline")) { AccessibilityNodeInfo target = clickTarget(n); if (target != null) { if (s.decline == null) s.decline = target; s.declineLabels.add(label); s.declineTargets.add(target); } }
    }
    private void diagnostic(String phase, Scan s, OfferSnapshot offer, OfferRule.Decision decision) {
        if (!DiagnosticLog.isEnabled(this)) return;
        String signature = phase + "|" + (offer == null ? "" : offer.fingerprint()) + "|" + (decision == null ? "" : decision.summary()) + "|" + (s.accept != null) + "|" + (s.decline != null);
        if (signature.equals(lastDiagnosticSignature)) return; lastDiagnosticSignature = signature;
        DiagnosticLog.log(this, "screen", signature + " labels=" + s.text + " metricParts=" + s.metricParts);
    }
    private void status(String message) { if (!message.equals(lastStatus)) { lastStatus = message; FilterStore.setLastStatus(this, message); DiagnosticLog.log(this, "status", message); } }
    @Override public void onInterrupt() { handler.removeCallbacks(recheck); declineState.reset(); acceptedTracker.reset(); status("Accessibility interrupted; waiting for a new readable offer."); }
    @Override public boolean onUnbind(Intent intent) { stop(); return super.onUnbind(intent); }
    @Override public void onDestroy() { stop(); super.onDestroy(); }
    private void stop() { if (active == this) active = null; handler.removeCallbacksAndMessages(null); declineState.reset(); acceptedTracker.reset(); }
    private static final class Scan {
        final List<String> text = new ArrayList<>(), metricParts = new ArrayList<>(), declineLabels = new ArrayList<>();
        final List<AccessibilityNodeInfo> declineTargets = new ArrayList<>();
        AccessibilityNodeInfo accept, decline; boolean hasAcceptLabel, truncated; int visited;
    }
}
