package com.local.dasherfilter;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Setup's page (0.6.26), the whole screen while {@link SetupFlow} has a step to show: a strip of sky with the step's
 * title (through the first run's walk, "2 of 5" over it) and the mascot, the step's few words, one button, at most one
 * quiet link, and at the foot "Stuck? Send feedback". Nothing else: the homepage shows once Offer Filter can filter
 * (the owner: "all ui on screen needs to be justified by what is currently functional"). It takes every touch, so
 * nothing behind it can be reached. Built once; a step's words and buttons are made only when they change, and
 * screen readers hear each new step's title.
 */
final class SetupPage {
    /** A button or link tapped on the step shown. */
    interface Act {
        void act(SetupFlow.Page page, SetupFlow.Action action);
    }

    static final String STUCK = "Stuck? Send feedback";

    final ScrollView view;
    private final Ui ui;
    private final Act act;
    private final TextView counter;
    private final TextView title;
    private final LinearLayout words;
    /** The step's button and its link, made with their words, so no button is ever without a label. */
    private final LinearLayout actions;
    private SetupFlow.Page shown;
    private String shownKey = "";

    /** @param stuck the foot's "Stuck? Send feedback" */
    SetupPage(Context context, Ui ui, Act act, Runnable stuck) {
        this.ui = ui;
        this.act = act;
        LinearLayout page = ui.column();

        LinearLayout header = ui.row();
        header.setBackground(new Scenery(Scenery.Part.SKY, ui));
        header.setPadding(ui.dp(20), ui.dp(22), ui.dp(16), ui.dp(14));
        header.setMinimumHeight(ui.dp(96));
        LinearLayout heading = ui.column();
        counter = ui.text("", 13, ui.inkSecondary, true);
        counter.setVisibility(View.GONE);
        heading.addView(counter, Ui.matchWidth());
        title = ui.text("", 24, ui.ink, true);
        title.setAccessibilityHeading(true);
        heading.addView(title, Ui.matchWidth());
        header.addView(heading, Ui.weighted());
        header.addView(new NoticePage.Face(context, ui), new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)));
        page.addView(header, Ui.matchWidth());

        LinearLayout body = ui.column();
        body.setPadding(ui.dp(20), ui.dp(6), ui.dp(20), ui.dp(16));
        words = ui.column();
        body.addView(words, Ui.matchWidth());
        actions = ui.column();
        body.addView(actions, Ui.matchWidth());
        page.addView(body, Ui.matchWidth());

        // The ground takes what the screen has left, so the page ends on the road as the notice does.
        View spacer = new View(context);
        spacer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        page.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        Button help = ui.link(STUCK, stuck);
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        helpParams.gravity = Gravity.CENTER_HORIZONTAL;
        helpParams.bottomMargin = ui.dp(4);
        page.addView(help, helpParams);
        View ground = new View(context);
        ground.setBackground(new Scenery(Scenery.Part.GROUND, ui));
        ground.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        page.addView(ground, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(110)));

        view = new ScrollView(context);
        view.setFillViewport(true);
        view.setBackgroundColor(ui.page);
        // Every touch lands here and goes no further.
        view.setClickable(true);
        view.setVisibility(View.GONE);
        view.addView(page, Ui.matchWidth());
    }

    /** {@code page}'s step, its words set only when they changed (a new step starts at the top). */
    void show(SetupFlow.Page page) {
        shown = page;
        String key = page.key();
        if (key.equals(shownKey)) return;
        boolean newStep = !shownKey.startsWith(page.screen + "/");
        shownKey = key;
        String count = page.counter();
        counter.setText(count);
        counter.setVisibility(count.isEmpty() ? View.GONE : View.VISIBLE);
        counter.setContentDescription(count.isEmpty() ? null : "Step " + page.number + " of " + page.of);
        title.setText(page.title);
        words.removeAllViews();
        for (int i = 0; i < page.words.size(); i++) {
            TextView line = ui.text(page.words.get(i), 17, ui.ink, false);
            LinearLayout.LayoutParams params = Ui.matchWidth();
            params.topMargin = ui.dp(i == 0 ? 10 : 12);
            words.addView(line, params);
        }
        actions.removeAllViews();
        Button button = ui.button(page.button, true, () -> {
            if (shown != null) act.act(shown, shown.action);
        });
        LinearLayout.LayoutParams buttonParams = Ui.matchWidth();
        buttonParams.topMargin = ui.dp(24);
        actions.addView(button, buttonParams);
        if (page.link != null) {
            Button link = ui.link(page.link, () -> {
                if (shown != null && shown.linkAction != null) act.act(shown, shown.linkAction);
            });
            LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            linkParams.gravity = Gravity.CENTER_HORIZONTAL;
            linkParams.topMargin = ui.dp(8);
            actions.addView(link, linkParams);
        }
        // Screen readers hear the new step's title as the page changes.
        view.setAccessibilityPaneTitle(page.title);
        if (newStep) view.scrollTo(0, 0);
    }

    boolean shown() {
        return view.getVisibility() == View.VISIBLE;
    }

    /** The step on screen, or null. */
    SetupFlow.Page page() {
        return shown() ? shown : null;
    }
}
