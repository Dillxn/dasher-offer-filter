package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dasher's acceptance rate as its decline question shows it ("Are you sure you want to decline this offer?", "Does not
 * lower acceptance rate", "9%"). Pure: it reads only the labels the question already gave the screen reader, keeps no
 * text, and is never a decision about an offer. Autopilot uses the percent for its acceptance-rate goal, and the
 * "does not lower" mark to leave that one offer out of its accounting.
 *
 * <p>Conservative by construction: a percent counts only beside acceptance-rate wording and only when the question
 * shows exactly one distinct "N%" label; anything ambiguous reads as no percent. A payment, account or earnings screen
 * is never read at all.
 */
final class AcceptanceRate {
    /** A label that is only a whole percent: "9%", "100%", "7 %". */
    private static final Pattern PERCENT = Pattern.compile("^(\\d{1,3})\\s?%$");
    /** Dasher's mark that declining this offer is free. */
    private static final Pattern EXEMPT = Pattern.compile("^does not lower (your )?acceptance rate[.!]?$");
    /** Wording that says declining does cost acceptance rate, which overrules the mark. */
    private static final String[] LOWERS = {"will lower", "may lower", "can lower", "lowers your acceptance"};

    /** What one decline question said. */
    static final class Parsed {
        /** The acceptance rate in whole percent, 0–100; -1 when the question showed none unambiguously. */
        final int percent;
        /** Dasher said declining this offer does not lower the acceptance rate. */
        final boolean exempt;

        Parsed(int percent, boolean exempt) {
            this.percent = percent;
            this.exempt = exempt;
        }

        boolean hasPercent() {
            return percent >= 0;
        }
    }

    /**
     * The acceptance rate and exemption on Dasher's decline question; null unless {@code labels} ask about declining
     * ({@link DeclineConfirmation#hasPrompt}) or when they show a payment, account or earnings screen
     * ({@link PersonalText#accountScreen}). Labels are compared after {@link OfferControls#normalize}.
     * <ul>
     *   <li>percent: v when some label contains "acceptance rate" and the distinct values of the labels that are only
     *   "N%" ({@code ^(\d{1,3})\s?%$}) are exactly one, v, at most 100; otherwise -1.</li>
     *   <li>exempt: some label is "does not lower (your) acceptance rate" (a final "." or "!" allowed), and no label
     *   says "will lower", "may lower", "can lower" or "lowers your acceptance".</li>
     * </ul>
     */
    static Parsed parse(Collection<String> labels) {
        if (labels == null) return null;
        List<String> present = new ArrayList<>();
        for (String label : labels) if (label != null) present.add(label);
        if (!DeclineConfirmation.hasPrompt(present) || PersonalText.accountScreen(present)) return null;
        boolean wording = false;
        boolean exemptLabel = false;
        boolean lowers = false;
        int value = -1;
        boolean several = false;
        for (String label : present) {
            String text = OfferControls.normalize(label);
            if (text.contains("acceptance rate")) wording = true;
            Matcher percent = PERCENT.matcher(text);
            if (percent.matches()) {
                int v = Integer.parseInt(percent.group(1));
                if (value < 0) value = v;
                else if (v != value) several = true;
            }
            if (EXEMPT.matcher(text).matches()) exemptLabel = true;
            for (String lower : LOWERS) {
                if (text.contains(lower)) lowers = true;
            }
        }
        boolean one = value >= 0 && !several && value <= 100;
        return new Parsed(wording && one ? value : -1, exemptLabel && !lowers);
    }

    private AcceptanceRate() {}
}
