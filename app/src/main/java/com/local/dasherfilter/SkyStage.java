package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The main page's sky, as one picture: the header (the sun and the round buttons) along the top, the page's few lines
 * (paused, what needs fixing) along the bottom, and behind them all the minimums' constellation, as large as the page's
 * width allows. Its middle stands a little right of the page's, and its faint rings run on past the page's edges and up
 * behind the header's buttons, so it reads as the sky rather than a framed target. The counts float over its upper arc
 * (beside the header's buttons when there is room, under them when not) and the mascot just below them at the left. Every point
 * of the constellation lies along one of its spokes, so the stage keeps the spokes, their points and their icons inside
 * the page and clear of the counts, the header and the mascot. The lines keep the circle's size: they cross its lower
 * part, where the constellation fades under them as it does under every word; the mascot occupies the upper-left
 * space, with its smaller drawing clear of the finite spokes and labels. In a short window (the
 * constellation up in the header, with the map below) the sky is a plain column instead: the header, the mascot with
 * its counts beside it, then the lines. The page's scene asks the stage where its words and icons stand, so its stars,
 * clouds and signpost keep out from under them.
 */
@SuppressLint("ViewConstructor")
final class SkyStage extends FrameLayout implements ScenePage.Over {
    private static final int SIDE_DP = 16;
    /** The least radius the constellation is drawn at in the sky, for working out the least room the sky needs. */
    private static final int LEAST_RADIUS_DP = 56;
    /** The mascot's ring, outer edge, from its middle: at most, and at least however tight the sky. */
    private static final int MASCOT_MOST_DP = 36;
    private static final int MASCOT_LEAST_DP = 28;
    /** Room kept between a spoke and the mascot's ring, and between its ring and the page's left edge. */
    private static final int SPOKE_CLEAR_DP = 12;
    private static final int MASCOT_EDGE_DP = 6;
    /** The spokes' slope, for keeping the mascot between the two on the left. */
    private static final float SIN = (float) Math.sin(Math.toRadians(MinimumsStarView.SPREAD));
    private static final float COS = (float) Math.cos(Math.toRadians(MinimumsStarView.SPREAD));
    /**
     * The circle's middle stands right of the page's, so it does not read as a centred, framed target: as far as it
     * can without making the circle smaller, between these two.
     */
    private static final int OFF_MIDDLE_LEAST_DP = 8;
    private static final int OFF_MIDDLE_MOST_DP = 24;
    /** Room kept between the spokes' icons and the page's sides, and under the lowest icons. */
    private static final int ICON_EDGE_DP = 4;
    /** The counts' columns need at least this much room each to sit beside the header's buttons. */
    private static final int HEADER_COUNTS_SPACING_DP = 60;

    private final Ui ui;
    private final MinimumsStarView star;
    private final FilterHeroView hero;
    private final View header;
    private final View title;
    private final LinearLayout lines;
    private final View sun;

    /** Worked out in measuring, used in layout: where the counts and the mascot stand, and the circle. */
    private final RectF counts = new RectF();
    private final RectF heroBox = new RectF();
    /** The counts in the mascot's view's pixels, handed to it in layout. */
    private final RectF inHero = new RectF();
    private float spacing;
    private float skyX;
    private float skyY;
    private float skyRadius;
    private float mascotX;
    private float mascotY;
    private float mascotRadius;

    SkyStage(Context context, Ui ui, MinimumsStarView star, FilterHeroView hero, View header, View title,
             LinearLayout lines, View sun) {
        super(context);
        this.ui = ui;
        this.star = star;
        this.hero = hero;
        this.header = header;
        this.title = title;
        this.lines = lines;
        this.sun = sun;
        // Behind to front: the constellation (added at the back when it is in the sky), the mascot and its counts,
        // then the header's buttons and the lines, which take their own touches first.
        addView(hero);
        addView(header);
        addView(lines);
    }

    /** The constellation is in the sky (not up in the header). */
    boolean holdsStar() {
        return star.getParent() == this;
    }

    /** Puts the constellation at the back of the sky. */
    void holdStar() {
        if (holdsStar()) return;
        addView(star, 0, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static boolean shown(View view) {
        return view.getVisibility() != GONE;
    }

    /**
     * Exactly the height given. Asked with no limit (the page working out whether it fits one screen), the least the
     * sky reads well at: the header and the counts, then a modest constellation, or, where the lines are taller, the
     * lines with the mascot's least above them (the lines cross the circle's lower part, so they need not stand below
     * it); in a short window's plain column, the header, the lines and the mascot's least between. Asked
     * with a limit (the page sharing out one screen), only what must be there: with the constellation, the header
     * and the counts, so the sky's share by weight on top of that holds the constellation, the mascot and the lines
     * (a line that needs the user takes its room from the sky, never from the skyline or the map, and the sky gets it
     * back when the line goes); in a short window's plain column, the header and the lines, the mascot taking the
     * share.
     */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int across = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        header.measure(across, any);
        lines.measure(across, any);
        int top = shown(header) ? header.getMeasuredHeight() : 0;
        int bottom = shown(lines) ? lines.getMeasuredHeight() : 0;
        boolean sky = holdsStar();
        int fixed;
        int least;
        if (sky) {
            placeCounts(width, top);
            fixed = Math.round(Math.max(top, counts.bottom));
            int circle = Math.round(star.backdropAbove(ui.dp(LEAST_RADIUS_DP)) + star.backdropBelow(ui.dp(LEAST_RADIUS_DP)) + ui.dp(8));
            // Below the counts and above the lines, as compose() keeps it.
            int mascot = bottom + Math.round(2 * ui.dp(MASCOT_LEAST_DP) + ui.dp(8));
            least = fixed + Math.max(circle, mascot);
        } else {
            hero.measure(across, any);
            fixed = top + bottom;
            least = fixed + hero.getMeasuredHeight();
        }
        int height;
        switch (MeasureSpec.getMode(heightSpec)) {
            case MeasureSpec.EXACTLY:
                height = MeasureSpec.getSize(heightSpec);
                break;
            case MeasureSpec.AT_MOST:
                height = Math.min(fixed, MeasureSpec.getSize(heightSpec));
                break;
            default:
                height = least;
        }
        setMeasuredDimension(width, height);
        if (sky) {
            compose(width, height, top, bottom);
            star.measure(across, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            hero.measure(MeasureSpec.makeMeasureSpec(Math.round(heroBox.width()), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(heroBox.height()), MeasureSpec.EXACTLY));
        } else {
            hero.measure(across, MeasureSpec.makeMeasureSpec(Math.max(0, height - top - bottom), MeasureSpec.EXACTLY));
        }
    }

    /**
     * The counts beside the header's buttons, in the room its empty title leaves, when their columns have room there;
     * otherwise centred just under the header.
     */
    private void placeCounts(int width, int headerHeight) {
        float tall = hero.countsHeight();
        float room = shown(header) ? title.getMeasuredWidth() : 0;
        spacing = hero.countsSpacing(room);
        if (spacing >= ui.dp(HEADER_COUNTS_SPACING_DP)) {
            float wide = hero.countsWidth(spacing);
            float left = header.getPaddingLeft();
            float top = Math.max(0, (headerHeight - tall) / 2f);
            counts.set(left, top, left + wide, top + tall);
        } else {
            spacing = hero.countsSpacing(width - 2 * ui.dp(SIDE_DP));
            float wide = hero.countsWidth(spacing);
            counts.set((width - wide) / 2f, headerHeight, (width + wide) / 2f, headerHeight + tall);
        }
    }

    /**
     * The largest circle whose spokes, points and icons stand inside the page, below the header and the counts and
     * inside the sky, with room for the mascot above the lines; its rim may run past the page's edges. It stands clear
     * of the lines too where there is room, and otherwise as high as the counts allow, the lines crossing its lower
     * part. The smaller mascot stays at the upper left immediately below the header and counts, away from the
     * plotted offer's center. Its finite drawing keeps clear of the axis labels and controls.
     */
    private void compose(int width, int height, int headerHeight, int linesHeight) {
        float top = Math.max(headerHeight, counts.bottom) + ui.dp(4);
        float floor = height - ui.dp(ICON_EDGE_DP);
        float linesTop = height - linesHeight;
        // As tall as the sky allows, then as far right as that size leaves room for, then as wide as that allows.
        float tallest = largest(Float.MAX_VALUE, width / 2f + ui.dp(OFF_MIDDLE_MOST_DP), top, floor, linesTop);
        float room = width / 2f - ui.dp(ICON_EDGE_DP) - star.backdropHalfWidth(tallest);
        skyX = width / 2f + Math.max(ui.dp(OFF_MIDDLE_LEAST_DP), Math.min(ui.dp(OFF_MIDDLE_MOST_DP), room));
        skyRadius = largest(width - skyX - ui.dp(ICON_EDGE_DP), skyX, top, floor, linesTop);
        float above = star.backdropAbove(skyRadius);
        float below = star.backdropBelow(skyRadius);
        float highest = top + above;
        // Clear of the lines and with its rim inside the sky, where there is room for that, else as high as it goes.
        float lowest = Math.min(Math.min(floor, linesTop - ui.dp(ICON_EDGE_DP)) - below, height - skyRadius);
        skyY = lowest >= highest ? (highest + lowest) / 2 : highest;

        // The mascot on the left, clear of both left spokes (which stand SPREAD above and below level) and inside the
        // page, over the circle's rim where there is room for that, and above the lines.
        float clear = ui.dp(SPOKE_CLEAR_DP);
        float edge = ui.dp(MASCOT_EDGE_DP);
        float least = ui.dp(MASCOT_LEAST_DP);
        float fits = (skyX - edge - clear / SIN) / (1 + 1 / SIN);
        // Between the two left icons.
        float between = MinimumsStarView.spokeHalfHeight(skyRadius) + ui.dp(4);
        mascotRadius = Math.max(least, Math.min(ui.dp(MASCOT_MOST_DP), Math.min(fits, between)));
        // Anchor to the header, not the graph. Clearance from an infinitely extended upper spoke used to cap
        // the rise, leaving the control near the graph's middle even when the upper-left corner was empty.
        mascotX = mascotRadius + edge;
        mascotY = top + mascotRadius + ui.dp(2);

        heroBox.set(Math.min(counts.left, mascotX - mascotRadius), Math.min(counts.top, mascotY - mascotRadius),
                Math.max(counts.right, mascotX + mascotRadius), Math.max(counts.bottom, mascotY + mascotRadius));
        heroBox.set((float) Math.floor(heroBox.left), (float) Math.floor(heroBox.top), (float) Math.ceil(heroBox.right),
                (float) Math.ceil(heroBox.bottom));
    }

    /** The lowest a mascot of {@code radius} may stand (its middle) to keep above the lines. */
    private float raisedTo(float linesTop, float radius) {
        return linesTop - ui.dp(4) - radius;
    }

    /**
     * How far left of the circle's middle ({@code cx}, {@code cy}) a mascot of {@code radius} must stand to keep clear
     * of the upper left spoke, once raised (if it must be) to stand above the lines; NaN when it cannot, raised into the
     * counts or pushed past the page's left edge.
     */
    private float mascotReach(float cx, float cy, float radius, float top, float linesTop) {
        float y = Math.min(cy, raisedTo(linesTop, radius));
        if (y - radius < top) return Float.NaN;
        float reach = (radius + ui.dp(SPOKE_CLEAR_DP) + (cy - y) * COS) / SIN;
        return reach <= cx - ui.dp(MASCOT_EDGE_DP) - radius ? reach : Float.NaN;
    }

    /**
     * The largest radius whose icons stand within {@code side} of the middle across, below {@code top} and above
     * {@code floor}, with room for the mascot's least above {@code linesTop} when the circle, its middle {@code cx}
     * across, stands as high as it can.
     */
    private float largest(float side, float cx, float top, float floor, float linesTop) {
        float least = ui.dp(MASCOT_LEAST_DP);
        float low = ui.dp(20);
        float high = Math.max(low, getMeasuredWidth());
        while (high - low > 0.5f) {
            float radius = (low + high) / 2;
            float above = star.backdropAbove(radius);
            float below = star.backdropBelow(radius);
            boolean fits = star.backdropHalfWidth(radius) <= side && top + above + below <= floor
                    && !Float.isNaN(mascotReach(cx, top + above, least, top, linesTop));
            if (fits) low = radius;
            else high = radius;
        }
        return low;
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        int headerHeight = shown(header) ? header.getMeasuredHeight() : 0;
        int linesHeight = shown(lines) ? lines.getMeasuredHeight() : 0;
        header.layout(0, 0, width, headerHeight);
        lines.layout(0, height - linesHeight, width, height);
        if (!holdsStar()) {
            hero.unplace();
            hero.layout(0, headerHeight, width, headerHeight + hero.getMeasuredHeight());
            return;
        }
        star.layout(0, 0, width, height);
        hero.layout(Math.round(heroBox.left), Math.round(heroBox.top), Math.round(heroBox.right),
                Math.round(heroBox.bottom));
        inHero.set(counts);
        inHero.offset(-heroBox.left, -heroBox.top);
        hero.place(mascotX - heroBox.left, mascotY - heroBox.top, mascotRadius, inHero, spacing);
        star.compose(skyX, skyY, skyRadius, veils(width, height - linesHeight));
    }

    /**
     * Where the constellation fades: under the counts, the sun and each of the lines; and where it is cut out, the
     * mascot's disc, so no ring shows through the drawing.
     */
    private List<MinimumsStarView.Veil> veils(int width, int linesTop) {
        List<MinimumsStarView.Veil> veils = new ArrayList<>();
        RectF box = new RectF(counts);
        box.inset(-ui.dp(10), -ui.dp(2));
        veils.add(new MinimumsStarView.Veil(box, false, 0.82f));
        // Just inside the ring: its feathered edge ends at the ring, so nothing beside the mascot fades.
        float ring = mascotRadius - ui.dp(4);
        box.set(mascotX - ring, mascotY - ring, mascotX + ring, mascotY + ring);
        veils.add(new MinimumsStarView.Veil(box, true, 1f));
        if (sun != null && shown(header) && sun.getWidth() > 0) {
            float x = header.getLeft() + sun.getLeft() + sun.getWidth() / 2f;
            float y = header.getTop() + sun.getTop() + sun.getHeight() / 2f;
            float glow = sun.getWidth() * 0.62f;
            box.set(x - glow, y - glow, x + glow, y + glow);
            veils.add(new MinimumsStarView.Veil(box, true, 0.7f));
        }
        for (int i = 0; i < lines.getChildCount(); i++) {
            View line = lines.getChildAt(i);
            if (!shown(line) || line.getHeight() <= 0) continue;
            addLineVeils(veils, line, linesTop);
        }
        return veils;
    }

    /**
     * Where words stand in the sky, in this view's pixels: the counts, each of the lines, and the rings' dollars.
     */
    @Override public void wordsAt(List<RectF> out) {
        if (getHeight() <= 0) return;
        RectF box = new RectF(counts);
        if (!holdsStar()) {
            // A plain column: the counts where the mascot draws them.
            hero.countsAt(box);
            box.offset(hero.getLeft(), hero.getTop());
        }
        box.inset(-ui.dp(4), -ui.dp(2));
        out.add(box);
        int from = out.size();
        List<MinimumsStarView.Veil> rows = new ArrayList<>();
        for (int i = 0; i < lines.getChildCount(); i++) {
            View line = lines.getChildAt(i);
            if (shown(line) && line.getHeight() > 0) addLineVeils(rows, line, lines.getTop());
        }
        for (MinimumsStarView.Veil row : rows) out.add(new RectF(row.box));
        star.wordsAt(out);
        for (int i = from + rows.size(); i < out.size(); i++) out.get(i).offset(star.getLeft(), star.getTop());
    }

    /** Where the constellation's icons stand, in this view's pixels. */
    @Override public void iconsAt(List<RectF> out) {
        if (!holdsStar() || getHeight() <= 0) return;
        int from = out.size();
        star.iconsAt(out);
        for (int i = from; i < out.size(); i++) out.get(i).offset(star.getLeft(), star.getTop());
    }

    /**
     * A veil under a line of words ({@code offsetY} being its parent's top in this view): under just the words of a
     * plain line ("Paused"), across the page under a row with a button ("Fix"), or under each row of a column of them.
     */
    private void addLineVeils(List<MinimumsStarView.Veil> veils, View line, float offsetY) {
        if (line instanceof LinearLayout && ((LinearLayout) line).getOrientation() == LinearLayout.VERTICAL) {
            LinearLayout column = (LinearLayout) line;
            for (int i = 0; i < column.getChildCount(); i++) {
                View row = column.getChildAt(i);
                if (shown(row) && row.getHeight() > 0) addLineVeils(veils, row, offsetY + column.getTop());
            }
            return;
        }
        RectF box = new RectF(lines.getPaddingLeft() - ui.dp(4), offsetY + line.getTop(),
                lines.getWidth() - lines.getPaddingRight() + ui.dp(4), offsetY + line.getBottom());
        if (line instanceof TextView && !(line instanceof android.widget.Button)
                && ((TextView) line).getLayout() != null && line.getParent() == lines) {
            TextView words = (TextView) line;
            android.text.Layout layout = words.getLayout();
            float left = Float.MAX_VALUE;
            float right = -Float.MAX_VALUE;
            for (int i = 0; i < layout.getLineCount(); i++) {
                left = Math.min(left, layout.getLineLeft(i));
                right = Math.max(right, layout.getLineRight(i));
            }
            float at = line.getLeft() + words.getTotalPaddingLeft();
            box.left = Math.max(box.left, at + left - ui.dp(12));
            box.right = Math.min(box.right, at + right + ui.dp(12));
        }
        veils.add(new MinimumsStarView.Veil(box, false, 0.8f));
    }
}
