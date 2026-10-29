package com.local.dasherfilter;

import android.content.Context;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Uses Autopilot's background worker and scanner safe point, including when Autopilot itself is off. */
final class TimeScoreRuntime {
    private static volatile Prepared active, derived;
    private static final AtomicReference<Prepared> pending = new AtomicReference<>();
    private static final class Prepared {
        final String key, zone;
        final long at, history, generation;
        final TimeScore.Weight weight;
        Prepared(FilterSettings rules, String zone, long at, long history, long generation, TimeScore.Weight weight) {
            key = TimeScore.key(rules); this.zone = zone;
            this.at = Math.floorDiv(at, TimeScore.BUCKET_MS) * TimeScore.BUCKET_MS; this.history = history;
            this.generation = generation; this.weight = weight;
        }
        boolean matches(FilterSettings rules, String zone, long now, long history, long generation) {
            return rules.timeAware && this.history == history && this.generation == generation
                    && key.equals(TimeScore.key(rules)) && this.zone.equals(zone)
                    && Math.floorDiv(at, TimeScore.BUCKET_MS) == Math.floorDiv(now, TimeScore.BUCKET_MS);
        }
    }

    static TimeScore.Weight weight(FilterSettings rules) {
        Prepared value = active;
        return rules.timeAware && value != null && value.generation == AutopilotRuntime.generation()
                && value.key.equals(TimeScore.key(rules)) ? value.weight : TimeScore.Weight.NONE;
    }

    /** Current history's comparison for display, independent of whether the scanner can apply it yet. */
    static TimeScore.Weight current(FilterSettings rules, long now) {
        Prepared value = derived;
        return value != null && value.matches(rules, ZoneId.systemDefault().getId(), now,
                DecisionLog.version(), AutopilotRuntime.generation()) ? value.weight : null;
    }

    /** Background thread only; the existing history is bounded to 200 records. */
    static boolean prepare(Context context, long now) {
        FilterSettings rules = FilterStore.load(context);
        if (!rules.timeAware) { pending.set(null); derived = null; return false; }
        long generation = AutopilotRuntime.generation();
        long version = DecisionLog.version();
        List<DecisionLog.Entry> history = DecisionLog.recent(context, DecisionLog.MAX_ENTRIES);
        if (version != DecisionLog.version()) return false;
        String zone = ZoneId.systemDefault().getId();
        Prepared previous = derived;
        if (previous != null && previous.matches(rules, zone, now, version, generation)) {
            if (active != previous) pending.set(previous);
            return false;
        }
        TimeScore.Weight weight = TimeScore.calculate(rules, history, now, ZoneId.of(zone));
        if (version == DecisionLog.version() && generation == AutopilotRuntime.generation()
                && TimeScore.key(rules).equals(TimeScore.key(FilterStore.load(context)))) {
            Prepared value = new Prepared(rules, zone, now, version, generation, weight);
            derived = value;
            pending.set(value);
            return true;
        }
        return false;
    }

    static boolean wanted() { return pending.get() != null; }

    /** Called under Autopilot's runtime lock, only at the scanner's existing between-offers safe point. */
    static boolean commit(Context context, long now) {
        Prepared value = pending.getAndSet(null);
        if (value == null) return false;
        FilterSettings rules = FilterStore.load(context);
        if (!value.matches(rules, ZoneId.systemDefault().getId(), now,
                DecisionLog.version(), AutopilotRuntime.generation())) return false;
        return DecisionLog.atVersion(value.history, () -> {
            active = value;
            return true;
        });
    }

    static void clear() { active = null; derived = null; pending.set(null); }
    private TimeScoreRuntime() {}
}
