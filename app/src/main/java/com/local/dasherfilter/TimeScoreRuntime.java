package com.local.dasherfilter;

import android.content.Context;
import java.time.ZoneId;
import java.util.List;

/** Uses Autopilot's background worker and scanner safe point, including when Autopilot itself is off. */
final class TimeScoreRuntime {
    private static volatile Prepared active, pending;
    private static final class Prepared {
        final String key, zone;
        final long at, history, generation;
        final TimeScore.Weight weight;
        Prepared(FilterSettings rules, String zone, long at, long history, long generation, TimeScore.Weight weight) {
            key = TimeScore.key(rules); this.zone = zone; this.at = at; this.history = history;
            this.generation = generation; this.weight = weight;
        }
    }

    static TimeScore.Weight weight(FilterSettings rules) {
        Prepared value = active;
        return rules.timeAware && value != null && value.generation == AutopilotRuntime.generation()
                && value.key.equals(TimeScore.key(rules)) ? value.weight : TimeScore.Weight.NONE;
    }

    /** Background thread only; the existing history is bounded to 200 records. */
    static void prepare(Context context, long now) {
        FilterSettings rules = FilterStore.load(context);
        if (!rules.timeAware) { pending = null; return; }
        long generation = AutopilotRuntime.generation();
        long version = DecisionLog.version();
        List<DecisionLog.Entry> history = DecisionLog.recent(context, DecisionLog.MAX_ENTRIES);
        if (version != DecisionLog.version()) return;
        String zone = ZoneId.systemDefault().getId();
        Prepared previous = pending != null ? pending : active;
        if (previous != null && previous.history == version && previous.generation == generation
                && previous.key.equals(TimeScore.key(rules)) && previous.zone.equals(zone)
                && Math.floorDiv(previous.at, TimeScore.BUCKET_MS) == Math.floorDiv(now, TimeScore.BUCKET_MS)) return;
        TimeScore.Weight weight = TimeScore.calculate(rules, history, now, ZoneId.of(zone));
        if (version == DecisionLog.version() && generation == AutopilotRuntime.generation()
                && TimeScore.key(rules).equals(TimeScore.key(FilterStore.load(context)))) {
            pending = new Prepared(rules, zone, now, version, generation, weight);
        }
    }

    static boolean wanted() { return pending != null; }

    /** Called under Autopilot's runtime lock, only at the scanner's existing between-offers safe point. */
    static boolean commit(Context context, long now) {
        Prepared value = pending;
        if (value == null) return false;
        pending = null;
        FilterSettings rules = FilterStore.load(context);
        if (!rules.timeAware || value.generation != AutopilotRuntime.generation() || now < value.at
                || now - value.at > TimeScore.BUCKET_MS || !value.zone.equals(ZoneId.systemDefault().getId())
                || !value.key.equals(TimeScore.key(rules))) return false;
        return DecisionLog.atVersion(value.history, () -> {
            active = value;
            return true;
        });
    }

    static void clear() { active = null; pending = null; }
    private TimeScoreRuntime() {}
}
