package com.example.aniga.ui.reader;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.example.aniga.R;
import com.example.aniga.ui.Ui;
import com.example.aniga.ui.widget.RollingBallView;

import java.util.ArrayList;
import java.util.List;

public class PageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_PAGE = 0;
    private static final int TYPE_END = 1;

    private final List<String> urls = new ArrayList<>();
    private final List<String> fallbacks = new ArrayList<>();
    private final List<String> captions = new ArrayList<>();
    private ReaderTheme theme;
    private final int gapPx;
    private String chapterLabel = "";
    private String credit;
    private boolean hasNext;
    private final Runnable onNext;
    private final Runnable onEndShown;

    public PageAdapter(ReaderTheme theme, int gapPx, Runnable onNext, Runnable onEndShown) {
        this.theme = theme;
        this.gapPx = gapPx;
        this.onNext = onNext;
        this.onEndShown = onEndShown;
    }

    public void submit(List<String> pageUrls, @Nullable List<String> fallbackUrls, @Nullable List<String> pageCaptions,
                       @Nullable String creditLine, String label, boolean next) {
        urls.clear();
        urls.addAll(pageUrls);
        fallbacks.clear();
        if (fallbackUrls != null) fallbacks.addAll(fallbackUrls);
        captions.clear();
        if (pageCaptions != null) captions.addAll(pageCaptions);
        credit = creditLine;
        chapterLabel = label;
        hasNext = next;
        notifyDataSetChanged();
    }

    public void setTheme(ReaderTheme next) {
        theme = next;
        notifyDataSetChanged();
    }

    public int pageCount() {
        return urls.size();
    }

    @Override
    public int getItemViewType(int position) {
        return position < urls.size() ? TYPE_PAGE : TYPE_END;
    }

    @Override
    public int getItemCount() {
        return urls.isEmpty() ? 0 : urls.size() + 1;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_END) return new EndHolder(inf.inflate(R.layout.item_chapter_end, parent, false));
        return new PageHolder(inf.inflate(R.layout.item_page, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Context c = holder.itemView.getContext();
        if (holder instanceof EndHolder) {
            EndHolder h = (EndHolder) holder;
            h.title.setText(c.getString(R.string.reader_end, chapterLabel));
            h.title.setTextColor(theme.text(c));
            h.ball.setBallColor(theme.accent);
            h.next.setVisibility(hasNext ? View.VISIBLE : View.GONE);
            h.next.setBackgroundTintList(android.content.res.ColorStateList.valueOf(theme.accent));
            h.next.setOnClickListener(v -> onNext.run());
            if (!hasNext) h.title.setText(R.string.reader_last);
            h.credit.setText(credit == null ? "" : credit);
            h.credit.setTextColor(theme.muted(c));
            onEndShown.run();
            return;
        }
        PageHolder h = (PageHolder) holder;
        h.frame.setBackgroundColor(theme.placeholder(c));
        h.frame.setMinimumHeight((int) Ui.dp(c, 560));
        ((ViewGroup.MarginLayoutParams) h.itemView.getLayoutParams()).bottomMargin = gapPx;
        h.label.setVisibility(View.VISIBLE);
        h.label.setTextColor(theme.muted(c));
        h.label.setText(String.valueOf(position + 1));
        h.label.setOnClickListener(null);
        String caption = position < captions.size() ? captions.get(position) : null;
        h.caption.setVisibility(caption == null || caption.isEmpty() ? View.GONE : View.VISIBLE);
        h.caption.setText(caption);
        h.caption.setTextColor(theme.text(c));
        load(h, position, false);
    }

    private void load(PageHolder h, int position, boolean useFallback) {
        String url = useFallback ? fallbacks.get(position) : urls.get(position);
        Glide.with(h.image)
                .load(url)
                .dontTransform()
                .format(DecodeFormat.PREFER_RGB_565)
                .override(Target.SIZE_ORIGINAL)
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, @Nullable Object model,
                                                Target<Drawable> target, boolean isFirstResource) {
                        if (h.getBindingAdapterPosition() != position) return false;
                        // MD@Home nodes 404 on cold cache misses; the origin always has the file.
                        if (!useFallback && position < fallbacks.size()) {
                            h.image.post(() -> load(h, position, true));
                        } else {
                            h.label.post(() -> {
                                h.label.setText((position + 1) + "\ntap to retry");
                                h.label.setTextSize(18);
                                h.label.setOnClickListener(v -> {
                                    h.label.setTextSize(40);
                                    h.label.setText(String.valueOf(position + 1));
                                    load(h, position, false);
                                });
                            });
                        }
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target,
                                                   DataSource dataSource, boolean isFirstResource) {
                        h.label.setVisibility(View.GONE);
                        h.frame.setMinimumHeight(0);
                        h.frame.setBackgroundColor(0);
                        return false;
                    }
                })
                .into(h.image);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof PageHolder) Glide.with(((PageHolder) holder).image).clear(((PageHolder) holder).image);
    }

    static class PageHolder extends RecyclerView.ViewHolder {
        final View frame;
        final ImageView image;
        final TextView label;
        final TextView caption;

        PageHolder(View v) {
            super(v);
            frame = v.findViewById(R.id.page_frame);
            image = v.findViewById(R.id.page_image);
            label = v.findViewById(R.id.page_label);
            caption = v.findViewById(R.id.page_caption);
        }
    }

    static class EndHolder extends RecyclerView.ViewHolder {
        final RollingBallView ball;
        final TextView title;
        final TextView credit;
        final com.google.android.material.button.MaterialButton next;

        EndHolder(View v) {
            super(v);
            ball = v.findViewById(R.id.end_ball);
            title = v.findViewById(R.id.end_title);
            credit = v.findViewById(R.id.end_credit);
            next = v.findViewById(R.id.end_next);
        }
    }
}
