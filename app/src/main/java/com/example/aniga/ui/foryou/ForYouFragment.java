package com.example.aniga.ui.foryou;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.aniga.R;
import com.example.aniga.data.Api;
import com.example.aniga.data.Progress;
import com.example.aniga.data.Session;
import com.example.aniga.data.model.Taste;
import com.example.aniga.data.model.TasteV2;
import com.example.aniga.data.model.User;
import com.example.aniga.ui.Ui;
import com.example.aniga.ui.widget.StatusView;
import com.example.aniga.ui.widget.TasteBarView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Recommendations plus the editable taste model: each bar is one feature (genre, tag or type) the ranker uses,
 * the white tick is where it sits from ratings alone, and the bar is where ratings + reactions + your hand-set
 * boost/mute put it. Tapping a row cycles boost → mute → clear.
 */
public class ForYouFragment extends Fragment {

    private static final int MAX_BARS = 10;

    private ChipGroup users;
    private LinearLayout genreBars;
    private LinearLayout changes;
    private ChipGroup liked;
    private ChipGroup disliked;
    private TextView tasteMeta;
    private StatusView status;
    private RecyclerView list;
    private RecommendationAdapter adapter;
    private final Map<String, BarRow> rows = new HashMap<>();
    private long userId;

    private static final class BarRow {
        final View root;
        final TextView name;
        final TextView pref;
        final TasteBarView bar;
        final TextView value;

        BarRow(View root, TextView name, TextView pref, TasteBarView bar, TextView value) {
            this.root = root;
            this.name = name;
            this.pref = pref;
            this.bar = bar;
            this.value = value;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_for_you, container, false);
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
        users = view.findViewById(R.id.users);
        genreBars = view.findViewById(R.id.genre_bars);
        changes = view.findViewById(R.id.changes);
        liked = view.findViewById(R.id.liked);
        disliked = view.findViewById(R.id.disliked);
        tasteMeta = view.findViewById(R.id.taste_meta);
        status = view.findViewById(R.id.status);
        list = view.findViewById(R.id.list);
        adapter = new RecommendationAdapter(getViewLifecycleOwner(), this::refreshModel);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        view.findViewById(R.id.reset).setOnClickListener(v ->
                Api.load(getViewLifecycleOwner(), Api.get().resetOnline(userId), r -> {
                    Toast.makeText(requireContext(), R.string.foryou_reset_done, Toast.LENGTH_SHORT).show();
                    loadFor(userId);
                }, e -> Toast.makeText(requireContext(), R.string.error_backend, Toast.LENGTH_SHORT).show()));
        loadUsers();
    }

    private void loadUsers() {
        status.loading(R.string.loading_list);
        Api.load(getViewLifecycleOwner(), Api.get().users(), this::showUsers,
                e -> status.error(getString(R.string.error_backend), this::loadUsers));
    }

    private void showUsers(List<User> all) {
        users.removeAllViews();
        long current = Session.userId(requireContext());
        // The API is locked to the signed-in account, so For You shows only you.
        for (User u : all) {
            if (u.id != current) {
                continue;
            }
            Chip chip = new Chip(requireContext());
            chip.setText(u.displayName != null && !u.displayName.isEmpty() ? u.displayName : u.username);
            chip.setCheckable(true);
            chip.setId(View.generateViewId());
            chip.setChecked(true);
            users.addView(chip);
        }
        loadFor(current);
    }

    private void loadFor(long id) {
        userId = id;
        rows.clear();
        genreBars.removeAllViews();
        status.loading(R.string.loading_list);
        Api.load(getViewLifecycleOwner(), Api.get().taste(id), this::showTerms, e -> {
        });
        refreshModel();
        Api.load(getViewLifecycleOwner(), Api.get().recommendations(id, 15, null, true), recs -> {
            status.hide();
            adapter.submit(recs);
            list.scheduleLayoutAnimation();
        }, e -> status.error(getString(R.string.error_backend), () -> loadFor(id)));
    }

    /** Re-reads the model after any reward; bars that already exist glide to their new value. */
    private void refreshModel() {
        long id = userId;
        Api.load(getViewLifecycleOwner(), Api.get().tasteV2(id), t -> {
            if (id == userId) showModel(t);
        }, e -> {
        });
    }

    private void showModel(TasteV2 t) {
        tasteMeta.setText(getString(R.string.foryou_model_meta, t.signals, t.rewards));
        List<TasteV2.Feature> top = new ArrayList<>();
        if (t.features != null) {
            for (TasteV2.Feature f : t.features) {
                if (top.size() >= MAX_BARS) break;
                top.add(f);
            }
        }
        double scale = 0.6;
        for (TasteV2.Feature f : top) {
            scale = Math.max(scale, Math.abs(f.effective));
            scale = Math.max(scale, Math.abs(f.history));
        }
        genreBars.removeAllViews();
        for (int i = 0; i < top.size(); i++) {
            TasteV2.Feature f = top.get(i);
            BarRow row = rows.get(f.feature);
            boolean fresh = row == null;
            if (fresh) {
                row = barRow(f);
                rows.put(f.feature, row);
            }
            bindRow(row, f, scale, fresh ? 70L * i : 0L, fresh);
            genreBars.addView(row.root);
        }
        showChanges(t.changes);
    }

    private BarRow barRow(TasteV2.Feature f) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) Ui.dp(requireContext(), 5);
        row.setPadding(0, pad, 0, pad);
        row.setBackgroundResource(android.R.drawable.list_selector_background);

        TextView name = new TextView(requireContext());
        name.setTextAppearance(R.style.TextAppearance_Aniga_Label);
        name.setTextColor(Ui.color(requireContext(), R.color.paper_200));
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams((int) Ui.dp(requireContext(), 96), -2));

        TextView pref = new TextView(requireContext());
        pref.setTextAppearance(R.style.TextAppearance_Aniga_Meta);
        pref.setGravity(Gravity.CENTER);
        row.addView(pref, new LinearLayout.LayoutParams((int) Ui.dp(requireContext(), 18), -2));

        TasteBarView bar = new TasteBarView(requireContext());
        row.addView(bar, new LinearLayout.LayoutParams(0, (int) Ui.dp(requireContext(), 14), 1f));

        TextView value = new TextView(requireContext());
        value.setTextAppearance(R.style.TextAppearance_Aniga_Meta);
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams((int) Ui.dp(requireContext(), 48), -2));
        return new BarRow(row, name, pref, bar, value);
    }

    private void bindRow(BarRow row, TasteV2.Feature f, double scale, long delay, boolean fresh) {
        String kind = "type".equals(f.kind) ? "type" : "tag".equals(f.kind) ? "#" : "";
        row.name.setText("#".equals(kind) ? "#" + f.label : "type".equals(kind) ? f.label + " (type)" : f.label);
        row.pref.setText(f.pref > 0 ? "▲" : f.pref < 0 ? "✕" : "");
        row.pref.setTextColor(Ui.color(requireContext(), f.pref > 0 ? R.color.taste_like : R.color.taste_dislike));
        float v = (float) (f.effective / scale);
        if (fresh) row.bar.setValue(v, delay);
        else row.bar.animateTo(v, 0);
        row.bar.setMarker(Math.abs(f.history - f.effective) > 0.02 ? (float) (f.history / scale) : Float.NaN);
        row.value.setText(String.format(Locale.ROOT, "%+.2f", f.effective));
        row.root.setContentDescription(String.format(Locale.ROOT, "%s, %+.2f, %s", f.label, f.effective,
                f.pref > 0 ? "boosted" : f.pref < 0 ? "muted" : "tap to boost"));
        row.root.setOnClickListener(v2 -> {
            // 0 → boost → mute → clear
            int next = f.pref == 0 ? 1 : f.pref > 0 ? -1 : 0;
            Api.load(getViewLifecycleOwner(), Api.get().pref(userId, new TasteV2.Pref(f.feature, next)), r -> {
                refreshModel();
                reloadRecs();
            }, e -> Toast.makeText(requireContext(), R.string.error_backend, Toast.LENGTH_SHORT).show());
        });
    }

    private void reloadRecs() {
        long id = userId;
        Api.load(getViewLifecycleOwner(), Api.get().recommendations(id, 15, null, true), recs -> {
            if (id != userId) return;
            adapter.submit(recs);
            list.scheduleLayoutAnimation();
        }, e -> {
        });
    }

    /** "Rated Berserk 3/10 — expected 82%, got 20%" then the features that moved, green up / red down. */
    private void showChanges(@Nullable List<TasteV2.Change> recent) {
        changes.removeAllViews();
        if (recent == null) return;
        int like = Ui.color(requireContext(), R.color.taste_like);
        int dislike = Ui.color(requireContext(), R.color.taste_dislike);
        int shown = 0;
        for (TasteV2.Change c : recent) {
            if (shown++ >= 5) break;
            TextView line = new TextView(requireContext());
            line.setTextAppearance(R.style.TextAppearance_Aniga_Meta);
            SpannableStringBuilder s = new SpannableStringBuilder();
            s.append(c.title != null ? c.title : "—").append(" · ").append(c.cause != null ? c.cause : "");
            if (c.expected != null) {
                s.append(String.format(Locale.ROOT, " · expected %d%%, got %d%%",
                        Math.round(c.expected * 100), Math.round(c.reward * 100)));
            }
            if (c.deltas != null && !c.deltas.isEmpty()) {
                List<Map.Entry<String, Double>> ds = new ArrayList<>(c.deltas.entrySet());
                ds.sort((a, b) -> Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue())));
                s.append("\n");
                for (int i = 0; i < Math.min(3, ds.size()); i++) {
                    Map.Entry<String, Double> d = ds.get(i);
                    int start = s.length();
                    s.append(label(d.getKey())).append(String.format(Locale.ROOT, " %+.2f", d.getValue()));
                    s.setSpan(new ForegroundColorSpan(d.getValue() >= 0 ? like : dislike), start, s.length(), 0);
                    if (i < Math.min(3, ds.size()) - 1) s.append("   ");
                }
            }
            line.setText(s);
            int pad = (int) Ui.dp(requireContext(), 4);
            line.setPadding(0, pad, 0, pad);
            changes.addView(line);
        }
    }

    private static String label(String feature) {
        int colon = feature.indexOf(':');
        String rest = colon >= 0 ? feature.substring(colon + 1) : feature;
        return feature.startsWith("t:") ? "#" + rest : rest;
    }

    private void showTerms(Taste taste) {
        fillTerms(liked, taste.likedTerms, R.color.taste_like);
        fillTerms(disliked, taste.dislikedTerms, R.color.taste_dislike);
    }

    private void fillTerms(ChipGroup group, List<Map<String, Double>> terms, int colorRes) {
        group.removeAllViews();
        if (terms == null) return;
        int color = Ui.color(requireContext(), colorRes);
        int count = 0;
        for (Map<String, Double> entry : terms) {
            for (String term : entry.keySet()) {
                if (count++ >= 8) return;
                Chip chip = new Chip(requireContext());
                chip.setText(term);
                chip.setCheckable(false);
                chip.setClickable(false);
                chip.setChipBackgroundColor(ColorStateList.valueOf(Ui.withAlpha(color, 0.12f)));
                chip.setChipStrokeColor(ColorStateList.valueOf(Ui.withAlpha(color, 0.6f)));
                chip.setTextColor(color);
                chip.setEnsureMinTouchTargetSize(false);
                group.addView(chip);
            }
        }
    }
}
