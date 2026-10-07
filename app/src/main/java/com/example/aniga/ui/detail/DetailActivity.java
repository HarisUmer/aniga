package com.example.aniga.ui.detail;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.example.aniga.R;
import com.example.aniga.data.Api;
import com.example.aniga.data.Session;
import com.example.aniga.data.model.Chapter;
import com.example.aniga.data.model.LibraryState;
import com.example.aniga.data.model.Media;
import com.example.aniga.data.model.MediaDetail;
import com.example.aniga.data.model.Series;
import com.example.aniga.ui.CoverAdapter;
import com.example.aniga.ui.CoverItem;
import com.example.aniga.ui.Ui;
import com.example.aniga.ui.reader.ReaderActivity;
import com.example.aniga.ui.shelf.ShelfPanel;
import com.example.aniga.ui.widget.SpeedLinesView;
import com.example.aniga.ui.widget.StatusView;
import com.example.aniga.ui.widget.TypographicCover;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One screen for every title. Two entry points:
 *  - a catalog Media (AniList metadata): facts, gallery, cast, shelf (status/progress/score), similar titles;
 *    comics then look for the title on every reading source and list that source's chapters,
 *    anime list their episodes so progress can be set by tapping "watched up to here";
 *  - a Series straight from a reading source (Discover): goes directly to its chapters, and gains a shelf
 *    if the catalog has a title with the same name.
 */
public class DetailActivity extends AppCompatActivity {

    private static final String EXTRA_MEDIA = "media";
    private static final String EXTRA_SERIES = "series";
    private static final Gson GSON = new Gson();

    private Media media;
    private Series series;
    private String title;
    private String type;
    private int accent;
    private int typeColor;
    private StatusView status;
    private ChapterAdapter adapter;
    private TextView chapterCount;
    private ShelfPanel shelf;
    private final List<Chapter> chapters = new ArrayList<>();
    private String chapterSource;
    private String chapterLang;
    private boolean transitionStarted;
    private int requestSeq;

    public static void open(Activity from, Media m, @Nullable View cover) {
        launch(from, new Intent(from, DetailActivity.class).putExtra(EXTRA_MEDIA, GSON.toJson(m)), cover);
    }

    public static void open(Activity from, Series s, @Nullable View cover) {
        launch(from, new Intent(from, DetailActivity.class).putExtra(EXTRA_SERIES, GSON.toJson(s)), cover);
    }

    private static void launch(Activity from, Intent i, @Nullable View cover) {
        if (cover != null) {
            ActivityOptionsCompat opts = ActivityOptionsCompat.makeSceneTransitionAnimation(from, cover, "cover");
            from.startActivity(i, opts.toBundle());
        } else {
            from.startActivity(i);
        }
    }

    private static String stats(Media m) {
        StringBuilder s = new StringBuilder();
        if (m.startYear != null) s.append(m.startYear);
        if (m.format != null) s.append("  ·  ").append(m.format.replace('_', ' '));
        Integer total = m.total();
        if (total != null) s.append("  ·  ").append(total).append(m.isAnime() ? " EPISODES" : " CHAPTERS");
        if (m.averageScore != null) s.append("  ·  ANILIST ").append(m.averageScore).append("%");
        return s.toString();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_detail);
        supportPostponeEnterTransition();

        Intent in = getIntent();
        String mediaJson = in.getStringExtra(EXTRA_MEDIA);
        String seriesJson = in.getStringExtra(EXTRA_SERIES);
        if (mediaJson != null) media = GSON.fromJson(mediaJson, Media.class);
        if (seriesJson != null) series = GSON.fromJson(seriesJson, Series.class);
        title = media != null ? media.title : series.title;
        type = media != null ? media.mediaType : series.contentType;
        accent = Ui.tame(Ui.accent(this, media != null ? media.accentColor : null, type));
        typeColor = Ui.typeColor(this, type);
        int ink = Ui.color(this, R.color.ink_900);

        View back = findViewById(R.id.back);
        back.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        ViewCompat.setOnApplyWindowInsetsListener(back, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            ((ViewGroup.MarginLayoutParams) v.getLayoutParams()).topMargin = bars.top + (int) Ui.dp(this, 8);
            v.requestLayout();
            return insets;
        });

        findViewById(R.id.header_bg).setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Ui.mix(accent, ink, 0.35f), Ui.mix(accent, ink, 0.82f)}));
        findViewById(R.id.header_fade).setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Ui.withAlpha(ink, 0f), ink}));
        ((com.example.aniga.ui.widget.SeigaihaView) findViewById(R.id.waves))
                .setLineColor(Ui.withAlpha(Ui.mix(accent, 0xFFFFFFFF, 0.3f), 0.7f));
        SpeedLinesView speed = findViewById(R.id.speed);
        speed.setLineColor(Ui.withAlpha(Ui.mix(accent, 0xFFFFFFFF, 0.55f), 0.30f));
        speed.setFocusY(0.42f);

        ImageView banner = findViewById(R.id.banner);
        String bannerUrl = media != null ? media.bannerUrl : null;
        if (bannerUrl != null) Glide.with(this).load(bannerUrl).centerCrop().into(banner);

        ImageView cover = findViewById(R.id.cover);
        loadCover(cover, media != null ? media.coverUrl : series.coverUrl);
        cover.postDelayed(this::startTransitionOnce, 400);

        TextView typeView = findViewById(R.id.type);
        typeView.setText(type);
        typeView.setTextColor(typeColor);
        ((TextView) findViewById(R.id.title)).setText(title);
        ((TextView) findViewById(R.id.stats)).setText(media != null ? stats(media) : seriesStats(series));
        String synopsis = media != null ? media.synopsis : series.description;
        ((TextView) findViewById(R.id.synopsis)).setText(synopsis == null || synopsis.isEmpty()
                ? getString(R.string.detail_no_synopsis) : synopsis);
        fillGenres(media != null ? media.genresOrEmpty() : series.tags);
        findViewById(R.id.chapter_rule).setBackgroundColor(typeColor);

        chapterCount = findViewById(R.id.chapter_count);
        status = findViewById(R.id.status);
        status.setBallColor(typeColor);
        RecyclerView list = findViewById(R.id.chapters);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChapterAdapter(typeColor, this::openChapter);
        list.setAdapter(adapter);
        shelf = findViewById(R.id.shelf);
        shelf.setOnChange(this::onShelf);

        animateIn(speed);
        parallax(findViewById(R.id.scroll), speed, cover, banner);

        if (media != null) {
            bindCatalog(media);
        } else {
            loadChapters(series.source, series.id, series.language);
            attachCatalogMatch();
        }
    }

    private static String seriesStats(Series s) {
        StringBuilder b = new StringBuilder();
        if (s.year != null) b.append(s.year).append("  ·  ");
        if (s.status != null) b.append(s.status.toUpperCase(Locale.ROOT)).append("  ·  ");
        b.append(s.source.toUpperCase(Locale.ROOT));
        if (s.author != null) b.append("  ·  ").append(s.author);
        return b.toString();
    }

    // ---- catalog title -------------------------------------------------------------------------

    private void bindCatalog(Media m) {
        findViewById(R.id.shelf_section).setVisibility(View.VISIBLE);
        shelf.bind(this, m);
        Api.load(this, Api.get().detail(m.id), this::showDetail, e -> {
        });
        Api.load(this, Api.get().similar(m.id, 12), this::showSimilar, e -> {
        });
        if (m.isAnime()) {
            showEpisodes(m, null);
        } else {
            findOnSources();
        }
    }

    private void showDetail(MediaDetail d) {
        media = d.media != null ? d.media : media;
        List<String> facts = new ArrayList<>();
        if (media.nativeTitle != null) facts.add(media.nativeTitle);
        if (d.romajiTitle != null && !d.romajiTitle.equalsIgnoreCase(media.title)) facts.add(d.romajiTitle);
        if (media.season != null && media.startYear != null) {
            facts.add(media.season + " " + media.startYear);
        }
        if (media.status != null) facts.add(media.status.replace('_', ' '));
        if (d.episodeMinutes != null) facts.add(d.episodeMinutes + " min / ep");
        if (d.volumes != null) facts.add(d.volumes + " volumes");
        if (media.makers != null && !media.makers.isEmpty()) {
            facts.add((media.isAnime() ? "Studio " : "By ") + String.join(", ", media.makers));
        }
        TextView factsView = findViewById(R.id.facts);
        if (!facts.isEmpty()) {
            factsView.setText(String.join("  ·  ", facts));
            factsView.setVisibility(View.VISIBLE);
        }

        List<MediaDetail.GalleryImage> gallery = d.galleryOrEmpty();
        if (!gallery.isEmpty()) {
            RecyclerView g = findViewById(R.id.gallery);
            g.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
            g.setAdapter(new GalleryAdapter(gallery));
            reveal(findViewById(R.id.gallery_label), g);
        }
        List<MediaDetail.Character> cast = d.charactersOrEmpty();
        if (!cast.isEmpty()) {
            RecyclerView c = findViewById(R.id.cast);
            c.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
            c.setAdapter(new CastAdapter(cast, typeColor));
            reveal(findViewById(R.id.cast_label), c);
        }
        if (media.isAnime()) showEpisodes(media, gallery);
    }

    private void reveal(View label, View list) {
        label.setVisibility(View.VISIBLE);
        list.setVisibility(View.VISIBLE);
        list.setAlpha(0f);
        list.setTranslationX(Ui.dp(this, 40));
        list.animate().alpha(1f).translationX(0f).setDuration(420)
                .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();
    }

    private void showSimilar(List<Media> similar) {
        if (similar.isEmpty()) return;
        RecyclerView s = findViewById(R.id.similar);
        s.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        CoverAdapter a = new CoverAdapter(false);
        List<CoverItem> items = new ArrayList<>();
        for (Media m : similar) items.add(CoverItem.of(m));
        a.submit(items);
        s.setAdapter(a);
        reveal(findViewById(R.id.similar_label), s);
    }

    /** Anime have no pages; each row is "watched up to episode N" and sets shelf progress. */
    private void showEpisodes(Media m, @Nullable List<MediaDetail.GalleryImage> gallery) {
        ((TextView) findViewById(R.id.chapters_title)).setText(R.string.detail_episodes);
        Integer total = m.total();
        chapters.clear();
        if (total == null || total == 0) {
            chapterCount.setText("");
            status.empty(getString(R.string.anime_note));
            adapter.submit(chapters);
            return;
        }
        List<String> stillTitles = new ArrayList<>();
        if (gallery != null) {
            for (MediaDetail.GalleryImage g : gallery) if ("episode".equals(g.kind)) stillTitles.add(g.caption);
        }
        for (int i = 1; i <= total; i++) {
            Chapter c = new Chapter();
            c.id = "ep-" + i;
            c.number = String.valueOf(i);
            c.title = episodeTitle(stillTitles, i);
            c.group = getString(R.string.detail_episode_hint);
            c.pages = -1;
            chapters.add(c);
        }
        status.hide();
        chapterCount.setText(total + " EP  ·  " + getString(R.string.anime_note_short));
        adapter.setEpisodes(true);
        adapter.submit(chapters);
    }

    /** AniList streaming stills are titled "Episode 3 - Name"; match by the leading number. */
    private static String episodeTitle(List<String> stills, int n) {
        for (String s : stills) {
            if (s == null) continue;
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.startsWith("episode " + n + " ") || lower.startsWith("episode " + n + "-")) {
                int dash = s.indexOf(" - ");
                return dash > 0 ? s.substring(dash + 3) : s;
            }
        }
        return null;
    }

    private void onShelf(LibraryState s) {
        adapter.setReadUpTo(s.progress);
    }

    // ---- reading sources -----------------------------------------------------------------------

    private void findOnSources() {
        status.loading(R.string.loading_chapters);
        String lang = Session.readingLang(this);
        int seq = ++requestSeq;
        String aliases = media != null && media.nativeTitle != null ? media.nativeTitle : null;
        Api.load(this, Api.get().find(title, lang, aliases), hits -> {
            if (seq != requestSeq) return;
            if (hits.isEmpty()) {
                chapterCount.setText("");
                status.empty(getString(R.string.empty_chapters_lang, lang.toUpperCase(Locale.ROOT)));
                return;
            }
            showSourceChips(hits);
        }, e -> status.error(getString(R.string.error_backend), this::findOnSources));
    }

    private void showSourceChips(List<Series> hits) {
        ChipGroup group = findViewById(R.id.source_chips);
        group.removeAllViews();
        for (int i = 0; i < Math.min(hits.size(), 6); i++) {
            Series s = hits.get(i);
            Chip chip = new Chip(this);
            chip.setId(View.generateViewId());
            String count = s.chapterCount != null ? " · " + s.chapterCount + " CH" : "";
            String lang = s.language != null ? " · " + s.language : "";
            chip.setText(s.source + lang + " · " + s.title + count);
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            chip.setEnsureMinTouchTargetSize(false);
            int[][] states = {{android.R.attr.state_checked}, {}};
            chip.setChipBackgroundColor(new ColorStateList(states,
                    new int[]{Ui.withAlpha(typeColor, 0.2f), Ui.color(this, R.color.ink_800)}));
            chip.setChipStrokeColor(new ColorStateList(states, new int[]{typeColor, Ui.color(this, R.color.ink_600)}));
            chip.setChipStrokeWidth(Ui.dp(this, 1));
            chip.setTextColor(Ui.color(this, R.color.paper_200));
            chip.setOnCheckedChangeListener((c, checked) -> {
                if (checked) loadChapters(s.source, s.id, s.language);
            });
            group.addView(chip);
            if (i == 0) chip.setChecked(true);
        }
        findViewById(R.id.source_scroll).setVisibility(hits.size() > 1 ? View.VISIBLE : View.GONE);
    }

    private void loadChapters(String source, String seriesId, String langHint) {
        status.loading(R.string.loading_chapters);
        chapterSource = source;
        String lang = langHint != null && !langHint.isBlank() ? langHint : Session.readingLang(this);
        chapterLang = lang;
        int seq = ++requestSeq;
        adapter.submit(new ArrayList<>());
        Api.load(this, Api.get().chapters(source, seriesId, lang), all -> {
            if (seq != requestSeq) return;
            chapters.clear();
            chapters.addAll(all);
            int external = 0;
            for (Chapter c : all) if (c.isExternal()) external++;
            String asked = Session.readingLang(this);
            String where = source.toUpperCase(Locale.ROOT) + " · " + lang.toUpperCase(Locale.ROOT);
            String extra = !lang.equalsIgnoreCase(asked) ? " · RICHEST LIST" : "";
            chapterCount.setText(external > 0 ? all.size() + " ON " + where + " (" + external + " OFFICIAL LINKS)" + extra
                    : all.size() + " ON " + where + extra);
            if (chapters.isEmpty()) {
                status.empty(getString(R.string.empty_chapters_lang, lang.toUpperCase(Locale.ROOT)));
            } else {
                status.hide();
                adapter.submit(chapters);
                ((RecyclerView) findViewById(R.id.chapters)).scheduleLayoutAnimation();
            }
        }, e -> status.error(getString(R.string.error_backend), () -> loadChapters(source, seriesId, langHint)));
    }

    /** A series opened from a source gets a shelf when the catalog knows the same title. */
    private void attachCatalogMatch() {
        Api.load(this, Api.get().media(series.title, null), found -> {
            for (Media m : found) {
                if (m.title != null && m.title.equalsIgnoreCase(series.title) && !m.isAnime()) {
                    media = m;
                    findViewById(R.id.shelf_section).setVisibility(View.VISIBLE);
                    shelf.bind(this, m);
                    return;
                }
            }
        }, e -> {
        });
    }

    private void openChapter(int position) {
        if (position < 0 || position >= chapters.size()) return;
        Chapter c = chapters.get(position);
        if (media != null && media.isAnime()) {
            shelf.setProgress(position + 1);
            adapter.setReadUpTo(position + 1);
            return;
        }
        if (c.isExternal()) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(c.externalUrl)));
            return;
        }
        List<Chapter> readable = new ArrayList<>();
        int index = 0;
        for (Chapter ch : chapters) {
            if (ch.isExternal()) continue;
            if (ch == c) index = readable.size();
            readable.add(ch);
        }
        ReaderActivity.open(this, title, type, chapterSource,
                chapterLang != null ? chapterLang : Session.readingLang(this),
                media != null ? media.id : -1, readable, index);
    }

    // ---- header --------------------------------------------------------------------------------

    private void loadCover(ImageView cover, @Nullable String url) {
        TypographicCover fallback = new TypographicCover(this, title, type);
        if (url == null) {
            cover.setImageDrawable(fallback);
            startTransitionOnce();
            return;
        }
        Glide.with(this).load(url).centerCrop().error(fallback).dontAnimate()
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, @Nullable Object model,
                                                @NonNull Target<Drawable> target, boolean isFirstResource) {
                        startTransitionOnce();
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model,
                                                   Target<Drawable> target, @NonNull DataSource dataSource,
                                                   boolean isFirstResource) {
                        startTransitionOnce();
                        return false;
                    }
                })
                .into(cover);
    }

    private void startTransitionOnce() {
        if (transitionStarted) return;
        transitionStarted = true;
        supportStartPostponedEnterTransition();
    }

    private void animateIn(SpeedLinesView speed) {
        speed.postDelayed(speed::burst, 120);
        PathInterpolator emphasized = new PathInterpolator(0.2f, 0f, 0f, 1f);
        View body = findViewById(R.id.body);
        body.setAlpha(0f);
        body.setTranslationY(Ui.dp(this, 28));
        body.animate().alpha(1f).translationY(0f).setStartDelay(260).setDuration(460)
                .setInterpolator(emphasized).start();
    }

    /** Header layers move at different speeds: lines slowest, cover fastest — cheap depth. */
    private void parallax(NestedScrollView scroll, View speed, View cover, View banner) {
        scroll.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (v, x, y, ox, oy) -> {
            speed.setTranslationY(y * 0.55f);
            banner.setTranslationY(y * 0.4f);
            cover.setTranslationY(y * 0.18f);
            float s = Math.max(0.85f, 1f - y / 2400f);
            cover.setScaleX(s);
            cover.setScaleY(s);
        });
    }

    private void fillGenres(@Nullable List<String> genres) {
        ChipGroup group = findViewById(R.id.genres);
        if (genres == null) return;
        for (String g : genres) {
            Chip chip = new Chip(this);
            chip.setText(g);
            chip.setCheckable(false);
            chip.setClickable(false);
            chip.setEnsureMinTouchTargetSize(false);
            chip.setChipStrokeColor(ColorStateList.valueOf(Ui.withAlpha(accent, 0.55f)));
            chip.setChipBackgroundColor(ColorStateList.valueOf(Ui.withAlpha(accent, 0.10f)));
            chip.setTextColor(Ui.mix(accent, 0xFFFFFFFF, 0.45f));
            group.addView(chip);
        }
    }

    // ---- small adapters ------------------------------------------------------------------------

    static class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.H> {
        private final List<MediaDetail.GalleryImage> items;

        GalleryAdapter(List<MediaDetail.GalleryImage> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new H(LayoutInflater.from(p.getContext()).inflate(R.layout.item_gallery, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int i) {
            MediaDetail.GalleryImage g = items.get(i);
            Glide.with(h.still).load(g.url).centerCrop().into(h.still);
            String label = g.caption != null ? g.caption : g.kind;
            h.caption.setText("trailer".equals(g.kind) ? "▶ " + (label == null ? "Trailer" : label) : label);
            h.itemView.setOnClickListener(v -> {
                String target = g.video != null ? g.video : g.url;
                if (target != null) v.getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class H extends RecyclerView.ViewHolder {
            final ImageView still;
            final TextView caption;

            H(View v) {
                super(v);
                still = v.findViewById(R.id.still);
                caption = v.findViewById(R.id.caption);
            }
        }
    }

    static class CastAdapter extends RecyclerView.Adapter<CastAdapter.H> {
        private final List<MediaDetail.Character> items;
        private final int accent;

        CastAdapter(List<MediaDetail.Character> items, int accent) {
            this.items = items;
            this.accent = accent;
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new H(LayoutInflater.from(p.getContext()).inflate(R.layout.item_character, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int i) {
            MediaDetail.Character c = items.get(i);
            Glide.with(h.portrait).load(c.image).centerCrop().into(h.portrait);
            h.portrait.setStrokeColor(ColorStateList.valueOf("MAIN".equals(c.role) ? accent
                    : Ui.color(h.itemView.getContext(), R.color.ink_600)));
            h.name.setText(c.name);
            h.nativeName.setText(c.nativeName);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class H extends RecyclerView.ViewHolder {
            final com.google.android.material.imageview.ShapeableImageView portrait;
            final TextView name;
            final TextView nativeName;

            H(View v) {
                super(v);
                portrait = v.findViewById(R.id.portrait);
                name = v.findViewById(R.id.name);
                nativeName = v.findViewById(R.id.native_name);
            }
        }
    }
}
