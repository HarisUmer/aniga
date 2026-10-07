package com.example.aniga.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.aniga.R;
import com.example.aniga.ui.Ui;

import java.util.function.IntConsumer;

/**
 * 1-10 score as a row of seal squares. Drag or tap to choose; releasing stamps the chosen square:
 * it drops from 1.8x with a −14° twist, lands with a damped squash, and throws an ink ring.
 * Squares up to the score fill with a warm ramp (yamabuki -> hanko) so a 9 looks hotter than a 4.
 */
public class HankoRatingView extends View {

    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;
    private final int cold;
    private final int hot;
    private final int muted;
    private final int ink;
    private int score;
    private int hover = -1;
    private float stampT = 1f;
    private IntConsumer listener;

    public HankoRatingView(Context context) {
        this(context, null);
    }

    public HankoRatingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        cold = Ui.color(context, R.color.yamabuki);
        hot = Ui.color(context, R.color.hanko);
        muted = Ui.color(context, R.color.ink_600);
        ink = Ui.color(context, R.color.ink_950);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1.2f * d);
        outline.setColor(muted);
        digit.setTextAlign(Paint.Align.CENTER);
        digit.setTypeface(Ui.display(context));
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(hot);
    }

    public void setOnRate(IntConsumer l) {
        listener = l;
    }

    public void setScore(int s) {
        score = Math.max(0, Math.min(10, s));
        invalidate();
    }

    public int score() {
        return score;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (52 * d));
    }

    private int indexAt(float x) {
        float cell = getWidth() / 10f;
        return Math.max(1, Math.min(10, (int) (x / cell) + 1));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                int i = indexAt(e.getX());
                if (i != hover) {
                    hover = i;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                score = indexAt(e.getX());
                hover = -1;
                stamp();
                performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                if (listener != null) listener.accept(score);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                hover = -1;
                invalidate();
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void stamp() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            stampT = 1f;
            invalidate();
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(520);
        a.addUpdateListener(v -> {
            stampT = (float) v.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        float cell = getWidth() / 10f;
        float size = Math.min(cell - 6 * d, 32 * d);
        float cy = getHeight() / 2f;
        int shown = hover > 0 ? hover : score;
        digit.setTextSize(size * 0.56f);
        for (int i = 1; i <= 10; i++) {
            float cx = cell * (i - 0.5f);
            float scale = 1f;
            float rot = 0f;
            boolean stamped = i == score && hover < 0 && stampT < 1f;
            if (stamped) {
                if (stampT < 0.4f) {
                    float u = stampT / 0.4f;
                    scale = 1.8f - 0.8f * u * u;
                    rot = -14f * (1 - u);
                } else {
                    float u = (stampT - 0.4f) / 0.6f;
                    scale = 1f - 0.1f * (float) (Math.exp(-6 * u) * Math.cos(12 * u));
                    float rr = (size * 0.6f + 20 * d * u);
                    ring.setStrokeWidth((2.2f - 1.6f * u) * d);
                    ring.setAlpha((int) (220 * (1 - u)));
                    c.drawCircle(cx, cy, rr, ring);
                }
            }
            float half = size / 2f * scale;
            c.save();
            c.rotate(rot - (i % 2 == 0 ? 2f : -2f), cx, cy);
            r.set(cx - half, cy - half, cx + half, cy + half);
            if (i <= shown) {
                box.setColor(Ui.mix(cold, hot, (i - 1) / 9f));
                c.drawRoundRect(r, 3 * d, 3 * d, box);
                digit.setColor(ink);
            } else {
                c.drawRoundRect(r, 3 * d, 3 * d, outline);
                digit.setColor(muted);
            }
            c.drawText(String.valueOf(i), cx, cy + digit.getTextSize() * 0.36f, digit);
            c.restore();
        }
    }
}
