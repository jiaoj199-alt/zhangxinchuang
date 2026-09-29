package dev.linjian.peek;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;

import java.io.InputStream;

/** Stable bitmap clipping for gallery/default avatars across Android vendors. */
public class SoftAvatarView extends View {
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds = new RectF();
    private final RectF imageBounds = new RectF();
    private final Path clipPath = new Path();
    private Bitmap bitmap;
    private Drawable fallback;
    private Bitmap fallbackBitmap;
    private float cornerDp = 24f;
    private float insetDp = 0f;
    private float fallbackPaddingDp = 13f;
    private boolean circle;
    private boolean showDot;
    private int fillColor = Color.WHITE;
    private int strokeColor = 0xFFF0CBD8;
    private int dotColor = 0xFF78AE90;

    public SoftAvatarView(Context context) {
        super(context);
        strokePaint.setStyle(Paint.Style.STROKE);
        dotPaint.setStyle(Paint.Style.FILL);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setCircle(boolean value) { circle = value; invalidate(); }
    public void setCornerDp(float value) { cornerDp = value; invalidate(); }
    public void setInsetDp(float value) { insetDp = value; invalidate(); }
    public void setFallbackPaddingDp(float value) { fallbackPaddingDp = value; invalidate(); }
    public void setShowDot(boolean value) { showDot = value; invalidate(); }
    public void setColors(int fill, int stroke, int dot) { fillColor = fill; strokeColor = stroke; dotColor = dot; invalidate(); }
    public void setFallback(Drawable value) { fallback = value; fallbackBitmap = null; invalidate(); }
    public void setFallbackBitmap(Bitmap value) { fallbackBitmap = value; fallback = null; invalidate(); }

    public boolean setImageUri(Uri uri) {
        recycleBitmap();
        if (uri == null) { invalidate(); return false; }
        try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in);
        } catch (Exception ignored) { bitmap = null; }
        invalidate();
        return bitmap != null;
    }

    public void clearImage() { recycleBitmap(); invalidate(); }

    private void recycleBitmap() {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = null;
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float inset = insetDp * density;
        bounds.set(0, 0, getWidth(), getHeight());
        imageBounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float radius = circle ? Math.min(imageBounds.width(), imageBounds.height()) / 2f : cornerDp * density;

        fillPaint.setColor(fillColor);
        canvas.drawRoundRect(bounds, circle ? Math.min(getWidth(), getHeight()) / 2f : radius + inset, circle ? Math.min(getWidth(), getHeight()) / 2f : radius + inset, fillPaint);

        clipPath.reset();
        clipPath.addRoundRect(imageBounds, radius, radius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clipPath);
        if (bitmap != null && !bitmap.isRecycled()) drawCenterCrop(canvas, bitmap, imageBounds);
        else if (fallbackBitmap != null && !fallbackBitmap.isRecycled()) {
            float fallbackPadding = fallbackPaddingDp * density;
            RectF fallbackBounds = new RectF(imageBounds.left + fallbackPadding, imageBounds.top + fallbackPadding,
                    imageBounds.right - fallbackPadding, imageBounds.bottom - fallbackPadding);
            canvas.drawBitmap(fallbackBitmap, null, fallbackBounds, fallbackPaint);
        } else if (fallback != null) {
            float fallbackPadding = fallbackPaddingDp * density;
            fallback.setBounds((int) (imageBounds.left + fallbackPadding), (int) (imageBounds.top + fallbackPadding),
                    (int) (imageBounds.right - fallbackPadding), (int) (imageBounds.bottom - fallbackPadding));
            fallback.draw(canvas);
        }
        canvas.restoreToCount(save);

        strokePaint.setStrokeWidth(Math.max(1f, density));
        strokePaint.setColor(strokeColor);
        float half = strokePaint.getStrokeWidth() / 2f;
        RectF strokeBounds = new RectF(imageBounds.left + half, imageBounds.top + half, imageBounds.right - half, imageBounds.bottom - half);
        canvas.drawRoundRect(strokeBounds, radius, radius, strokePaint);

        if (showDot) {
            float dotRadius = 6f * density;
            float cx = imageBounds.right - dotRadius * .55f;
            float cy = imageBounds.bottom - dotRadius * .55f;
            dotPaint.setColor(fillColor);
            canvas.drawCircle(cx, cy, dotRadius + 2f * density, dotPaint);
            dotPaint.setColor(dotColor);
            canvas.drawCircle(cx, cy, dotRadius, dotPaint);
        }
    }

    private void drawCenterCrop(Canvas canvas, Bitmap source, RectF target) {
        float scale = Math.max(target.width() / source.getWidth(), target.height() / source.getHeight());
        float width = source.getWidth() * scale;
        float height = source.getHeight() * scale;
        float left = target.centerX() - width / 2f;
        float top = target.centerY() - height * .48f;
        canvas.drawBitmap(source, null, new RectF(left, top, left + width, top + height), bitmapPaint);
    }
}
