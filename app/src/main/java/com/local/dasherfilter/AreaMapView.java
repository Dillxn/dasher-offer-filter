package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The offer areas drawn into the homepage's ground, north up, with no map tiles: each square where offers came in is
 * gilded deeper the better its pay per mile, with coins on the best three (named after their neighbourhood when the
 * phone's place lookup knows it) and a dotted trail from you to the best. Squares with too few offers to rank are
 * dashed outlines. A dot marks where the phone is now; a scale bar and a compass rose give distance and direction.
 * The land fades out toward every edge instead of stopping at a hard line, so a square or coin the edge cuts through
 * dissolves into the scene's ground; the compass and the scale stay crisp above it. Tap a square to select it. With
 * nothing to show yet, it says why.
 */
@SuppressLint("ViewConstructor")
final class AreaMapView extends View {
    interface OnSelect {
        void selected(AreaMap.Cell cell);
    }

    private static final double MILES_PER_DEGREE_LATITUDE = 69.05;
    private static final int[] MEDALS = {0xFFE3B341, 0xFFB9BDC3, 0xFFCD8B4E};
    private static final int GOLD = 0xFFD39B2A;
    static final int EXPLAIN_ATLAS = 0x01030001;
    private static final String TITLE = "Offers received · $/mi";
    private static final String HELP = "Each square is the approximate area your phone was in when standalone "
            + "offers arrived, including declined offers. These are receiving areas, not pickups, final stops "
            + "or Dasher hotspots.\n\n"
            + "Gold: deeper gold means higher offered pay per mile among your ranked areas. The rate is total "
            + "offered pay divided by total offer miles, using only offers with both figures.\n\n"
            + "1, 2, 3: the highest ranked areas. At least " + AreaMap.MIN_OFFERS
            + " offers with pay and miles are needed before ranking, so one offer cannot put an area first. "
            + "Dashed squares need more measured offers.\n\n"
            + "You: the phone’s latest available location. The dotted trail points toward #1; it is not a road "
            + "route. North is up and the scale shows straight-line distance.\n\n"
            + "Tap a square for its rate and sample count. Tap its details below the map to open Maps. "
            + "This is recorded offer history, not earnings or a prediction of future offers.";

    private final Ui ui;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF pill = new RectF();
    private final android.text.TextPaint labelText = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final android.text.TextPaint keyText = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF hereLabel = new RectF();
    private final RectF labelObstacle = new RectF();
    private boolean helpPressed;
    private final Path path = new Path();
    /** Made once: the map redraws every frame while its "You" halo breathes. */
    private final DashPathEffect unrankedDash;
    private final DashPathEffect frameDash;
    /** How far in from each edge the land fades from nothing to full. */
    static final int FADE_DP = 22;
    /** Keeps the land only where the edge ramps allow: multiplies what was drawn by the ramp's alpha. */
    private final Paint fade = new Paint();
    private final RectF strip = new RectF();
    /** The least height it reads well at, when the page asks with no limit; less in a short window. */
    private int leastDp = 96;
    /** Where the place names already drawn this frame are, so none is drawn over another. */
    private final RectF[] namePills = {new RectF(), new RectF(), new RectF()};
    private int namesShown;
    /** One ramp per edge, from clear at the edge to opaque inward; remade only when the size changes. */
    private Shader fadeTop;
    private Shader fadeBottom;
    private Shader fadeLeft;
    private Shader fadeRight;
    private List<AreaMap.Cell> cells = Collections.emptyList();
    private List<AreaMap.Cell> ranked = Collections.emptyList();
    private double[] here;
    private AreaMap.Cell selected;
    private OnSelect onSelect;
    /** Place names by square ("row,col"), and for where the phone is. */
    private java.util.Map<String, String> names = Collections.emptyMap();
    private String hereName;
    private String emptyMessage = "Offers you see will be pinned here";

    // The projection, decided at each drawing: x = longitude × cos(reference latitude), y = latitude.
    private double scale;
    private double left;
    private double top;
    private double squash;

    AreaMapView(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        unrankedDash = new DashPathEffect(new float[] {ui.dp(4), ui.dp(3)}, 0);
        frameDash = new DashPathEffect(new float[] {ui.dp(6), ui.dp(4)}, 0);
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setClickable(true);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float landWidth = mapWidth(width);
        float across = sideFade(landWidth);
        float down = topFade(height);
        int clear = 0x00000000;
        int opaque = 0xFF000000;
        fadeTop = new LinearGradient(0, 0, 0, down, clear, opaque, Shader.TileMode.CLAMP);
        fadeBottom = new LinearGradient(0, height - down, 0, height, opaque, clear, Shader.TileMode.CLAMP);
        fadeLeft = new LinearGradient(0, 0, across, 0, clear, opaque, Shader.TileMode.CLAMP);
        fadeRight = new LinearGradient(landWidth - across, 0, landWidth, 0, opaque, clear, Shader.TileMode.CLAMP);
    }

    /** The left and right ramps: {@link #FADE_DP}, never more than a quarter of a narrow map. */
    private float sideFade(float width) {
        return Math.max(1, Math.min(ui.dp(FADE_DP), width / 4));
    }

    /** The top and bottom ramps: shorter, so a short map (half a split screen) keeps most of its height clear. */
    private float topFade(float height) {
        return Math.max(1, Math.min(ui.dp(FADE_DP), height * 0.12f));
    }

    /**
     * Fades what was drawn into the current layer toward every edge: each edge's strip is multiplied by its ramp, so
     * the corners, under two ramps, fade the most.
     */
    private void fadeEdges(Canvas canvas, float width, float height) {
        if (fadeTop == null) return;
        float across = sideFade(width);
        float down = topFade(height);
        strip.set(0, 0, width, down);
        fade.setShader(fadeTop);
        canvas.drawRect(strip, fade);
        strip.set(0, height - down, width, height);
        fade.setShader(fadeBottom);
        canvas.drawRect(strip, fade);
        strip.set(0, 0, across, height);
        fade.setShader(fadeLeft);
        canvas.drawRect(strip, fade);
        strip.set(width - across, 0, width, height);
        fade.setShader(fadeRight);
        canvas.drawRect(strip, fade);
        fade.setShader(null);
    }

    void setOnSelect(OnSelect onSelect) {
        this.onSelect = onSelect;
    }

    void show(List<AreaMap.Cell> cells, double[] here) {
        this.cells = new ArrayList<>(cells);
        this.ranked = AreaMap.ranked(this.cells);
        this.here = here;
        if (selected != null) selected = find(selected.row, selected.col);
        setContentDescription(describe());
        invalidate();
    }

    /** Names from the phone's place lookup: by square as "row,col", and for where the phone is. */
    void setNames(java.util.Map<String, String> byCell, String here) {
        names = byCell == null ? Collections.emptyMap() : new java.util.HashMap<>(byCell);
        hereName = here;
        setContentDescription(describe());
        invalidate();
    }

    /** What the ground says while there is nothing to map (for example, that location is needed). */
    void setEmptyMessage(String message) {
        if (message.equals(emptyMessage)) return;
        emptyMessage = message;
        setContentDescription(describe());
        invalidate();
    }

    static String key(AreaMap.Cell cell) {
        return cell.row + "," + cell.col;
    }

    AreaMap.Cell selected() {
        return selected;
    }

    /** Shows {@code cell} as selected without telling the listener: the screen's own choice, not the user's. */
    void select(AreaMap.Cell cell) {
        selected = cell == null ? null : find(cell.row, cell.col);
        invalidate();
    }

    /** The user's choice of square: selected, and reported to the listener. */
    void pick(AreaMap.Cell cell) {
        select(cell);
        if (onSelect != null && selected != null) onSelect.selected(selected);
    }

    /** 1 for the best square, 2, 3…; 0 when it has too few offers to rank. */
    int rank(AreaMap.Cell cell) {
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).row == cell.row && ranked.get(i).col == cell.col) return i + 1;
        }
        return 0;
    }

    private AreaMap.Cell find(int row, int col) {
        for (AreaMap.Cell cell : cells) if (cell.row == row && cell.col == col) return cell;
        return null;
    }

    private String describe() {
        if (cells.isEmpty()) return emptyMessage + ". " + spokenKey();
        List<String> best = new ArrayList<>();
        for (int i = 0; i < Math.min(3, ranked.size()); i++) {
            AreaMap.Cell cell = ranked.get(i);
            String where = AreaMap.from(here, cell);
            String name = names.get(key(cell));
            best.add((i + 1) + (name == null ? "" : ", " + name) + (where.isEmpty() ? "" : ", " + where) + ", "
                    + cell.perMile() + " over "
                    + cell.mileOffers + " offers");
        }
        int unranked = cells.size() - ranked.size();
        return (hereName == null ? "" : "You are in " + hereName + ". ")
                + "Best paying areas by pay per mile: " + (best.isEmpty() ? "none ranked yet" : String.join("; ", best))
                + "." + (unranked > 0 ? " " + unranked + (unranked == 1 ? " area has" : " areas have")
                + " too few offers to rank." : "") + " " + spokenKey();
    }

    private String spokenKey() {
        return "Offer map: phone areas when offers arrived, not pickups, final stops or Dasher hotspots. "
                + "Deeper gold means higher total offered pay divided by total offer miles. "
                + "Numbers 1 to 3 rank the best sampled areas. Dashed squares have fewer than " + AreaMap.MIN_OFFERS
                + " offers with pay and miles. The blue dot is You; the dotted trail points toward number 1, "
                + "not a road route. North is up. Recorded history, not a prediction. Use Explain offer map for the key.";
    }

    /** Whatever the page gives it on one screen; asked with no limit, the least it reads well at. */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? ui.dp(leastDp)
                : resolveSize(Math.min(ui.dp(320), Math.max(ui.dp(220), width)), heightSpec);
        setMeasuredDimension(width, height);
    }

    private void project(float width, float height) {
        // The heading and right-hand ornaments have their own space, including the info control's full hit area.
        width = mapWidth(width);
        float keyHeight = keyHeight();
        height = Math.max(ui.dp(34), height - keyHeight);
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLng = Double.MAX_VALUE;
        double maxLng = -Double.MAX_VALUE;
        for (AreaMap.Cell cell : cells) {
            minLat = Math.min(minLat, cell.row * AreaMap.CELL_DEGREES);
            maxLat = Math.max(maxLat, (cell.row + 1) * AreaMap.CELL_DEGREES);
            minLng = Math.min(minLng, cell.col * AreaMap.CELL_DEGREES);
            maxLng = Math.max(maxLng, (cell.col + 1) * AreaMap.CELL_DEGREES);
        }
        if (here != null) {
            minLat = Math.min(minLat, here[0]);
            maxLat = Math.max(maxLat, here[0]);
            minLng = Math.min(minLng, here[1]);
            maxLng = Math.max(maxLng, here[1]);
        }
        double margin = AreaMap.CELL_DEGREES * 0.6;
        minLat -= margin;
        maxLat += margin;
        minLng -= margin;
        maxLng += margin;
        squash = Math.cos(Math.toRadians((minLat + maxLat) / 2));
        double spanX = (maxLng - minLng) * squash;
        double spanY = maxLat - minLat;
        double pad = ui.dp(16);
        scale = Math.min((width - 2 * pad) / spanX, (height - 2 * pad) / spanY);
        // A lone square is not blown up to fill the map.
        scale = Math.min(scale, ui.dp(70) / AreaMap.CELL_DEGREES);
        left = minLng * squash - (width / scale - spanX) / 2;
        top = maxLat + (height / scale - spanY) / 2;
        // Squares stay big enough to read and tap: in a short strip of ground, the map centres on where you are
        // (else on the best square) and lets far squares fall outside it.
        // A slightly smaller square in a very short map can keep both your dot and a
        // nearby #1 badge visible there; normal maps keep their 34 dp minimum.
        double readable = Math.min(ui.dp(34), Math.max(ui.dp(24), height - ui.dp(22))) / AreaMap.CELL_DEGREES;
        if (scale < readable) {
            scale = readable;
            AreaMap.Cell focus = ranked.isEmpty() ? cells.get(0) : ranked.get(0);
            double latitude = here != null ? here[0] : focus.latitude();
            double longitude = here != null ? here[1] : focus.longitude();
            if (here != null && !ranked.isEmpty()) {
                // Where the best square's coin is drawn, in degrees: its square's top-right corner.
                double corner = Math.max(0, (AreaMap.CELL_DEGREES * scale / 2 - ui.dp(14))) / scale;
                double coinLatitude = focus.latitude() + corner;
                double coinLongitude = focus.longitude() + corner / squash;
                // Both in the clear middle (inside the faded edges, with room for the coin) when they fit: centre
                // between them. Otherwise stay on you.
                double roomX = (width / 2 - sideFade(width) - ui.dp(14)) / scale;
                // Below the key there is no faded top edge. In a short map keep the two markers within its
                // actual clear height; using the full-map edge margin would unnecessarily hide a nearby #1.
                double roomY = (height / 2 - (height <= ui.dp(80) ? ui.dp(10)
                        : topFade(height) + ui.dp(14))) / scale;
                if (Math.abs(coinLongitude - longitude) * squash <= 2 * roomX
                        && Math.abs(coinLatitude - latitude) <= 2 * roomY) {
                    latitude = (latitude + coinLatitude) / 2;
                    longitude = (longitude + coinLongitude) / 2;
                }
            }
            left = longitude * squash - width / scale / 2;
            top = latitude + height / scale / 2;
        }
        top += keyHeight / scale;
    }

    private float keyHeight() {
        return ui.dp(20);
    }

    private float mapWidth(float width) {
        return Math.max(1, width - ui.dp(58));
    }

    private void cellBounds(AreaMap.Cell cell, RectF out) {
        float inset = ui.dp(2);
        out.set(x(cell.col * AreaMap.CELL_DEGREES) + inset, y((cell.row + 1) * AreaMap.CELL_DEGREES) + inset,
                x((cell.col + 1) * AreaMap.CELL_DEGREES) - inset, y(cell.row * AreaMap.CELL_DEGREES) - inset);
    }

    /** Actual plotted geometry, shared with the drawing and selection checks. */
    RectF areaBounds(AreaMap.Cell cell) {
        project(getWidth(), getHeight());
        RectF out = new RectF();
        cellBounds(cell, out);
        return out;
    }

    private void medalBounds(AreaMap.Cell cell, RectF out) {
        float radius = ui.dp(11);
        float corner = Math.max(0, (float) (AreaMap.CELL_DEGREES * scale / 2) - radius - ui.dp(3));
        float cx = x(cell.longitude()) + corner, cy = y(cell.latitude()) - corner;
        out.set(cx - radius, cy - radius, cx + radius, cy + radius);
    }

    RectF rankBounds(AreaMap.Cell cell) {
        RectF out = new RectF();
        medalBounds(cell, out);
        return out;
    }

    RectF hereLabelBounds() {
        return new RectF(hereLabel);
    }

    private float x(double longitude) {
        return (float) ((longitude * squash - left) * scale);
    }

    private float y(double latitude) {
        return (float) ((top - latitude) * scale);
    }

    @Override protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        float landWidth = mapWidth(width);
        hereLabel.setEmpty();
        // No paper: the squares lie on the scene's own ground.
        rect.set(0, 0, width, height);
        if (cells.isEmpty()) {
            int fields = canvas.saveLayer(0, 0, width, height, null);
            canvas.clipRect(0, keyHeight(), landWidth, height);
            drawEmptyFields(canvas, landWidth, height);
            fadeEdges(canvas, landWidth, height);
            canvas.restoreToCount(fields);
            text.setColor(brown());
            text.setTextSize(Math.min(ui.sp(14), ui.dp(20)));
            text.setFakeBoldText(false);
            canvas.drawText(emptyMessage, width / 2, (height + keyHeight()) / 2, text);
            text.setFakeBoldText(true);
            drawNorth(canvas, width);
            drawKey(canvas, width);
            return;
        }
        project(width, height);
        // The land is drawn into its own layer, faded at the edges, then laid on the ground.
        int land = canvas.saveLayer(0, 0, width, height, null);
        canvas.clipRect(0, keyHeight(), landWidth, height);
        drawGrid(canvas, landWidth, height);

        double best = ranked.isEmpty() ? 0 : ranked.get(0).centsPerMile();
        double worst = ranked.isEmpty() ? 0 : ranked.get(ranked.size() - 1).centsPerMile();
        for (AreaMap.Cell cell : cells) {
            cellBounds(cell, rect);
            float corner = Math.min(ui.dp(8), rect.width() / 4);
            if (cell.ranked()) {
                double share = best > worst ? (cell.centsPerMile() - worst) / (best - worst) : 1;
                int alpha = (int) Math.round(0x40 + share * (0xE6 - 0x40));
                fill.setColor((GOLD & 0x00FFFFFF) | (alpha << 24));
                canvas.drawRoundRect(rect, corner, corner, fill);
            } else {
                line.setColor(brown());
                line.setStrokeWidth(Math.max(1, ui.dp(1.5f)));
                line.setPathEffect(unrankedDash);
                line.setColor(brown());
                canvas.drawRoundRect(rect, corner, corner, line);
                line.setPathEffect(null);
            }
            if (selected != null && selected.row == cell.row && selected.col == cell.col) {
                line.setColor(ui.dark ? 0xFFF3E6C8 : 0xFF4A3622);
                line.setStrokeWidth(ui.dp(2.5f));
                canvas.drawRoundRect(rect, corner, corner, line);
            }
        }
        if (here != null && !ranked.isEmpty()) drawTrail(canvas, ranked.get(0));
        for (int i = Math.min(3, ranked.size()) - 1; i >= 0; i--) drawMedal(canvas, ranked.get(i), i);
        namesShown = 0;
        for (int i = 0; i < Math.min(3, ranked.size()); i++) drawName(canvas, ranked.get(i));
        if (here != null) drawHere(canvas);
        fadeEdges(canvas, landWidth, height);
        canvas.restoreToCount(land);
        drawNorth(canvas, width);
        drawScale(canvas, height);
        drawKey(canvas, width);
        if (here != null) Motion.next(this);
    }

    private int brown() {
        return ui.dark ? 0xFFC9B48C : 0xFF7A5C3A;
    }

    /** One quiet heading; the full key is one tap away, outside Settings. */
    private void drawKey(Canvas canvas, float width) {
        keyText.setColor(brown());
        keyText.setTextAlign(Paint.Align.LEFT);
        keyText.setTypeface(android.graphics.Typeface.DEFAULT);
        keyText.setTextSize(Math.min(ui.sp(11), ui.dp(14)));
        CharSequence title = Ui.fit(keyText, TITLE, width - ui.dp(60), 0.9f);
        canvas.drawText(title, 0, title.length(), ui.dp(12), ui.dp(14), keyText);

        float cx = width - ui.dp(25), cy = ui.dp(13);
        line.setColor(brown());
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        canvas.drawCircle(cx, cy, ui.dp(7), line);
        text.setColor(brown());
        text.setTextSize(ui.dp(10));
        canvas.drawText("i", cx, cy + ui.dp(3.5f), text);
    }

    /** Shared by the visible info target and the screen-reader action. */
    void explainAtlas() {
        OwnWindowTouches.show(new AlertDialog.Builder(getContext()).setTitle("Reading the offer map").setMessage(HELP)
                .setPositiveButton("Got it", null));
    }

    /** Bounds of the info control; independent of any particular recorded area. */
    RectF helpBounds() {
        return new RectF(getWidth() - ui.dp(49), 0, getWidth() - ui.dp(1), Math.min(getHeight(), ui.dp(48)));
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(EXPLAIN_ATLAS, "Explain offer map"));
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == EXPLAIN_ATLAS) {
            explainAtlas();
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    /** A few faint field boundaries, so the ground still reads as land before any offer is mapped. */
    private void drawEmptyFields(Canvas canvas, float width, float height) {
        line.setColor((brown() & 0x00FFFFFF) | 0x24000000);
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        line.setPathEffect(frameDash);
        float step = ui.dp(56);
        for (float x = (width % step) / 2; x <= width; x += step) canvas.drawLine(x, 0, x, height, line);
        for (float y = (height % step) / 2; y <= height; y += step) canvas.drawLine(0, y, width, y, line);
        line.setPathEffect(null);
    }

    /**
     * The square's neighbourhood on a small label under its coin, when the phone's place lookup knows it. The best
     * square's name goes first; a later one that would overlap a name already shown is left out.
     */
    private void drawName(Canvas canvas, AreaMap.Cell cell) {
        String name = names.get(key(cell));
        if (name == null) return;
        float half = (float) (AreaMap.CELL_DEGREES * scale / 2);
        float cx = x(cell.longitude());
        float cy = y(cell.latitude()) + Math.min(half - ui.dp(4), ui.dp(14));
        CharSequence shown = measureLabel(name, cx, cy);
        for (int i = 0; i < namesShown; i++) {
            if (RectF.intersects(pill, namePills[i])) return;
        }
        namePills[namesShown++].set(pill);
        drawLabel(canvas, shown, cx, cy);
    }

    void setLeastDp(int dp) {
        leastDp = dp;
        requestLayout();
    }

    /** How many place names the last drawing showed (for tests). */
    int namesShown() {
        return namesShown;
    }

    /** Fits the label and sets {@link #pill} to where it would be drawn. */
    private CharSequence measureLabel(String label, float cx, float cy) {
        labelText.setFakeBoldText(true);
        labelText.setTextAlign(Paint.Align.CENTER);
        labelText.setTextSize(Math.min(ui.sp(10), ui.dp(14)));
        CharSequence shown = Ui.fit(labelText, label, ui.dp(120), 0.8f);
        float halfWidth = labelText.measureText(shown, 0, shown.length()) / 2 + ui.dp(5);
        float halfHeight = labelText.getTextSize() * 0.75f;
        pill.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight);
        return shown;
    }

    /** Text on a soft pill, so a name reads over any square. */
    private void drawLabel(Canvas canvas, CharSequence shown, float cx, float cy) {
        float halfHeight = pill.height() / 2;
        fill.setColor(ui.dark ? 0xCC14171C : 0xE6FFFFFF);
        canvas.drawRoundRect(pill, halfHeight, halfHeight, fill);
        labelText.setColor(ui.dark ? 0xFFF3E6C8 : 0xFF3A2A18);
        canvas.drawText(shown, 0, shown.length(), cx, cy + labelText.getTextSize() / 3, labelText);
    }

    /** A dotted trail from where you are to the best area's coin. */
    private void drawTrail(Canvas canvas, AreaMap.Cell best) {
        float fromX = x(here[1]);
        float fromY = y(here[0]);
        float corner = Math.max(0, (float) (AreaMap.CELL_DEGREES * scale / 2) - ui.dp(14));
        float toX = x(best.longitude()) + corner;
        float toY = y(best.latitude()) - corner;
        if (Math.hypot(toX - fromX, toY - fromY) < ui.dp(24)) return;
        path.reset();
        path.moveTo(fromX, fromY);
        path.quadTo((fromX + toX) / 2 + (toY - fromY) * 0.25f, (fromY + toY) / 2 - (toX - fromX) * 0.25f, toX, toY);
        line.setColor(ui.dark ? 0xFFE0876E : 0xFFB5523B);
        line.setStrokeWidth(ui.dp(2.5f));
        // The dots walk slowly toward the best area.
        line.setPathEffect(new DashPathEffect(new float[] {ui.dp(2), ui.dp(6)}, -ui.dp(8) * Motion.loop(1.6f, 0)));
        canvas.drawPath(path, line);
        line.setPathEffect(null);
    }

    /** Faint lines along the square edges, so the squares read as a grid on the paper. */
    private void drawGrid(Canvas canvas, float width, float height) {
        line.setColor((brown() & 0x00FFFFFF) | (ui.dark ? 0x1C000000 : 0x26000000));
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        double step = AreaMap.CELL_DEGREES;
        if (step * scale < ui.dp(10)) return;
        double firstLng = Math.floor((left / squash) / step) * step;
        for (double lng = firstLng; x(lng) <= width; lng += step) canvas.drawLine(x(lng), 0, x(lng), height, line);
        double firstLat = Math.ceil(top / step) * step;
        for (double lat = firstLat; y(lat) <= height; lat -= step) canvas.drawLine(0, y(lat), width, y(lat), line);
    }

    /** A coin with the area's place, in its square's top-right corner so the "You" dot stays clear. */
    private void drawMedal(Canvas canvas, AreaMap.Cell cell, int place) {
        float radius = ui.dp(11);
        medalBounds(cell, rect);
        float cx = rect.centerX(), cy = rect.centerY();
        fill.setColor(0x33000000);
        canvas.drawCircle(cx, cy + ui.dp(1.5f), radius, fill);
        fill.setColor(MEDALS[place]);
        canvas.drawCircle(cx, cy, radius, fill);
        line.setColor(0xFFFFFFFF);
        line.setStrokeWidth(ui.dp(1.5f));
        canvas.drawCircle(cx, cy, radius - ui.dp(2), line);
        text.setColor(0xFF2B2A27);
        text.setTextSize(ui.dp(12));
        canvas.drawText(Integer.toString(place + 1), cx, cy + text.getTextSize() / 3, text);
    }

    /** The phone's position: a dot with a halo that breathes out and fades. */
    private void drawHere(Canvas canvas) {
        float cx = x(here[1]);
        float cy = y(here[0]);
        float pulse = Motion.on() ? Motion.loop(2.8f, 0) : 0.4f;
        fill.setColor((ui.accent & 0x00FFFFFF) | (Math.round(0x4D * (1 - pulse)) << 24));
        canvas.drawCircle(cx, cy, ui.dp(9) + ui.dp(10) * pulse, fill);
        fill.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, ui.dp(7.5f), fill);
        fill.setColor(ui.accent);
        canvas.drawCircle(cx, cy, ui.dp(5.5f), fill);
        // The meaning travels with its marker, without a second legend floating in another corner.
        keyText.setTypeface(android.graphics.Typeface.DEFAULT);
        keyText.setColor(brown());
        keyText.setTextSize(Math.min(ui.sp(10), ui.dp(12)));
        keyText.setTextAlign(Paint.Align.LEFT);
        placeHereLabel(cx, cy);
        if (!hereLabel.isEmpty()) canvas.drawText("You", hereLabel.left,
                hereLabel.top - keyText.getFontMetrics().ascent, keyText);
    }

    /** Prefer clear land beside the dot, then free space on a square; never cover a rank or a place name. */
    private void placeHereLabel(float cx, float cy) {
        Paint.FontMetrics metrics = keyText.getFontMetrics();
        float width = keyText.measureText("You"), height = metrics.descent - metrics.ascent;
        for (int pass = 0; pass < 2; pass++) {
            for (int ring = 0; ring < 2; ring++) {
                float gap = ui.dp(14 + ring * 12);
                for (int side = 0; side < 8; side++) {
                    float left = side == 0 || side == 4 || side == 6 ? cx + gap
                            : side == 1 || side == 5 || side == 7 ? cx - gap - width : cx - width / 2;
                    float top = side == 2 || side == 4 || side == 5 ? cy + gap
                            : side == 3 || side == 6 || side == 7 ? cy - gap - height : cy - height / 2;
                    hereLabel.set(left, top, left + width, top + height);
                    if (labelHasRoom(pass == 0)) return;
                }
            }
        }
        // Dense or clipped data can leave no honest local label position. The dot and its accessible key remain.
        hereLabel.setEmpty();
    }

    private boolean labelHasRoom(boolean avoidSquares) {
        if (hereLabel.left < ui.dp(6) || hereLabel.right > mapWidth(getWidth()) - ui.dp(6)
                || hereLabel.top < keyHeight() + ui.dp(2) || hereLabel.bottom > getHeight() - ui.dp(8)) return false;
        for (int i = 0; i < Math.min(3, ranked.size()); i++) {
            medalBounds(ranked.get(i), labelObstacle);
            labelObstacle.inset(-ui.dp(3), -ui.dp(3));
            if (RectF.intersects(hereLabel, labelObstacle)) return false;
        }
        for (int i = 0; i < namesShown; i++) {
            if (RectF.intersects(hereLabel, namePills[i])) return false;
        }
        // Reserve the distance bar and its text as well as the ornaments in the right-hand gutter.
        labelObstacle.set(ui.dp(12), getHeight() - ui.dp(38), ui.dp(22) + mapWidth(getWidth()) / 3,
                getHeight() - ui.dp(14));
        if (RectF.intersects(hereLabel, labelObstacle)) return false;
        if (avoidSquares) {
            for (AreaMap.Cell cell : cells) {
                cellBounds(cell, labelObstacle);
                if (RectF.intersects(hereLabel, labelObstacle)) return false;
            }
        }
        return true;
    }

    /** A compass rose with north marked. */
    private void drawNorth(Canvas canvas, float width) {
        float cx = width - ui.dp(30);
        float cy = ui.dp(54);
        float big = ui.dp(15);
        float small = ui.dp(4);
        int ink = brown();
        line.setColor((ink & 0x00FFFFFF) | 0x80000000);
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        canvas.drawCircle(cx, cy, ui.dp(10), line);
        for (int point = 0; point < 4; point++) {
            double angle = Math.toRadians(point * 90 - 90);
            double side = Math.toRadians(point * 90 - 45);
            path.reset();
            path.moveTo(cx, cy);
            path.lineTo((float) (cx + Math.cos(side) * small), (float) (cy + Math.sin(side) * small));
            path.lineTo((float) (cx + Math.cos(angle) * big), (float) (cy + Math.sin(angle) * big));
            path.close();
            fill.setColor(point == 0 ? (ui.dark ? 0xFFE0876E : 0xFFB5523B) : ink);
            canvas.drawPath(path, fill);
        }
        text.setColor(ink);
        text.setTextSize(Math.min(ui.sp(10), ui.dp(14)));
        canvas.drawText("N", cx, cy - big - ui.dp(3), text);
    }

    /** A bar of a round distance (¼, ½, 1, 2, 5… mi) no wider than a third of the map. */
    private void drawScale(Canvas canvas, float height) {
        double pixelsPerMile = scale / MILES_PER_DEGREE_LATITUDE;
        double[] choices = {0.25, 0.5, 1, 2, 5, 10, 20, 50};
        double miles = choices[0];
        for (double choice : choices) if (choice * pixelsPerMile <= mapWidth(getWidth()) / 3.0) miles = choice;
        float length = (float) (miles * pixelsPerMile);
        float x0 = ui.dp(18);
        float y0 = height - ui.dp(18);
        line.setColor(brown());
        line.setStrokeWidth(ui.dp(2));
        canvas.drawLine(x0, y0, x0 + length, y0, line);
        canvas.drawLine(x0, y0 - ui.dp(4), x0, y0 + ui.dp(1), line);
        canvas.drawLine(x0 + length, y0 - ui.dp(4), x0 + length, y0 + ui.dp(1), line);
        text.setColor(brown());
        text.setTextSize(Math.min(ui.sp(10), ui.dp(14)));
        text.setTextAlign(Paint.Align.LEFT);
        String label = miles < 1 ? String.format(Locale.US, "%s mi", miles == 0.25 ? "¼" : "½")
                : String.format(Locale.US, "%.0f mi", miles);
        canvas.drawText(label, x0, y0 - ui.dp(7), text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            helpPressed = helpBounds().contains(event.getX(), event.getY());
            if (helpPressed) return true;
        }
        if (helpPressed) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                helpPressed = false;
                if (helpBounds().contains(event.getX(), event.getY())) explainAtlas();
            } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                helpPressed = false;
            }
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP && !cells.isEmpty()
                && event.getX() >= 0 && event.getX() < mapWidth(getWidth()) && event.getY() >= keyHeight()) {
            project(getWidth(), getHeight());
            AreaMap.Cell nearest = null;
            double nearestDistance = ui.dp(28);
            for (AreaMap.Cell cell : cells) {
                cellBounds(cell, rect);
                if (rect.right <= 0 || rect.left >= mapWidth(getWidth()) || rect.bottom <= keyHeight()
                        || rect.top >= getHeight()) continue;
                double distance = Math.hypot(x(cell.longitude()) - event.getX(), y(cell.latitude()) - event.getY());
                double half = AreaMap.CELL_DEGREES * scale / 2;
                if (distance - half < nearestDistance) {
                    nearestDistance = distance - half;
                    nearest = cell;
                }
            }
            if (nearest != null) pick(nearest);
        }
        return super.onTouchEvent(event);
    }
}
