package com.example.aniga.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.aniga.R;
import com.example.aniga.ui.Ui;

import java.util.Random;

/**
 * Falling sakura petals. Each petal is a notched teardrop that falls at its own speed, sways on a sine
 * (x += A·sin(ωt + φ)), spins, and flips (scaleX = cos(flip)) so it reads as a thin card turning in the air.
 * A slow "gust" term pushes every petal sideways together every few seconds.
 * Runs only while visible on screen; draws a still frame when the system disables animations.
 */
public class SakuraView extends View {

    private static final int COUNT = 22;

    private final Paint petalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path petal = new Path();
    private final float[] x = new float[COUNT];
    private final float[] y = new float[COUNT];
    private final float[] size = new float[COUNT];
    private final float[] speed = new float[COUNT];
    private final float[] phase = new float[COUNT];
    private final float[] spin = new float[COUNT];
    private final float[] flip = new float[COUNT];
    private final float[] depth = new float[COUNT];
    private final Random random = new Random(7);
    private final float d;
    private long last;
    private float clock;
    private boolean running;
    private int color;

    public SakuraView(Context context) {
        this(context, null);
    }

    public SakuraView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        color = Ui.color(context, R.color.sakura);
        veinPaint.setStyle(Paint.Style.STROKE);
        veinPaint.setStrokeWidth(0.8f * d);
        // Petal centred on (0,0), tip at -y, notch at +y; unit height 1.
        petal.moveTo(0f, -0.5f);
        petal.cubicTo(0.42f, -0.35f, 0.45f, 0.25f, 0.12f, 0.5f);
        petal.lineTo(0f, 0.38f);
        petal.lineTo(-0.12f, 0.5f);
        petal.cubicTo(-0.45f, 0.25f, -0.42f, -0.35f, 0f, -0.5f);
        petal.close();
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setPetalColor(int c) {
        color = c;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        for (int i = 0; i < COUNT; i++) seed(i, true);
    }

    private void seed(int i, boolean anywhere) {
        int w = Math.max(getWidth(), 1);
        int h = Math.max(getHeight(), 1);
        depth[i] = 0.45f + random.nextFloat() * 0.55f;
        x[i] = random.nextFloat() * w;
        y[i] = anywhere ? random.nextFloat() * h : -20 * d;
        size[i] = (9 + random.nextFloat() * 9) * d * depth[i];
        speed[i] = (18 + random.nextFloat() * 26) * d * depth[i];
        phase[i] = random.nextFloat() * 6.28f;
        spin[i] = random.nextFloat() * 360f;
        flip[i] = random.nextFloat() * 6.28f;
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        running = isVisible && ValueAnimator.areAnimatorsEnabled();
        last = 0;
        if (running) postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0 : Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        clock += dt;
        float gust = (float) (Math.sin(clock * 0.45) * Math.sin(clock * 0.17)) * 34 * d;
        int h = getHeight();
        for (int i = 0; i < COUNT; i++) {
            if (running) {
                y[i] += speed[i] * dt;
                x[i] += (gust * depth[i] + (float) Math.cos(clock * 1.3 + phase[i]) * 14 * d) * dt;
                spin[i] += (40 + 50 * depth[i]) * dt;
                flip[i] += 1.8f * dt;
                if (y[i] > h + 20 * d) seed(i, false);
                if (x[i] < -30 * d) x[i] += getWidth() + 60 * d;
                if (x[i] > getWidth() + 30 * d) x[i] -= getWidth() + 60 * d;
            }
            float sway = (float) Math.sin(clock * 1.1 + phase[i]) * 10 * d;
            c.save();
            c.translate(x[i] + sway, y[i]);
            c.rotate(spin[i]);
            c.scale(size[i] * (0.35f + 0.65f * Math.abs((float) Math.cos(flip[i]))), size[i]);
            // Kept faint: the view sits behind every tab, so petals cross text.
            petalPaint.setColor(Ui.withAlpha(color, 0.18f + 0.32f * depth[i]));
            c.drawPath(petal, petalPaint);
            c.restore();
        }
        if (running) postInvalidateOnAnimation();
    }
}
