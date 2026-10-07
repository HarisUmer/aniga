package com.example.aniga.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.aniga.R;
import com.example.aniga.ui.Ui;

import java.util.Locale;

/**
 * Stand-in cover for titles with no art: printed like a bootleg tankobon spine — type colour
 * field, halftone fade, title set in Anton. Never a grey box with an icon.
 */
public class TypographicCover extends Drawable {

    private final String title;
    private final String typeLabel;
    private final int field;
    private final int ink;
    private final int paper;
    private final float density;
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint();

    public TypographicCover(Context c, String title, String type) {
        this.title = title == null ? "" : title.toUpperCase(Locale.ROOT);
        this.typeLabel = type == null ? "" : type;
        int typeColor = Ui.typeColor(c, type);
        this.field = Ui.mix(typeColor, Ui.color(c, R.color.ink_950), 0.55f);
        this.ink = Ui.color(c, R.color.ink_950);
        this.paper = Ui.color(c, R.color.paper_100);
        this.density = c.getResources().getDisplayMetrics().density;
        titlePaint.setTypeface(Ui.display(c));
        titlePaint.setColor(paper);
        labelPaint.setTypeface(Ui.mono(c));
        labelPaint.setColor(typeColor);
        labelPaint.setLetterSpacing(0.15f);
        dotPaint.setColor(Ui.withAlpha(typeColor, 0.55f));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) return;
        canvas.save();
        canvas.translate(b.left, b.top);

        fillPaint.setColor(field);
        canvas.drawRect(0, 0, w, h, fillPaint);

        // Halftone that grows toward the bottom, like a screentone gradient.
        float step = Math.max(5 * density, w / 18f);
        int row = 0;
        for (float y = h * 0.45f; y < h + step; y += step * 0.866f, row++) {
            float t = (y - h * 0.45f) / (h * 0.55f);
            float r = step * 0.42f * Math.min(1f, t);
            float offset = (row % 2 == 0) ? 0 : step / 2f;
            for (float x = offset; x < w + step; x += step) {
                canvas.drawCircle(x, y, r, dotPaint);
            }
        }

        float pad = w * 0.09f;
        fillPaint.setColor(ink);
        canvas.drawRect(0, 0, w, pad * 2.6f, fillPaint);
        labelPaint.setTextSize(Math.max(8 * density, w * 0.075f));
        canvas.drawText(typeLabel, pad, pad * 1.75f, labelPaint);

        titlePaint.setTextSize(w * 0.17f);
        int textWidth = Math.max(1, (int) (w - pad * 2));
        StaticLayout layout = StaticLayout.Builder
                .obtain(title, 0, title.length(), titlePaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, 0.92f)
                .setMaxLines(4)
                .build();
        canvas.translate(pad, pad * 3.4f);
        layout.draw(canvas);
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        titlePaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
