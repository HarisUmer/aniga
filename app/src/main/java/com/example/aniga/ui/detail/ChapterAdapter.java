package com.example.aniga.ui.detail;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.aniga.R;
import com.example.aniga.data.model.Chapter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

public class ChapterAdapter extends RecyclerView.Adapter<ChapterAdapter.Holder> {

    private final List<Chapter> items = new ArrayList<>();
    private final int accent;
    private final IntConsumer onOpen;
    private int readUpTo;
    private boolean episodes;

    public ChapterAdapter(int accent, IntConsumer onOpen) {
        this.accent = accent;
        this.onOpen = onOpen;
    }

    public void setEpisodes(boolean on) {
        episodes = on;
    }

    public void submit(List<Chapter> next) {
        items.clear();
        items.addAll(next);
        notifyDataSetChanged();
    }

    /** Chapters numbered at or below the shelf progress are drawn as read. */
    public void setReadUpTo(int progress) {
        if (progress == readUpTo) return;
        readUpTo = progress;
        notifyDataSetChanged();
    }

    static double number(Chapter c) {
        try {
            return c.number == null ? -1 : Double.parseDouble(c.number);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chapter, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Chapter c = items.get(position);
        h.number.setText(c.label());
        h.number.setTextColor(accent);
        h.kicker.setText(episodes ? "EP." : "CH.");
        String noun = episodes ? "Episode " : "Chapter ";
        h.title.setText(c.title == null || c.title.isEmpty() ? noun + c.label() : c.title);
        StringBuilder meta = new StringBuilder();
        if (episodes) meta.append(c.group);
        else if (c.isExternal()) meta.append("OFFICIAL SITE ↗");
        else if (c.pages > 0) meta.append(c.pages).append(c.pages == 1 ? " page" : " pages");
        else meta.append("open to read");
        if (!episodes && c.group != null && !c.group.isEmpty()) meta.append("  ·  ").append(c.group);
        if (c.publishedAt != null && c.publishedAt.length() >= 10) meta.append("  ·  ").append(c.publishedAt, 0, 10);
        h.meta.setText(meta);
        double n = number(c);
        boolean read = readUpTo > 0 && n > 0 && n <= readUpTo;
        h.itemView.setAlpha(read ? 0.45f : 1f);
        h.itemView.setOnClickListener(v -> onOpen.accept(h.getBindingAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView number;
        final TextView kicker;
        final TextView title;
        final TextView meta;

        Holder(View v) {
            super(v);
            number = v.findViewById(R.id.number);
            kicker = v.findViewById(R.id.number_kicker);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
        }
    }
}
