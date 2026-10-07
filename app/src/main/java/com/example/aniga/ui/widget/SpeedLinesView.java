package com.example.aniga.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Random;

/**
 * 集中線 (shuuchuusen) — manga focus lines converging on a point. burst() shoots the lines in
 * from the edges; afterwards they breathe very slightly so the header feels alive, not looping.
 */
public class SpeedLinesView extends View {

    private static final int LINES = 96;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] angle = new float[LINES];
    private final float[] inner = new float[LINES];
    private final float[] width = new float[LINES];
    private float focusY = 0.5f;
    private float progress = 1f;
    private ValueAnimator animator;

    public SpeedLinesView(Context context) {
        this(context, null);
    }

    public SpeedLinesView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(0x33FFFFFF);
        Random rnd = new Random(42);
        for (int i = 0; i < LINES; i++) {
            angle[i] = (float) (2 * Math.PI * (i + rnd.nextFloat() * 0.8f) / LINES);
            inner[i] = 0.30f + rnd.nextFloat() * 0.22f;
            width[i] = 0.6f + rnd.nextFloat() * rnd.nextFloat() * 4.5f;
        }
    }

    public void setLineColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    public void setFocusY(float fraction) {
        focusY = fraction;
        invalidate();
    }

    public void burst() {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(720);
        animator.setInterpolator(new DecelerateInterpolator(2.2f));
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h * focusY;
        float outer = (float) Math.hypot(w, h);
        float diag = Math.min(w, h);
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < LINES; i++) {
            double a = angle[i];
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            float target = inner[i] * diag;
            float tip = outer - (outer - target) * progress;
            float half = width[i] * density;
            // Perpendicular offset for the wide outer end; the line tapers to a point at the tip.
            float px = -sin * half;
            float py = cos * half;
            path.reset();
            path.moveTo(cx + cos * outer + px, cy + sin * outer + py);
            path.lineTo(cx + cos * outer - px, cy + sin * outer - py);
            path.lineTo(cx + cos * tip, cy + sin * tip);
            path.close();
            canvas.drawPath(path, paint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
