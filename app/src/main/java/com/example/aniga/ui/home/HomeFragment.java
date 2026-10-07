package com.example.aniga.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

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
import com.example.aniga.data.Session;
import com.example.aniga.data.model.LibraryState;
import com.example.aniga.data.model.Media;
import com.example.aniga.data.model.Recommendation;
import com.example.aniga.ui.CoverAdapter;
import com.example.aniga.ui.CoverItem;
import com.example.aniga.ui.Covers;
import com.example.aniga.ui.Ui;
import com.example.aniga.ui.detail.DetailActivity;
import com.example.aniga.ui.widget.BurstView;
import com.example.aniga.ui.widget.PanelImageView;
import com.example.aniga.ui.widget.StatusView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class HomeFragment extends Fragment {

    private static final String[] RAIL_TYPES = {"MANHWA", "MANGA", "MANHUA", "ANIME"};
    private static final int[] RAIL_COLORS = {R.color.type_manhwa, R.color.type_manga, R.color.type_manhua, R.color.type_anime};
    private static final float PAGE_PAD = 0.035f;
    private static final float GUTTER_TOP_X = 0.60f;
    private static final float GUTTER_BOTTOM_X = 0.52f;

    private StatusView status;
    private LinearLayout rails;
    private View heroPage;
    private PanelImageView[] panels;
    private boolean heroShown;
    private View continueRail;
    private View picksRail;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View headerContent = view.findViewById(R.id.header_content);
        int basePad = headerContent.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(headerContent, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            v.setPadding(v.getPaddingLeft(), basePad + bars.top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        status = view.findViewById(R.id.status);
        rails = view.findViewById(R.id.rails);
        heroPage = view.findViewById(R.id.hero_page);
        panels = new PanelImageView[]{
                view.findViewById(R.id.panel_1), view.findViewById(R.id.panel_2), view.findViewById(R.id.panel_3)};
        layoutPanels();

        String name = Session.userName(requireContext());
        ((TextView) view.findViewById(R.id.avatar)).setText(name.substring(0, 1).toUpperCase(Locale.ROOT));
        load();
    }

    /**
     * One big panel on the left with a slanted gutter, two stacked on the right with a tilted
     * horizontal gutter — the most common shoujo/shonen page layout.
     */
    private void layoutPanels() {
        float p = PAGE_PAD;
        float g = 0.028f;
        float splitLeft = 0.47f;
        float splitRight = 0.40f;

        panels[0].setPolygon(new float[]{p, p, GUTTER_TOP_X, p, GUTTER_BOTTOM_X, 1 - p, p, 1 - p});
        panels[1].setPolygon(new float[]{
                gutterX(p) + g, p,
                1 - p, p,
                1 - p, splitRight - g / 2,
                gutterX(splitLeft - g / 2) + g, splitLeft - g / 2});
        panels[2].setPolygon(new float[]{
                gutterX(splitLeft + g / 2) + g, splitLeft + g / 2,
                1 - p, splitRight + g / 2,
                1 - p, 1 - p,
                gutterX(1 - p) + g, 1 - p});
    }

    /** x of the slanted vertical gutter at height y. */
    private static float gutterX(float y) {
        return GUTTER_TOP_X - (GUTTER_TOP_X - GUTTER_BOTTOM_X) * (y - PAGE_PAD) / (1 - 2 * PAGE_PAD);
    }

    private void load() {
        status.loading(R.string.loading_shelf);
        long user = Session.userId(requireContext());
        Api.load(getViewLifecycleOwner(), Api.get().recommendations(user, 18, null), this::showPicks, e -> {
        });
        Api.load(getViewLifecycleOwner(), Api.get().library(user, "CURRENT", null), this::showContinue, e -> {
        });
        String[] names = {"Manhwa", "Manga", "Manhua", "Anime"};
        String[] subs = {"", "", "", ""};
        // Each type rail is its own request so they fill in independently and keep their order.
        View[] slots = new View[RAIL_TYPES.length];
        for (int t = 0; t < RAIL_TYPES.length; t++) {
            View slot = new View(requireContext());
            slot.setVisibility(View.GONE);
            rails.addView(slot);
            slots[t] = slot;
        }
        for (int t = 0; t < RAIL_TYPES.length; t++) {
            final int ti = t;
            Api.load(getViewLifecycleOwner(), Api.get().browse(RAIL_TYPES[t], null, "popularity", 40, 0), list -> {
                status.hide();
                List<CoverItem> items = new ArrayList<>();
                for (Media m : list) items.add(CoverItem.of(m));
                if (items.isEmpty()) return;
                View rail = buildRail(String.format(Locale.ROOT, "%02d", ti + 1), names[ti], subs[ti], items,
                        Ui.color(requireContext(), RAIL_COLORS[ti]));
                int at = rails.indexOfChild(slots[ti]);
                rails.removeViewAt(at);
                rails.addView(rail, at);
                slots[ti] = rail;
            }, e -> {
                if (ti == 0) status.error(getString(R.string.error_backend), this::reload);
            });
        }
    }

    private void reload() {
        rails.removeAllViews();
        heroShown = false;
        continueRail = null;
        picksRail = null;
        load();
    }

    private void showPicks(List<Recommendation> recs) {
        List<Media> withArt = new ArrayList<>();
        for (Recommendation r : recs) if (r.media.coverUrl != null) withArt.add(r.media);
        if (withArt.size() >= 3) showHero(withArt.subList(0, 3), recs.get(0));

        List<CoverItem> items = new ArrayList<>();
        String because = null;
        for (int i = 3; i < recs.size(); i++) {
            Recommendation r = recs.get(i);
            items.add(CoverItem.of(r.media, r.becauseTitle != null ? "推" : null));
            if (because == null && r.becauseTitle != null) because = r.becauseTitle;
        }
        if (!items.isEmpty()) {
            String sub = because != null
                    ? getString(R.string.home_picks_because, because)
                    : getString(R.string.home_picks_sub, Session.userName(requireContext()));
            picksRail = buildRail("推", getString(R.string.home_picks), sub, items, Ui.color(requireContext(), R.color.hanko));
            rails.addView(picksRail, continueRail != null ? 1 : 0);
        }
    }

    /** "Continue" = shelf entries in progress, most recently touched first. */
    private void showContinue(List<LibraryState.Entry> entries) {
        if (entries.isEmpty()) return;
        List<CoverItem> items = new ArrayList<>();
        for (LibraryState.Entry e : entries) items.add(CoverItem.of(e.media));
        continueRail = buildRail("続", getString(R.string.home_continue), getString(R.string.home_continue_sub),
                items, Ui.color(requireContext(), R.color.status_current));
        rails.addView(continueRail, 0);
    }

    private void showHero(List<Media> top, Recommendation first) {
        if (heroShown) return;
        heroShown = true;
        for (int i = 0; i < 3; i++) {
            Media m = top.get(i);
            Covers.loadPanel(panels[i], m.coverUrl, m.title, m.mediaType);
            panels[i].setOnClickListener(v -> DetailActivity.open(requireActivity(), m, null));
        }
        View root = requireView();
        ((TextView) root.findViewById(R.id.hero_kicker))
                .setText(getString(R.string.home_hero_caption, Session.userName(requireContext())));
        ((TextView) root.findViewById(R.id.hero_title)).setText(top.get(0).title);
        String reason = first.reasons != null && !first.reasons.isEmpty() ? first.reasons.get(0) : "";
        ((TextView) root.findViewById(R.id.hero_reason)).setText(reason);
        BurstView burst = root.findViewById(R.id.hero_burst);
        burst.setLabel("No.1");

        // Page slides up like it is being placed on the desk, then the panels ink in one by one.
        PathInterpolator emphasized = new PathInterpolator(0.2f, 0f, 0f, 1f);
        heroPage.setAlpha(0f);
        heroPage.setTranslationY(Ui.dp(requireContext(), 40));
        heroPage.setRotation(-2.5f);
        heroPage.setVisibility(View.VISIBLE);
        heroPage.animate().alpha(1f).translationY(0f).rotation(0f).setDuration(520)
                .setInterpolator(emphasized).start();
        for (int i = 0; i < panels.length; i++) {
            panels[i].setAlpha(0f);
            panels[i].animate().alpha(1f).setStartDelay(220 + i * 120L).setDuration(320).start();
        }
        View caption = root.findViewById(R.id.hero_caption);
        caption.setAlpha(0f);
        caption.setTranslationX(-Ui.dp(requireContext(), 24));
        caption.animate().alpha(1f).translationX(0f).setStartDelay(620).setDuration(360)
                .setInterpolator(emphasized).start();
        burst.setScaleX(0f);
        burst.setScaleY(0f);
        burst.animate().scaleX(1f).scaleY(1f).setStartDelay(760).setDuration(380)
                .setInterpolator(new OvershootInterpolator(2.6f)).start();
    }

    private View buildRail(String indexLabel, String title, String sub, List<CoverItem> items, int color) {
        View rail = LayoutInflater.from(requireContext()).inflate(R.layout.view_rail, rails, false);
        rail.findViewById(R.id.rail_bar).setBackgroundColor(color);
        ((TextView) rail.findViewById(R.id.rail_index)).setText(indexLabel);
        ((TextView) rail.findViewById(R.id.rail_title)).setText(title);
        TextView subView = rail.findViewById(R.id.rail_sub);
        boolean showSub = sub != null && !sub.isEmpty();
        subView.setVisibility(showSub ? View.VISIBLE : View.GONE);
        subView.setText(sub);
        TextView count = rail.findViewById(R.id.rail_count);
        count.setVisibility(View.GONE);
        RecyclerView list = rail.findViewById(R.id.rail_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false));
        CoverAdapter adapter = new CoverAdapter(false);
        list.setAdapter(adapter);
        adapter.submit(items);
        list.setLayoutAnimation(android.view.animation.AnimationUtils.loadLayoutAnimation(requireContext(),
                R.anim.layout_rise));
        return rail;
    }
}
