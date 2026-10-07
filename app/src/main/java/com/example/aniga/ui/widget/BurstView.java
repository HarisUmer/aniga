package com.example.aniga.ui.widget;

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

/** Spiky manga "shout" balloon with a short label, e.g. No.1. */
public class BurstView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private String label = "No.1";

    public BurstView(Context context) {
        this(context, null);
    }

    public BurstView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        fill.setColor(Ui.color(context, R.color.hanko));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeJoin(Paint.Join.MITER);
        edge.setStrokeWidth(Ui.dp(context, 2.5f));
        edge.setColor(Ui.color(context, R.color.ink_950));
        text.setColor(Ui.color(context, R.color.paper_100));
        text.setTypeface(Ui.display(context));
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setLabel(String label) {
        this.label = label;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx = w / 2f;
        float cy = h / 2f;
        float outer = Math.min(w, h) / 2f - edge.getStrokeWidth();
        float inner = outer * 0.74f;
        int spikes = 14;
        path.reset();
        for (int i = 0; i < spikes * 2; i++) {
            double a = Math.PI * i / spikes - Math.PI / 2;
            // Uneven spike lengths make it look hand-cut rather than a perfect star.
            float r = (i % 2 == 0) ? outer * (i % 4 == 0 ? 1f : 0.9f) : inner;
            float x = (float) (cx + Math.cos(a) * r);
            float y = (float) (cy + Math.sin(a) * r);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        text.setTextSize(Math.min(w, h) * 0.27f);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        canvas.drawPath(path, fill);
        canvas.drawPath(path, edge);
        float baseline = getHeight() / 2f - (text.descent() + text.ascent()) / 2f;
        canvas.drawText(label, getWidth() / 2f, baseline, text);
    }
}
