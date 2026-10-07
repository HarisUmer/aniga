package com.example.aniga.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.aniga.R;
import com.example.aniga.ui.Ui;

/**
 * Loading indicator: a folded paper plane flying a figure-eight, banking with the curve and
 * leaving a fading contrail. Position and heading come from PathMeasure.getPosTan, so the nose
 * always follows the tangent of the route.
 */
public class PaperPlaneLoaderView extends View {

    private static final int PUFFS = 16;
    private static final long LOOP_MS = 2800;

    private final Path route = new Path();
    private final Path plane = new Path();
    private final Path wingShade = new Path();
    private final Path keel = new Path();
    private PathMeasure measure;
    private float routeLength;
    private final float[] pos = new float[2];
    private final float[] tan = new float[2];
    private float progress;
    private ValueAnimator animator;

    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint puffPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paperPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public PaperPlaneLoaderView(Context context) {
        this(context, null);
    }

    public PaperPlaneLoaderView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeWidth(1.2f * density);
        routePaint.setColor(Ui.withAlpha(Ui.color(context, R.color.paper_500), 0.35f));
        routePaint.setPathEffect(new DashPathEffect(new float[]{3 * density, 6 * density}, 0));
        puffPaint.setColor(Ui.color(context, R.color.paper_300));
        paperPaint.setColor(Ui.color(context, R.color.paper_100));
        shadePaint.setColor(Ui.color(context, R.color.paper_200));
        keelPaint.setColor(Ui.color(context, R.color.hanko));
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeJoin(Paint.Join.ROUND);
        edgePaint.setStrokeWidth(1.4f * density);
        edgePaint.setColor(Ui.color(context, R.color.ink_950));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx = w / 2f;
        float cy = h / 2f;
        float a = w * 0.36f;
        float b = h * 0.30f;
        route.reset();
        int samples = 160;
        for (int i = 0; i <= samples; i++) {
            double t = 2 * Math.PI * i / samples;
            // Lemniscate of Gerono: a figure-eight that reads as "circling while you wait".
            float x = (float) (cx + a * Math.sin(t));
            float y = (float) (cy + b * Math.sin(t) * Math.cos(t) * 1.6);
            if (i == 0) route.moveTo(x, y);
            else route.lineTo(x, y);
        }
        route.close();
        measure = new PathMeasure(route, true);
        routeLength = measure.getLength();

        float s = Math.min(w, h) * 0.13f;
        plane.reset();
        plane.moveTo(s, 0);
        plane.lineTo(-s * 0.85f, -s * 0.62f);
        plane.lineTo(-s * 0.38f, 0);
        plane.close();
        wingShade.reset();
        wingShade.moveTo(s, 0);
        wingShade.lineTo(-s * 0.85f, s * 0.46f);
        wingShade.lineTo(-s * 0.38f, 0);
        wingShade.close();
        keel.reset();
        keel.moveTo(s, 0);
        keel.lineTo(-s * 0.38f, 0);
        keel.lineTo(-s * 0.62f, s * 0.2f);
        keel.close();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (measure == null || routeLength == 0) return;
        canvas.drawPath(route, routePaint);

        float d = progress * routeLength;
        float spacing = routeLength * 0.016f;
        for (int k = PUFFS; k >= 1; k--) {
            float dist = mod(d - k * spacing * 1.15f, routeLength);
            measure.getPosTan(dist, pos, null);
            float life = 1f - (float) k / (PUFFS + 1);
            puffPaint.setAlpha((int) (165 * life * life));
            canvas.drawCircle(pos[0], pos[1], density * (1.1f + 3.6f * life), puffPaint);
        }

        measure.getPosTan(d, pos, tan);
        float heading = (float) Math.toDegrees(Math.atan2(tan[1], tan[0]));
        canvas.save();
        canvas.translate(pos[0], pos[1]);
        canvas.rotate(heading);
        // Bank: squash the plane vertically on the steep parts of the curve.
        float bank = 0.72f + 0.28f * Math.abs(tan[0]);
        canvas.scale(1f, bank);
        canvas.drawPath(wingShade, shadePaint);
        canvas.drawPath(wingShade, edgePaint);
        canvas.drawPath(keel, keelPaint);
        canvas.drawPath(plane, paperPaint);
        canvas.drawPath(plane, edgePaint);
        canvas.restore();
    }

    private static float mod(float v, float m) {
        float r = v % m;
        return r < 0 ? r + m : r;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (!isAttachedToWindow()) return;
        if (visibility == VISIBLE && isShown()) start();
        else stop();
    }

    private void start() {
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(LOOP_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }
}
