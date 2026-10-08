package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * The app's own taps on Dasher (each one {@code performAction} call), so their echoes are never taken for the user's
 * touch or click. Each tap has a window of its own: from the moment it began until {@link #TOUCH_ECHO_MS} after it
 * returned. A later tap opens a window of its own and never stretches an earlier one, and a tap the app did not make
 * (a control it found not clickable) opens none. A click event is the app's own only when it names the very node a
 * tap was asked to click within {@link #TARGET_ECHO_MS} after that tap began; a click event with no node is judged by
 * its own time against each tap's window. A click on any other node is the user's, however soon after a tap of ours.
 *
 * <p>Written on the scanner thread only; any thread may ask. Each change publishes a new immutable list. Pure Java:
 * the nodes are opaque, compared by the caller's predicate.
 */
final class OwnTaps {
    /** A touch this soon after one of the app's taps returned (or during it) is that tap's echo. */
    static final long TOUCH_ECHO_MS = 150;
    /** A click event with no node, stamped this soon after one of the app's taps returned (or during it), is its echo. */
    static final long CLICK_ECHO_MS = 150;
    /**
     * A click on the very node the app tapped this soon after the tap began is that tap's echo, even when its event
     * comes late (a read can take seconds on a slow phone).
     */
    static final long TARGET_ECHO_MS = 5_000;
    /**
     * A touch Android made up for an accessibility click ({@link TouchWatch#madeUp}) this soon after one of the app's
     * taps began is that tap's echo: it is stamped when Offer Filter's main thread got to it, which a busy phone puts
     * well past {@link #TOUCH_ECHO_MS} (0.5.1: 209 ms after a peek's first-step Decline). A finger is never one.
     */
    static final long MADE_UP_ECHO_MS = TARGET_ECHO_MS;
    /** Taps remembered at most; older ones are long past every window. */
    static final int KEPT = 8;
    /** Not ended yet. */
    static final long UNDER_WAY = Long.MIN_VALUE;

    /** One tap: its target, when it began and ended (uptime), and whether Android took the click. */
    static final class Tap {
        final Object target;
        final long began;
        final long ended;
        /** Android took the click (performAction returned true); false while under way or when it refused. */
        final boolean requested;

        Tap(Object target, long began, long ended, boolean requested) {
            this.target = target;
            this.began = began;
            this.ended = ended;
            this.requested = requested;
        }

        boolean underWay() {
            return ended == UNDER_WAY;
        }

        /** Whether {@code at} lies in this tap's window: during it, or within {@code echoMs} after it returned. */
        boolean covers(long at, long echoMs) {
            return at >= began && (underWay() || at - ended <= echoMs);
        }
    }

    private volatile List<Tap> taps = Collections.emptyList();

    /** A tap begins: published before Android is asked, so its echo is known even while the call is under way. */
    void began(Object target, long at) {
        List<Tap> next = new ArrayList<>(taps);
        next.add(new Tap(target, at, UNDER_WAY, false));
        while (next.size() > KEPT) next.remove(0);
        taps = Collections.unmodifiableList(next);
    }

    /** The tap begun last has returned: whether Android took the click. */
    void ended(long at, boolean requested) {
        List<Tap> next = new ArrayList<>(taps);
        if (next.isEmpty()) return;
        Tap last = next.remove(next.size() - 1);
        next.add(new Tap(last.target, last.began, Math.max(at, last.began), requested));
        taps = Collections.unmodifiableList(next);
    }

    /** The newest tap, or null for none. */
    Tap last() {
        List<Tap> now = taps;
        return now.isEmpty() ? null : now.get(now.size() - 1);
    }

    /** The tap whose echo a touch landing at {@code at} (uptime) is, or null: the touch is the user's. */
    Tap touchEcho(long at) {
        return touchEcho(at, false);
    }

    /**
     * The tap whose echo a touch the watch reports at {@code at} (uptime) is, or null: the touch is the user's. A
     * finger's touch is an echo only in a tap's window; one Android made up for an accessibility click
     * ({@code madeUp}) is when one of the app's taps began at most {@link #MADE_UP_ECHO_MS} before it (long after the
     * app's last tap, it is another service's click, the user's).
     */
    Tap touchEcho(long at, boolean madeUp) {
        List<Tap> now = taps;
        for (int i = now.size() - 1; i >= 0; i--) {
            Tap tap = now.get(i);
            if (tap.covers(at, TOUCH_ECHO_MS) || madeUp && at >= tap.began && at - tap.began <= MADE_UP_ECHO_MS) {
                return tap;
            }
        }
        return null;
    }

    /**
     * The tap whose echo a click event is, or null: the click is the user's. A click with a node is the app's own only
     * when it is the node a tap was under way on or Android took the click of, within {@link #TARGET_ECHO_MS} after
     * it began; with no node, only when its time lies in a tap's window. A refused tap clicked nothing, so it has no
     * echo once it returned.
     *
     * @param source the click's node, or null when Android gave none
     * @param at the click's own time (uptime)
     * @param same whether two nodes are the same node
     */
    Tap clickEcho(Object source, long at, BiPredicate<Object, Object> same) {
        List<Tap> now = taps;
        for (int i = now.size() - 1; i >= 0; i--) {
            Tap tap = now.get(i);
            if (!tap.underWay() && !tap.requested) continue;
            if (at < tap.began) continue;
            if (source != null) {
                if (tap.target != null && at - tap.began <= TARGET_ECHO_MS && same.test(source, tap.target)) return tap;
            } else if (tap.covers(at, CLICK_ECHO_MS)) {
                return tap;
            }
        }
        return null;
    }
}
