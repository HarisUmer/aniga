package com.example.aniga.ui.widget;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

import com.google.android.material.imageview.ShapeableImageView;

/** Locks to the 2:3 tankobon cover ratio so grid and rail covers never letterbox. */
public class CoverImageView extends ShapeableImageView {

    public CoverImageView(Context context) {
        super(context);
    }

    public CoverImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(width * 1.5f);
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
