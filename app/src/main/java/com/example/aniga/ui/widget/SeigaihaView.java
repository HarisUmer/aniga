package com.example.aniga.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.aniga.R;
import com.example.aniga.ui.Ui;

/**
 * 青海波 seigaiha — the "blue ocean waves" pattern: overlapping fans of concentric arcs in staggered rows.
 * Drawn once into a small repeating tile and painted with a BitmapShader; the shader matrix drifts slowly
 * sideways so the sea seems to move. A vertical alpha fade keeps it behind text.
 */
public class SeigaihaView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final float d;
    private int lineColor;
    private float radius;
    private float drift;
    private ValueAnimator animator;
    private Bitmap tile;

    public SeigaihaView(Context context) {
        this(context, null);
    }

    public SeigaihaView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        radius = 22 * d;
        lineColor = Ui.withAlpha(Ui.color(context, R.color.ai), 0.55f);
    }

    public void setLineColor(int c) {
        lineColor = c;
        tile = null;
        invalidate();
    }

    private void buildTile() {
        int w = Math.round(radius * 2);
        int h = Math.round(radius);
        tile = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(tile);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f * d);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Ui.color(getContext(), R.color.ink_900));
        // Rows every R/2, alternating x offset 0 / R; painted top to bottom so each row overlaps the one above,
        // which is what makes the fans. The pattern repeats every R vertically and 2R horizontally.
        float r = radius;
        float[][] rows = {{-r / 2, r}, {0, 0}, {0, 2 * r}, {r / 2, r}, {r, 0}, {r, 2 * r}, {3 * r / 2, r}};
        for (float[] o : rows) {
            float cy = o[0];
            float cx = o[1];
            c.drawCircle(cx, cy, r, bg);
            p.setColor(lineColor);
            for (int k = 1; k <= 4; k++) {
                c.drawCircle(cx, cy, r * (k / 4f) - p.getStrokeWidth(), p);
            }
        }
        fill.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible && ValueAnimator.areAnimatorsEnabled()) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(16000);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.setInterpolator(new LinearInterpolator());
                animator.addUpdateListener(a -> {
                    drift = (float) a.getAnimatedValue();
                    invalidate();
                });
            }
            animator.start();
        } else if (animator != null) {
            animator.cancel();
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        if (tile == null) buildTile();
        matrix.setTranslate(drift * radius * 2, (float) Math.sin(drift * Math.PI * 2) * 2 * d);
        fill.getShader().setLocalMatrix(matrix);
        c.drawRect(0, 0, getWidth(), getHeight(), fill);
    }
}
