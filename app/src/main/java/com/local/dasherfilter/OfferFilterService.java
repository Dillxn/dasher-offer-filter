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
    /** One history record per visible offer (countdown ticks keep it). */
    private final OfferSession session = new OfferSession();
    private OfferHistoryStore history;
    private long recheckUntil;
    /** History record of the offer whose first-step decline was requested (for CONFIRM_REQUESTED), or -1. */
    private long declinedRecordId = -1;
    /** History record of the offer whose accept tap is pending confirmation (for ACCEPT_OBSERVED), or -1. */
    private long acceptRecordId = -1;
    /** Set by observeAcceptance when the last non-offer screen confirmed a pending accept. */
    private boolean acceptanceObserved;
    /**
     * The stored route is cleared at every accept tap, but the add-on card that was tapped stays on screen for a moment: it is
     * still evaluated against the route it appeared with, so its identity (and the pending accept) does not change until the
     * accept resolves or the card goes away.
     */
    private boolean acceptTapPending;
    private OfferSnapshot routeAtAcceptTap;
    /** Offer key of the last fully readable offer, and of the card whose accept was tapped. */
    private String lastOfferKey = "", acceptTapOfferKey = "";
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
    /**
     * A new DoorDash offer notification was seen. Only an offer whose explicit facts differ from the offer whose decline is
     * pending revokes that confirmation authority; the declined offer's own notification (same facts, or merchant only) never
     * does. Runs on the main thread like every accessibility callback.
     */
    static void notificationOfferSeen(OfferSnapshot offer) {
        OfferFilterService s = active; if (s == null || offer == null) return;
        Runnable check = () -> { if (s.declineState.offerObserved(offer, SystemClock.uptimeMillis())) s.status("A different offer notification arrived; old decline confirmation authority revoked."); };
        if (Looper.myLooper() == Looper.getMainLooper()) check.run(); else s.handler.post(check);
    }
    @Override public void onCreate() { super.onCreate(); history = OfferHistoryStore.get(this); }
    @Override protected void onServiceConnected() {
        active = this; Updater.schedule(this); DiagnosticLog.log(this, "accessibility", "connected; no automatic activity launches");
        status("Accessibility connected. Only visible offer screens can be fully evaluated."); Updater.check(this, false, null);
    }
    private OfferHistoryStore history() { if (history == null) history = OfferHistoryStore.get(this); return history; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOWS_CHANGED && (event.getPackageName() == null || !DASHER_PACKAGE.contentEquals(event.getPackageName()))) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // Any accept tap (including "Add to route") may change the route; it stays unknown until an observed acceptance stores the new one.
            if (isAcceptClick(event)) acceptTapped(SystemClock.uptimeMillis());
            for (CharSequence label : event.getText()) {
                String text = OfferEvidence.normalize(label == null ? null : label.toString()).toLowerCase(java.util.Locale.US);
                if (text.equals("confirm pickup") || text.equals("complete pickup") || text.equals("complete delivery") || text.equals("confirm dropoff")) ActiveRouteStore.invalidateTravel(this);
            }
        }
        handler.removeCallbacks(recheck); recheckUntil = SystemClock.uptimeMillis() + 3000; if (checkOffer()) handler.postDelayed(recheck, 200);
    }
    private void acceptTapped(long now) {
        AcceptedOfferTracker.AcceptClick click = acceptedTracker.acceptClicked(now);
        if (!acceptTapPending) { routeAtAcceptTap = ActiveRouteStore.load(this); acceptTapOfferKey = lastOfferKey; }
        acceptTapPending = true;
        ActiveRouteStore.clear(this);
        boolean tracked = click == AcceptedOfferTracker.AcceptClick.RECORDED || click == AcceptedOfferTracker.AcceptClick.ROUTE_UNKNOWN;
        acceptRecordId = tracked && session.active() ? session.id() : -1;
        if (click == AcceptedOfferTracker.AcceptClick.ROUTE_UNKNOWN || click == AcceptedOfferTracker.AcceptClick.ADDON_UNTRACKED)
            status("Add-on accept tap seen; route context cleared until an acceptance with a readable route is observed.");
    }
    private boolean checkOffer() {
        try { return checkReadableOffer(); }
        catch (RuntimeException error) {
            declineState.reset(); acceptedTracker.offerNotVisible(); endOffer();
            DiagnosticLog.log(this, "accessibility", "scan rejected; no further action: " + error.getClass().getSimpleName()); status("Screen read failed. No automatic action until a new readable screen."); return false;
        }
    }
    private boolean checkReadableOffer() {
        long now = SystemClock.uptimeMillis(); FilterSettings settings = FilterStore.load(this); if (!settings.enabled) declineState.reset();
        AccessibilityNodeInfo root = getRootInActiveWindow(); if (root == null) return settings.enabled;
        if (!isDasher(root)) { declineState.reset(); acceptedTracker.reset(); endOffer(); acceptRecordId = -1; return false; }
        Scan scan = new Scan(); scan(root, scan, 0);
        if (scan.truncated) { acceptedTracker.offerNotVisible(); status("Offer screen exceeded safe read limits; no automatic action."); return false; }
        // Confirmation authority exists only while a first-step decline is pending, and only for the declined offer.
        Scan confirmation = settings.enabled && declineState.hasPendingConfirmation(now) ? confirmationScan(scan) : null;
        if (confirmation != null) {
            diagnostic("confirmation", confirmation, null, null);
            int selected = DeclineConfirmation.select(confirmation.text, confirmation.declineLabels, confirmation.hasAcceptLabel, showsDeclinedOffer(confirmation));
            if (selected >= 0 && declineState.mayConfirm(now) && click(confirmation.declineTargets.get(selected))) {
                declineState.confirmationSent(now); if (declinedRecordId >= 0) history().addActions(declinedRecordId, OfferRecord.CONFIRM_REQUESTED);
                status("Decline confirmation requested; waiting for Dasher to close the offer.");
            }
            return true;
        }
        OfferSnapshot offer = OfferParser.parse(scan.text, scan.metricParts);
        if (scan.accept == null || scan.decline == null) {
            String acceptNote = observeAcceptance(scan.text, now);
            if (acceptanceObserved) { endOffer(); status(acceptNote); return false; }
            if (OfferEvidence.isIdle(scan.text)) {
                boolean pending = declineState.hasPendingConfirmation(now); ActiveRouteStore.clear(this); declineState.reset(); endOffer();
                status(acceptNote != null ? acceptNote : pending ? "Dasher returned to the idle screen after decline; no active offer visible." : "Dasher is finding offers."); return false;
            }
            if (scan.accept == null && scan.decline == null) { declineState.offerGone(); endOffer(); }
            if (scan.accept != null || scan.decline != null) { diagnostic("incomplete-controls", scan, offer, null); status(acceptNote != null ? acceptNote : offer.summary() + "\nBoth offer controls are not yet readable; no action."); return settings.enabled; }
            if (acceptNote != null) status(acceptNote);
            return declineState.hasPendingConfirmation(now);
        }
        if (scan.accept.equals(scan.decline)) { acceptedTracker.offerNotVisible(); status("Ambiguous shared button target; no action."); return false; }
        boolean isAddOn = AddOnOffer.isLikely(scan.text);
        String key = DeclineState.offerKey(offer, scan.text); lastOfferKey = key;
        // Only the very card whose accept was tapped keeps the route it appeared with; any other add-on uses the stored route.
        OfferSnapshot route = !isAddOn ? null : acceptTapPending && key.equals(acceptTapOfferKey) ? routeAtAcceptTap : ActiveRouteStore.load(this);
        AddOnOffer addOn = isAddOn ? AddOnOffer.parse(route, offer, scan.text) : null;
        acceptedTracker.observeOffer(offer, isAddOn ? addOn.combined : offer, isAddOn, scan.text, now);
        // Identity of this offer: the standalone facts, or an add-on's explicit increments when the card shows no standalone facts.
        OfferSnapshot identity = isAddOn && !offer.hasFacts() ? addOn.incremental : offer;
        long wall = System.currentTimeMillis();
        OfferSession.Step step = session.observe(identity, isAddOn, history().nextId(wall));
        EvaluationContext ctx = EvaluationContext.at(wall);
        if (isAddOn) ctx = ctx.withAddOn(route);
        if (settings.maxDeclinesPerHour > 0) ctx = ctx.withBudget(history().budget(), wall, OfferHistoryStore.budgetKey(step.id));
        OfferRule.Decision rule = OfferRule.evaluate(offer, scan.text, settings, ctx);
        OfferRule.Decision decision = settings.enabled ? rule : OfferRule.Decision.paused();
        diagnostic(isAddOn ? "add-on" : "offer", scan, offer, rule); String detail = isAddOn ? addOn.summary() : offer.summary();
        OfferSnapshot facts = isAddOn ? shownIncrement(addOn) : offer;
        if (decision.result != OfferRule.Result.DECLINE) {
            declineState.reset(); declinedRecordId = -1;
            record(step, facts, isAddOn, rule, !settings.enabled && rule.result == OfferRule.Result.DECLINE ? OfferRecord.PAUSED_SHADOW : 0, wall);
            status(detail + "\n" + reviewStatus(decision, rule, settings)); return settings.enabled && decision.result == OfferRule.Result.REVIEW;
        }
        if (declineState.offerObserved(identity, now)) DiagnosticLog.log(this, "accessibility", "new offer seen; old confirmation authority revoked");
        if (!declineState.mayDecline(key, now)) { record(step, facts, isAddOn, rule, 0, wall); return declineState.hasPendingConfirmation(now); }
        if (isDasher(getRootInActiveWindow()) && click(scan.decline)) {
            // Everything below runs after the click: the first decline request is never delayed by bookkeeping.
            declineState.declineSent(key, identity, now); declinedRecordId = step.id;
            record(step, facts, isAddOn, rule, OfferRecord.DECLINE_REQUESTED, wall);
            DiagnosticLog.log(this, "accessibility", "first-step Decline REQUESTED: " + detail); status("Decline requested: " + detail + "\n" + decision.summary());
        } else { record(step, facts, isAddOn, rule, 0, wall); status("Decline click was not accepted by Android. No completion claimed."); }
        return true;
    }
    /** An add-on's increments as displayed; an increment derived from the stored route ("New total" minus route pay) is not a fact. */
    static OfferSnapshot shownIncrement(AddOnOffer addOn) {
        OfferSnapshot i = addOn.incremental;
        return new OfferSnapshot(addOn.explicitIncrementPay ? i.payCents : null, i.miles, i.minutes, i.stops, addOn.hourly);
    }
    /** Handles a pending accept on a non-offer screen; returns a status line, or null when there is nothing to report. */
    private String observeAcceptance(List<String> labels, long now) {
        AcceptedOfferTracker.Observation seen = acceptedTracker.observeScreen(labels, now);
        long recordId = acceptRecordId;
        acceptanceObserved = seen.outcome == AcceptedOfferTracker.Outcome.ACCEPTED;
        if (seen.outcome != AcceptedOfferTracker.Outcome.NONE) { acceptTapPending = false; routeAtAcceptTap = null; acceptTapOfferKey = ""; }
        switch (seen.outcome) {
            case ACCEPTED: {
                acceptRecordId = -1;
                AcceptedOfferTracker.Acceptance accepted = seen.acceptance;
                if (accepted.updatesBaseline()) FilterStore.recordAccepted(this, accepted.acceptedOffer.payCents);
                if (seen.clearsRoute()) ActiveRouteStore.clear(this); else ActiveRouteStore.save(this, accepted.routeAfter);
                if (recordId >= 0) history().addActions(recordId, OfferRecord.ACCEPT_OBSERVED);
                return "Acceptance observed. " + (accepted.addOn ? (seen.clearsRoute() ? "Add-on accepted; route context cleared (route after adding is not readable)." : "Add-on route updated; standalone baseline unchanged.")
                        : accepted.updatesBaseline() ? "Standalone payout baseline updated." : "Standalone payout baseline unchanged.");
            }
            case AMBIGUOUS:
                acceptRecordId = -1; ActiveRouteStore.clear(this);
                return "Accept tap seen; acceptance not confirmed. Route context unknown.";
            case REJECTED:
                acceptRecordId = -1;
                return "Dasher said the offer is no longer available; payout baseline unchanged.";
            default: return null;
        }
    }
    private static String reviewStatus(OfferRule.Decision decision, OfferRule.Decision rule, FilterSettings settings) {
        if (rule.code == OfferRule.Code.HOURLY_MODE) return "Earn by Time offer — not auto-declined.";
        if (!settings.enabled) return decision.summary() + (rule.result == OfferRule.Result.DECLINE ? "\nAuto-decline is off (would have requested decline: " + rule.reason + ")." : "\nAuto-decline is off.");
        if (rule.code == OfferRule.Code.DECLINE_LIMIT) return "Decline limit reached (" + settings.maxDeclinesPerHour + " per hour); no automatic decline. Review this offer in Dasher.";
        return decision.summary();
    }
    /** Updates this offer's single history record (only when the evaluation is at least as complete) and ORs in action flags. */
    private void record(OfferSession.Step step, OfferSnapshot facts, boolean addOn, OfferRule.Decision rule, int flags, long wall) {
        OfferHistoryStore h = history();
        OfferRecord old = h.get(step.id);
        OfferRecord next = old == null ? OfferRecord.of(step.id, wall, OfferRecord.Source.SCREEN, facts, addOn, rule, null)
                : step.upgrade ? old.withEvaluation(facts, addOn, rule, null) : old;
        h.upsert(flags == 0 ? next : next.withAction(flags));
    }
    private Scan confirmationScan(Scan primary) {
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = window.getRoot(); if (!isDasher(root)) continue;
            Scan candidate = new Scan(); scan(root, candidate, 0);
            if (!candidate.truncated && DeclineConfirmation.isSurface(candidate.text, candidate.hasAcceptLabel, showsDeclinedOffer(candidate))) return candidate;
        }
        return DeclineConfirmation.isSurface(primary.text, primary.hasAcceptLabel, showsDeclinedOffer(primary)) ? primary : null;
    }
    /** A sheet laid over the declined offer still shows that offer's facts; a different offer never matches. */
    private boolean showsDeclinedOffer(Scan s) { return declineState.isDeclinedOffer(OfferParser.parse(s.text, s.metricParts)); }
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
    /** The visible offer is gone (or unreadable): the next readable offer starts a new history record and uses the stored route. */
    private void endOffer() { session.end(); acceptTapPending = false; routeAtAcceptTap = null; acceptTapOfferKey = ""; }
    private void status(String message) { if (!message.equals(lastStatus)) { lastStatus = message; FilterStore.setLastStatus(this, message); DiagnosticLog.log(this, "status", message); } }
    @Override public void onInterrupt() { handler.removeCallbacks(recheck); declineState.reset(); acceptedTracker.reset(); endOffer(); status("Accessibility interrupted; waiting for a new readable offer."); }
    @Override public boolean onUnbind(Intent intent) { stop(); return super.onUnbind(intent); }
    @Override public void onDestroy() { stop(); super.onDestroy(); }
    private void stop() { if (active == this) active = null; handler.removeCallbacksAndMessages(null); declineState.reset(); acceptedTracker.reset(); endOffer(); }
    private static final class Scan {
        final List<String> text = new ArrayList<>(), metricParts = new ArrayList<>(), declineLabels = new ArrayList<>();
        final List<AccessibilityNodeInfo> declineTargets = new ArrayList<>();
        AccessibilityNodeInfo accept, decline; boolean hasAcceptLabel, truncated; int visited;
    }
}
