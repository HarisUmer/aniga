package com.example.aniga.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.example.aniga.R;
import com.example.aniga.ui.Ui;
import com.google.android.material.button.MaterialButton;

/**
 * One place for loading / empty / error states so every screen gets the same illustrated
 * treatment: the paper plane while waiting, the rolling ball when there is nothing to show.
 */
public class StatusView extends LinearLayout {

    private final PaperPlaneLoaderView plane;
    private final RollingBallView ball;
    private final TextView caption;
    private final MaterialButton retry;
    private final MaterialButton report;

    public StatusView(Context context) {
        this(context, null);
    }

    public StatusView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) Ui.dp(context, 16);
        setPadding(pad, pad, pad, pad);

        plane = new PaperPlaneLoaderView(context);
        addView(plane, new LayoutParams(LayoutParams.MATCH_PARENT, (int) Ui.dp(context, 120)));
        ball = new RollingBallView(context);
        addView(ball, new LayoutParams(LayoutParams.MATCH_PARENT, (int) Ui.dp(context, 110)));

        caption = new TextView(context);
        caption.setTextAppearance(R.style.TextAppearance_Aniga_Kicker);
        caption.setGravity(Gravity.CENTER);
        caption.setLineSpacing(0, 1.3f);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) Ui.dp(context, 8);
        addView(caption, lp);

        retry = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        retry.setText(R.string.retry);
        retry.setTextColor(Ui.color(context, R.color.paper_100));
        retry.setStrokeColorResource(R.color.ink_600);
        retry.setCornerRadius((int) Ui.dp(context, 2));
        retry.setTypeface(Ui.mono(context));
        LayoutParams rp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        rp.topMargin = (int) Ui.dp(context, 12);
        addView(retry, rp);

        report = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        report.setText(R.string.report_broken);
        report.setTextColor(Ui.color(context, R.color.hanko));
        report.setStrokeColorResource(R.color.hanko_deep);
        report.setCornerRadius((int) Ui.dp(context, 2));
        report.setTypeface(Ui.mono(context));
        report.setVisibility(GONE);
        LayoutParams reportParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        reportParams.topMargin = (int) Ui.dp(context, 8);
        addView(report, reportParams);
        setVisibility(GONE);
    }

    public void loading(@StringRes int text) {
        show(true, false, getContext().getString(text), null);
    }

    public void empty(CharSequence text) {
        show(false, true, text, null);
    }

    public void error(CharSequence text, @Nullable Runnable onRetry) {
        error(text, onRetry, null);
    }

    public void error(CharSequence text, @Nullable Runnable onRetry, @Nullable Runnable onReport) {
        show(false, true, text, onRetry);
        report.setVisibility(onReport != null ? VISIBLE : GONE);
        if (onReport != null) report.setOnClickListener(v -> onReport.run());
    }

    public void hide() {
        if (getVisibility() == GONE) return;
        animate().cancel();
        animate().alpha(0f).setDuration(180).withEndAction(() -> setVisibility(GONE)).start();
    }

    private void show(boolean loading, boolean idle, CharSequence text, @Nullable Runnable onRetry) {
        plane.setVisibility(loading ? VISIBLE : GONE);
        ball.setVisibility(idle ? VISIBLE : GONE);
        caption.setText(text);
        retry.setVisibility(onRetry != null ? VISIBLE : GONE);
        report.setVisibility(GONE);
        if (onRetry != null) retry.setOnClickListener(v -> onRetry.run());
        animate().cancel();
        if (getVisibility() != VISIBLE) {
            setAlpha(0f);
            setVisibility(VISIBLE);
        }
        animate().alpha(1f).setDuration(280).start();
    }

    public void setBallColor(int color) {
        ball.setBallColor(color);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == View.GONE) setAlpha(0f);
    }
}
