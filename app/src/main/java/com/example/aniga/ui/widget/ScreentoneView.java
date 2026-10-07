package com.example.aniga.ui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.aniga.R;

/**
 * Halftone dot gradient (manga screentone). Dots grow from 0 at the "clear" edge to full size
 * at the "dense" edge, on a hex grid so it reads as print rather than a polka-dot pattern.
 */
public class ScreentoneView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float spacing;
    private boolean denseAtTop;

    public ScreentoneView(Context context) {
        this(context, null);
    }

    public ScreentoneView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        int color = 0x22E5532D;
        spacing = 7 * density;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ScreentoneView);
            color = a.getColor(R.styleable.ScreentoneView_toneColor, color);
            spacing = a.getDimension(R.styleable.ScreentoneView_toneSpacing, spacing);
            denseAtTop = a.getBoolean(R.styleable.ScreentoneView_denseAtTop, false);
            a.recycle();
        }
        paint.setColor(color);
    }

    public void setToneColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) return;
        float rowStep = spacing * 0.866f;
        int row = 0;
        for (float y = 0; y < h + spacing; y += rowStep, row++) {
            float t = y / h;
            float density = denseAtTop ? 1f - t : t;
            float r = spacing * 0.46f * density * density;
            if (r < 0.35f) continue;
            float offset = (row % 2 == 0) ? 0 : spacing / 2f;
            for (float x = offset; x < w + spacing; x += spacing) {
                canvas.drawCircle(x, y, r, paint);
            }
        }
    }
}
