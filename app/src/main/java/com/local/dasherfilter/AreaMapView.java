package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The offer areas as a treasure map drawn without map tiles, north up: each square where offers came in is gilded
 * deeper the better its pay per mile, with coins on the best three and a dotted trail from you to the best. Squares
 * with too few offers to rank are dashed outlines. A dot marks where the phone is now, when known; a scale bar and a
 * compass rose give distance and direction. Tap a square to select it.
 */
@SuppressLint("ViewConstructor")
final class AreaMapView extends View {
    interface OnSelect {
        void selected(AreaMap.Cell cell);
    }

    private static final double MILES_PER_DEGREE_LATITUDE = 69.05;
    private static final int[] MEDALS = {0xFFE3B341, 0xFFB9BDC3, 0xFFCD8B4E};
    private static final int GOLD = 0xFFD39B2A;

    private final Ui ui;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private List<AreaMap.Cell> cells = Collections.emptyList();
    private List<AreaMap.Cell> ranked = Collections.emptyList();
    private double[] here;
    private AreaMap.Cell selected;
    private OnSelect onSelect;

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
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setClickable(true);
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
        if (cells.isEmpty()) return "No offer areas yet.";
        List<String> best = new ArrayList<>();
        for (int i = 0; i < Math.min(3, ranked.size()); i++) {
            AreaMap.Cell cell = ranked.get(i);
            String where = AreaMap.from(here, cell);
            best.add((i + 1) + (where.isEmpty() ? "" : ", " + where) + ", " + cell.perMile() + " over "
                    + cell.mileOffers + " offers");
        }
        int unranked = cells.size() - ranked.size();
        return "Best paying areas by pay per mile: " + (best.isEmpty() ? "none ranked yet" : String.join("; ", best))
                + "." + (unranked > 0 ? " " + unranked + (unranked == 1 ? " area has" : " areas have")
                + " too few offers to rank." : "");
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, resolveSize(Math.min(ui.dp(320), Math.max(ui.dp(220), width)), heightSpec));
    }

    private void project(float width, float height) {
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
        drawParchment(canvas, width, height);
        if (cells.isEmpty()) {
            text.setColor(brown());
            text.setTextSize(Math.min(ui.sp(14), ui.dp(20)));
            text.setFakeBoldText(false);
            canvas.drawText("Offers you see will be pinned here", width / 2, height / 2, text);
            text.setFakeBoldText(true);
            drawNorth(canvas, width);
            return;
        }
        project(width, height);
        canvas.save();
        path.reset();
        path.addRoundRect(rect, ui.dp(12), ui.dp(12), Path.Direction.CW);
        canvas.clipPath(path);
        drawGrid(canvas, width, height);

        double best = ranked.isEmpty() ? 0 : ranked.get(0).centsPerMile();
        double worst = ranked.isEmpty() ? 0 : ranked.get(ranked.size() - 1).centsPerMile();
        float inset = ui.dp(2);
        for (AreaMap.Cell cell : cells) {
            rect.set(x(cell.col * AreaMap.CELL_DEGREES) + inset, y((cell.row + 1) * AreaMap.CELL_DEGREES) + inset,
                    x((cell.col + 1) * AreaMap.CELL_DEGREES) - inset, y(cell.row * AreaMap.CELL_DEGREES) - inset);
            float corner = Math.min(ui.dp(8), rect.width() / 4);
            if (cell.ranked()) {
                double share = best > worst ? (cell.centsPerMile() - worst) / (best - worst) : 1;
                int alpha = (int) Math.round(0x40 + share * (0xE6 - 0x40));
                fill.setColor((GOLD & 0x00FFFFFF) | (alpha << 24));
                canvas.drawRoundRect(rect, corner, corner, fill);
            } else {
                line.setColor(brown());
                line.setStrokeWidth(Math.max(1, ui.dp(1.5f)));
                line.setPathEffect(new DashPathEffect(new float[] {ui.dp(4), ui.dp(3)}, 0));
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
        if (here != null) drawHere(canvas);
        canvas.restore();
        drawNorth(canvas, width);
        drawScale(canvas, height);
    }

    private int brown() {
        return ui.dark ? 0xFFC9B48C : 0xFF7A5C3A;
    }

    /** Old paper with a dashed frame. */
    private void drawParchment(Canvas canvas, float width, float height) {
        rect.set(0, 0, width, height);
        fill.setColor(ui.dark ? 0xFF2B2519 : 0xFFF4E7C9);
        canvas.drawRoundRect(rect, ui.dp(14), ui.dp(14), fill);
        line.setColor(ui.dark ? 0xFF4A3F2A : 0xFFE0CB9A);
        line.setStrokeWidth(ui.dp(3));
        canvas.drawRoundRect(rect, ui.dp(14), ui.dp(14), line);
        rect.inset(ui.dp(7), ui.dp(7));
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        line.setColor((brown() & 0x00FFFFFF) | 0x66000000);
        line.setPathEffect(new DashPathEffect(new float[] {ui.dp(6), ui.dp(4)}, 0));
        canvas.drawRoundRect(rect, ui.dp(9), ui.dp(9), line);
        line.setPathEffect(null);
        rect.set(0, 0, width, height);
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
        line.setPathEffect(new DashPathEffect(new float[] {ui.dp(2), ui.dp(6)}, 0));
        canvas.drawPath(path, line);
        line.setPathEffect(null);
    }

    /** Faint lines along the square edges, so the squares read as a grid on the paper. */
    private void drawGrid(Canvas canvas, float width, float height) {
        line.setColor((brown() & 0x00FFFFFF) | 0x2E000000);
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
        float half = (float) (AreaMap.CELL_DEGREES * scale / 2);
        float corner = Math.max(0, half - radius - ui.dp(3));
        float cx = x(cell.longitude()) + corner;
        float cy = y(cell.latitude()) - corner;
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

    /** The phone's position: a dot with a halo and "You". */
    private void drawHere(Canvas canvas) {
        float cx = x(here[1]);
        float cy = y(here[0]);
        fill.setColor((ui.accent & 0x00FFFFFF) | 0x33000000);
        canvas.drawCircle(cx, cy, ui.dp(14), fill);
        fill.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, ui.dp(7.5f), fill);
        fill.setColor(ui.accent);
        canvas.drawCircle(cx, cy, ui.dp(5.5f), fill);
        text.setColor(ui.dark ? 0xFFF3E6C8 : 0xFF3A2A18);
        text.setTextSize(Math.min(ui.sp(11), ui.dp(15)));
        canvas.drawText("You", cx, cy + ui.dp(14) - text.getFontMetrics().ascent, text);
    }

    /** A compass rose with north marked. */
    private void drawNorth(Canvas canvas, float width) {
        float cx = width - ui.dp(30);
        float cy = ui.dp(36);
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
        for (double choice : choices) if (choice * pixelsPerMile <= getWidth() / 3.0) miles = choice;
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
        if (event.getAction() == MotionEvent.ACTION_UP && !cells.isEmpty()) {
            project(getWidth(), getHeight());
            AreaMap.Cell nearest = null;
            double nearestDistance = ui.dp(28);
            for (AreaMap.Cell cell : cells) {
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
