package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one of Dasher's click events says about the tap: Accept, Decline, "End dash" (on Dasher's "End your current
 * dash?"), none of them, or Offer Filter's own tap coming back. Android Views put a button's text into the event; Jetpack Compose (which Dasher's screens look like) puts
 * none there, and its clickable node sits above the label, so the label is looked for on the node, a few nodes
 * above it, a few below it (only when those are few and show none of an offer's facts, so a tap on an offer card's
 * body is no Accept), and by the node being the very control the last offer read found. A tap that shows both
 * Accept and Decline (a container holding both) is neither. Pure Java: the screen reader reads the nodes.
 */
final class ClickEvidence {
    /** Labels kept in a description at most, so a log line stays short. */
    private static final int MAX_DESCRIBED = 400;
    /**
     * Labels below the tapped node name the control only when there are this many at most: a button holds its label
     * (and perhaps its countdown), while a card holds the whole offer.
     */
    static final int MAX_BELOW_LABELS = 6;

    enum Verdict { OWN, ACCEPT, DECLINE, BOTH, END_DASH, OTHER }

    final List<String> eventText;
    final String description;
    final boolean hasSource;
    final String sourceClass;
    final boolean sourceIsAccept;
    final boolean sourceIsDecline;
    final boolean own;
    /** Since Offer Filter's own last tap (ms), -1 for none. */
    final long sinceOwnTap;
    /** The source and up to three nodes above it. */
    final List<String> above;
    /** Visible nodes below the source, a few levels deep at most. */
    final List<String> below;

    ClickEvidence(List<String> eventText, String description, boolean hasSource, String sourceClass,
                  boolean sourceIsAccept, boolean sourceIsDecline, boolean own, long sinceOwnTap, List<String> above,
                  List<String> below) {
        this.eventText = eventText == null ? Collections.<String>emptyList() : new ArrayList<>(eventText);
        this.description = description == null ? "" : description;
        this.hasSource = hasSource;
        this.sourceClass = sourceClass == null ? "" : sourceClass;
        this.sourceIsAccept = sourceIsAccept;
        this.sourceIsDecline = sourceIsDecline;
        this.own = own;
        this.sinceOwnTap = sinceOwnTap;
        this.above = above == null ? Collections.<String>emptyList() : new ArrayList<>(above);
        this.below = below == null ? Collections.<String>emptyList() : new ArrayList<>(below);
    }

    /** Anything about the tap names Accept: its text, description, the node or those above, the control itself. */
    boolean acceptSignal() {
        return signal("accept", sourceIsAccept);
    }

    boolean declineSignal() {
        return signal("decline", sourceIsDecline);
    }

    private boolean signal(String verb, boolean isTarget) {
        if (isTarget || OfferControls.isButton(description, verb)) return true;
        for (String label : eventText) if (OfferControls.isButton(label, verb)) return true;
        for (String label : above) if (OfferControls.isButton(label, verb)) return true;
        if (belowNamesTheControl()) {
            for (String label : below) if (OfferControls.isButton(label, verb)) return true;
        }
        return false;
    }

    /**
     * Whether the labels below the tapped node can name it: few of them, and none of an offer's facts (pay, miles,
     * minutes, stops). A tap on an offer card's body has the whole offer below it, its Accept included: no Accept.
     */
    boolean belowNamesTheControl() {
        return below.size() <= MAX_BELOW_LABELS && !AcceptedOfferTracker.showsOfferFacts(below);
    }

    /** The user's Accept: not Offer Filter's own tap, and nothing about it names Decline too. */
    boolean accept() {
        return !own && acceptSignal() && !declineSignal();
    }

    /** The user's Decline: not Offer Filter's own tap, and nothing about it names Accept too. */
    boolean decline() {
        return !own && declineSignal() && !acceptSignal();
    }

    /**
     * The user's "End dash" (on Dasher's "End your current dash?"): not Offer Filter's own tap, its event, node or the
     * nodes just above it (or the few below, as for Accept) say exactly that, and nothing about it names the
     * question's other button ("Go back"), as a tap on the whole dialog would.
     */
    boolean endDash() {
        if (own || acceptSignal() || declineSignal()) return false;
        List<String> labels = new ArrayList<>(eventText);
        labels.add(description);
        labels.addAll(above);
        if (belowNamesTheControl()) labels.addAll(below);
        boolean endDash = false;
        for (String label : labels) {
            String name = label == null ? "" : OfferControls.normalize(label);
            if (name.equals("go back")) return false;
            if (name.equals("end dash")) endDash = true;
        }
        return endDash;
    }

    Verdict verdict() {
        if (own) return Verdict.OWN;
        boolean accept = acceptSignal();
        boolean decline = declineSignal();
        if (accept && decline) return Verdict.BOTH;
        if (accept) return Verdict.ACCEPT;
        if (decline) return Verdict.DECLINE;
        return endDash() ? Verdict.END_DASH : Verdict.OTHER;
    }

    /**
     * One line for the screens log: "class=Button source=yes target=decline since-own-tap=2500 text=[…] desc=…
     * above=[…] below=[…] -> decline". Without labels, only the shape. The labels are masked ({@link PersonalText})
     * before the line is cut, and the verdict is never cut off. A tap on a payment, account or earnings screen keeps
     * none of its labels ({@link PersonalText#LABELS_NOT_KEPT}).
     */
    String describe(boolean withLabels) {
        String target = sourceIsAccept ? "accept" : sourceIsDecline ? "decline" : "none";
        String shape = "class=" + (sourceClass.isEmpty() ? "?" : sourceClass) + " source=" + (hasSource ? "yes" : "no")
                + " target=" + target + " since-own-tap=" + (sinceOwnTap < 0 ? "-" : String.valueOf(sinceOwnTap));
        String verdict = " -> " + verdict().name().toLowerCase(java.util.Locale.US);
        if (!withLabels) return shape + verdict;
        if (PersonalText.accountScreen(eventText) || PersonalText.accountText(description)
                || PersonalText.accountScreen(above) || PersonalText.accountScreen(below)) {
            return shape + " labels=" + PersonalText.LABELS_NOT_KEPT + verdict;
        }
        String labels = " text=" + PersonalText.mask(eventText) + " desc=" + PersonalText.mask(description)
                + " above=" + PersonalText.mask(above) + " below=" + PersonalText.mask(below);
        if (labels.length() > MAX_DESCRIBED) labels = labels.substring(0, MAX_DESCRIBED) + "…";
        return shape + labels + verdict;
    }
}
