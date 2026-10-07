package com.example.aniga.ui.library;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.aniga.R;
import com.example.aniga.data.Api;
import com.example.aniga.data.Session;
import com.example.aniga.data.model.LibraryState;
import com.example.aniga.data.model.Media;
import com.example.aniga.ui.Covers;
import com.example.aniga.ui.Ui;
import com.example.aniga.ui.detail.DetailActivity;
import com.example.aniga.ui.shelf.RateSheet;
import com.example.aniga.ui.widget.StatusView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 帳 — the user's shelf across anime and comics, filterable by status, editable in place. */
public class LibraryFragment extends Fragment {

    private static final String[] FILTERS = {null, "CURRENT", "COMPLETED", "PLANNING", "PAUSED", "DROPPED"};

    private StatusView status;
    private RecyclerView list;
    private TextView stats;
    private ChipGroup filters;
    private final Adapter adapter = new Adapter();
    private final List<LibraryState.Entry> all = new ArrayList<>();
    private String filter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_library, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View content = view.findViewById(R.id.content);
        int basePad = content.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            v.setPadding(v.getPaddingLeft(), basePad + bars.top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        status = view.findViewById(R.id.status);
        stats = view.findViewById(R.id.stats);
        filters = view.findViewById(R.id.filters);
        list = view.findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        buildFilters();
        refresh();
    }

    private void buildFilters() {
        filters.removeAllViews();
        for (String f : FILTERS) {
            Chip chip = new Chip(requireContext());
            chip.setId(View.generateViewId());
            chip.setText(f == null ? getString(R.string.library_all) : label(f));
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            chip.setEnsureMinTouchTargetSize(false);
            chip.setChecked(f == null);
            chip.setOnCheckedChangeListener((c, checked) -> {
                if (!checked) return;
                filter = f;
                apply();
            });
            filters.addView(chip);
        }
    }

    private static String label(String status) {
        return "CURRENT".equals(status) ? "In progress" : Ui.statusLabel(status, false);
    }

    /** Called when the tab is shown, so ratings made elsewhere appear. */
    public void refresh() {
        if (getView() == null) return;
        if (all.isEmpty()) status.loading(R.string.loading_shelf);
        Api.load(getViewLifecycleOwner(), Api.get().library(Session.userId(requireContext()), null, null), entries -> {
            all.clear();
            all.addAll(entries);
            showStats();
            apply();
        }, e -> status.error(getString(R.string.error_backend), this::refresh));
    }

    private void showStats() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int anime = 0;
        int episodes = 0;
        int chapters = 0;
        for (LibraryState.Entry e : all) {
            counts.merge(e.status, 1, Integer::sum);
            if (e.media.isAnime()) {
                anime++;
                episodes += e.progress;
            } else {
                chapters += e.progress;
            }
        }
        stats.setText(String.format(Locale.ROOT, "%d titles · %d anime · %d episodes watched · %d chapters read",
                all.size(), anime, episodes, chapters));
    }

    private void apply() {
        List<LibraryState.Entry> shown = new ArrayList<>();
        for (LibraryState.Entry e : all) if (filter == null || filter.equals(e.status)) shown.add(e);
        if (shown.isEmpty()) {
            adapter.submit(shown);
            status.empty(getString(all.isEmpty() ? R.string.library_empty : R.string.library_empty_filter));
            return;
        }
        status.hide();
        adapter.submit(shown);
        list.scheduleLayoutAnimation();
    }

    class Adapter extends RecyclerView.Adapter<Adapter.H> {
        private final List<LibraryState.Entry> items = new ArrayList<>();

        void submit(List<LibraryState.Entry> next) {
            items.clear();
            items.addAll(next);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new H(LayoutInflater.from(p.getContext()).inflate(R.layout.item_library, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int i) {
            LibraryState.Entry e = items.get(i);
            Media m = e.media;
            android.content.Context c = h.itemView.getContext();
            int sc = Ui.statusColor(c, e.status);
            h.spine.setBackgroundColor(sc);
            h.kicker.setText(Ui.statusLabel(e.status, m.isAnime()).toUpperCase(Locale.ROOT) + "  ·  " + m.mediaType);
            h.kicker.setTextColor(sc);
            h.title.setText(m.title);
            Integer total = m.total();
            String unit = m.isAnime() ? "EP" : "CH";
            h.progressLabel.setText(total != null ? unit + " " + e.progress + " / " + total : unit + " " + e.progress);
            h.progress.setIndicatorColor(Ui.typeColor(c, m.mediaType));
            h.progress.setProgressCompat(total != null && total > 0 ? 1000 * Math.min(e.progress, total) / total
                    : (e.progress > 0 ? 500 : 0), true);
            if (e.score != null && e.score > 0) {
                GradientDrawable seal = new GradientDrawable();
                seal.setColor(Ui.mix(Ui.color(c, R.color.yamabuki), Ui.color(c, R.color.hanko), (e.score - 1) / 9f));
                seal.setCornerRadius(Ui.dp(c, 3));
                h.score.setBackground(seal);
                h.score.setText(String.valueOf(e.score));
                h.score.setRotation(-6f);
                h.score.setVisibility(View.VISIBLE);
            } else {
                h.score.setVisibility(View.INVISIBLE);
            }
            Covers.load(h.cover, m.coverUrl, m.title, m.mediaType);
            h.itemView.setOnClickListener(v -> DetailActivity.open((Activity) v.getContext(), m, h.cover));
            h.edit.setOnClickListener(v -> RateSheet.show((AppCompatActivity) requireActivity(), m, s -> {
                if (!"NONE".equals(s.status)) e.status = s.status;
                e.progress = s.progress;
                e.score = s.score;
                int pos = h.getBindingAdapterPosition();
                if (pos >= 0) notifyItemChanged(pos);
                showStats();
            }));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class H extends RecyclerView.ViewHolder {
            final View spine;
            final ImageView cover;
            final TextView kicker;
            final TextView title;
            final LinearProgressIndicator progress;
            final TextView progressLabel;
            final TextView score;
            final View edit;

            H(View v) {
                super(v);
                spine = v.findViewById(R.id.spine);
                cover = v.findViewById(R.id.cover);
                kicker = v.findViewById(R.id.kicker);
                title = v.findViewById(R.id.title);
                progress = v.findViewById(R.id.progress);
                progressLabel = v.findViewById(R.id.progress_label);
                score = v.findViewById(R.id.score);
                edit = v.findViewById(R.id.edit);
            }
        }
    }
}
