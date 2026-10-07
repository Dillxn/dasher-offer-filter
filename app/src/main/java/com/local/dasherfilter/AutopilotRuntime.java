package com.local.dasherfilter;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Autopilot's runtime (finalSpec.autopilot.updateTiming, arGuard and explainability): it plans on its own thread and
 * commits only where the screen reader says it is safe.
 *
 * <p><b>Planning</b> runs on one background executor, "offer-autopilot". {@link #requestPlan} is coalesced and returns
 * at once; it does nothing before the current notice is accepted or while Autopilot is off (the 60 s tick also needs
 * auto-decline on and a dash under way, not paused). A plan loads the rules, the decision history and the watched
 * waiting (each under its own lock, never nested), runs the pure {@link Autopilot#plan}, keeps the acceptance-rate
 * state it changed, logs it when its target or mode changed (else at most every 10 minutes), publishes it, and calls
 * the commit requester the screen reader registered ({@link #setCommitRequester}) when it wants the bar moved. A plan
 * whose history was cleared, or whose rules changed, while it was worked out is dropped, never kept or published.
 * Failures are caught and logged ("plan failed: …"); nothing here ever decides an offer.
 *
 * <p><b>Commits</b> ({@link #commitIfDue}) run only on the screen reader's own thread at a safe point (between
 * offers, nothing in flight): the runtime never references the service, which registers the requester and decides the
 * safe point. A commit discards a plan that is stale (history cleared since, older than 15 minutes, other rules, or a
 * bar that moved since), moves the bar one limited {@link Autopilot#step} by compare-and-set
 * ({@link FilterStore#commitAutopilotBar}), keeps a note of the change and logs it. Its last checks and its writes
 * happen under the runtime's lock, which a user's change and Clear history take too, so neither can land between
 * them. A user's change (Autopilot turned on, another goal, other minimums, history cleared) is a "jump": the next
 * commit goes straight to the target, still only at a safe point.
 *
 * <p><b>Dasher's acceptance rate</b> comes only from its decline question ({@link #confirmationSeen}): the labels are
 * parsed here, off the screen reader's thread, one number is kept (no text), and an offer Dasher says is free to
 * decline gets its mark on its own history line.
 *
 * <p>Every log line is category {@value #LOG}, built by {@link AutopilotText} from fixed words and numbers.
 */
final class AutopilotRuntime {
    /** The diagnostic log's category for every Autopilot line. */
    static final String LOG = "autopilot";
    /** Decision-history lines a plan reads, newest first (all of them). */
    static final int HISTORY_LINES = DecisionLog.MAX_ENTRIES;
    /** An offer's "does not lower acceptance rate" mark goes on its line when that was recorded this recently. */
    static final long STEP_WINDOW_MS = 10 * 60_000L;
    /** An unchanged plan is logged at most this often. */
    static final long PLAN_LOG_EVERY_MS = 10 * 60_000L;
    /** "commit deferred" is logged at most this often. */
    static final long DEFERRAL_LOG_EVERY_MS = 60_000L;
    /** The exemption valve is logged at most this often. */
    static final long EXEMPT_LOG_EVERY_MS = 24 * 60 * 60_000L;

    /** What asked for a plan. Only the tick has gates of its own. */
    enum Trigger {
        /** The screen reader's 60 s tick: only with auto-decline on and a dash under way, not paused. */
        TICK,
        /** The user changed Autopilot or the rules. */
        USER,
        /** A new acceptance-rate reading, or an offer marked free to decline. */
        READING,
        /** The screen reader connected. */
        CONNECT,
        /** The homepage came back (for display; a plan moves nothing by itself). */
        RESUME,
        /** Clear history. */
        CLEAR,
        /** After a commit, or after a commit found its plan stale. */
        COMMIT
    }

    /** A change the user made, already saved. Every one but turning Autopilot off makes the next commit a jump. */
    enum UserChange {
        TURNED_ON(Autopilot.Reason.TURNED_ON),
        TURNED_OFF(null),
        GOAL_CHANGED(Autopilot.Reason.GOAL_CHANGED),
        /** A minimum or max stops changed (not pausing or resuming auto-decline). */
        RULES_CHANGED(Autopilot.Reason.RULES_CHANGED);

        /** The commit reason of its jump; null for turning off, which needs no commit (the bar is 100 at once). */
        final Autopilot.Reason jump;

        UserChange(Autopilot.Reason jump) {
            this.jump = jump;
        }
    }

    /** What {@link #commitIfDue} did. */
    enum Commit {
        /** No plan for the current rules (none yet, Autopilot off, or the notice not accepted). */
        NO_PLAN,
        /** The plan was stale; a new one was asked for. */
        DISCARDED,
        /** The step holds the bar (at the target, within a minute of the last commit, deadband, waiting to raise). */
        HELD,
        /** The bar moved. */
        COMMITTED,
        /** The stored bar was not the one expected: nothing was written; a new plan was asked for. */
        CONFLICT,
        /** Something failed and was logged; nothing more was written. */
        FAILED
    }

    /** The homepage listens while it is started; told on the main thread after a plan or a commit. */
    interface Listener {
        void autopilotChanged();
    }

    // ---- Test seams ----

    /** Wall clock (plans, readings, change notes). */
    static volatile LongSupplier wallClock = System::currentTimeMillis;
    /** Elapsed clock (the in-memory commit spacing). */
    static volatile LongSupplier elapsedClock = SystemClock::elapsedRealtime;
    /** Runs plans and readings instead of the "offer-autopilot" thread when set (tests: {@code Runnable::run}). */
    static volatile Executor executorForTests;
    /** Runs inside a plan after its inputs are read and before it is worked out (tests). */
    static volatile Runnable afterInputsForTests;
    /** Runs inside a commit right before its compare-and-set (tests). */
    static volatile Runnable beforeWriteForTests;
    /** Runs right before a decline question's "ar reading" line is logged, under {@link #LOCK} (tests). */
    static volatile Runnable beforeReadingLogForTests;
    /** Runs right before a published plan's line is logged, under {@link #LOCK} (tests). */
    static volatile Runnable beforePlanLogForTests;

    // ---- State ----

    private static final AtomicReference<Autopilot.Plan> latest = new AtomicReference<>();
    private static final AtomicBoolean pending = new AtomicBoolean();
    /** Clear history moves it on: anything worked out from before is dropped. */
    private static final AtomicLong generation = new AtomicLong();
    /**
     * Turning Autopilot on or off and choosing another goal move it on (under {@link #LOCK}): they forget the
     * goal-relative state, so a plan worked out from the state before them is dropped and never writes it back.
     */
    private static final AtomicLong goalEpoch = new AtomicLong();
    private static volatile Runnable commitRequester;
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /**
     * Publishing a plan (and its state), a commit's last checks and writes, and resetting for a user's change or
     * Clear history happen under this lock, so a plan worked out before such a change can never write its stale state
     * back, and a commit never lands its bar, change note or jump clearing across one. The lines that carry the
     * acceptance rate (a question's reading, a plan's line) are logged under it too, in the same hold as their
     * generation check: Clear history moves the generation on under this lock before it clears the log, so such a line
     * is always queued before that clear and goes with the old log, never into the new one. Taken before FilterStore's
     * and AutopilotStore's own locks (in that order), never the other way; nothing under it waits on another thread
     * (a log line is only queued for the log's writer).
     */
    private static final Object LOCK = new Object();

    /** In memory only, from the elapsed clock: the last commit and the last raise, both process start at first. */
    private static volatile long lastCommitElapsed = elapsedClock.getAsLong();
    private static volatile long lastRaiseElapsed = lastCommitElapsed;
    private static final AtomicLong deferralLoggedAt = new AtomicLong(Long.MIN_VALUE);
    /** The last plan logged: its target and mode, and when (elapsed). */
    private static volatile int loggedTarget = -1;
    private static volatile Autopilot.Mode loggedMode;
    private static volatile long loggedAt = Long.MIN_VALUE;
    /** The last decline question seen, for its one log line and one exemption mark. */
    private static volatile Question lastQuestion;

    /** One decline question as last seen: the offer's fingerprint, its percent (-1 none), its mark, and when. */
    private static final class Question {
        final String fingerprint;
        final int percent;
        final boolean exempt;
        final long elapsed;

        Question(String fingerprint, int percent, boolean exempt, long elapsed) {
            this.fingerprint = fingerprint;
            this.percent = percent;
            this.exempt = exempt;
            this.elapsed = elapsed;
        }
    }

    /** The "offer-autopilot" thread, created on first use, at background priority. */
    private static final class Worker {
        static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(() -> {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                } catch (RuntimeException unavailable) {
                    // The default priority is still correct, only less polite.
                }
                work.run();
            }, "offer-autopilot");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static final class Main {
        static final Handler HANDLER = new Handler(Looper.getMainLooper());
    }

    // ---- Wiring ----

    /**
     * The screen reader's way to be asked for a commit (it posts one to its own thread, which commits at a safe point
     * with {@link #commitIfDue}); null when it stops. Called on the planning thread.
     */
    static void setCommitRequester(Runnable requester) {
        commitRequester = requester;
    }

    /**
     * Starts the "offer-autopilot" thread now if it has not started yet (the screen reader calls this as it connects, on
     * the main thread), so that {@link #confirmationSeen}, called on the screen reader's thread between finding Dasher's
     * decline question and tapping it, only ever queues its parse: it never creates and starts a thread there. Once per
     * process; nothing while the tests run Autopilot elsewhere ({@link #executorForTests}).
     */
    static void warm() {
        if (executorForTests != null) return;
        try {
            Worker.EXECUTOR.execute(() -> { });
        } catch (RejectedExecutionException stopped) {
            // No thread to start: the hand-over finds the same.
        }
    }

    static void listen(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    static void unlisten(Listener listener) {
        listeners.remove(listener);
    }

    /** The latest plan, or null. */
    static Autopilot.Plan latest() {
        return latest.get();
    }

    /** The current generation: Clear history moves it on. */
    static long generation() {
        return generation.get();
    }

    // ---- Planning ----

    /**
     * Asks for a plan; returns at once. Coalesced: while one is waiting to start, more requests add nothing. Nothing
     * happens before the current notice is accepted or while Autopilot is off, and the tick also needs auto-decline on
     * and a dash under way that is not paused.
     */
    static void requestPlan(Context context, Trigger trigger) {
        if (context == null || trigger == null) return;
        Context app = app(context);
        try {
            if (!Consent.accepted(app)) return;
            FilterSettings rules = FilterStore.load(app);
            if (!rules.autopilot) return;
            if (trigger == Trigger.TICK && (!rules.enabled || !Dashing.on(app) || Dashing.isPaused(app))) return;
        } catch (RuntimeException unreadable) {
            log(app, AutopilotText.logPlanFailed(unreadable));
            return;
        }
        if (!pending.compareAndSet(false, true)) return;
        try {
            executor().execute(() -> plan(app));
        } catch (RejectedExecutionException stopped) {
            pending.set(false);
        }
    }

    /** One plan, on the planning thread. */
    private static void plan(Context app) {
        // A request from now on asks for a plan of its own: this one may already have read its inputs.
        pending.set(false);
        try {
            if (!Consent.accepted(app)) return;
            long planned = generation.get();
            // Before the state is read: a user's change from here on drops this plan instead of meeting its state.
            long epoch = goalEpoch.get();
            FilterSettings rules = FilterStore.load(app);
            if (!rules.autopilot) return;
            long wall = wallClock.getAsLong();
            List<Autopilot.OfferRecord> lines = records(DecisionLog.recent(app, HISTORY_LINES));
            List<QualifyingWait.Sample> waits = QualifyingWaitStore.snapshot(app);
            AutopilotStore.Reading stored = AutopilotStore.reading(app, wall);
            Autopilot.State state = state(app, rules);
            Runnable hook = afterInputsForTests;
            if (hook != null) hook.run();
            Autopilot.Plan plan = Autopilot.plan(new Autopilot.Inputs(rules, lines, waits, reading(stored), state, wall,
                    planned));
            String dropped = publish(app, plan, state, epoch, stored, wall);
            if (dropped != null) {
                log(app, dropped);
                return;
            }
            notifyListeners();
            Runnable requester = commitRequester;
            if (requester != null && wanted(app, plan)) requester.run();
        } catch (RuntimeException failure) {
            log(app, AutopilotText.logPlanFailed(failure));
        }
    }

    /**
     * Keeps what the plan changed of the acceptance-rate state, publishes it and logs it (its line and the exemption
     * valve's), unless Clear history, a change of rules, or Autopilot turned off or on or another goal (which forget
     * that state) came while it was worked out. The plan's line names the acceptance rate it counted with, so it is
     * logged here, under {@link #LOCK} with the generation check (see there), never after the lock is let go.
     *
     * @param epoch {@link #goalEpoch} as the plan began, before it read the state
     * @param stored the reading the plan counted with, as stored (for its age in the line)
     * @return null when published; otherwise the log line saying why it was dropped
     */
    private static String publish(Context app, Autopilot.Plan plan, Autopilot.State before, long epoch,
                                  AutopilotStore.Reading stored, long wall) {
        synchronized (LOCK) {
            if (plan.generation != generation.get()) return AutopilotText.DISCARD_CLEARED;
            if (!plan.rulesKey.equals(FilterStore.load(app).rulesKey())) return AutopilotText.DISCARD_RULES;
            if (epoch != goalEpoch.get()) return AutopilotText.DISCARD_AUTOPILOT_CHANGED;
            if (plan.recovering != before.recovering || plan.extra != before.extra
                    || plan.checkpointAt != before.checkpointAt || plan.checkpointAr != before.checkpointAr) {
                boolean checkpoint = plan.checkpointAt != Autopilot.NEVER && plan.checkpointAt > 0;
                AutopilotStore.savePlanState(app, plan.recovering, plan.extra, checkpoint ? plan.checkpointAt : 0,
                        checkpoint ? plan.checkpointAr : -1);
            }
            latest.set(plan);
            Runnable hook = beforePlanLogForTests;
            if (hook != null) hook.run();
            logPlan(app, plan, stored, wall);
            logValve(app, plan, wall);
            return null;
        }
    }

    /** What the planner keeps between plans, as stored; the bar is the stored one. */
    private static Autopilot.State state(Context app, FilterSettings rules) {
        long checkpointAt = AutopilotStore.checkpointAt(app);
        boolean checkpoint = checkpointAt > 0;
        long raisedAt = AutopilotStore.raisedAt(app);
        return new Autopilot.State(AutopilotStore.recovering(app), AutopilotStore.extra(app),
                checkpoint ? checkpointAt : Autopilot.NEVER, checkpoint ? AutopilotStore.checkpointAr(app) : -1,
                raisedAt > 0 ? raisedAt : Autopilot.NEVER, rules.minimumScalePercent, jumpCause(app));
    }

    /** The stored reading as Autopilot counts it, with the fingerprint of the offer its question was about. */
    private static Autopilot.Reading reading(AutopilotStore.Reading stored) {
        if (stored == null) return null;
        try {
            return new Autopilot.Reading(stored.percent, stored.at, stored.fingerprint);
        } catch (IllegalArgumentException notAReading) {
            return null;
        }
    }

    /**
     * The decision history as Autopilot counts it, newest first: each line's facts (never a "+$" bound on unread pay,
     * which a stored line does not keep either: {@link Autopilot.OfferRecord#facts}) and time, add-on and replay,
     * accepted ({@link DecisionLog#accepted}), declined (the app's decline went through, a "not accepted" or "counted
     * as your Decline" step, or a hidden notification of a failing offer), and Dasher's "does not lower acceptance
     * rate" mark.
     */
    static List<Autopilot.OfferRecord> records(List<DecisionLog.Entry> newestFirst) {
        List<Autopilot.OfferRecord> records = new ArrayList<>();
        if (newestFirst == null) return records;
        for (DecisionLog.Entry entry : newestFirst) {
            if (entry != null) records.add(record(entry));
        }
        return records;
    }

    static Autopilot.OfferRecord record(DecisionLog.Entry entry) {
        boolean declined = DecisionLog.outcome(entry) == DecisionLog.Outcome.DECLINED
                || DecisionLog.hasStep(entry, DecisionLog.StepKind.NOT_ACCEPTED)
                || DecisionLog.hasStep(entry, DecisionLog.StepKind.DECLINE_COUNTED)
                || (entry.action == DecisionLog.Action.NOTIFICATION_HIDDEN
                        && entry.result == OfferRule.Result.DECLINE);
        return new Autopilot.OfferRecord(entry.facts, entry.at, entry.addOn,
                entry.action == DecisionLog.Action.REPLAY, DecisionLog.accepted(entry), declined,
                DecisionLog.hasStep(entry, DecisionLog.StepKind.AR_EXEMPT));
    }

    // ---- Commits ----

    /**
     * Moves the bar one step toward the latest plan's target, if one is due. Only on the screen reader's own thread,
     * and only at a safe point (the service decides; nothing here knows about offers). A plan from before Clear
     * history, older than 15 minutes, for other rules, or made at another bar is discarded and a new one asked for.
     * A pending user change jumps straight to the target; otherwise {@link Autopilot#step} limits the move. The bar
     * is written by compare-and-set and nothing else is: no rule save, no replay.
     *
     * <p>The step is worked out first; then, under {@link #LOCK} (which Clear history, a user's change and a new plan
     * take too), the plan must still be the latest one, for the same generation, rules and pending jump, before the
     * bar, the change note and the jump's clearing are written. A jump is cleared only while it is still the one this
     * commit used, so one the user set meanwhile is never lost.
     */
    static Commit commitIfDue(Context context) {
        if (context == null) return Commit.NO_PLAN;
        Context app = app(context);
        try {
            if (!Consent.accepted(app)) return Commit.NO_PLAN;
            Autopilot.Plan plan = latest.get();
            if (plan == null) return Commit.NO_PLAN;
            FilterSettings rules = FilterStore.load(app);
            if (!rules.autopilot) {
                latest.compareAndSet(plan, null);
                return Commit.NO_PLAN;
            }
            long wall = wallClock.getAsLong();
            String stale = staleness(plan, rules, wall);
            int current = rules.minimumScalePercent;
            if (stale != null || plan.current != current) return discard(app, plan, stale);
            Autopilot.Reason jump = jumpCause(app);
            long elapsed = elapsedClock.getAsLong();
            Autopilot.Step step = step(plan, jump, elapsed);
            if (step.next == current && jump == null) return Commit.HELD;
            if (step.next != current) {
                Runnable hook = beforeWriteForTests;
                if (hook != null) hook.run();
            }
            Autopilot.Reason reason = null;
            Commit outcome;
            synchronized (LOCK) {
                // A newer plan replaced this one, or a user's change or Clear history dropped it: the commit is
                // theirs to ask for now (they did), not this plan's.
                if (latest.get() != plan) return Commit.DISCARDED;
                FilterSettings now = FilterStore.load(app);
                if (!now.autopilot) {
                    latest.compareAndSet(plan, null);
                    return Commit.NO_PLAN;
                }
                stale = staleness(plan, now, wall);
                if (stale != null || jumpCause(app) != jump) {
                    // Stale since the checks above, or the user's pending change is another one now.
                    latest.compareAndSet(plan, null);
                    outcome = Commit.DISCARDED;
                } else if (step.next == current) {
                    // A user's change that needs no move is done: it must not make a later move a jump.
                    AutopilotStore.clearJumpIf(app, jump.name());
                    return Commit.HELD;
                } else if (!FilterStore.commitAutopilotBar(app, current, step.next)) {
                    // The stored bar was not the one expected: nothing is written.
                    latest.compareAndSet(plan, null);
                    outcome = Commit.CONFLICT;
                } else {
                    reason = Autopilot.commitReason(plan, current, step.next, jump);
                    boolean raised = step.next > current;
                    AutopilotStore.recordChange(app, current, step.next, reason.name(), wall, raised);
                    lastCommitElapsed = elapsed;
                    if (raised) lastRaiseElapsed = elapsed;
                    if (jump != null) AutopilotStore.clearJumpIf(app, jump.name());
                    outcome = Commit.COMMITTED;
                }
            }
            if (outcome != Commit.COMMITTED) {
                if (stale != null) log(app, stale);
                requestPlan(app, Trigger.COMMIT);
                return outcome;
            }
            log(app, AutopilotText.logCommit(current, step.next, reason));
            notifyListeners();
            // The next step works from a plan made at the new bar.
            requestPlan(app, Trigger.COMMIT);
            return Commit.COMMITTED;
        } catch (RuntimeException failure) {
            log(app, AutopilotText.logCommitFailed(failure));
            return Commit.FAILED;
        }
    }

    /**
     * Drops a plan that can no longer be committed from and asks for a new one; {@code stale} is its log line, null
     * when the plan is only behind the bar (its own last commit moved it), which is no news.
     */
    private static Commit discard(Context app, Autopilot.Plan plan, String stale) {
        latest.compareAndSet(plan, null);
        if (stale != null) log(app, stale);
        requestPlan(app, Trigger.COMMIT);
        return Commit.DISCARDED;
    }

    /**
     * Whether the latest plan wants the bar moved now: a pending user change, or a step that would move it. Cheap; for
     * the screen reader's tick to decide whether to ask for a commit. A plan for other rules, another generation or
     * another bar wants nothing (a new one is already on its way).
     */
    static boolean commitWanted(Context context) {
        if (context == null) return false;
        Context app = app(context);
        try {
            if (!Consent.accepted(app)) return false;
            Autopilot.Plan plan = latest.get();
            if (plan == null) return false;
            FilterSettings rules = FilterStore.load(app);
            if (!rules.autopilot || plan.generation != generation.get() || !plan.rulesKey.equals(rules.rulesKey())
                    || plan.current != rules.minimumScalePercent) {
                return false;
            }
            return wanted(app, plan);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** The screen reader could not commit now (an offer or decline in flight): logged at most once a minute. */
    static void commitDeferred(Context context) {
        if (context == null) return;
        long now = elapsedClock.getAsLong();
        long last = deferralLoggedAt.get();
        if (last != Long.MIN_VALUE && now >= last && now - last < DEFERRAL_LOG_EVERY_MS) return;
        if (deferralLoggedAt.compareAndSet(last, now)) log(app(context), AutopilotText.LOG_DEFERRED);
    }

    private static boolean wanted(Context app, Autopilot.Plan plan) {
        Autopilot.Reason jump = jumpCause(app);
        return jump != null || step(plan, null, elapsedClock.getAsLong()).next != plan.current;
    }

    private static Autopilot.Step step(Autopilot.Plan plan, Autopilot.Reason jump, long elapsed) {
        return Autopilot.step(plan.current, plan.target, jump != null, elapsed - lastCommitElapsed,
                elapsed - lastRaiseElapsed, plan.offersSinceRaise, plan.barShare);
    }

    /** Why a plan can no longer be used, in the spec's order, as its log line; null when it still can. */
    private static String staleness(Autopilot.Plan plan, FilterSettings rules, long wall) {
        if (plan.generation != generation.get()) return AutopilotText.DISCARD_CLEARED;
        if (Math.abs(wall - plan.computedAt) > Autopilot.PLAN_MAX_AGE_MS) return AutopilotText.DISCARD_OLD;
        if (!plan.rulesKey.equals(rules.rulesKey())) return AutopilotText.DISCARD_RULES;
        return null;
    }

    /** The pending user change's commit reason, or null (none, or not a name this version knows). */
    private static Autopilot.Reason jumpCause(Context app) {
        String name = AutopilotStore.jump(app);
        if (name == null || name.isEmpty()) return null;
        try {
            Autopilot.Reason reason = Autopilot.Reason.valueOf(name);
            return reason.jump ? reason : null;
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    // ---- The user's changes ----

    /**
     * The user turned Autopilot on or off, or chose another goal (70, 50 or 0, pay first): it is saved
     * ({@link FilterStore#setAutopilot}: off puts the bar back at exactly 100 at once), logged, and followed
     * ({@link #userChanged}). The way the Autopilot button and the goal chooser change Autopilot.
     */
    static void setAutopilot(Context context, boolean on, int goal) {
        if (context == null) return;
        Context app = app(context);
        FilterSettings before = FilterStore.load(app);
        int chosen = FilterSettings.sanitizedGoal(goal);
        FilterStore.setAutopilot(app, on, chosen);
        if (on && !before.autopilot) {
            log(app, AutopilotText.logOn(chosen));
            userChanged(app, UserChange.TURNED_ON);
        } else if (!on && before.autopilot) {
            log(app, AutopilotText.LOG_OFF);
            userChanged(app, UserChange.TURNED_OFF);
        } else if (on && before.autopilotGoalPercent != chosen) {
            log(app, AutopilotText.logGoal(chosen, before.autopilotGoalPercent));
            userChanged(app, UserChange.GOAL_CHANGED);
        }
    }

    /** The user changed a minimum or max stops (already saved with {@link FilterStore#save}). */
    static void rulesChanged(Context context) {
        userChanged(context, UserChange.RULES_CHANGED);
    }

    /**
     * The user is about to choose typical minimums (the 0.5.0 notice's "Use typical minimums", the new-install
     * starter's "Use these"): with Autopilot on, the bar goes back to exactly 100 first (the owner's decision), so the
     * plan {@link #rulesChanged} then asks for starts from 100, never from a bar set for the old minimums (a pinned 50,
     * say). Under {@link #LOCK} like a commit, with its change note ("you changed your minimums") and log line; no
     * plan is asked for here. Nothing happens while Autopilot is off: the bar is exactly 100 already.
     */
    static void barBackToMinimums(Context context) {
        if (context == null) return;
        Context app = app(context);
        int from = -1;
        synchronized (LOCK) {
            FilterSettings rules = FilterStore.load(app);
            int bar = rules.minimumScalePercent;
            if (rules.autopilot && bar != Autopilot.BAR_OFF
                    && FilterStore.commitAutopilotBar(app, bar, Autopilot.BAR_OFF)) {
                from = bar;
                boolean raised = Autopilot.BAR_OFF > bar;
                long elapsed = elapsedClock.getAsLong();
                AutopilotStore.recordChange(app, bar, Autopilot.BAR_OFF, Autopilot.Reason.RULES_CHANGED.name(),
                        wallClock.getAsLong(), raised);
                lastCommitElapsed = elapsed;
                if (raised) lastRaiseElapsed = elapsed;
                // A plan made at the old bar can no longer be committed from.
                latest.set(null);
            }
        }
        if (from < 0) return;
        log(app, AutopilotText.logCommit(from, Autopilot.BAR_OFF, Autopilot.Reason.RULES_CHANGED));
        notifyListeners();
    }

    /**
     * A change the user made, already saved. Turning Autopilot on, choosing another goal and turning it off forget the
     * goal-relative state (recovering, the stall correction and its checkpoint) and the plan: a plan worked out
     * before (or still being worked out) is never published or committed from, so it cannot write that state back.
     * Every change but turning off makes the next commit a jump straight to the new plan's target (at a safe point,
     * like every commit); turning off needs no commit, as the bar is already exactly 100, and forgets the note of the
     * last change, which no longer explains the bar. Then a plan is asked for. It writes no log line of its own:
     * {@link #setAutopilot} does.
     */
    static void userChanged(Context context, UserChange change) {
        if (context == null || change == null) return;
        Context app = app(context);
        synchronized (LOCK) {
            if (change != UserChange.RULES_CHANGED) {
                goalEpoch.incrementAndGet();
                latest.set(null);
                AutopilotStore.resetGoalState(app);
            }
            if (change == UserChange.TURNED_OFF) {
                AutopilotStore.clearJump(app);
                // The bar is exactly 100 again: the note of Autopilot's last move no longer explains it.
                AutopilotStore.forgetChange(app);
            } else if (FilterStore.load(app).autopilot) {
                AutopilotStore.setJump(app, change.jump.name());
            }
        }
        notifyListeners();
        if (change != UserChange.TURNED_OFF) requestPlan(app, Trigger.USER);
    }

    /**
     * Clear history: a new generation (anything worked out before is dropped, and a commit under way writes nothing),
     * no plan, prefs "autopilot" emptied (the reading, the acceptance-rate state, the change note), and, with
     * Autopilot on, a jump so the bar goes back to exactly 100 at the next safe point (an empty history is learning).
     *
     * <p>Call it after the decision history ({@link DecisionLog#clear}) and the watched waiting are cleared: the plan
     * it asks for reads them, and its CLEARED jump goes to that plan's target, which must be worked out from nothing.
     */
    static void cleared(Context context) {
        if (context == null) return;
        Context app = app(context);
        synchronized (LOCK) {
            generation.incrementAndGet();
            latest.set(null);
            AutopilotStore.clear(app);
            if (FilterStore.load(app).autopilot) AutopilotStore.setJump(app, Autopilot.Reason.CLEARED.name());
            lastQuestion = null;
            loggedTarget = -1;
            loggedMode = null;
            loggedAt = Long.MIN_VALUE;
        }
        notifyListeners();
        requestPlan(app, Trigger.CLEAR);
    }

    // ---- Dasher's acceptance rate ----

    /**
     * Dasher's decline question was read (the app's own decline or the user's): {@code labels} is a copy of what the
     * screen reader already has, {@code facts} the offer it asks about (null when not known). Returns at once: parsing
     * happens on the planning thread, so the confirmation tap is never delayed. Kept whether or not Autopilot is on:
     * one percent ({@link AutopilotStore#recordReading}; the same offer within 2 minutes only refreshes when it was
     * seen), one log line per distinct reading, and, when Dasher says declining it does not lower the acceptance rate,
     * that mark on the offer's own line (on the main thread, after the line itself). No text is kept or logged.
     */
    static void confirmationSeen(Context context, List<String> labels, OfferSnapshot facts) {
        if (context == null || labels == null) return;
        Context app = app(context);
        List<String> copy = new ArrayList<>(labels);
        long seenGeneration = generation.get();
        long wall = wallClock.getAsLong();
        long elapsed = elapsedClock.getAsLong();
        try {
            executor().execute(() -> question(app, copy, facts, seenGeneration, wall, elapsed));
        } catch (RejectedExecutionException stopped) {
            // Nothing is lost that the next question will not bring again.
        }
    }

    private static void question(Context app, List<String> labels, OfferSnapshot facts, long seenGeneration,
                                 long wall, long elapsed) {
        try {
            if (!Consent.accepted(app)) return;
            AcceptanceRate.Parsed parsed = AcceptanceRate.parse(labels);
            if (parsed == null) return;
            boolean known = known(facts);
            String fingerprint = known ? facts.fingerprint() : "";
            boolean distinct;
            boolean newlyExempt;
            boolean stored;
            synchronized (LOCK) {
                // Clear history came after the question: nothing of it is kept.
                if (seenGeneration != generation.get()) return;
                Question last = lastQuestion;
                boolean same = last != null && last.fingerprint.equals(fingerprint) && last.percent == parsed.percent
                        && Math.abs(elapsed - last.elapsed) <= AutopilotStore.READING_DEDUP_MS;
                newlyExempt = parsed.exempt && !(same && last.exempt);
                lastQuestion = new Question(fingerprint, parsed.percent, parsed.exempt || (same && last.exempt),
                        elapsed);
                stored = parsed.hasPercent() && AutopilotStore.recordReading(app, parsed.percent, fingerprint, wall);
                // A percent counts as new when the store kept it as new (that survives a restart); none, when unseen.
                distinct = parsed.hasPercent() ? stored : !same;
                // Logged in the same hold as the generation check above, never after the lock is let go: a Clear
                // history in between would otherwise let this rate line into the log it just cleared (see LOCK).
                if (distinct || newlyExempt) {
                    Runnable hook = beforeReadingLogForTests;
                    if (hook != null) hook.run();
                    log(app, AutopilotText.logReading(parsed.percent, parsed.exempt));
                }
            }
            if (newlyExempt && known) {
                onMain(() -> {
                    DecisionLog.markStep(app, facts, DecisionLog.StepKind.AR_EXEMPT, "", STEP_WINDOW_MS);
                    requestPlan(app, Trigger.READING);
                });
            } else if (stored) {
                requestPlan(app, Trigger.READING);
            }
        } catch (RuntimeException failure) {
            log(app, AutopilotText.logPlanFailed(failure));
        }
    }

    /** Facts that can name an offer's line: pay, miles, minutes or stops read. */
    private static boolean known(OfferSnapshot facts) {
        return facts != null && (facts.payCents != null || facts.miles != null || facts.minutes != null
                || facts.stops != null);
    }

    // ---- Status ----

    /**
     * What the homepage, the chip, the button, the details and the reports say now ({@link AutopilotText}):
     * the rules, the latest plan for exactly these rules, Dasher's latest reading and the last change.
     *
     * @param readerConnected whether the screen reader is running (Autopilot only commits through it)
     */
    static AutopilotText.Status status(Context context, long wallNow, boolean readerConnected) {
        Context app = app(context);
        FilterSettings rules = FilterStore.load(app);
        Autopilot.Plan plan = latest.get();
        if (plan != null && plan.generation != generation.get()) plan = null;
        return new AutopilotText.Status(rules, readerConnected, plan, AutopilotStore.reading(app, wallNow),
                AutopilotStore.lastChange(app), AutopilotStore.recovering(app), AutopilotStore.extra(app), wallNow);
    }

    // ---- Logging and listeners ----

    private static void logPlan(Context app, Autopilot.Plan plan, AutopilotStore.Reading reading, long wall) {
        long now = elapsedClock.getAsLong();
        boolean changed = plan.target != loggedTarget || plan.mode != loggedMode;
        long last = loggedAt;
        if (!changed && last != Long.MIN_VALUE && now >= last && now - last < PLAN_LOG_EVERY_MS) return;
        loggedTarget = plan.target;
        loggedMode = plan.mode;
        loggedAt = now;
        long readingAt = plan.arSource == Autopilot.ArSource.DASHER && reading != null ? reading.at : -1;
        log(app, AutopilotText.logPlan(plan, readingAt, wall));
    }

    /** The exemption valve, logged at most once a day. */
    private static void logValve(Context app, Autopilot.Plan plan, long wall) {
        if (!plan.exemptionsIgnored) return;
        long last = AutopilotStore.exemptLoggedAt(app);
        if (last > 0 && wall >= last && wall - last < EXEMPT_LOG_EVERY_MS) return;
        AutopilotStore.setExemptLoggedAt(app, wall);
        log(app, AutopilotText.logExemptionsIgnored(plan.exemptInWindow, plan.declinedInWindow));
    }

    private static void log(Context app, String message) {
        DiagnosticLog.log(app, LOG, message);
    }

    private static void notifyListeners() {
        if (listeners.isEmpty()) return;
        onMain(() -> {
            for (Listener listener : listeners) listener.autopilotChanged();
        });
    }

    private static void onMain(Runnable work) {
        Main.HANDLER.post(work);
    }

    private static Executor executor() {
        Executor test = executorForTests;
        return test != null ? test : Worker.EXECUTOR;
    }

    private static Context app(Context context) {
        Context app = context.getApplicationContext();
        return app != null ? app : context;
    }

    /**
     * Forgets everything kept in memory, as a process restart would (tests): no plan, no requester or listeners, and
     * the commit and raise clocks start again from now on the elapsed clock. Stored state and the seams stay.
     */
    static void forgetCache() {
        synchronized (LOCK) {
            latest.set(null);
            pending.set(false);
            commitRequester = null;
            listeners.clear();
            long now = elapsedClock.getAsLong();
            lastCommitElapsed = now;
            lastRaiseElapsed = now;
            deferralLoggedAt.set(Long.MIN_VALUE);
            loggedTarget = -1;
            loggedMode = null;
            loggedAt = Long.MIN_VALUE;
            lastQuestion = null;
        }
    }

    private AutopilotRuntime() {}
}
