package com.example.aniga.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.aniga.R;
import com.example.aniga.ui.Ui;

/**
 * Reading progress as a flight: a dashed route, a solid "flown" line behind a tiny paper plane.
 * The plane pitches up while you scroll forward and levels out when you stop.
 */
public class PlaneProgressView extends View {

    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flownPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint planePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path plane = new Path();
    private final float density;
    private float shown;
    private float pitch;
    private ValueAnimator animator;

    public PlaneProgressView(Context context) {
        this(context, null);
    }

    public PlaneProgressView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeWidth(1.5f * density);
        routePaint.setPathEffect(new DashPathEffect(new float[]{4 * density, 5 * density}, 0));
        flownPaint.setStyle(Paint.Style.STROKE);
        flownPaint.setStrokeCap(Paint.Cap.ROUND);
        flownPaint.setStrokeWidth(2.2f * density);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeJoin(Paint.Join.ROUND);
        edgePaint.setStrokeWidth(1.1f * density);
        edgePaint.setColor(Ui.color(context, R.color.ink_950));
        setColors(Ui.color(context, R.color.paper_500), Ui.color(context, R.color.hanko),
                Ui.color(context, R.color.paper_100));
        float s = 7 * density;
        plane.moveTo(s, 0);
        plane.lineTo(-s, -s * 0.7f);
        plane.lineTo(-s * 0.45f, 0);
        plane.lineTo(-s, s * 0.55f);
        plane.close();
    }

    public void setColors(int route, int flown, int planeFill) {
        routePaint.setColor(Ui.withAlpha(route, 0.55f));
        flownPaint.setColor(flown);
        planePaint.setColor(planeFill);
        invalidate();
    }

    public void setProgress(float target) {
        float clamped = Math.max(0f, Math.min(1f, target));
        if (animator != null) animator.cancel();
        float from = shown;
        pitch = Math.max(-1f, Math.min(1f, (clamped - from) * 40f));
        animator = ValueAnimator.ofFloat(from, clamped);
        animator.setDuration(260);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            pitch *= 0.9f;
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float cy = getHeight() / 2f;
        float pad = 12 * density;
        float span = w - pad * 2;
        float x = pad + span * shown;
        canvas.drawLine(x, cy, w - pad, cy, routePaint);
        canvas.drawLine(pad, cy, x, cy, flownPaint);
        canvas.save();
        canvas.translate(x, cy);
        canvas.rotate(-18f * pitch);
        canvas.drawPath(plane, planePaint);
        canvas.drawPath(plane, edgePaint);
        canvas.restore();
    }
}
