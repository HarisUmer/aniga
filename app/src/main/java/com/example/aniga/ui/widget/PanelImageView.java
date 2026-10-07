package com.example.aniga.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

import com.example.aniga.R;
import com.example.aniga.ui.Ui;

/**
 * An image clipped to an arbitrary (usually slanted) manga panel polygon with an inked border.
 * Several of these stack full-size inside one page; each center-crops its image to its own
 * polygon's bounds and only accepts touches inside its polygon so the stack behaves like a
 * real page of panels.
 */
public class PanelImageView extends AppCompatImageView {

    private float[] polygon = {0, 0, 1, 0, 1, 1, 0, 1};
    private final Path clip = new Path();
    private final RectF bounds = new RectF();
    private final Region hit = new Region();
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();

    public PanelImageView(Context context) {
        this(context, null);
    }

    public PanelImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeJoin(Paint.Join.MITER);
        border.setStrokeWidth(Ui.dp(context, 2.5f));
        border.setColor(Ui.color(context, R.color.ink_950));
    }

    /** Polygon in page-relative coordinates, x0,y0,x1,y1,... each in 0..1. */
    public void setPolygon(float[] normalized) {
        polygon = normalized.clone();
        rebuild();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild();
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        fitMatrix();
    }

    private void rebuild() {
        float w = getWidth();
        float h = getHeight();
        clip.reset();
        for (int i = 0; i < polygon.length; i += 2) {
            float x = polygon[i] * w;
            float y = polygon[i + 1] * h;
            if (i == 0) clip.moveTo(x, y);
            else clip.lineTo(x, y);
        }
        clip.close();
        clip.computeBounds(bounds, true);
        hit.setPath(clip, new Region(0, 0, Math.max(1, (int) w), Math.max(1, (int) h)));
        fitMatrix();
        invalidate();
    }

    private void fitMatrix() {
        Drawable d = getDrawable();
        // bounds is null while the superclass constructor applies android:src.
        if (d == null || bounds == null || bounds.isEmpty()) return;
        float dw = d.getIntrinsicWidth();
        float dh = d.getIntrinsicHeight();
        matrix.reset();
        if (dw <= 0 || dh <= 0) {
            // Shape drawables have no intrinsic size; stretch them over the panel instead.
            d.setBounds(0, 0, (int) bounds.width(), (int) bounds.height());
            dw = bounds.width();
            dh = bounds.height();
        }
        float scale = Math.max(bounds.width() / dw, bounds.height() / dh);
        float dx = bounds.left + (bounds.width() - dw * scale) / 2f;
        // Bias upward: faces sit in the top third of most covers.
        float dy = bounds.top + (bounds.height() - dh * scale) * 0.3f;
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        canvas.save();
        canvas.clipPath(clip);
        super.onDraw(canvas);
        canvas.restore();
        canvas.drawPath(clip, border);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && !hit.contains((int) event.getX(), (int) event.getY())) {
            return false;
        }
        return super.dispatchTouchEvent(event);
    }
}
