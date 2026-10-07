package com.example.aniga.ui;

import android.graphics.drawable.ColorDrawable;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.example.aniga.R;
import com.example.aniga.ui.widget.TypographicCover;

public final class Covers {

    private Covers() {
    }

    public static void load(ImageView view, String url, String title, String type) {
        TypographicCover fallback = new TypographicCover(view.getContext(), title, type);
        if (url == null || url.isEmpty()) {
            Glide.with(view).clear(view);
            view.setImageDrawable(fallback);
            return;
        }
        Glide.with(view)
                .load(url)
                .placeholder(new ColorDrawable(Ui.color(view.getContext(), R.color.ink_700)))
                .error(fallback)
                .transition(DrawableTransitionOptions.withCrossFade(220))
                .centerCrop()
                .into(view);
    }

    /** For PanelImageView: no Glide transform, the view crops to its own polygon. */
    public static void loadPanel(ImageView view, String url, String title, String type) {
        TypographicCover fallback = new TypographicCover(view.getContext(), title, type);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(fallback);
            return;
        }
        Glide.with(view).load(url).dontAnimate().dontTransform().error(fallback).into(view);
    }
}
