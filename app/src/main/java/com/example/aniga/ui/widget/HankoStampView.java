package com.example.aniga.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.aniga.R;
import com.example.aniga.ui.Ui;

import java.util.Random;

/**
 * The Aniga seal. play() drops it from above, presses it into the page (squash + overshoot),
 * then sends ink rings and splatter outward. Left unplayed it renders settled, so the same view
 * works as a static logo mark.
 */
public class HankoStampView extends View {

    private static final long DURATION_MS = 1100;
    private static final float IMPACT = 0.42f;
    private static final String[] GLYPHS = {"ア", "ニ", "ガ"};

    private final Paint sealPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint carvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint splatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] grain = new float[120];
    private final float[] splat = new float[30];
    private float progress = 1f;
    private ValueAnimator animator;

    public HankoStampView(Context context) {
        this(context, null);
    }

    public HankoStampView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        int hanko = Ui.color(context, R.color.hanko);
        int ink = Ui.color(context, R.color.ink_950);
        sealPaint.setColor(hanko);
        carvePaint.setColor(ink);
        carvePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        carvePaint.setTextAlign(Paint.Align.CENTER);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(ink);
        grainPaint.setColor(Ui.withAlpha(ink, 0.45f));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(hanko);
        splatPaint.setColor(hanko);

        // Fixed seed: the seal's worn texture must be identical on every launch.
        Random rnd = new Random(7);
        for (int i = 0; i < grain.length; i += 3) {
            grain[i] = rnd.nextFloat();
            grain[i + 1] = rnd.nextFloat();
            grain[i + 2] = 0.4f + rnd.nextFloat();
        }
        for (int i = 0; i < splat.length; i += 3) {
            splat[i] = (float) (rnd.nextFloat() * Math.PI * 2);
            splat[i + 1] = 0.55f + rnd.nextFloat() * 0.5f;
            splat[i + 2] = 0.5f + rnd.nextFloat();
        }
    }

    public void play(@Nullable Runnable onImpactSettled) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        if (onImpactSettled != null) {
            postDelayed(onImpactSettled, (long) (DURATION_MS * 0.62f));
        }
        progress = 0f;
        animator.start();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float sealH = Math.min(h, w * 1.45f) * 0.9f;
        float sealW = sealH * 0.62f;
        float cx = w / 2f;
        float cy = h / 2f;
        float density = getResources().getDisplayMetrics().density;

        float scale;
        float rotation;
        float alpha;
        if (progress < IMPACT) {
            float u = progress / IMPACT;
            float fall = u * u;
            scale = 2.6f - 1.6f * fall;
            rotation = -16f + 10f * fall;
            alpha = Math.min(1f, u * 2.2f);
        } else {
            float u = (progress - IMPACT) / (1f - IMPACT);
            // Press-in squash then a damped settle.
            scale = 1f - 0.07f * (float) (Math.exp(-6 * u) * Math.cos(10 * u));
            rotation = -6f;
            alpha = 1f;
        }

        if (progress > IMPACT && progress < 1f) {
            float u = (progress - IMPACT) / (1f - IMPACT);
            for (int ring = 0; ring < 2; ring++) {
                float ru = Math.max(0f, u - ring * 0.18f);
                if (ru <= 0) continue;
                float grow = 1f + ru * 0.9f;
                ringPaint.setAlpha((int) (160 * (1 - ru)));
                ringPaint.setStrokeWidth(density * (3f - 2f * ru));
                rect.set(cx - sealW / 2 * grow, cy - sealH / 2 * grow, cx + sealW / 2 * grow, cy + sealH / 2 * grow);
                canvas.drawRoundRect(rect, sealW * 0.16f * grow, sealW * 0.16f * grow, ringPaint);
            }
            for (int i = 0; i < splat.length; i += 3) {
                float dist = sealH * (0.5f + splat[i + 1] * 0.45f * (float) Math.sqrt(u));
                float sx = cx + (float) Math.cos(splat[i]) * dist * 0.75f;
                float sy = cy + (float) Math.sin(splat[i]) * dist;
                splatPaint.setAlpha((int) (230 * (1 - u)));
                canvas.drawCircle(sx, sy, density * 2.2f * splat[i + 2] * (1 - u * 0.6f), splatPaint);
            }
        }

        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(rotation);
        canvas.scale(scale, scale);
        int a = (int) (255 * alpha);
        sealPaint.setAlpha(a);
        rect.set(-sealW / 2, -sealH / 2, sealW / 2, sealH / 2);
        float corner = sealW * 0.16f;
        canvas.drawRoundRect(rect, corner, corner, sealPaint);

        float inset = sealW * 0.09f;
        borderPaint.setStrokeWidth(sealW * 0.035f);
        borderPaint.setAlpha(a);
        rect.inset(inset, inset);
        canvas.drawRoundRect(rect, corner * 0.6f, corner * 0.6f, borderPaint);

        carvePaint.setAlpha(a);
        carvePaint.setTextSize(sealH * 0.235f);
        float step = (sealH - inset * 2) / 3f;
        float top = -sealH / 2 + inset;
        for (int i = 0; i < GLYPHS.length; i++) {
            float baseline = top + step * i + step * 0.5f - (carvePaint.descent() + carvePaint.ascent()) / 2f;
            canvas.drawText(GLYPHS[i], 0, baseline, carvePaint);
        }

        grainPaint.setAlpha((int) (110 * alpha));
        for (int i = 0; i < grain.length; i += 3) {
            float gx = -sealW / 2 + grain[i] * sealW;
            float gy = -sealH / 2 + grain[i + 1] * sealH;
            canvas.drawCircle(gx, gy, density * 0.9f * grain[i + 2], grainPaint);
        }
        canvas.restore();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
