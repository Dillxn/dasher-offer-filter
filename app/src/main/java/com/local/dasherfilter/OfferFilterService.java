package com.local.dasherfilter;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Reads only the visible Dasher window and may request Decline on a readable, known-failing offer. A notification
 * is never authority to click another offer, and a successful click is a request, not a confirmed decline. In split
 * screen, Dasher's half is read even while the user is in the other half, so an offer there is judged at once.
 */
public final class OfferFilterService extends AccessibilityService {
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private static final long OVERLAY_CHECK_MS = 1_500L;
    private static final long RECHECK_WINDOW_MS = 3000;
    private static final long RECHECK_INTERVAL_MS = 200;
    private static final int MAX_SCAN_DEPTH = 60;
    private static final int MAX_SCAN_NODES = 1500;
    private static final int MAX_CLICK_TARGET_ANCESTORS = 8;
    private static final int MAX_ACCEPT_LABEL_ANCESTORS = 4;
    /** A click event this soon after the app's own tap is that tap's echo, not the user's. */
    private static final long OWN_TAP_ECHO_MS = 1500;
    /** A manual Decline counts for the offer seen on screen at most this long ago. */
    private static final long MANUAL_DECLINE_OFFER_AGE_MS = 90_000;
    /** A declined offer or its confirmation still showing this long after the decline tap is reported as stuck. */
    static final long STUCK_MS = 5_000;
    /** How long a takeover lasts at most; offers expire well before this. */
    static final long TAKEOVER_MS = 120_000;
    /** Taps that prove delivery progress, making stored travel estimates stale. */
    private static final List<String> PROGRESS_TAPS = Arrays.asList(
            "confirm pickup", "complete pickup", "complete delivery", "confirm dropoff");

    private static volatile OfferFilterService active;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DeclineState declineState = new DeclineState();
    private final AcceptedOfferTracker acceptedTracker = new AcceptedOfferTracker();
    private long ownTapAt = -OWN_TAP_ECHO_MS;
    /** The last standalone offer on screen that the rules let through, for a manual Decline to refer to. */
    private OfferSnapshot passingOffer;
    private long passingOfferAt;
    private final Runnable syncAutomation = this::syncAutomation;
    private TouchWatch touchWatch;
    private DasherOverlay overlay;
    /** While the filter tab is up, a light check that Dasher is still on screen (switching apps may send no event). */
    private final Runnable overlayCheck = new Runnable() {
        @Override public void run() {
            syncOverlay();
            if (overlay != null && overlay.isShowing()) handler.postDelayed(this, OVERLAY_CHECK_MS);
        }
    };
    private OfferSilencer silencer;
    private long recheckUntil;
    /** Notification generation when the last decline was requested; a newer offer revokes confirmation. */
    private long declineGeneration;
    /** The offer whose Decline was last tapped: only its own confirmation may be tapped. */
    private OfferSnapshot declinedOffer = OfferSnapshot.UNKNOWN;
    /** Its decision-log entry, upgraded when the confirmation is tapped too. */
    private DecisionLog.Entry declinedEntry;
    private String declinedKey = "";
    private long declinedAt;
    /** The offer the user took over by touching the screen: nothing automatic happens to it again. */
    private OfferSnapshot takenOverOffer = OfferSnapshot.UNKNOWN;
    /** When it was taken over (uptime), 0 for none. */
    private long takenOverAt;
    /** Whether the latest read showed the declined offer or its own confirmation, so its ring may be turned down. */
    private boolean declinedOfferShowing;
    /** Whether the latest read showed an offer or a confirmation, which the best-area guide must not cover. */
    private boolean offerOnScreen;
    /** Whether the last look at the windows found the screen split (Android's split-screen divider). */
    private boolean splitSeen;
    /** The last declined offer already reported as stuck. */
    private String reportedStuck = "";
    /** The last offer already reported as unreadable, so repeated reads of it file nothing more. */
    private String reportedOffer = "";
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

    /** Whether the user took over an offer these facts could belong to, so the notification path leaves it alone. */
    static boolean userHasOffer(OfferSnapshot facts) {
        OfferFilterService service = active;
        return service != null && service.isTakenOver(facts, SystemClock.uptimeMillis());
    }

    /** Whether Dasher is on screen: the active window, or its half of a split screen. */
    static boolean isDasherForeground() {
        OfferFilterService service = active;
        if (service == null) return false;
        try {
            return service.dasherRoot() != null;
        } catch (RuntimeException error) {
            return false;
        }
    }

    /**
     * At the user's tap on Split with Dasher: asks Android to split the screen, as its own Split screen accessibility
     * shortcut does. Never called otherwise.
     *
     * @return whether Android took the request
     */
    static boolean splitScreen() {
        return globalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
    }

    /**
     * At the user's tap on Split with Dasher, when the phone would not split the screen for us: opens recent apps,
     * where the user starts split screen from Offer Filter's card. Never called otherwise.
     *
     * @return whether Android took the request
     */
    static boolean openRecents() {
        return globalAction(GLOBAL_ACTION_RECENTS);
    }

    private static boolean globalAction(int action) {
        OfferFilterService service = active;
        if (service == null) return false;
        try {
            return service.performGlobalAction(action);
        } catch (RuntimeException refused) {
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

    @Override public void onCreate() {
        super.onCreate();
        touchWatch = new TouchWatch(this, this::userTookOver);
        overlay = new DasherOverlay(this);
        silencer = new OfferSilencer(this);
        // Puts back any sound left turned down if the app died during a decline.
        OfferSilencer.restore(this);
    }

    @Override protected void onServiceConnected() {
        active = this;
        Updater.schedule(this);
        DiagnosticLog.log(this, "accessibility", "connected; never opens Dasher");
        // Offer Filter was on screen when an update began: open it again, never over Dasher.
        if (!isDasherForeground()) Updater.relaunchAfterUpdate(this);
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
        // The offer first, so a decline is never kept waiting on the tab.
        if (checkOffer()) handler.postDelayed(recheck, RECHECK_INTERVAL_MS);
        syncOverlay();
    }

    @Override public void onInterrupt() {
        handler.removeCallbacks(recheck);
        declineState.reset();
        acceptedTracker.reset();
        syncAutomation();
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

    /** The filter tab over Dasher follows Dasher on and off the screen, and into its half of a split screen. */
    private void syncOverlay() {
        if (overlay == null) return;
        boolean wasShowing = overlay.isShowing();
        Rect area = null;
        if (active == this) {
            try {
                area = dasherArea();
            } catch (RuntimeException unreadable) {
                area = null;
            }
        }
        overlay.sync(area, area != null && splitSeen, offerOnScreen);
        if (!wasShowing && overlay.isShowing()) {
            handler.removeCallbacks(overlayCheck);
            handler.postDelayed(overlayCheck, OVERLAY_CHECK_MS);
        }
    }

    /** For tests: the filter tab over Dasher. */
    DasherOverlay overlay() {
        return overlay;
    }

    private void stop() {
        if (active == this) active = null;
        handler.removeCallbacks(overlayCheck);
        if (overlay != null) overlay.hide();
        handler.removeCallbacksAndMessages(null);
        declineState.reset();
        acceptedTracker.reset();
        syncAutomation();
    }

    private void observeClick(AccessibilityEvent event) {
        long now = SystemClock.uptimeMillis();
        if (isAcceptClick(event)) {
            acceptedTracker.acceptClicked(now);
            passingOffer = null;
        }
        if (isDeclineClick(event) && now - ownTapAt > OWN_TAP_ECHO_MS) {
            // The user's own Decline on an offer the rules let through: the rules were too lenient for them.
            if (passingOffer != null && now - passingOfferAt <= MANUAL_DECLINE_OFFER_AGE_MS) {
                ManualDeclines.declined(this, passingOffer, System.currentTimeMillis());
                DiagnosticLog.log(this, "accessibility", "manual Decline of a passing offer; held until the next offer");
            }
            passingOffer = null;
        }
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
            ReportOutbox.fileAutomatic(this, ProblemReport.Kind.SCAN_ERROR, null, null, error);
            status("Screen read failed. No automatic action until a new readable screen.");
            return false;
        } finally {
            syncAutomation();
        }
    }

    /**
     * The touch watch runs exactly while a decline this service requested is in progress: from the Decline tap until
     * its confirmation closes, the offer is taken over, or the authority lapses. The silencer runs within that, and
     * only while the declined offer or its confirmation is what the screen shows.
     */
    private void syncAutomation() {
        if (touchWatch == null) return;
        boolean declining = declineState.hasPendingConfirmation(SystemClock.uptimeMillis());
        if (declining) touchWatch.start();
        else touchWatch.stop();
        // Quiet only while the declined offer itself is on screen: never over a next offer, even an unreadable one.
        boolean quiet = declining && declinedOfferShowing
                && declineGeneration == OfferNotificationService.generation()
                && FilterStore.silenceWhileDeclining(this);
        if (quiet) silencer.start();
        else silencer.stop();
    }

    /**
     * Any touch while a decline is in progress hands the offer back to the user: its confirmation is not tapped,
     * the offer is not declined again, and the sound comes back. A confirmation already tapped cannot be undone.
     */
    private void userTookOver() {
        if (!declineState.hasPendingConfirmation(SystemClock.uptimeMillis())) {
            syncAutomation();
            return;
        }
        boolean alreadyConfirmed = declineState.confirmationTapped();
        takenOverOffer = declinedOffer;
        takenOverAt = SystemClock.uptimeMillis();
        declineState.reset();
        handler.removeCallbacks(recheck);
        syncAutomation();
        if (declinedEntry != null && !alreadyConfirmed) {
            DecisionLog.record(this, new DecisionLog.Entry(declinedEntry.at, declinedEntry.source,
                    declinedEntry.addOn, declinedEntry.facts, declinedEntry.requiredCents, declinedEntry.result,
                    declinedEntry.reason, DecisionLog.Action.USER_TOOK_OVER, true, declinedEntry.evidence));
        }
        DiagnosticLog.log(this, "accessibility", "screen touched; automatic decline stopped"
                + (alreadyConfirmed ? " after its confirmation was tapped" : ""));
        status(alreadyConfirmed
                ? "You touched the screen after the decline was confirmed; nothing more will be tapped."
                : "You touched the screen, so auto-decline stopped for this offer.");
        String toast = alreadyConfirmed ? "Decline was already confirmed" : "Dash Buddy stopped tapping this offer";
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    /**
     * Whether these facts could be the offer the user took over. Anything that does not contradict it counts, so a
     * partly drawn frame of that offer (pay without the route line, or the reverse) is never declined.
     */
    private boolean isTakenOver(OfferSnapshot offer, long now) {
        return takenOverAt != 0 && now - takenOverAt < TAKEOVER_MS && !offer.contradicts(takenOverOffer);
    }

    private void forgetTakeover() {
        takenOverAt = 0;
        takenOverOffer = OfferSnapshot.UNKNOWN;
    }

    private boolean checkReadableOffer() {
        long now = SystemClock.uptimeMillis();
        FilterSettings settings = FilterStore.load(this);
        if (!settings.enabled) declineState.reset();
        offerOnScreen = false;
        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        if (activeRoot == null) return settings.enabled;
        AccessibilityNodeInfo root = isDasher(activeRoot) ? activeRoot : splitDasherRoot();
        if (root == null) {
            declineState.reset();
            acceptedTracker.reset();
            return false;
        }
        Scan scan = Scan.of(root);
        // A screen too big to read might be an offer: the guide stays off it too.
        offerOnScreen = scan.truncated;
        if (scan.truncated) {
            declinedOfferShowing = false;
            status("Offer screen exceeded safe read limits; no automatic action.");
            return false;
        }

        boolean pending = declineState.hasPendingConfirmation(now);
        Scan confirmation = confirmationScan(scan, pending);
        offerOnScreen = confirmation != null || scan.accept != null || scan.decline != null;
        if (confirmation == null) {
            // Our confirmation closed: no later dialog inherits the authority, even inside the window.
            if (declineState.confirmationSettled(now)) declineState.endConfirmation();
        } else if (!pending) {
            // Nothing to confirm. An offer that merely has a Back or Cancel button is still an offer to judge.
            if (isOfferScreen(confirmation)) confirmation = null;
        } else if (!ownsConfirmation(confirmation)) {
            declineState.endConfirmation();
            status("A different offer is showing; the earlier decline cannot confirm it. Judging it on its own.");
            confirmation = null;
        }
        if (confirmation != null) return handleConfirmation(confirmation, settings, now);

        OfferSnapshot offer = OfferParser.parse(scan.text, scan.metricParts);
        if (scan.accept == null || scan.decline == null) return handleOtherScreen(scan, offer, settings, now);
        Dashing.seen(this);
        if (scan.accept.equals(scan.decline)) {
            status("Ambiguous shared button target; no action.");
            return false;
        }
        return handleOffer(scan, offer, settings, now);
    }

    /** Second step of a decline this service requested. Pausing or a newer offer revokes the authority. */
    private boolean handleConfirmation(Scan confirmation, FilterSettings settings, long now) {
        diagnostic("confirmation", confirmation, null, null);
        declinedOfferShowing = true;
        if (!settings.enabled || !declineState.hasPendingConfirmation(now)) return false;
        if (declineGeneration != OfferNotificationService.generation()) {
            declineState.reset();
            status("A new notification arrived; old decline confirmation authority revoked.");
            return false;
        }
        int selected = DeclineConfirmation.select(confirmation.text, confirmation.declineLabels);
        reportIfStuck(confirmation, now);
        if (selected >= 0 && declineState.mayConfirm(now) && ownClick(confirmation.declineTargets.get(selected))) {
            declineState.confirmationSent(now);
            if (declinedEntry != null) {
                DecisionLog.record(this, new DecisionLog.Entry(declinedEntry.at, declinedEntry.source,
                        declinedEntry.addOn, declinedEntry.facts, declinedEntry.requiredCents, declinedEntry.result,
                        declinedEntry.reason, DecisionLog.Action.CONFIRMATION_TAPPED, true, declinedEntry.evidence));
            }
            status("Decline confirmation requested; waiting for Dasher to close the offer.");
        }
        return true;
    }

    /**
     * Whether a confirmation-shaped surface is the confirmation of the offer we declined. A surface whose facts
     * contradict that offer is a different offer. A surface that still has an Accept button is the declined offer
     * with a dialog drawn over it, so it must positively show that offer; otherwise it is the next offer, perhaps
     * not fully drawn, and is judged on its own.
     */
    private boolean ownsConfirmation(Scan surface) {
        OfferSnapshot shown = OfferParser.parse(surface.text, surface.metricParts);
        if (shown.contradicts(declinedOffer)) return false;
        return surface.accept == null || shown.agreesWith(declinedOffer);
    }

    /** Distinct Accept and Decline targets with no question about declining: an offer, not a dialog. */
    private static boolean isOfferScreen(Scan scan) {
        return scan.accept != null && scan.decline != null && !scan.accept.equals(scan.decline)
                && !DeclineConfirmation.hasPrompt(scan.text);
    }

    /** A Dasher screen without both offer controls: delivery progress, idle, or an offer still loading. */
    private boolean handleOtherScreen(Scan scan, OfferSnapshot offer, FilterSettings settings, long now) {
        AcceptedOfferTracker.Acceptance accepted = acceptedTracker.observeOtherScreen(scan.text, now);
        if (accepted != null) {
            recordAcceptance(accepted);
            return false;
        }
        if (OfferEvidence.isDashOver(scan.text)) {
            ManualDeclines.dashEnded(this);
            Dashing.ended(this);
        } else if (OfferEvidence.isIdle(scan.text) || AcceptedOfferTracker.isDeliveryScreen(scan.text)) {
            Dashing.seen(this);
        }
        if (OfferEvidence.isIdle(scan.text)) {
            boolean pending = declineState.hasPendingConfirmation(now);
            ActiveRouteStore.clear(this);
            declineState.reset();
            forgetTakeover();
            status(pending
                    ? "Dasher returned to the idle screen after decline; no active offer visible."
                    : "Dasher is finding offers.");
            return false;
        }
        // Declining an add-on returns straight to the delivery: no confirmation is coming after that.
        if (AcceptedOfferTracker.isDeliveryScreen(scan.text)) {
            declineState.endConfirmation();
            forgetTakeover();
        }
        if (scan.accept == null && scan.decline == null) {
            declineState.offerGone();
            return declineState.hasPendingConfirmation(now);
        }
        // Half an offer: still ours only if it positively shows the declined offer's facts.
        declinedOfferShowing = offer.agreesWith(declinedOffer);
        diagnostic("incomplete-controls", scan, offer, null);
        status(offer.summary() + "\nBoth offer controls are not yet readable; no action.");
        return settings.enabled;
    }

    private void recordAcceptance(AcceptedOfferTracker.Acceptance accepted) {
        // Accepting after all means an earlier Decline tap on this offer was backed out of.
        ManualDeclines.forget(this);
        if (!accepted.addOn && accepted.acceptedOffer.payCents != null) {
            FilterStore.recordAccepted(this, accepted.acceptedOffer);
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
        // An add-on's own pay is its explicit "+$" increment, which is never standalone pay.
        acceptedTracker.observeOffer(
                isAddOn ? addOn.incremental : offer, isAddOn ? addOn.combined : offer, isAddOn, now);
        OfferRule.Decision decision = isAddOn
                ? OfferRule.evaluateAddOn(addOn, settings) : OfferRule.evaluate(offer, settings);
        diagnostic(isAddOn ? "add-on" : "offer", scan, offer, decision);
        String detail = isAddOn ? addOn.summary() : offer.summary();
        String key = DeclineState.offerKey(offer, scan.text);
        if (isTakenOver(offer, now)) {
            declinedOfferShowing = false;
            status(detail + "\nYou took over this offer; no automatic action.");
            return false;
        }
        // A clearly different offer, or a long-expired takeover, ends it.
        forgetTakeover();
        declinedOfferShowing = decision.result == OfferRule.Result.DECLINE
                && (key.equals(declinedKey) || offer.agreesWith(declinedOffer));

        boolean letThrough = settings.enabled && decision.result == OfferRule.Result.KEEP;
        if (letThrough && !isAddOn) {
            passingOffer = offer;
            passingOfferAt = now;
        } else if (passingOffer != null && !offer.agreesWith(passingOffer)) {
            passingOffer = null;
        }
        if (!settings.enabled || decision.result != OfferRule.Result.DECLINE) {
            declineState.reset();
            DecisionLog.Entry entry = record(scan, isAddOn, decision, settings, !settings.enabled
                    ? DecisionLog.Action.PAUSED
                    : decision.result == OfferRule.Result.KEEP ? DecisionLog.Action.PASSES
                    : DecisionLog.Action.NEEDS_REVIEW);
            if (decision.result == OfferRule.Result.REVIEW) reportUnreadable(scan, offer, entry);
            status(detail + "\n" + decision.summary() + (settings.enabled ? "" : "\nAuto-decline is off."));
            return settings.enabled && decision.result == OfferRule.Result.REVIEW;
        }
        if (!declineState.mayDecline(key, now)) {
            if (key.equals(declinedKey)) reportIfStuck(scan, now);
            return declineState.hasPendingConfirmation(now);
        }
        // Re-check that Dasher is still on screen just before acting: the screen can change while it is being read.
        if (dasherRoot() != null && ownClick(scan.decline)) {
            boolean firstTap = !key.equals(declinedKey) || !declineState.hasPendingConfirmation(now);
            declineState.declineSent(key, now);
            declineGeneration = OfferNotificationService.generation();
            declinedOffer = offer;
            declinedEntry = record(scan, isAddOn, decision, settings, DecisionLog.Action.DECLINE_TAPPED);
            if (firstTap) {
                declinedKey = key;
                declinedAt = now;
            }
            declinedOfferShowing = true;
            // Silence and watch for a takeover at once; the pass's end would be a few milliseconds later.
            syncAutomation();
            handler.removeCallbacks(syncAutomation);
            handler.postDelayed(syncAutomation, DeclineState.CONFIRMATION_WINDOW_MS + RECHECK_INTERVAL_MS);
            DiagnosticLog.log(this, "accessibility", "first-step Decline REQUESTED: " + detail
                    + "; sound playing: " + OfferSilencer.playing(this));
            status("Decline requested: " + detail + "\n" + decision.summary());
        } else {
            record(scan, isAddOn, decision, settings, DecisionLog.Action.DECLINE_REFUSED);
            status("Decline click was not accepted by Android. No completion claimed.");
        }
        return true;
    }

    /**
     * A declined offer, or its confirmation, still on screen {@link #STUCK_MS} after the first Decline tap means
     * Dasher is still ringing for it: report what the screen shows, once per offer.
     */
    private void reportIfStuck(Scan scan, long now) {
        if (declinedKey.isEmpty() || declinedKey.equals(reportedStuck) || now - declinedAt < STUCK_MS) return;
        reportedStuck = declinedKey;
        List<String> labels = new ArrayList<>(scan.text);
        labels.addAll(scan.metricParts);
        DiagnosticLog.log(this, "accessibility", "decline still showing after " + (now - declinedAt) + " ms");
        ReportOutbox.fileAutomatic(this, ProblemReport.Kind.DECLINE_STUCK, declinedEntry, labels, null);
    }

    /** A visible offer the rules could not judge is a reading gap worth fixing: report it once per offer. */
    private void reportUnreadable(Scan scan, OfferSnapshot offer, DecisionLog.Entry entry) {
        String key = DeclineState.offerKey(offer, scan.text);
        if (key.equals(reportedOffer) || !ReportOutbox.enabled(this)) return;
        reportedOffer = key;
        List<String> labels = new ArrayList<>(scan.text);
        labels.addAll(scan.metricParts);
        ReportOutbox.fileAutomatic(this, ProblemReport.Kind.UNREADABLE_OFFER, entry, labels, null);
    }

    private DecisionLog.Entry record(Scan scan, boolean addOn, OfferRule.Decision decision, FilterSettings settings,
                                     DecisionLog.Action action) {
        List<String> labels = new ArrayList<>(scan.text);
        labels.addAll(scan.metricParts);
        DecisionLog.Entry entry = DecisionLog.Entry.of(DecisionLog.Source.SCREEN, addOn, decision.basis, decision,
                action, settings.enabled, labels);
        DecisionLog.record(this, entry);
        return entry;
    }

    /** A confirmation surface in any Dasher window while a decline is pending, else in the active window. */
    private Scan confirmationScan(Scan primary, boolean pending) {
        if (pending) {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (!isDasher(root)) continue;
                Scan candidate = Scan.of(root);
                if (!candidate.truncated && DeclineConfirmation.isSurface(candidate.text)) return candidate;
            }
        }
        return DeclineConfirmation.isSurface(primary.text) ? primary : null;
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

    private static boolean isDeclineClick(AccessibilityEvent event) {
        for (CharSequence label : event.getText()) {
            if (isDeclineLabel(label)) return true;
        }
        if (isDeclineLabel(event.getContentDescription())) return true;
        AccessibilityNodeInfo node = event.getSource();
        for (int depth = 0; depth < MAX_ACCEPT_LABEL_ANCESTORS && node != null; depth++, node = node.getParent()) {
            if (isDeclineLabel(node.getText()) || isDeclineLabel(node.getContentDescription())) return true;
        }
        return false;
    }

    private static boolean isDeclineLabel(CharSequence label) {
        return label != null && OfferControls.isButton(label.toString(), "decline");
    }

    /** The app's own tap; its click event, arriving just after, is not mistaken for the user's. */
    private boolean ownClick(AccessibilityNodeInfo node) {
        ownTapAt = SystemClock.uptimeMillis();
        return click(node);
    }

    /** Dasher's window to read: the active one, or Dasher's half of a split screen; null when neither. */
    private AccessibilityNodeInfo dasherRoot() {
        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        return isDasher(activeRoot) ? activeRoot : splitDasherRoot();
    }

    /** Dasher's half of a split screen while the user is in the other app's half (Offer Filter's, say). */
    private AccessibilityNodeInfo splitDasherRoot() {
        AccessibilityWindowInfo half = dasherWindow(false);
        AccessibilityNodeInfo root = half == null ? null : half.getRoot();
        return isDasher(root) ? root : null;
    }

    /**
     * Dasher's application window among those on screen: with {@code dasherActive}, the one the active root is in;
     * otherwise only Dasher's half of a split screen, and only while another app's half is the active window. While
     * the shade, recents or any other system surface is in front, nothing: Dasher is left alone then, as before.
     */
    private AccessibilityWindowInfo dasherWindow(boolean dasherActive) {
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (RuntimeException unavailable) {
            splitSeen = false;
            return null;
        }
        boolean split = false;
        boolean otherAppActive = false;
        AccessibilityWindowInfo dasher = null;
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) split = true;
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            if (!isDasher(window.getRoot())) {
                if (window.isActive()) otherAppActive = true;
                continue;
            }
            // The active Dasher window first, else the one in front.
            if (dasher == null || (window.isActive() && !dasher.isActive())
                    || (window.isActive() == dasher.isActive() && window.getLayer() > dasher.getLayer())) {
                dasher = window;
            }
        }
        splitSeen = split;
        if (dasher == null) return null;
        return dasherActive || (split && otherAppActive) ? dasher : null;
    }

    /**
     * Where Dasher is on screen, for the filter tab: its window's bounds (half the screen when split), or the whole
     * screen when Android does not say; null when Dasher is not on screen.
     */
    private Rect dasherArea() {
        boolean dasherActive = isDasher(getRootInActiveWindow());
        AccessibilityWindowInfo window = dasherWindow(dasherActive);
        if (window != null) {
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) return bounds;
        }
        if (!dasherActive) return null;
        android.util.DisplayMetrics screen = getResources().getDisplayMetrics();
        return new Rect(0, 0, screen.widthPixels, screen.heightPixels);
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
            if (OfferControls.isButton(label, "accept") && accept == null) accept = clickTarget(node);
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
