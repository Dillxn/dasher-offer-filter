package com.local.dasherfilter;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Moves Android's split-screen divider for a map in one half, as {@link SplitDivider} decides (0.6.21; for a delivery
 * from 0.6.11), with the divider's own accessibility actions (stock Android gives its handle custom actions such as
 * "Top 30%" for screen readers, Samsung "70% top, 30% bottom"): one {@code performAction} on the divider's node, never a
 * gesture, a drag or {@code dispatchGesture}, and never anything of Dasher's or the map's. It reads Android's list of
 * windows, the divider window's few nodes (once per split for its labels, again only when acting, and for the probe),
 * and, once per window, the root alone of a half not yet known by its ID (as a look asks once whose a window is); never
 * the root of a window known as Dasher's. Scanner thread, except {@link #kick}.
 *
 * <p>The probe: on entering split screen, once the split has stood {@link SplitDivider#SETTLE_MS} and at most every
 * {@link SplitDivider#PROBE_EVERY_MS}, never while an offer or decline is up, the divider's action labels go in the log
 * ("[split] divider actions: Top full screen | Top 70% | ..."), so a report shows whether a phone offers them.
 */
final class SplitDividerMover {
    /** What the screen reader gives the mover. */
    interface Host {
        Context context();

        /** Android's list of windows, or null when it would not give one. Nothing is asked of any app. */
        List<AccessibilityWindowInfo> windows();

        /** Running with the notice accepted, the screen on and the phone unlocked: Android's windows may be looked at. */
        boolean running();

        /** Dasher's or Offer Filter's, by the window's ID alone (as reads and events named it); null when not known. */
        SplitWindows.Owner knownOwner(AccessibilityWindowInfo window);

        /** The package of a window not known by its ID, by its root alone (nothing below it); null when not given. */
        String rootPackage(AccessibilityWindowInfo window);

        /** The guards' facts now. */
        SplitDivider.Moment moment(long now);

        /** An offer or its question is up, or was in the last 3 s, or a decline is under way: no probe now. */
        boolean busy(long now);

        /** The dash under way, by its start; 0 for none. */
        long dashKey();

        /** Runs {@code run} on the scanner thread at {@code uptime}. */
        void postAt(Runnable run, long uptime);

        /** {@code run} no longer runs (as posted by {@link #postAt}). */
        void remove(Runnable run);
    }

    /** How often the split is looked at while it stands. */
    static final long TICK_MS = 1_000;
    /** The soonest a kick looks again after the last look. */
    static final long KICK_GAP_MS = 250;
    /** For tests: each window whose root the mover asks for. */
    static volatile Consumer<AccessibilityWindowInfo> rootAskedForTests;

    private final Host host;
    private final SplitDivider plan = new SplitDivider();
    private final AtomicBoolean kickQueued = new AtomicBoolean();
    private static final long NONE = Long.MIN_VALUE;
    /** When the next look is posted for (uptime), or {@link #NONE}; scanner thread. */
    private long tickDueAt = NONE;
    /** The package of each half asked for by its root, by window ID, for this split. */
    private final Map<Integer, String> packages = new HashMap<>();
    private long lastTickAt = Long.MIN_VALUE / 2;
    /** When this split was first seen (uptime), 0 while none. */
    private long splitSince;
    private boolean probed;
    private long probedAt = Long.MIN_VALUE / 2;
    private boolean labelsNoted;
    /** The divider's actions as first read in this split (their labels and shares; no node kept), or null. */
    private Actions labels;

    SplitDividerMover(Host host) {
        this.host = host;
    }

    /** Any thread: the windows may have changed; look soon (the scanner thread decides when). */
    void kick() {
        if (kickQueued.compareAndSet(false, true)) host.postAt(kicked, SystemClock.uptimeMillis());
    }

    private final Runnable kicked = () -> {
        kickQueued.set(false);
        again(Math.max(SystemClock.uptimeMillis(), lastTickAt + KICK_GAP_MS));
    };

    /** Scanner thread: the next look at {@code at}, unless one is due sooner. */
    private void again(long at) {
        if (tickDueAt != NONE && tickDueAt <= at) return;
        if (tickDueAt != NONE) host.remove(tick);
        tickDueAt = at;
        host.postAt(tick, at);
    }

    private final Runnable tick = () -> {
        tickDueAt = NONE;
        look();
    };

    private void log(String line) {
        if (line != null && !line.isEmpty()) DiagnosticLog.log(host.context(), "split", line);
    }

    /** One look at the split: the probe, a move's check, the user's move, and a move when one is due and safe. */
    private void look() {
        if (!host.running()) return;
        long now = SystemClock.uptimeMillis();
        lastTickAt = now;
        List<AccessibilityWindowInfo> windows;
        try {
            windows = host.windows();
        } catch (RuntimeException unavailable) {
            windows = null;
        }
        if (windows == null) {
            if (splitSince != 0) again(now + TICK_MS);
            return;
        }
        AccessibilityWindowInfo divider = divider(windows);
        if (divider == null) {
            if (splitSince != 0) {
                splitSince = 0;
                probed = false;
                labelsNoted = false;
                labels = null;
                packages.clear();
                log(plan.splitEnded());
            }
            return;
        }
        if (splitSince == 0) splitSince = now;
        plan.dash(host.dashKey());
        if (!probed && now - splitSince >= SplitDivider.SETTLE_MS && now - probedAt >= SplitDivider.PROBE_EVERY_MS
                && !host.busy(now)) {
            probe(divider);
            probed = true;
            probedAt = now;
        }
        Halves halves = Halves.of(windows, divider);
        if (halves != null) {
            log(plan.verify(halves.firstShare, now));
            Pane panes = panes(halves);
            log(plan.look(panes == null ? null : panes.first, panes == null ? null : panes.second, halves.firstShare,
                    now));
            if (panes != null) act(halves, panes, divider, now);
        }
        again(now + TICK_MS);
    }

    /** A move, when one is due, the divider offers one, the cap allows it and the moment is safe. */
    private void act(Halves halves, Pane panes, AccessibilityWindowInfo divider, long now) {
        SplitDivider.Want want = plan.want(now);
        if (want == SplitDivider.Want.NONE) return;
        if (labels == null) labels = actions(divider);
        if (labels == null || labels.targets.isEmpty()) {
            if (!labelsNoted) {
                labelsNoted = true;
                log("divider: no action names a share ("
                        + (labels == null || labels.labels.isEmpty() ? "none" : String.join(" | ", labels.labels))
                        + "); left as it is");
            }
            labels = null;
            return;
        }
        SplitDivider.Target target;
        String line;
        if (want == SplitDivider.Want.SHRINK) {
            SplitDivider.Pane small = SplitDivider.shrinks(panes.first, panes.second);
            boolean mapFirst = panes.first == SplitDivider.Pane.MAP;
            target = SplitDivider.forMap(labels.targets, mapFirst);
            if (target == null) {
                if (!labelsNoted) {
                    labelsNoted = true;
                    log("divider: no action gives the map's half " + SplitDivider.MAP_SHARE + "% ("
                            + String.join(" | ", labels.labels) + "); left as it is");
                }
                return;
            }
            int halfNow = mapFirst ? 100 - halves.firstShare : halves.firstShare;
            int halfThen = mapFirst ? 100 - target.share : target.share;
            if (!SplitDivider.roomFor(halfNow, halfThen)) {
                if (plan.sayAlready()) {
                    log("divider: " + small.possessive + " half is " + halfNow + "% beside the map already; left as "
                            + "it is");
                }
                return;
            }
            line = "divider: " + small.possessive + " half to " + halfThen + "% for the map";
        } else {
            target = SplitDivider.forRestore(labels.targets, plan.firstBefore());
            if (target == null) return;
            if (Math.abs(target.share - halves.firstShare) <= SplitDivider.MOVED_WITHIN) {
                plan.restored();
                return;
            }
            line = "divider: top or left half back to " + target.share + "% after the map left";
        }
        if (SplitDivider.blocked(host.moment(now)) != null) return;
        String cap = plan.capped();
        if (cap != null) {
            log(cap);
            return;
        }
        // The node is read again for the action itself: one kept from an earlier look may be stale.
        Actions fresh = actions(divider);
        SplitDivider.Target action = fresh == null || fresh.node == null ? null : sameShare(fresh.targets, target);
        if (action == null) {
            labels = fresh != null && !fresh.targets.isEmpty() ? fresh : null;
            return;
        }
        boolean done;
        try {
            done = fresh.node.performAction(action.id);
        } catch (RuntimeException refused) {
            done = false;
        }
        if (!done) {
            log(plan.stop("divider refused its action (\"" + action.label + "\")"));
            return;
        }
        plan.moved(want, halves.firstShare, action.share, now);
        log(line + " (\"" + action.label + "\")");
        again(now + SplitDivider.VERIFY_MS);
    }

    /** The action among {@code targets} giving {@code wanted}'s share, or null. */
    private static SplitDivider.Target sameShare(List<SplitDivider.Target> targets, SplitDivider.Target wanted) {
        for (SplitDivider.Target target : targets) {
            if (target.share == wanted.share) return target;
        }
        return null;
    }

    /** The probe's line: the divider's action labels, or none with its node's class. */
    private void probe(AccessibilityWindowInfo divider) {
        Actions actions = actions(divider);
        if (actions != null && !actions.targets.isEmpty()) labels = actions;
        if (actions == null || actions.labels.isEmpty()) {
            log("divider actions: none (" + (actions == null ? "no node" : actions.className) + ")");
        } else {
            log("divider actions: " + String.join(" | ", actions.labels));
        }
    }

    // ---- The divider's node ----

    /** The divider's node holding its labelled actions, those labels, and the ones naming a share. */
    private static final class Actions {
        AccessibilityNodeInfo node;
        String className = "?";
        final List<String> labels = new ArrayList<>();
        final List<SplitDivider.Target> targets = new ArrayList<>();
    }

    /** Longest label kept: these are fixed system words. */
    private static final int MAX_LABEL = 60;

    /**
     * The divider window's root, then at most {@link SplitDivider#MAX_NODES} of its nodes, for the one with the most
     * labelled actions. Null when Android gives no root.
     */
    private Actions actions(AccessibilityWindowInfo divider) {
        Consumer<AccessibilityWindowInfo> asked = rootAskedForTests;
        if (asked != null) asked.accept(divider);
        AccessibilityNodeInfo root;
        try {
            root = divider.getRoot();
        } catch (RuntimeException unavailable) {
            root = null;
        }
        if (root == null) return null;
        Actions found = new Actions();
        CharSequence rootClass = root.getClassName();
        found.className = rootClass == null ? "?" : rootClass.toString();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int walked = 0;
        int most = 0;
        while (!queue.isEmpty() && walked < SplitDivider.MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            walked++;
            int labelled = 0;
            for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                CharSequence label = action.getLabel();
                if (label != null && label.length() > 0) labelled++;
            }
            if (labelled > most) {
                most = labelled;
                found.node = node;
            }
            for (int i = 0, n = node.getChildCount(); i < n && queue.size() + walked < SplitDivider.MAX_NODES; i++) {
                AccessibilityNodeInfo child;
                try {
                    child = node.getChild(i);
                } catch (RuntimeException gone) {
                    child = null;
                }
                if (child != null) queue.add(child);
            }
        }
        if (found.node == null) return found;
        CharSequence nodeClass = found.node.getClassName();
        if (nodeClass != null) found.className = nodeClass.toString();
        for (AccessibilityNodeInfo.AccessibilityAction action : found.node.getActionList()) {
            CharSequence label = action.getLabel();
            if (label == null || label.length() == 0) continue;
            String text = label.toString().replace('\n', ' ').trim();
            if (text.length() > MAX_LABEL) text = text.substring(0, MAX_LABEL);
            found.labels.add(text);
            int share = SplitDivider.share(text);
            if (share > 0) found.targets.add(new SplitDivider.Target(action.getId(), text, share));
        }
        return found;
    }

    private static AccessibilityWindowInfo divider(List<AccessibilityWindowInfo> windows) {
        Rect bounds = new Rect();
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) continue;
            window.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) return window;
        }
        return null;
    }

    // ---- The halves ----

    /** The two halves beside the divider, and the top or left one's share of the screen. */
    static final class Halves {
        final AccessibilityWindowInfo first;
        final AccessibilityWindowInfo second;
        final int firstShare;

        private Halves(AccessibilityWindowInfo first, AccessibilityWindowInfo second, int firstShare) {
            this.first = first;
            this.second = second;
            this.firstShare = firstShare;
        }

        /**
         * Two application windows with a divider between them, as Android lists them, or null: a keyboard, an active
         * system window, a third app's window (a dialog), a half covered, or bounds not known. A picture-in-picture
         * window floats over the halves and is neither (0.6.21: before, it hid the split). From Android's list alone.
         */
        static Halves of(List<AccessibilityWindowInfo> windows, AccessibilityWindowInfo divider) {
            Rect bounds = new Rect();
            Rect line = new Rect();
            divider.getBoundsInScreen(line);
            Rect screen = new Rect(line);
            AccessibilityWindowInfo a = null;
            AccessibilityWindowInfo b = null;
            for (AccessibilityWindowInfo window : windows) {
                int type = window.getType();
                window.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()) screen.union(bounds);
                if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                        || (type == AccessibilityWindowInfo.TYPE_SYSTEM && window.isActive())) {
                    return null;
                }
                if (type != AccessibilityWindowInfo.TYPE_APPLICATION || window.isInPictureInPictureMode()) continue;
                if (bounds.isEmpty()) return null;
                if (a == null) a = window;
                else if (b == null) b = window;
                else return null;
            }
            if (a == null || b == null || screen.isEmpty()) return null;
            if (SplitWindows.covered(windows, a, b) != null || SplitWindows.covered(windows, b, a) != null) return null;
            boolean stacked = line.width() >= line.height();
            Rect ra = new Rect();
            Rect rb = new Rect();
            a.getBoundsInScreen(ra);
            b.getBoundsInScreen(rb);
            boolean aFirst = stacked ? ra.exactCenterY() < rb.exactCenterY() : ra.exactCenterX() < rb.exactCenterX();
            float share = stacked ? (line.exactCenterY() - screen.top) / screen.height()
                    : (line.exactCenterX() - screen.left) / screen.width();
            int percent = Math.max(0, Math.min(100, Math.round(100f * share)));
            return aFirst ? new Halves(a, b, percent) : new Halves(b, a, percent);
        }
    }

    /** Whose each half is. */
    private static final class Pane {
        final SplitDivider.Pane first;
        final SplitDivider.Pane second;

        Pane(SplitDivider.Pane first, SplitDivider.Pane second) {
            this.first = first;
            this.second = second;
        }
    }

    /** Whose each half is, or null when one cannot be told. */
    private Pane panes(Halves halves) {
        SplitDivider.Pane first = pane(halves.first);
        SplitDivider.Pane second = first == null ? null : pane(halves.second);
        return second == null ? null : new Pane(first, second);
    }

    /**
     * Whose a half is: Dasher's or Offer Filter's by its ID, else by its root alone, asked once per window and split
     * (a window Android gave no ID is never asked: it could be Dasher's). Each look asks, so a map taking a half is
     * seen within a look.
     */
    private SplitDivider.Pane pane(AccessibilityWindowInfo window) {
        SplitWindows.Owner known = host.knownOwner(window);
        if (known == SplitWindows.Owner.DASHER) return SplitDivider.Pane.DASHER;
        if (known == SplitWindows.Owner.OURS) return SplitDivider.Pane.OURS;
        int id = window.getId();
        if (id < 0 || id == Integer.MAX_VALUE) return null;
        String pkg = packages.get(id);
        if (pkg == null) {
            Consumer<AccessibilityWindowInfo> asked = rootAskedForTests;
            if (asked != null) asked.accept(window);
            try {
                pkg = host.rootPackage(window);
            } catch (RuntimeException unavailable) {
                pkg = null;
            }
            if (pkg == null) return null;
            if (packages.size() >= 16) packages.clear();
            packages.put(id, pkg);
        }
        if (DasherSplit.DASHER_PACKAGE.equals(pkg)) return SplitDivider.Pane.DASHER;
        if (pkg.equals(host.context().getPackageName())) return SplitDivider.Pane.OURS;
        return Peek.NAVIGATION.contains(pkg) ? SplitDivider.Pane.MAP : SplitDivider.Pane.OTHER;
    }
}
