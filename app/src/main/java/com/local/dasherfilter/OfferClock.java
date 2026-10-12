package com.local.dasherfilter;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The offer clock (0.6.2; the owner, 12 October 2026: "we need how time is affecting their earnings to be something the
 * user is aware of", with "% of minimums" chosen as its measure). It splits the day into eight 3-hour slots. Each slot
 * shows the middle score of the retained offers that came in it: pay as a percent of what the minimums ask now
 * ({@link AreaScore#scorePercent} under the current rules and scoring model, the same figure the filter decides with).
 * The current slot is marked. It is history, never a promise: it keeps nothing of its own and reads only the history
 * the app already keeps (the latest {@link DecisionLog#MAX_ENTRIES} decisions).
 *
 * <p>It is drawn in the skyline's caption row ({@link Face}), which is empty unless an older offer is selected, so it
 * takes no room of its own; screen readers hear {@link #said}.
 */
final class OfferClock {
    static final int SLOTS = 8;
    static final int HOURS = 24 / SLOTS;
    /** A slot with fewer offers than this is drawn hollow: too few to say much. */
    static final int FEWEST = 5;
    /** No clock at all before this many scored offers. */
    static final int LEAST_OFFERS = 10;
    /**
     * Lines of one offer read again this close together count once: the same pay with no fact that differs (a screen
     * reading and Dasher's notification of it, which may lack the miles, included).
     */
    static final long SAME_OFFER_MS = 90_000;
    static final String TITLE = "Offers by time of day";
    static final String DETAILS = "Each bar is the middle score of your recent offers that came in those three hours, "
            + "as a percent of your minimums now (100% just meets them). Hollow bars have fewer than " + FEWEST
            + " offers. The hours marked now are the three you are in. This is history, not a promise.";
    private static final String[] STARTS = {"12a", "3a", "6a", "9a", "12p", "3p", "6p", "9p"};
    private static final String[] SAID_STARTS = {"midnight", "3 AM", "6 AM", "9 AM", "noon", "3 PM", "6 PM", "9 PM"};
    private static final String[] TABLE_STARTS = {"12 am", "3 am", "6 am", "9 am", "12 pm", "3 pm", "6 pm", "9 pm"};

    /** Each slot's middle score in percent (-1 with no offer), and how many offers it has. */
    final int[] middle = new int[SLOTS];
    final int[] offers = new int[SLOTS];
    /** Scored offers in all. */
    final int total;
    /** The slot the clock was made in, and the time zone its offers were sorted into slots by. */
    final int now;
    final ZoneId zone;

    private OfferClock(List<List<Integer>> scores, int now, ZoneId zone) {
        int sum = 0;
        for (int i = 0; i < SLOTS; i++) {
            List<Integer> slot = scores.get(i);
            offers[i] = slot.size();
            sum += slot.size();
            if (slot.isEmpty()) {
                middle[i] = -1;
            } else {
                Collections.sort(slot);
                int n = slot.size();
                // The lower middle of an even count: a whole percent, never a half the offers did not have.
                middle[i] = slot.get((n - 1) / 2);
            }
        }
        total = sum;
        this.now = now;
        this.zone = zone;
    }

    /**
     * The clock for {@code entries} under {@code rules}, or null while fewer than {@link #LEAST_OFFERS} offers can be
     * scored (no money minimum set, or too little history). Only standalone offers count, each offer once: an add-on,
     * a re-check of an offer already recorded, the same offer read again within {@link #SAME_OFFER_MS} and an offer
     * whose pay or a needed fact was not read are left out.
     */
    static OfferClock of(List<DecisionLog.Entry> entries, FilterSettings rules, ZoneId zone, long nowMillis) {
        if (entries == null || rules == null || !rules.hasMonetaryRule()) return null;
        List<List<Integer>> scores = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) scores.add(new ArrayList<>());
        List<DecisionLog.Entry> counted = new ArrayList<>();
        for (DecisionLog.Entry entry : entries) {
            if (entry.addOn || entry.replay || entry.action == DecisionLog.Action.REPLAY) continue;
            if (entry.facts == null || entry.facts.payCents == null || readAgain(entry, counted)) continue;
            int score = AreaScore.scorePercent(rules, entry.facts);
            if (score < 0) continue;
            counted.add(entry);
            scores.get(slot(entry.at, zone)).add(score);
        }
        OfferClock clock = new OfferClock(scores, slot(nowMillis, zone), zone);
        return clock.total < LEAST_OFFERS ? null : clock;
    }

    /** The same offer as one already counted: the same pay and nothing that differs, read within the minute and a half. */
    private static boolean readAgain(DecisionLog.Entry entry, List<DecisionLog.Entry> counted) {
        for (DecisionLog.Entry other : counted) {
            if (Math.abs(other.at - entry.at) <= SAME_OFFER_MS && entry.facts.payCents.equals(other.facts.payCents)
                    && !entry.facts.contradicts(other.facts)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this clock still stands at {@code nowMillis}: the same slot, in the same time zone. */
    boolean current(long nowMillis, ZoneId zone) {
        return this.zone.equals(zone) && now == slot(nowMillis, zone);
    }

    static int slot(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).getHour() / HOURS;
    }

    /** The clock in words, for screen readers. */
    String said() {
        StringBuilder words = new StringBuilder(TITLE).append(", as a percent of your minimums now. ");
        for (int i = 0; i < SLOTS; i++) {
            String start = SAID_STARTS[i];
            words.append(Character.toUpperCase(start.charAt(0))).append(start, 1, start.length()).append(" to ")
                    .append(SAID_STARTS[(i + 1) % SLOTS]).append(": ");
            if (offers[i] == 0) {
                words.append("no offers");
            } else {
                words.append(middle[i]).append("%, ").append(offers[i]).append(offers[i] == 1 ? " offer" : " offers");
            }
            words.append(i == now ? ", now. " : ". ");
        }
        return words.append("History, not a promise.").toString();
    }

    /** Each slot on a line of its own, for the clock's details: "6 pm–9 pm  150% · 4 offers (now)". */
    String table() {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < SLOTS; i++) {
            if (i > 0) lines.append('\n');
            lines.append(TABLE_STARTS[i]).append('–').append(TABLE_STARTS[(i + 1) % SLOTS]).append("  ");
            if (offers[i] == 0) {
                lines.append("no offers");
            } else {
                lines.append(middle[i]).append("% · ").append(offers[i]).append(offers[i] == 1 ? " offer" : " offers");
            }
            if (i == now) lines.append(" (now)");
        }
        return lines.toString();
    }

    /** The clock as a drawing in its row: eight bars over their hours, the 100% line across, the current slot marked. */
    static final class Face extends Drawable {
        /** The most the bars' scale shows; a higher middle is drawn at the top with a mark. */
        static final int SCALE_MOST = 300;
        /** It fades in over this long as it comes into its row, never popping in (an older offer's words leave at once). */
        static final long APPEAR_MS = 200;
        /**
         * It is drawn from this wide (0.6.3: 100 dp, from 120, so the skyline's column keeps it in a short split
         * screen), its sides 8 dp in there, widening to 12 dp by 20 dp more and staying so.
         */
        static final float LEAST_WIDE_DP = 100;
        private static final float SIDE_LEAST_DP = 8;
        private static final float SIDE_MORE_DP = 4;
        private static final float SIDE_OVER_DP = 20;
        private final Ui ui;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private OfferClock clock;
        private int alpha = 255;
        /** {@link #alpha} as this frame draws it, through the fade-in. */
        private int drawAlpha = 255;
        private long appearedAt = Long.MIN_VALUE;

        Face(Ui ui) {
            this.ui = ui;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            label.setTextAlign(Paint.Align.CENTER);
        }

        /** What to draw; null draws nothing. */
        void show(OfferClock clock) {
            if (this.clock == clock) return;
            if (this.clock == null && clock != null) appearedAt = SystemClock.uptimeMillis();
            this.clock = clock;
            invalidateSelf();
        }

        OfferClock clock() {
            return clock;
        }

        /** The room at each side of a face {@code wide} pixels across: 8 dp at 100 dp, 12 dp from 120 dp, in step. */
        float sideFor(float wide) {
            float more = Math.max(0, Math.min(1, (wide - ui.dp(LEAST_WIDE_DP)) / ui.dp(SIDE_OVER_DP)));
            return ui.dp(SIDE_LEAST_DP) + ui.dp(SIDE_MORE_DP) * more;
        }

        /** The top of the scale for this clock: 150% at least, else the highest middle rounded up to 50, at most 300%. */
        static int scaleTop(OfferClock clock) {
            int top = 150;
            for (int m : clock.middle) if (m > top) top = (m + 49) / 50 * 50;
            return Math.min(SCALE_MOST, top);
        }

        @Override public void draw(Canvas canvas) {
            OfferClock c = clock;
            android.graphics.Rect b = getBounds();
            if (c == null || b.height() < ui.dp(32) || b.width() < ui.dp(LEAST_WIDE_DP)) return;
            long since = SystemClock.uptimeMillis() - appearedAt;
            float appear = since >= APPEAR_MS || since < 0 ? 1f : since / (float) APPEAR_MS;
            drawAlpha = Math.round(alpha * appear);
            if (appear < 1f) invalidateSelf();
            float side = sideFor(b.width());
            float left = b.left + side;
            float right = b.right - side;
            float text = Math.min(ui.sp(10), ui.dp(11));
            label.setTextSize(text);
            float titleBottom = b.top + ui.dp(2) + text;
            float labelsTop = b.bottom - ui.dp(2) - text;
            float barsTop = titleBottom + ui.dp(3);
            float barsBottom = labelsTop - ui.dp(2);
            if (barsBottom - barsTop < ui.dp(8)) return;
            int top = scaleTop(c);
            float slot = (right - left) / SLOTS;
            // The skyline's tree colour: a score, as its trees are.
            int bar = faded(ui.dark ? 0xFF9DD6B6 : 0xFF32664F);
            // The title, at the left over the bars.
            label.setTextAlign(Paint.Align.LEFT);
            label.setColor(faded(ui.inkSecondary));
            label.setFakeBoldText(false);
            // Only where it fits beside the key (screen readers hear it whatever the width).
            float key = label.measureText("100%") + ui.dp(20);
            if (label.measureText(TITLE) + ui.dp(8) + key <= right - left) {
                canvas.drawText(TITLE, left, titleBottom - label.descent(), label);
            }
            // 100%: just meets the minimums now. Solid and thin: the skyline's dashed line is Autopilot's bar.
            float hundred = y(100, top, barsTop, barsBottom);
            line.setStrokeWidth(ui.dp(1));
            line.setColor(faded(ui.inkMuted));
            canvas.drawLine(left, hundred, right, hundred, line);
            // Its key, in the title's row at the right: a short line and "100%".
            label.setTextAlign(Paint.Align.RIGHT);
            label.setColor(faded(ui.inkMuted));
            float keyEnd = right - label.measureText("100%") - ui.dp(4);
            float keyY = titleBottom - text / 2 + ui.dp(1);
            canvas.drawLine(keyEnd - ui.dp(12), keyY, keyEnd, keyY, line);
            canvas.drawText("100%", right, titleBottom - label.descent(), label);
            label.setTextAlign(Paint.Align.CENTER);
            float width = Math.min(ui.dp(18), slot * 0.5f);
            // Every hour's label where they fit side by side; else every other one, and only "now" where even those
            // would touch ("now" always, with its neighbours left out so nothing touches it).
            label.setFakeBoldText(true);
            float widest = Math.max(label.measureText("12p"), label.measureText("now")) + ui.dp(2);
            label.setFakeBoldText(false);
            boolean every = widest <= slot;
            boolean everyOther = widest <= 2 * slot;
            for (int i = 0; i < SLOTS; i++) {
                float center = left + slot * (i + 0.5f);
                boolean current = i == c.now;
                if (c.offers[i] > 0) {
                    // A low middle still shows a sliver: the slot has offers.
                    float tip = Math.min(y(Math.min(c.middle[i], top), top, barsTop, barsBottom),
                            barsBottom - ui.dp(2));
                    rect.set(center - width / 2, tip, center + width / 2, barsBottom);
                    float round = Math.min(ui.dp(3), width / 2);
                    if (c.offers[i] >= FEWEST) {
                        fill.setStyle(Paint.Style.FILL);
                        fill.setColor(bar);
                        canvas.drawRoundRect(rect, round, round, fill);
                    } else {
                        line.setStrokeWidth(ui.dp(1.5f));
                        line.setColor(bar);
                        rect.inset(ui.dp(0.75f), ui.dp(0.75f));
                        canvas.drawRoundRect(rect, round, round, line);
                    }
                    if (c.middle[i] > top) {
                        // Clipped for the picture: a small chevron cut into its top says it goes higher.
                        line.setStrokeWidth(ui.dp(1.5f));
                        line.setColor(c.offers[i] >= FEWEST ? faded(ui.page) : bar);
                        tip += ui.dp(2);
                        float h = ui.dp(3);
                        canvas.drawLine(center - h, tip + h, center, tip, line);
                        canvas.drawLine(center, tip, center + h, tip + h, line);
                    }
                } else {
                    line.setStrokeWidth(ui.dp(1.5f));
                    line.setColor(faded(ui.gridline));
                    canvas.drawLine(center - width / 3, barsBottom, center + width / 3, barsBottom, line);
                }
                if (!current && !every && (!everyOther || i % 2 != 0 || Math.abs(i - c.now) == 1)) continue;
                label.setColor(faded(current ? ui.ink : ui.inkMuted));
                label.setFakeBoldText(current);
                canvas.drawText(current ? "now" : STARTS[i], center, b.bottom - ui.dp(2) - label.descent(), label);
            }
            label.setFakeBoldText(false);
        }

        private static float y(int percent, int top, float barsTop, float barsBottom) {
            return barsBottom - (barsBottom - barsTop) * Math.max(0, percent) / (float) top;
        }

        private int faded(int color) {
            return drawAlpha >= 255 ? color : (color & 0x00FFFFFF) | ((Color.alpha(color) * drawAlpha / 255) << 24);
        }

        @Override public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter filter) {
            fill.setColorFilter(filter);
            line.setColorFilter(filter);
            label.setColorFilter(filter);
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

}
