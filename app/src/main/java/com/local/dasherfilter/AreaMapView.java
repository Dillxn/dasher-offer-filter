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
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The offer areas drawn as a strip under the homepage's radar and skyline, north up, with no map tiles: each square
 * where offers came in is gilded deeper the better its pay per mile. Squares with too few offers to rank are dashed
 * outlines. A dot marks where the phone is now; a scale bar and a small "N ↑" give distance and direction. The view
 * always holds both you and the best square. The land (its grid and squares) runs to every edge of the view, under the
 * key's "N ↑" and ⓘ too, and fades out toward every edge, with no frame round it, so a square the edge cuts through
 * dissolves into the scene's ground (0.6.21, the owner: "remove its container's outline and just let the map fade out
 * at the edges"); the scale, the key and the name stay crisp above it. The best square and the dot are still placed
 * clear of the key's row and the ⓘ's column. The selected
 * square (the best until another is picked) is outlined and named beside it with its pay per mile ("Hunt Rd ·
 * $2.40/mi"), on whichever side has room and never cut (0.6.8, the owner's mockup: no rank coins, no trail; 0.6.6, the
 * line under the map, its title and its compass rose gone: say each thing once). Tap a square to select it. With nothing to show yet, it says why, in
 * as many lines as the land holds (three at most). Drag it and the land goes with the finger, giving more the farther it
 * goes, and springs back into place when let go; the key, the ⓘ and the scale stay put (0.6.22, the owner: "i want to
 * be able to drag the map on our app and it spring back into place on release").
 */
@SuppressLint("ViewConstructor")
final class AreaMapView extends View {
    interface OnSelect {
        void selected(AreaMap.Cell cell);
    }

    private static final double MILES_PER_DEGREE_LATITUDE = 69.05;
    private static final int GOLD = 0xFFD39B2A;
    static final int EXPLAIN_ATLAS = 0x01030001;
    static final String NORTH = "N ↑";
    private static final String HELP = "Each square is the approximate area your phone was in when standalone "
            + "offers arrived, including declined offers. These are receiving areas, not pickups, final stops "
            + "or Dasher hotspots.\n\n"
            + "Gold: deeper gold means higher offered pay per mile among your ranked areas. The rate is total "
            + "offered pay divided by total offer miles, using only offers with both figures.\n\n"
            + "The named square is the best ranked area until you tap another. At least " + AreaMap.MIN_OFFERS
            + " offers with pay and miles are needed before ranking, so one offer cannot put an area first. "
            + "Dashed squares need more measured offers.\n\n"
            + "You: the phone’s latest available location. The map always shows both you and the best area. "
            + "North is up and the scale shows straight-line distance.\n\n"
            + "Tap a square for its name and rate. Tap the named square again to open it in Maps. "
            + "This is recorded offer history, not earnings or a prediction of future offers.";

    private final Ui ui;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF pill = new RectF();
    private final android.text.TextPaint labelText = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final android.text.TextPaint keyText = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final android.text.TextPaint emptyText = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    /** The words with nothing mapped, laid out for the message, width and height they were worked out for. */
    private android.text.StaticLayout emptyLayout;
    private String emptyLaidOut;
    private float emptyLaidWidth = -1;
    private float emptyLaidHeight = -1;
    private final RectF hereLabel = new RectF();
    private final RectF labelObstacle = new RectF();
    private boolean helpPressed;
    private final Path path = new Path();
    private String selectedSaid;
    /** Made once: the map redraws every frame while its "You" halo breathes. */
    private final DashPathEffect unrankedDash;
    private final DashPathEffect frameDash;
    /** How far in from each edge the land fades from nothing to full. */
    static final int FADE_DP = 22;
    /**
     * The right-hand gutter beside the land: the info control's 48 dp target and 1 dp, and the compass's 9 dp more as
     * much as the compass is there.
     */
    private static final float GUTTER_DP = 49;
    private static final float GUTTER_COMPASS_DP = 9;
    /** The words with nothing mapped: at most this many lines, this far in from the land's sides (both together). */
    private static final int EMPTY_LINES_MOST = 3;
    private static final float EMPTY_SIDES_DP = 12;
    /**
     * The land follows a drag at first and gives more the farther it goes: pulled this far (dp), it has moved half as
     * far, and it never moves this far.
     */
    static final int STRETCH_DP = 240;
    /** Let go, it springs back: past its place by about a tenth, back again, and at rest within this long (ms). */
    static final long SPRING_MS = 500;
    /** The spring's own pace (radians a second) and its damping (at 1 it would come back without passing its place). */
    private static final double SPRING_RATE = 24;
    private static final double SPRING_DAMPING = 0.6;
    /** Keeps the land only where the edge ramps allow: multiplies what was drawn by the ramp's alpha. */
    private final Paint fade = new Paint();
    private final RectF strip = new RectF();
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
    /** A drag (0.6.22): the finger it follows and where its pull counts from, in this view's pixels. */
    private final int touchSlop;
    private int dragFinger;
    private float downX;
    private float downY;
    /** This touch is a drag (never a tap); the land is under the finger now. */
    private boolean dragged;
    private boolean holding;
    /** How far the land is pulled from its place as drawn, in pixels; let go, when (uptime ms) and from where. */
    private float pullX;
    private float pullY;
    private boolean springing;
    private long springStart;
    private float springX;
    private float springY;

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
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setClickable(true);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // The land fills the whole view (0.6.21), so it fades at the view's own edges.
        float across = sideFade(width);
        float down = topFade(height);
        int clear = 0x00000000;
        int opaque = 0xFF000000;
        fadeTop = new LinearGradient(0, 0, 0, down, clear, opaque, Shader.TileMode.CLAMP);
        fadeBottom = new LinearGradient(0, height - down, 0, height, opaque, clear, Shader.TileMode.CLAMP);
        fadeLeft = new LinearGradient(0, 0, across, 0, clear, opaque, Shader.TileMode.CLAMP);
        fadeRight = new LinearGradient(width - across, 0, width, 0, opaque, clear, Shader.TileMode.CLAMP);
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

    /** What screen readers hear of the selected square (where, how far, its rate; a second tap opens Maps). */
    void setSelectedSaid(String said) {
        if (java.util.Objects.equals(said, selectedSaid)) return;
        selectedSaid = said;
        setContentDescription(describe());
    }

    /** What screen readers hear of the selected square, as set (for tests); null before one is shown. */
    String selectedSaid() {
        return selectedSaid;
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
                + " too few offers to rank." : "") + (selectedSaid == null ? "" : " " + selectedSaid) + " "
                + spokenKey();
    }

    private String spokenKey() {
        return "Offer map: phone areas when offers arrived, not pickups, final stops or Dasher hotspots. "
                + "Deeper gold means higher total offered pay divided by total offer miles. "
                + "The named square is the best ranked area until another is chosen. Dashed squares have fewer than "
                + AreaMap.MIN_OFFERS + " offers with pay and miles. The blue dot is You. North is up. Recorded "
                + "history, not a prediction. Use Explain offer map for the key.";
    }

    /** Whatever the page gives it; asked with no limit, the least it reads well at ({@link FluidLayout}). */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? ui.dp(FluidLayout.MAP_LEAST_DP)
                : resolveSize(Math.min(ui.dp(320), Math.max(ui.dp(220), width)), heightSpec);
        setMeasuredDimension(width, height);
    }

    private void project(float width, float height) {
        settlePull();
        // The heading and right-hand ornaments have their own space, including the info control's full hit area.
        width = mapWidth(width, height);
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
                // 0.6.8: the view always holds both you and the best square (the owner's mockup), smaller squares if
                // that is what it takes, centred between them.
                double half = AreaMap.CELL_DEGREES * 0.6;
                double edge = ui.dp(6);
                double spanBothX = (Math.abs(focus.longitude() - here[1]) + 2 * half) * squash;
                double spanBothY = Math.abs(focus.latitude() - here[0]) + 2 * half;
                double both = Math.min((width - 2 * edge) / spanBothX, (height - 2 * edge) / spanBothY);
                scale = Math.min(readable, both);
                latitude = (here[0] + focus.latitude()) / 2;
                longitude = (here[1] + focus.longitude()) / 2;
            }
            left = longitude * squash - width / scale / 2;
            top = latitude + height / scale / 2;
        }
        top += keyHeight / scale;
        // Dragged (0.6.22), the land and all on it go with the finger.
        left -= pullX / scale;
        top += pullY / scale;
    }

    private float keyHeight() {
        return ui.dp(20);
    }

    /**
     * The land's width in a map {@code width} by {@code height} pixels: all but the right-hand gutter, which keeps the
     * info control's 48 dp and, as much as the compass is there, its 9 dp more (58 dp in all, 49 with the compass
     * gone).
     */
    private float mapWidth(float width, float height) {
        return Math.max(1, width - ui.dp(GUTTER_DP) - ui.dp(GUTTER_COMPASS_DP) * compass(height));
    }

    /** How much of the compass rose is there: none from 0.6.6 ("N ↑" in the key's row says north). */
    float compass(float height) {
        return 0;
    }

    /** The land's width now, in pixels (for tests too). */
    float landWidth() {
        return mapWidth(getWidth(), getHeight());
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
        float landWidth = mapWidth(width, height);
        hereLabel.setEmpty();
        // No paper: the squares lie on the scene's own ground.
        rect.set(0, 0, width, height);
        if (cells.isEmpty()) {
            settlePull();
            int fields = canvas.saveLayer(0, 0, width, height, null);
            drawEmptyFields(canvas, width, height);
            fadeEdges(canvas, width, height);
            canvas.restoreToCount(fields);
            android.text.StaticLayout words = emptyLayout(landWidth, height);
            if (words != null) {
                canvas.save();
                canvas.translate(ui.dp(EMPTY_SIDES_DP) / 2f,
                        keyHeight() + Math.max(0, (height - keyHeight() - words.getHeight()) / 2));
                words.draw(canvas);
                canvas.restore();
            }
            drawKey(canvas, width);
            if (springing) Motion.settling(this);
            return;
        }
        project(width, height);
        // The land is drawn into its own layer, to every edge, faded at the edges, then laid on the ground.
        int land = canvas.saveLayer(0, 0, width, height, null);
        drawGrid(canvas, width, height);

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
        // 0.6.8 (the owner's mockup): no rank coins and no trail; the selected square (the best until another is
        // picked) carries its name and pay per mile beside it, over the faded edges, never cut.
        namesShown = 0;
        if (here != null) drawHere(canvas);
        fadeEdges(canvas, width, height);
        canvas.restoreToCount(land);
        AreaMap.Cell named = named();
        if (named != null) drawName(canvas, named);
        drawScale(canvas, height);
        drawKey(canvas, width);
        if (here != null) Motion.next(this);
        if (springing) Motion.settling(this);
    }

    private int brown() {
        return ui.dark ? 0xFFC9B48C : 0xFF7A5C3A;
    }

    /**
     * The words with nothing mapped, laid out in the land's width less 12 dp, centred: as many lines as the land under
     * the heading holds, three at most (one at the least); at 14 sp (20 dp at most), or where they need it smaller by
     * the least that fits them, never below three quarters of the user's size nor below the default size; still too
     * long there, their end is cut with an ellipsis. Screen readers hear them whole. Worked out again only when the
     * words or the size change.
     */
    private android.text.StaticLayout emptyLayout(float landWidth, float height) {
        int across = Math.round(landWidth - ui.dp(EMPTY_SIDES_DP));
        if (across <= 0) return null;
        if (emptyLayout != null && emptyMessage.equals(emptyLaidOut) && landWidth == emptyLaidWidth
                && height == emptyLaidHeight) {
            return emptyLayout;
        }
        emptyLaidOut = emptyMessage;
        emptyLaidWidth = landWidth;
        emptyLaidHeight = height;
        emptyText.setColor(brown());
        emptyText.setTypeface(android.graphics.Typeface.DEFAULT);
        float start = Math.min(ui.sp(14), ui.dp(20));
        float least = Math.min(start, Math.max(ui.sp(14) * SetupRow.LEAST_SCALE, ui.dp(14)));
        float room = Math.max(0, height - keyHeight());
        float step = Math.max(1f, (start - least) / 10f);
        android.text.StaticLayout laid = null;
        for (float size = start; ; size = Math.max(least, size - step)) {
            emptyText.setTextSize(size);
            int lines = Math.max(1, Math.min(EMPTY_LINES_MOST, (int) (room / Ui.lineHeight(emptyText))));
            laid = android.text.StaticLayout.Builder.obtain(emptyMessage, 0, emptyMessage.length(), emptyText, across)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .setMaxLines(lines)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build();
            boolean whole = laid.getEllipsisCount(laid.getLineCount() - 1) == 0
                    && laid.getLineEnd(laid.getLineCount() - 1) >= emptyMessage.length();
            if (whole || size <= least) break;
        }
        emptyLayout = laid;
        return laid;
    }

    /** The words with nothing mapped as the map shows them now, an ellipsis where they are cut (for tests). */
    String emptyShown() {
        android.text.StaticLayout words = emptyLayout(landWidth(), getHeight());
        if (words == null) return "";
        StringBuilder shown = new StringBuilder();
        for (int line = 0; line < words.getLineCount(); line++) {
            int start = words.getLineStart(line);
            boolean cut = words.getEllipsisCount(line) > 0;
            int end = cut ? start + words.getEllipsisStart(line) : words.getLineEnd(line);
            String text = emptyMessage.substring(start, Math.max(start, Math.min(emptyMessage.length(), end))).trim();
            if (shown.length() > 0 && !text.isEmpty()) shown.append(' ');
            shown.append(text);
            if (cut) shown.append('…');
        }
        return shown.toString();
    }

    /** The empty words' size as laid out now, in pixels (for tests). */
    float emptyTextSize() {
        android.text.StaticLayout words = emptyLayout(landWidth(), getHeight());
        return words == null ? 0 : words.getPaint().getTextSize();
    }

    /** "N ↑" for north; the full key is one tap away on the ⓘ, outside Settings. */
    private void drawKey(Canvas canvas, float width) {
        keyText.setColor(brown());
        keyText.setTextAlign(Paint.Align.LEFT);
        keyText.setTypeface(Ui.MEDIUM);
        keyText.setTextSize(Math.min(ui.sp(11), ui.dp(14)));
        // "N ↑" beside the ⓘ at the top right (0.6.8, the owner's mockup).
        keyText.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(NORTH, width - ui.dp(42), ui.dp(17), keyText);
        keyText.setTextAlign(Paint.Align.LEFT);

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
        // Dragged, the fields go with the finger: drawn as far round the view as the land can be pulled, each line
        // starting a whole number of dashes out, so its dashes go too.
        float dash = ui.dp(6) + ui.dp(4);
        float reach = pullX == 0 && pullY == 0 ? 0 : dash * (float) Math.ceil(ui.dp(STRETCH_DP) / dash);
        float before = step * (float) Math.ceil(reach / step);
        canvas.save();
        canvas.translate(pullX, pullY);
        for (float x = (width % step) / 2 - before; x <= width + reach; x += step) {
            canvas.drawLine(x, -reach, x, height + reach, line);
        }
        for (float y = (height % step) / 2 - before; y <= height + reach; y += step) {
            canvas.drawLine(-reach, y, width + reach, y, line);
        }
        canvas.restore();
        line.setPathEffect(null);
    }

    /** The selected square's neighbourhood (when the phone's place lookup knows it) and its pay per mile, beside it. */
    private void drawName(Canvas canvas, AreaMap.Cell cell) {
        String name = nameShown(cell);
        if (name == null) return;
        cellBounds(cell, rect);
        float cy = rect.centerY();
        CharSequence shown = measureLabel(name, 0, cy);
        float halfWidth = pill.width() / 2;
        float gap = ui.dp(6);
        float edge = ui.dp(6);
        // Beside the square on whichever side has room (its right first), else over it; always wholly inside the map.
        float cx;
        if (rect.right + gap + 2 * halfWidth <= getWidth() - edge) cx = rect.right + gap + halfWidth;
        else if (rect.left - gap - 2 * halfWidth >= edge) cx = rect.left - gap - halfWidth;
        else cx = rect.centerX();
        cx = Math.max(edge + halfWidth, Math.min(getWidth() - edge - halfWidth, cx));
        float halfHeight = pill.height() / 2;
        cy = Math.max(keyHeight() + halfHeight, Math.min(getHeight() - edge - halfHeight, cy));
        pill.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight);
        namePills[namesShown++].set(pill);
        drawLabel(canvas, shown, cx, cy);
    }

    /** Where the selected square's name stands, in this view's pixels, as last drawn; empty for none (for tests). */
    RectF namePill() {
        return namesShown > 0 ? new RectF(namePills[0]) : new RectF();
    }

    /**
     * What the map names a square: its neighbourhood, and for the selected one its pay per mile too ("Hunt Rd ·
     * $2.40/mi", or the rate alone with no name known; an unranked one says so); null for no label.
     */
    String nameShown(AreaMap.Cell cell) {
        String name = names.get(key(cell));
        AreaMap.Cell named = named();
        boolean chosen = named != null && named.row == cell.row && named.col == cell.col;
        if (!chosen) return name;
        String rate = cell.ranked() ? cell.perMile() : "Unranked";
        return name == null ? rate : name + " · " + rate;
    }

    /** The square the map names: the selected one, else the best ranked; null with none. */
    private AreaMap.Cell named() {
        if (selected != null) return selected;
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /** How many place names the last drawing showed (for tests). */
    int namesShown() {
        return namesShown;
    }

    /** Fits the label and sets {@link #pill} to where it would be drawn. */
    private CharSequence measureLabel(String label, float cx, float cy) {
        labelText.setFakeBoldText(true);
        labelText.setTextAlign(Paint.Align.CENTER);
        labelText.setTextSize(Math.min(ui.sp(13), ui.dp(17)));
        CharSequence shown = Ui.fit(labelText, label, Math.max(ui.dp(60), Math.min(ui.dp(220), getWidth()
                - ui.dp(16))), 0.8f);
        float halfWidth = labelText.measureText(shown, 0, shown.length()) / 2 + ui.dp(8);
        float halfHeight = labelText.getTextSize() * 0.8f;
        pill.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight);
        return shown;
    }

    /** Text on a soft pill, so a name reads over any square. */
    private void drawLabel(Canvas canvas, CharSequence shown, float cx, float cy) {
        float halfHeight = pill.height() / 2;
        fill.setColor(ui.dark ? 0xE014171C : 0xF0FFFFFF);
        canvas.drawRoundRect(pill, halfHeight, halfHeight, fill);
        line.setPathEffect(null);
        line.setStrokeWidth(ui.dp(1.5f));
        line.setColor(GOLD);
        canvas.drawRoundRect(pill, halfHeight, halfHeight, line);
        labelText.setColor(ui.dark ? 0xFFF3E6C8 : 0xFF3A2A18);
        canvas.drawText(shown, 0, shown.length(), cx, cy + labelText.getTextSize() / 3, labelText);
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
        if (hereLabel.left < ui.dp(6) || hereLabel.right > landWidth() - ui.dp(6)
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
        labelObstacle.set(ui.dp(12), getHeight() - ui.dp(38), ui.dp(22) + landWidth() / 3,
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

    /** {@code color} with its alpha multiplied by {@code shown} (0 to 1). */
    private static int faded(int color, float shown) {
        int alpha = Math.round((color >>> 24) * Math.max(0, Math.min(1, shown)));
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** A bar of a round distance (¼, ½, 1, 2, 5… mi) no wider than a third of the map. */
    private void drawScale(Canvas canvas, float height) {
        double pixelsPerMile = scale / MILES_PER_DEGREE_LATITUDE;
        double[] choices = {0.25, 0.5, 1, 2, 5, 10, 20, 50};
        double miles = choices[0];
        for (double choice : choices) if (choice * pixelsPerMile <= landWidth() / 3.0) miles = choice;
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

    /** Where the land is now: under the finger while held; let go, on its way back; else in its place. */
    private void settlePull() {
        if (!springing) return;
        long since = SystemClock.uptimeMillis() - springStart;
        if (!Motion.on() || since >= SPRING_MS) {
            springing = false;
            pullX = 0;
            pullY = 0;
            return;
        }
        double t = since / 1000.0;
        double ringing = SPRING_RATE * Math.sqrt(1 - SPRING_DAMPING * SPRING_DAMPING);
        double share = Math.exp(-SPRING_DAMPING * SPRING_RATE * t) * (Math.cos(ringing * t)
                + SPRING_DAMPING * SPRING_RATE / ringing * Math.sin(ringing * t));
        pullX = (float) (springX * share);
        pullY = (float) (springY * share);
    }

    /** How far the land is pulled from its place now, in pixels (for tests). */
    android.graphics.PointF pulled() {
        settlePull();
        return new android.graphics.PointF(pullX, pullY);
    }

    /** On its way back into place (for tests). */
    boolean springing() {
        settlePull();
        return springing;
    }

    /**
     * A drag (0.6.22, the owner: "i want to be able to drag the map on our app and it spring back into place on
     * release"). Past the touch slop the land goes with the finger from wherever it is (caught on its way back, it is
     * held there), and the page round it leaves that finger to the map; let go, it springs back. A drag is never a
     * tap: no square is picked, the ⓘ stays shut and the map's own tap (allowing location) does not happen. True for
     * the drag's events.
     */
    private boolean drag(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragFinger = event.getPointerId(0);
                downX = event.getX();
                downY = event.getY();
                dragged = false;
                holding = false;
                return false;
            case MotionEvent.ACTION_MOVE: {
                int index = event.findPointerIndex(dragFinger);
                if (index < 0 || dragged && !holding) return dragged;
                float x = event.getX(index);
                float y = event.getY(index);
                if (!dragged) {
                    if (Math.hypot(x - downX, y - downY) <= touchSlop) return false;
                    takeDrag(event, x, y);
                }
                stretch(x - downX, y - downY);
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (holding && event.getPointerId(event.getActionIndex()) == dragFinger) letGo();
                return dragged;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (holding) letGo();
                return dragged;
            default:
                return dragged;
        }
    }

    private void takeDrag(MotionEvent event, float x, float y) {
        dragged = true;
        holding = true;
        helpPressed = false;
        // From where the land is: the pull counts from a point that gives exactly that, its give undone.
        settlePull();
        springing = false;
        double most = ui.dp(STRETCH_DP);
        double undone = most / (most - Math.min(Math.hypot(pullX, pullY), most * 0.99));
        downX = x - (float) (pullX * undone);
        downY = y - (float) (pullY * undone);
        // The press the view began is over, and the page round the map no longer takes this finger for a scroll.
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.onTouchEvent(cancel);
        cancel.recycle();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
    }

    /** The land pulled {@code rawX}, {@code rawY} pixels by the finger: all of it at first, the farther the less. */
    private void stretch(float rawX, float rawY) {
        double most = ui.dp(STRETCH_DP);
        double give = most / (Math.hypot(rawX, rawY) + most);
        pullX = (float) (rawX * give);
        pullY = (float) (rawY * give);
        invalidate();
    }

    /** Let go: it springs back into place from where it is, at once with Android's animations off. */
    private void letGo() {
        holding = false;
        springing = true;
        springStart = SystemClock.uptimeMillis();
        springX = pullX;
        springY = pullY;
        invalidate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (drag(event)) return true;
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
        // The land runs to every edge (0.6.21): a square drawn anywhere but under the ⓘ (taken above) can be tapped.
        if (event.getAction() == MotionEvent.ACTION_UP && !cells.isEmpty()
                && event.getX() >= 0 && event.getX() < getWidth() && event.getY() >= 0) {
            project(getWidth(), getHeight());
            AreaMap.Cell nearest = null;
            double nearestDistance = ui.dp(28);
            for (AreaMap.Cell cell : cells) {
                cellBounds(cell, rect);
                if (rect.right <= 0 || rect.left >= getWidth() || rect.bottom <= 0
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
