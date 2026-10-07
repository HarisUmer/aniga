package com.example.aniga.ui;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.RecyclerView;

import com.example.aniga.R;
import com.example.aniga.data.Progress;
import com.example.aniga.data.model.Media;
import com.example.aniga.ui.detail.DetailActivity;

import java.util.ArrayList;
import java.util.List;

public class CoverAdapter extends RecyclerView.Adapter<CoverAdapter.Holder> {

    private final List<CoverItem> items = new ArrayList<>();
    private final boolean grid;

    public CoverAdapter(boolean grid) {
        this.grid = grid;
    }

    public void submit(List<CoverItem> next) {
        items.clear();
        items.addAll(next);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_cover, parent, false);
        if (grid) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            int gap = (int) Ui.dp(parent.getContext(), 5);
            lp.setMargins(gap, 0, gap, (int) Ui.dp(parent.getContext(), 14));
            v.setLayoutParams(lp);
        }
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        CoverItem item = items.get(position);
        h.title.setText(item.title);
        h.meta.setText(item.meta);
        h.typeTag.setText(item.type);
        GradientDrawable tag = new GradientDrawable();
        tag.setColor(Ui.typeColor(h.itemView.getContext(), item.type));
        tag.setCornerRadius(Ui.dp(h.itemView.getContext(), 1.5f));
        h.typeTag.setBackground(tag);
        Covers.load(h.cover, item.coverUrl, item.title, item.type);
        h.badge.setVisibility(item.badge == null ? View.GONE : View.VISIBLE);
        h.badge.setText(item.badge);
        bindProgress(h, item);
        h.itemView.setOnClickListener(v -> {
            Activity a = (Activity) v.getContext();
            if (item.media != null) DetailActivity.open(a, item.media, h.cover);
            else if (item.series != null) DetailActivity.open(a, item.series, h.cover);
        });
        // Press feedback: the cover sinks slightly like a stamped card.
        h.itemView.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(110).start();
            } else if (e.getAction() == android.view.MotionEvent.ACTION_UP
                    || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
            }
            return false;
        });
    }

    /** "EP 3 / 220 · 217 left" and a bar along the cover's bottom edge for titles on the user's shelf. */
    private static void bindProgress(Holder h, CoverItem item) {
        Progress.State s = item.media != null ? Progress.get(item.media.id) : null;
        if (s == null || (s.progress == 0 && !"COMPLETED".equals(s.status))) {
            h.track.setVisibility(View.GONE);
            h.fill.setVisibility(View.GONE);
            if (s != null) h.meta.setText(Ui.statusLabel(s.status, item.media.isAnime()) + " · " + item.meta);
            return;
        }
        Media m = item.media;
        Integer total = m.total();
        int seen = "COMPLETED".equals(s.status) && total != null ? total : s.progress;
        StringBuilder text = new StringBuilder(m.isAnime() ? "EP " : "CH ").append(seen);
        if (total != null) {
            text.append(" / ").append(total);
            if (total > seen) text.append(" · ").append(total - seen).append(" left");
        } else {
            text.append(" · ").append(Ui.statusLabel(s.status, m.isAnime()));
        }
        h.meta.setText(text);
        int color = Ui.statusColor(h.itemView.getContext(), s.status);
        h.track.setVisibility(View.VISIBLE);
        h.fill.setVisibility(View.VISIBLE);
        h.fill.setBackgroundColor(color);
        // Running series without a final count: show a short stub so "in progress" still reads.
        float frac = total != null && total > 0 ? Math.min(1f, seen / (float) total) : 0.12f;
        h.fill.setPivotX(0f);
        h.fill.setScaleX(frac);
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView rv) {
        Progress.version().observeForever(progressObserver);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView rv) {
        Progress.version().removeObserver(progressObserver);
    }

    private final Observer<Integer> progressObserver = v -> {
        if (!items.isEmpty()) notifyItemRangeChanged(0, items.size());
    };

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title;
        final TextView meta;
        final TextView typeTag;
        final TextView badge;
        final View track;
        final View fill;

        Holder(View v) {
            super(v);
            cover = v.findViewById(R.id.cover);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            typeTag = v.findViewById(R.id.type_tag);
            badge = v.findViewById(R.id.badge);
            track = v.findViewById(R.id.progress_track);
            fill = v.findViewById(R.id.progress_fill);
        }
    }
}
