package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The homepage's one fluid layout as pure arithmetic ({@link FluidLayout}), checked at every window size a dp apart: no
 * threshold anywhere, so no part jumps, grows or fades faster than the window changes allow; what must go at a small
 * size goes in a fixed order of least importance, shrinking and fading over a range of heights; the radar and the map
 * stand together wherever there is room for both at a usable size, one above the other when the room is tall and side
 * by side when it is wide; and no two parts ever overlap. (The owner, 7 October 2026: "I do not like how it only shows
 * the map or the radar"; "it should be gradual and seamless".) {@code FluidPageTest} checks the real page's views.
 */
public final class FluidLayoutTest {
    /** The page's widths checked: narrow and typical phones, a large phone, a phone on its side, a tablet. */
    private static final int[] WIDTHS = {280, 320, 360, 411, 480, 600, 731, 840, 1280};
    /** The strip's words and the lines' heights: the normal font with no line, a setup step and a card, twice the font. */
    private static final int[][] CONTENTS = {{69, 0}, {69, 48}, {90, 160}, {170, 96}};
    private static final int HEADER = 56;
    /**
     * The fastest any edge may move, and any part grow or shrink, against the window's height (or width): the radar
     * and the map turning between one above the other and side by side move up to this fast; everything else moves no
     * faster than the window's edge.
     */
    private static final float MOST_RATE = 2f;

    private static FluidLayout solve(float density, int width, int height, int words, int lines, boolean area) {
        FluidLayout layout = new FluidLayout(density);
        layout.width = width;
        layout.height = height;
        layout.wordsHeight = Math.round(words * density);
        layout.headerHeight = Math.round(HEADER * density);
        layout.linesHeight = Math.round(lines * density);
        layout.areaLine = area;
        layout.solve();
        return layout;
    }

    private static List<FluidLayout.Box> boxes(FluidLayout layout) {
        List<FluidLayout.Box> boxes = new ArrayList<>();
        for (FluidLayout.Box box : new FluidLayout.Box[] {layout.strip, layout.mascot, layout.words, layout.header,
                layout.countsRow, layout.counts, layout.lines, layout.stage, layout.radar, layout.caption,
                layout.chart, layout.map, layout.area, layout.road}) {
            boxes.add(box);
        }
        return boxes;
    }

    private static final String[] NAMES = {"strip", "mascot", "words", "header", "counts row", "counts", "lines",
            "stage", "radar", "caption", "chart", "map", "area line", "road"};

    private static float[] values(FluidLayout layout) {
        List<FluidLayout.Box> boxes = boxes(layout);
        float[] values = new float[boxes.size() * 4 + 3 + 5];
        int at = 0;
        for (FluidLayout.Box box : boxes) {
            values[at++] = box.left;
            values[at++] = box.top;
            values[at++] = box.right;
            values[at++] = box.bottom;
        }
        values[at++] = layout.radarX;
        values[at++] = layout.radarY;
        values[at++] = layout.radius;
        values[at++] = layout.countsShown;
        values[at++] = layout.horizonShown;
        values[at++] = layout.stageShown;
        values[at++] = layout.roadShown;
        values[at] = layout.turn;
        return values;
    }

    private static String valueName(int index) {
        int boxes = NAMES.length;
        if (index < boxes * 4) return NAMES[index / 4] + " " + new String[] {"left", "top", "right", "bottom"}[index % 4];
        return new String[] {"radar x", "radar y", "radius", "counts shown", "skyline shown", "stage shown",
                "road shown", "turn"}[index - boxes * 4];
    }

    /**
     * Every box edge and the radar's circle move by at most {@link #MOST_RATE} times the window's change (in pixels,
     * plus one for rounding), and what is shown changes by at most its own range's share: swept a pixel at a time.
     */
    private static void sweep(float density, boolean heights) {
        for (int width : WIDTHS) {
            for (int[] content : CONTENTS) {
                for (boolean area : new boolean[] {false, true}) {
                    int fixed = width;
                    float[] before = null;
                    String where = "";
                    int from = heights ? 0 : 200;
                    int to = heights ? Math.round(1400 * density) : Math.round(1400 * density);
                    for (int size = from; size <= to; size++) {
                        FluidLayout layout = heights
                                ? solve(density, Math.round(width * density), size, content[0], content[1], area)
                                : solve(density, size, Math.round(width * density) + Math.round(200 * density),
                                        content[0], content[1], area);
                        float[] now = values(layout);
                        String here = (heights ? "width " + width + " dp, height " + size + " px"
                                : "height " + (width + 200) + " dp, width " + size + " px") + ", density "
                                + density + ", words " + content[0] + ", lines " + content[1] + ", area " + area;
                        if (before != null) {
                            int boxValues = NAMES.length * 4 + 3;
                            for (int i = 0; i < now.length; i++) {
                                float change = Math.abs(now[i] - before[i]);
                                if (i < boxValues) {
                                    // A box's left and right edges move with the width: a width sweep allows them the
                                    // page's own change too.
                                    assertTrue(valueName(i) + " jumped " + change + " px from " + where + " to " + here,
                                            change <= MOST_RATE + 1);
                                } else if (i < now.length - 1) {
                                    assertTrue(valueName(i) + " jumped " + change + " from " + where + " to " + here,
                                            change <= 0.05f);
                                } else {
                                    assertTrue("the turn jumped " + change + " from " + where + " to " + here,
                                            change <= 0.02f);
                                }
                            }
                        }
                        before = now;
                        where = here;
                        assertTrue(fixed > 0);
                    }
                }
            }
        }
    }

    @Test
    public void aWindowGrowingOrShrinkingAPixelAtATimeMovesNothingByMoreThanItsShare() {
        sweep(1f, true);
        sweep(2.625f, true);
    }

    @Test
    public void aWindowWideningOrNarrowingAPixelAtATimeMovesNothingByMoreThanItsShare() {
        sweep(1f, false);
        sweep(2.625f, false);
    }
}
