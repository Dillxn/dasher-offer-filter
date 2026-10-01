package com.local.dasherfilter;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * A column whose weighted parts share the height it is given, as a vertical LinearLayout's do, but never below the
 * least each part reads well at (what it answers when asked with no limit) while another part has room above its own
 * least to give. Asked with no limit itself (the page working out whether it fits one screen), it answers each part at
 * its least, with no share by weight, so a page that cannot fit one screen scrolls by the difference rather than
 * squeezing one part (the skyline, the map) below its least while another keeps more than its own.
 */
class LeastColumn extends LinearLayout {
    /** Each part's weight and height, set aside while it is asked its least. */
    private float[] weights = new float[0];
    private int[] heights = new int[0];

    LeastColumn(Context context) {
        super(context);
        setOrientation(VERTICAL);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int mode = MeasureSpec.getMode(heightSpec);
        boolean column = getOrientation() == VERTICAL;
        // Asked with no limit: each part at its least, the weights set aside and a part with no height of its own
        // asked what it needs.
        boolean unlimited = column && mode == MeasureSpec.UNSPECIFIED;
        int count = getChildCount();
        if (unlimited) {
            roomFor(count);
            for (int i = 0; i < count; i++) {
                LayoutParams params = (LayoutParams) getChildAt(i).getLayoutParams();
                weights[i] = params.weight;
                heights[i] = params.height;
                if (params.weight > 0 && params.height == 0) params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                params.weight = 0;
            }
        }
        try {
            super.onMeasure(widthSpec, heightSpec);
        } finally {
            if (unlimited) {
                for (int i = 0; i < count; i++) {
                    LayoutParams params = (LayoutParams) getChildAt(i).getLayoutParams();
                    params.weight = weights[i];
                    params.height = heights[i];
                }
            }
        }
        if (column && mode == MeasureSpec.EXACTLY) keepLeasts(widthSpec);
    }

    /** Room to set aside {@code count} parts' weights and heights. */
    private void roomFor(int count) {
        if (weights.length >= count) return;
        weights = new float[count];
        heights = new int[count];
    }

    /**
     * After the share by weight: a weighted part given less than its least gets the difference from the weighted parts
     * given more than theirs, in proportion to what each has to spare, as far as that goes. Nothing changes where every
     * part already has its least.
     */
    private void keepLeasts(int widthSpec) {
        int count = getChildCount();
        int[] given = new int[count];
        int[] least = new int[count];
        boolean[] weighted = new boolean[count];
        long wanting = 0;
        long spare = 0;
        int any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            if (child.getVisibility() == GONE || params.weight <= 0) continue;
            weighted[i] = true;
            given[i] = child.getMeasuredHeight();
            child.measure(childWidth(widthSpec, params), any);
            least[i] = child.getMeasuredHeight();
            wanting += Math.max(0, least[i] - given[i]);
            spare += Math.max(0, given[i] - least[i]);
        }
        int[] next = given.clone();
        long moved = Math.min(wanting, spare);
        if (moved > 0) {
            long taken = 0;
            for (int i = 0; i < count; i++) {
                if (!weighted[i] || given[i] <= least[i]) continue;
                int take = (int) ((given[i] - least[i]) * moved / spare);
                next[i] -= take;
                taken += take;
            }
            long left = taken;
            int first = -1;
            for (int i = 0; i < count; i++) {
                if (!weighted[i] || least[i] <= given[i]) continue;
                if (first < 0) first = i;
                int add = (int) ((least[i] - given[i]) * taken / wanting);
                next[i] += add;
                left -= add;
            }
            if (first >= 0) next[first] += (int) left;
        }
        for (int i = 0; i < count; i++) {
            if (!weighted[i]) continue;
            View child = getChildAt(i);
            child.measure(childWidth(widthSpec, (LayoutParams) child.getLayoutParams()),
                    MeasureSpec.makeMeasureSpec(Math.max(0, next[i]), MeasureSpec.EXACTLY));
        }
    }

    private int childWidth(int widthSpec, LayoutParams params) {
        return getChildMeasureSpec(widthSpec, getPaddingLeft() + getPaddingRight() + params.leftMargin
                + params.rightMargin, params.width);
    }
}
