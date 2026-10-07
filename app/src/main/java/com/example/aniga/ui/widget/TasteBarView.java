package com.example.aniga.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.aniga.R;
import com.example.aniga.ui.Ui;

/**
 * Diverging bar around a centre axis: positive genre weight grows right in jade, negative grows
 * left in hanko red. This is the "why did I rate this 10 and that 5" chart.
 */
public class TasteBarView extends View {

    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint like = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dislike = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float value;
    private float shown;
    private float marker = Float.NaN;
    private ValueAnimator animator;

    public TasteBarView(Context context) {
        this(context, null);
    }

    public TasteBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        axis.setColor(Ui.color(context, R.color.paper_500));
        axis.setStrokeWidth(Ui.dp(context, 1));
        like.setColor(Ui.color(context, R.color.taste_like));
        dislike.setColor(Ui.color(context, R.color.taste_dislike));
        track.setColor(Ui.color(context, R.color.ink_700));
        markerPaint.setColor(Ui.color(context, R.color.paper_100));
    }

    /** value in -1..1; animates in after delayMs so a list of bars grows like a cascade. */
    public void setValue(float v, long delayMs) {
        shown = 0f;
        animateTo(v, delayMs);
    }

    /** Glides from what is drawn now to v, so a re-rated title visibly pushes the bar. */
    public void animateTo(float v, long delayMs) {
        value = Math.max(-1f, Math.min(1f, v));
        if (animator != null) animator.cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            shown = value;
            invalidate();
            return;
        }
        float from = shown;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setStartDelay(delayMs);
        animator.setDuration(520);
        animator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        animator.addUpdateListener(an -> {
            shown = from + (value - from) * (float) an.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    /** A thin tick at v (-1..1), e.g. where the bar would be from ratings alone; NaN hides it. */
    public void setMarker(float v) {
        marker = v;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) animator.cancel();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float mid = w / 2f;
        float barH = h * 0.5f;
        float top = (h - barH) / 2f;
        canvas.drawRect(0, top, w, top + barH, track);
        float len = (w / 2f) * Math.abs(shown);
        if (shown >= 0) canvas.drawRect(mid, top, mid + len, top + barH, like);
        else canvas.drawRect(mid - len, top, mid, top + barH, dislike);
        canvas.drawLine(mid, 0, mid, h, axis);
        if (!Float.isNaN(marker)) {
            float mx = mid + (w / 2f) * Math.max(-1f, Math.min(1f, marker));
            canvas.drawRect(mx - axis.getStrokeWidth(), 0, mx + axis.getStrokeWidth(), h, markerPaint);
        }
    }
}
