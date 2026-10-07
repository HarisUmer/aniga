package com.example.aniga.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.aniga.R;
import com.example.aniga.ui.Ui;

/**
 * Empty-state / end-of-chapter animation: a striped ball rolls across two manga panel floors,
 * hops the gutter between them, squashes on landing and kicks up dust. Rotation is derived
 * from distance travelled (angle = x / r) so it never looks like it is sliding.
 */
public class RollingBallView extends View {

    private static final long LOOP_MS = 3200;

    private final Paint groundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ballPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stripePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dustPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF oval = new RectF();
    private final float density;
    private float t;
    private ValueAnimator animator;

    public RollingBallView(Context context) {
        this(context, null);
    }

    public RollingBallView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        groundPaint.setStyle(Paint.Style.STROKE);
        groundPaint.setStrokeCap(Paint.Cap.ROUND);
        groundPaint.setStrokeWidth(2f * density);
        groundPaint.setColor(Ui.color(context, R.color.paper_500));
        ballPaint.setColor(Ui.color(context, R.color.hanko));
        stripePaint.setColor(Ui.color(context, R.color.paper_100));
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(2f * density);
        edgePaint.setColor(Ui.color(context, R.color.ink_950));
        shinePaint.setStyle(Paint.Style.STROKE);
        shinePaint.setStrokeCap(Paint.Cap.ROUND);
        shinePaint.setStrokeWidth(2f * density);
        shinePaint.setColor(Ui.withAlpha(Ui.color(context, R.color.paper_100), 0.7f));
        shadowPaint.setColor(Ui.color(context, R.color.ink_950));
        dustPaint.setColor(Ui.color(context, R.color.paper_300));
    }

    public void setBallColor(int color) {
        ballPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) return;
        float r = Math.min(h * 0.16f, w * 0.07f);
        float ground = h * 0.80f;
        float gapStart = w * 0.44f;
        float gapEnd = w * 0.56f;
        float jumpStart = w * 0.36f;
        float landA = w * 0.64f;
        float landB = w * 0.76f;

        canvas.drawLine(w * 0.04f, ground, gapStart, ground, groundPaint);
        canvas.drawLine(gapEnd, ground, w * 0.96f, ground, groundPaint);
        // Panel corner ticks so the floor reads as two panels separated by a gutter.
        canvas.drawLine(gapStart, ground, gapStart, ground + 8 * density, groundPaint);
        canvas.drawLine(gapEnd, ground, gapEnd, ground + 8 * density, groundPaint);

        float x = -r + (w + 2 * r) * t;
        float lift = 0f;
        float bigHop = h * 0.42f;
        float crouch = 0f;
        if (x > jumpStart - w * 0.08f && x <= jumpStart) {
            crouch = 1f - (jumpStart - x) / (w * 0.08f);
        }
        if (x > jumpStart && x < landA) {
            float u = (x - jumpStart) / (landA - jumpStart);
            lift = (float) (bigHop * Math.sin(Math.PI * u));
        } else if (x >= landA && x < landB) {
            float u = (x - landA) / (landB - landA);
            lift = (float) (bigHop * 0.22f * Math.sin(Math.PI * u));
        }

        float squash = Math.max(impact(x, landA, w), Math.max(impact(x, landB, w) * 0.6f, crouch * 0.55f));
        float sx = 1f + 0.20f * squash;
        float sy = 1f - 0.24f * squash;

        float heightRatio = lift / bigHop;
        float shadowW = r * (1.1f - 0.55f * heightRatio);
        shadowPaint.setAlpha((int) (110 * (1f - 0.7f * heightRatio)));
        if (x < gapStart - r * 0.3f || x > gapEnd + r * 0.3f || lift > 0) {
            oval.set(x - shadowW, ground - 2.5f * density, x + shadowW, ground + 2.5f * density);
            canvas.drawOval(oval, shadowPaint);
        }

        drawDust(canvas, x, landA, ground, w, r);

        canvas.save();
        canvas.translate(x, ground - lift);
        canvas.scale(sx, sy);
        canvas.translate(0, -r);

        clip.reset();
        clip.addCircle(0, 0, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawCircle(0, 0, r, ballPaint);
        canvas.rotate((float) Math.toDegrees(x / r));
        canvas.drawRect(-r * 1.2f, -r * 0.22f, r * 1.2f, r * 0.22f, stripePaint);
        canvas.drawLine(-r * 1.2f, -r * 0.22f, r * 1.2f, -r * 0.22f, edgePaint);
        canvas.drawLine(-r * 1.2f, r * 0.22f, r * 1.2f, r * 0.22f, edgePaint);
        canvas.restore();
        canvas.drawCircle(0, 0, r, edgePaint);
        oval.set(-r * 0.62f, -r * 0.62f, r * 0.62f, r * 0.62f);
        canvas.drawArc(oval, 200, 50, false, shinePaint);
        canvas.restore();
    }

    private float impact(float x, float landing, float w) {
        float window = w * 0.03f;
        float d = x - landing;
        if (d < -window * 0.4f || d > window) return 0f;
        return 1f - Math.abs(d) / window;
    }

    private void drawDust(Canvas canvas, float x, float landing, float ground, float w, float r) {
        float since = (x - landing) / (w * 0.14f);
        if (since < 0 || since > 1) return;
        dustPaint.setAlpha((int) (170 * (1 - since)));
        for (int i = 0; i < 3; i++) {
            float spread = r * (0.6f + 1.4f * since) * (1 + i * 0.45f);
            float rise = r * 0.5f * since * (1 + i * 0.3f);
            float size = density * (2.4f - i * 0.5f) * (1 - since * 0.5f);
            canvas.drawCircle(landing - spread, ground - rise, size, dustPaint);
            canvas.drawCircle(landing + spread * 0.7f, ground - rise * 0.8f, size, dustPaint);
        }
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
            t = (float) a.getAnimatedValue();
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
