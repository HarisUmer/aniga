package com.example.aniga.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.aniga.R;
import com.example.aniga.ui.Ui;

import java.util.function.IntConsumer;

/**
 * Bottom navigation drawn like a brush-and-seal print instead of Material pills.
 *
 * Selecting a tab plays three things at once (420 ms, emphasized easing):
 *  - a sumi brush stroke travels under the labels: its head runs ahead (ease-out) and its tail follows
 *    (ease-in), so the stroke stretches mid-flight and snaps back to a short dab when it lands;
 *  - a hanko seal drops behind the new glyph: scale 1.7 -> 1 in the first 45 %, then a damped squash
 *    1 - 0.08·e^(-6u)·cos(11u), plus an ink ring that expands and fades from the impact;
 *  - the old seal lifts off (fades and shrinks) during the first 30 %.
 * Reselecting the current tab gives the seal a small wobble.
 */
public class InkNavView extends View {

    public static class Item {
        final String glyph;
        final String label;

        public Item(String glyph, String label) {
            this.glyph = glyph;
            this.label = label;
        }
    }

    private static final long DURATION = 420;

    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sealPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hairline = new Paint();
    private final Path brush = new Path();
    private final RectF rect = new RectF();
    private final PathInterpolator emphasized = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final float d;
    private final int inkColor;
    private final int mutedColor;
    private final int paperColor;
    private final int sealColor;

    private Item[] items = new Item[0];
    private int selected;
    private int previous = -1;
    private float t = 1f;
    private float wobble;
    private int bottomInset;
    private ValueAnimator anim;
    private IntConsumer listener;

    public InkNavView(Context context) {
        this(context, null);
    }

    public InkNavView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        inkColor = Ui.color(context, R.color.ink_950);
        mutedColor = Ui.color(context, R.color.paper_500);
        paperColor = Ui.color(context, R.color.paper_100);
        sealColor = Ui.color(context, R.color.hanko);
        glyphPaint.setTextAlign(Paint.Align.CENTER);
        glyphPaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        glyphPaint.setTextSize(21 * d);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Ui.mono(context));
        labelPaint.setTextSize(9.5f * d);
        labelPaint.setLetterSpacing(0.12f);
        sealPaint.setColor(sealColor);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(sealColor);
        strokePaint.setColor(paperColor);
        hairline.setColor(Ui.color(context, R.color.ink_700));
        setClickable(true);
    }

    public void setItems(Item... next) {
        items = next;
        requestLayout();
        invalidate();
    }

    public void setOnSelect(IntConsumer l) {
        listener = l;
    }

    public void setBottomInset(int px) {
        bottomInset = px;
        requestLayout();
    }

    public int selected() {
        return selected;
    }

    public void select(int index, boolean animate) {
        if (index == selected) {
            if (animate) wobble();
            return;
        }
        previous = selected;
        selected = index;
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            t = 1f;
            invalidate();
            return;
        }
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(DURATION);
        anim.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    private void wobble() {
        ValueAnimator w = ValueAnimator.ofFloat(0f, 1f);
        w.setDuration(520);
        w.addUpdateListener(a -> {
            float u = (float) a.getAnimatedValue();
            wobble = (float) (Math.exp(-5 * u) * Math.sin(u * Math.PI * 5));
            invalidate();
        });
        w.start();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int h = (int) (66 * d) + bottomInset;
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (items.length == 0) return false;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            int index = Math.min(items.length - 1, Math.max(0, (int) (e.getX() / (getWidth() / (float) items.length))));
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            boolean changed = index != selected;
            select(index, true);
            if (changed && listener != null) listener.accept(index);
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private float centerX(int i) {
        float cell = getWidth() / (float) items.length;
        return cell * i + cell / 2f;
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        c.drawColor(inkColor);
        c.drawRect(0, 0, getWidth(), d, hairline);
        if (items.length == 0) return;
        float glyphY = 30 * d;
        float sealCy = 23 * d;
        float labelY = 54 * d;
        float e = emphasized.getInterpolation(t);

        drawBrush(c, labelY + 3.5f * d);

        for (int i = 0; i < items.length; i++) {
            float cx = centerX(i);
            boolean isNew = i == selected;
            boolean isOld = i == previous && t < 1f;
            float sealAlpha = 0f;
            float sealScale = 1f;
            float sealRot = -6f;
            if (isNew) {
                if (t < 0.45f) {
                    float u = t / 0.45f;
                    sealScale = 1.7f - 0.7f * u * u;
                    sealAlpha = Math.min(1f, u * 1.6f);
                    sealRot = -16f + 10f * u;
                } else {
                    float u = (t - 0.45f) / 0.55f;
                    sealScale = 1f - 0.08f * (float) (Math.exp(-6 * u) * Math.cos(11 * u));
                    sealAlpha = 1f;
                }
                sealRot += wobble * 9f;
                if (t > 0.42f && t < 1f) drawRing(c, cx, sealCy, (t - 0.42f) / 0.58f);
            } else if (isOld) {
                float u = Math.min(1f, t / 0.3f);
                sealAlpha = 1f - u;
                sealScale = 1f - 0.25f * u;
            }
            if (sealAlpha > 0f) {
                float half = 15 * d * sealScale;
                c.save();
                c.rotate(sealRot, cx, sealCy);
                sealPaint.setAlpha((int) (255 * sealAlpha));
                rect.set(cx - half, sealCy - half, cx + half, sealCy + half);
                c.drawRoundRect(rect, 4 * d, 4 * d, sealPaint);
                c.restore();
            }
            int glyphColor = isNew ? Ui.mix(mutedColor, paperColor, Math.min(1f, t * 2.2f))
                    : isOld ? Ui.mix(paperColor, mutedColor, e) : mutedColor;
            glyphPaint.setColor(glyphColor);
            c.drawText(items[i].glyph, cx, glyphY, glyphPaint);
            labelPaint.setColor(isNew ? Ui.mix(mutedColor, paperColor, e) : mutedColor);
            c.drawText(items[i].label.toUpperCase(), cx, labelY, labelPaint);
        }
    }

    private void drawRing(Canvas c, float cx, float cy, float u) {
        float r = (16 + 22 * u) * d;
        ringPaint.setStrokeWidth((2.4f - 1.8f * u) * d);
        ringPaint.setAlpha((int) (200 * (1 - u)));
        c.drawCircle(cx, cy, r, ringPaint);
    }

    /** Tapered sumi stroke between the previous and the selected tab. */
    private void drawBrush(Canvas c, float y) {
        float to = centerX(selected);
        float from = previous < 0 ? to : centerX(previous);
        float head = from + (to - from) * easeOut(t);
        float tail = from + (to - from) * easeIn(t);
        float dab = 13 * d;
        float left = Math.min(head, tail) - dab;
        float right = Math.max(head, tail) + dab;
        boolean rightward = to >= from;
        float thick = 3.2f * d;
        float thin = 1.1f * d;
        float headW = thick;
        float tailW = t < 1f ? thin : thick * 0.7f;
        float lw = rightward ? tailW : headW;
        float rw = rightward ? headW : tailW;
        float sag = (right - left) * 0.035f;
        brush.reset();
        brush.moveTo(left, y - lw * 0.5f);
        brush.quadTo((left + right) / 2f, y - sag - (lw + rw) * 0.35f, right, y - rw);
        brush.quadTo(right + rw * 1.2f, y, right, y + rw * 0.6f);
        brush.quadTo((left + right) / 2f, y + sag + (lw + rw) * 0.3f, left, y + lw * 0.5f);
        brush.close();
        strokePaint.setColor(sealColor);
        c.drawPath(brush, strokePaint);
    }

    private static float easeOut(float x) {
        return 1 - (1 - x) * (1 - x) * (1 - x);
    }

    private static float easeIn(float x) {
        return x * x * x;
    }
}
