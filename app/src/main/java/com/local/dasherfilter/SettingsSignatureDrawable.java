package com.local.dasherfilter;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Keeps the approved lettering intact while adding space before its passage. */
final class SettingsSignatureDrawable extends Drawable {
    // The bundled 1412 x 1114 artwork has a clear seam between the headline and passage here.
    private static final int PASSAGE_TOP = 898;
    // At the 121 dp signature width, this adds eight dp without shrinking either text block.
    private static final int PASSAGE_GAP = 94;
    private final Drawable artwork;

    SettingsSignatureDrawable(Resources resources) {
        artwork = resources.getDrawable(R.drawable.jesus_loves_you_emblem, null).mutate();
        artwork.setBounds(0, 0, artwork.getIntrinsicWidth(), artwork.getIntrinsicHeight());
    }

    @Override public int getIntrinsicWidth() { return artwork.getIntrinsicWidth(); }
    @Override public int getIntrinsicHeight() { return artwork.getIntrinsicHeight() + PASSAGE_GAP; }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int whole = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale((float) bounds.width() / getIntrinsicWidth(),
                (float) bounds.height() / getIntrinsicHeight());
        int headline = canvas.save();
        canvas.clipRect(0, 0, getIntrinsicWidth(), PASSAGE_TOP);
        artwork.draw(canvas);
        canvas.restoreToCount(headline);
        canvas.translate(0, PASSAGE_GAP);
        canvas.clipRect(0, PASSAGE_TOP, getIntrinsicWidth(), artwork.getIntrinsicHeight());
        artwork.draw(canvas);
        canvas.restoreToCount(whole);
    }

    @Override public void setAlpha(int alpha) { artwork.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter colorFilter) {
        artwork.setColorFilter(colorFilter);
        invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
