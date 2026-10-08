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
public class DetailActivity extends AppCompatActivity implements ReadingPane.Host {

    private static final String EXTRA_MEDIA = "media";
    private static final String EXTRA_SERIES = "series";
    private static final String EXTRA_CONTINUE = "continue";
    private static final Gson GSON = new Gson();

    private Media media;
    private Series series;
    private String title;
    private String type;
    private int accent;
    private int typeColor;
    private ShelfPanel shelf;
    private ReadingPane reading;
    private boolean transitionStarted;
    private String romajiTitle;
    private boolean continueRequested;

    public static void open(Activity from, Media m, @Nullable View cover) {
        launch(from, new Intent(from, DetailActivity.class).putExtra(EXTRA_MEDIA, GSON.toJson(m)), cover);
    }

    /** Opens the detail and, once chapters load, jumps straight to the first chapter past your progress. */
    public static void openContinue(Activity from, Media m, @Nullable View cover) {
        launch(from, new Intent(from, DetailActivity.class)
                .putExtra(EXTRA_MEDIA, GSON.toJson(m)).putExtra(EXTRA_CONTINUE, true), cover);
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
        continueRequested = in.getBooleanExtra(EXTRA_CONTINUE, false);
        title = media != null ? media.title : series.title;
        type = media != null ? media.mediaType : series.contentType;
        accent = Ui.tame(Ui.accent(this, media != null ? media.accentColor : null, type));
        typeColor = Ui.typeColor(this, type);
        int ink = Ui.color(this, R.color.ink_900);

        View back = findViewById(R.id.back);
        back.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.share).setOnClickListener(v -> shareToChat());
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

        shelf = findViewById(R.id.shelf);
        shelf.setOnChange(this::onShelf);
        reading = new ReadingPane(this, this);
        reading.attach();

        animateIn(speed);
        parallax(findViewById(R.id.scroll), speed, cover, banner);

        if (media != null) {
            bindCatalog(media);
        } else {
            reading.load(series.source, series.id, series.language);
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
            reading.showEpisodes(m, null);
        } else {
            reading.find();
        }
    }

    private void showDetail(MediaDetail d) {
        media = d.media != null ? d.media : media;
        // A series opened from a reading source often has a thin description and no cast; borrow the
        // catalog's synopsis, genres and characters when the source gave us little.
        if (media.synopsis != null && !media.synopsis.isEmpty()) {
            TextView synopsisView = findViewById(R.id.synopsis);
            CharSequence shown = synopsisView.getText();
            if (shown == null || shown.length() == 0 || getString(R.string.detail_no_synopsis).contentEquals(shown)) {
                synopsisView.setText(media.synopsis);
            }
        }
        ChipGroup genreGroup = findViewById(R.id.genres);
        if (genreGroup.getChildCount() == 0) {
            fillGenres(media.genresOrEmpty());
        }
        List<String> facts = new ArrayList<>();
        if (media.nativeTitle != null) facts.add(media.nativeTitle);
        if (d.romajiTitle != null && !d.romajiTitle.equalsIgnoreCase(media.title)) {
            facts.add(d.romajiTitle);
            romajiTitle = d.romajiTitle;
        }
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
        if (media.isAnime()) reading.showEpisodes(media, gallery);
        else if (reading.isEmpty() && romajiTitle != null) reading.find();
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

    private void onShelf(LibraryState s) {
        reading.markReadUpTo(s.progress);
    }

    /** A series opened from a source gets a shelf when the catalog knows the same title. */
    private void attachCatalogMatch() {
        Api.load(this, Api.get().media(series.title, null), found -> {
            for (Media m : found) {
                if (m.title != null && m.title.equalsIgnoreCase(series.title) && !m.isAnime()) {
                    media = m;
                    findViewById(R.id.shelf_section).setVisibility(View.VISIBLE);
                    shelf.bind(this, m);
                    Api.load(this, Api.get().detail(m.id), this::showDetail, e2 -> {
                    });
                    Api.load(this, Api.get().similar(m.id, 12), this::showSimilar, e2 -> {
                    });
                    return;
                }
            }
        }, e -> {
        });
    }

    // ---- share ---------------------------------------------------------------------------------

    private void shareToChat() {
        Api.load(this, Api.get().friends(), friends -> {
            if (friends == null || friends.isEmpty()) {
                android.widget.Toast.makeText(this, R.string.detail_share_none,
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            CharSequence[] labels = new CharSequence[friends.size()];
            for (int i = 0; i < friends.size(); i++) labels[i] = friends.get(i).name();
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.detail_share_pick)
                    .setItems(labels, (d, which) -> {
                        com.example.aniga.data.model.Person friend = friends.get(which);
                        java.util.Map<String, String> who = new java.util.HashMap<>();
                        who.put("username", friend.username);
                        Api.load(this, Api.get().openDirect(who), this::postShare,
                                e -> android.widget.Toast.makeText(this, R.string.error_backend,
                                        android.widget.Toast.LENGTH_SHORT).show());
                    })
                    .show();
        }, e -> android.widget.Toast.makeText(this, R.string.error_backend,
                android.widget.Toast.LENGTH_SHORT).show());
    }

    private void postShare(com.example.aniga.data.model.ChatRoom room) {
        java.util.Map<String, Object> share = new java.util.HashMap<>();
        com.example.aniga.data.Progress.State progress = media != null
                ? com.example.aniga.data.Progress.get(media.id) : null;
        // Share as a rating card if the title is scored, otherwise as "currently reading".
        if (progress != null && "COMPLETED".equals(progress.status)) {
            share.put("kind", "rating");
        } else {
            share.put("kind", "reading");
        }
        if (media != null && media.id > 0) share.put("mediaId", media.id);
        share.put("source", reading.source());
        share.put("seriesId", reading.seriesId());
        share.put("title", title);
        share.put("coverUrl", media != null ? media.coverUrl : (series != null ? series.coverUrl : null));
        share.put("mediaType", type);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("body", "");
        body.put("share", share);
        Api.load(this, Api.get().sendShare(room.id, body), m ->
                        android.widget.Toast.makeText(this, getString(R.string.detail_share_sent, room.label()),
                                android.widget.Toast.LENGTH_SHORT).show(),
                e -> android.widget.Toast.makeText(this, R.string.error_backend,
                        android.widget.Toast.LENGTH_SHORT).show());
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

    @Override public Media media() { return media; }

    @Override public String title() { return title; }

    @Override public String type() { return type; }

    @Override public int typeColor() { return typeColor; }

    @Override public ShelfPanel shelf() { return shelf; }

    @Override public String romaji() { return romajiTitle; }

    @Override public boolean takeContinue() {
        boolean go = continueRequested;
        continueRequested = false;
        return go;
    }
}
