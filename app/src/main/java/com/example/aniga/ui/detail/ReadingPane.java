package com.example.aniga.ui.detail;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.aniga.R;
import com.example.aniga.data.Api;
import com.example.aniga.data.Session;
import com.example.aniga.data.Trace;
import com.example.aniga.data.model.Chapter;
import com.example.aniga.data.model.LibraryState;
import com.example.aniga.data.model.Media;
import com.example.aniga.data.model.MediaDetail;
import com.example.aniga.data.model.Series;
import com.example.aniga.ui.Ui;
import com.example.aniga.ui.reader.ReaderActivity;
import com.example.aniga.ui.shelf.ShelfPanel;
import com.example.aniga.ui.widget.StatusView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The chapter panel: which source, which chapter, and the box that scrolls on its own.
 * The detail screen keeps the cover, the story, and the score.
 */
public final class ReadingPane {

    public interface Host {
        Media media();

        String title();

        String type();

        int typeColor();

        ShelfPanel shelf();

        @Nullable String romaji();

        /** True once, the first time chapters are ready and the opener asked to continue. */
        boolean takeContinue();
    }

    private static final int PAGE = 40;

    private final DetailActivity activity;
    private final Host host;
    private final List<Chapter> chapters = new ArrayList<>();
    private ChapterAdapter adapter;
    private TextView chapterCount;
    private StatusView status;
    private View readControls;
    private TextView chaptersMore;
    private String source;
    private String seriesId;
    private String lang;
    private int requestSeq;
    private List<Series> hits = new ArrayList<>();
    private int pick;
    private int limit = PAGE;
    private boolean pinned;

    public ReadingPane(DetailActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    public void attach() {
        chapterCount = activity.findViewById(R.id.chapter_count);
        status = activity.findViewById(R.id.status);
        status.setBallColor(host.typeColor());
        RecyclerView list = activity.findViewById(R.id.chapters);
        list.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new ChapterAdapter(host.typeColor(), this::open);
        list.setAdapter(adapter);
        list.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
        readControls = activity.findViewById(R.id.read_controls);
        chaptersMore = activity.findViewById(R.id.chapters_more);
        activity.findViewById(R.id.read_start).setOnClickListener(v -> openFirst());
        activity.findViewById(R.id.read_latest).setOnClickListener(v -> openLast());
        activity.findViewById(R.id.read_jump).setOnClickListener(v -> showJump());
        chaptersMore.setOnClickListener(v -> {
            limit += PAGE;
            showWindow();
        });
    }

    public boolean isEmpty() {
        return chapters.isEmpty();
    }

    public String source() {
        return source;
    }

    public String seriesId() {
        return seriesId;
    }

    public void markReadUpTo(int progress) {
        adapter.setReadUpTo(progress);
    }

    public void find() {
        status.loading(R.string.loading_chapters);
        String asked = Session.readingLang(activity);
        int seq = ++requestSeq;
        Api.load(activity, Api.get().find(host.title(), asked, aliases(), host.type()), found -> {
            if (seq != requestSeq) return;
            if (found.isEmpty()) {
                Trace.event("find", host.title() + " -> 0 sources");
                chapterCount.setText("");
                status.empty(activity.getString(R.string.empty_chapters_lang, asked.toUpperCase(Locale.ROOT)));
                return;
            }
            Trace.event("find", host.title() + " -> " + found.size() + " sources");
            showChips(found);
        }, e -> {
            Trace.fail("find", e);
            status.error(activity.getString(R.string.error_backend), this::find);
        });
    }

    public void load(String source, String seriesId, String langHint) {
        status.loading(R.string.loading_chapters);
        this.source = source;
        this.seriesId = seriesId;
        lang = langHint != null && !langHint.isBlank() ? langHint : Session.readingLang(activity);
        int seq = ++requestSeq;
        adapter.submit(new ArrayList<>());
        Api.load(activity, Api.get().chapters(source, seriesId, lang), all -> {
            if (seq != requestSeq) return;
            chapters.clear();
            chapters.addAll(all);
            int external = 0;
            int hosted = 0;
            for (Chapter c : all) {
                if (c.isExternal()) external++;
                else hosted++;
            }
            Trace.event("chapters", source + " " + lang + " -> " + all.size()
                    + " (" + hosted + " with pages, " + external + " links)");
            if (hosted == 0 && external > 0 && pick + 1 < Math.min(hits.size(), 12)) {
                Trace.event("chapters", source + " has no pages, trying the next source");
                pick++;
                ChipGroup group = activity.findViewById(R.id.source_chips);
                if (pick < group.getChildCount()) {
                    for (int i = 0; i < group.getChildCount(); i++) {
                        ((Chip) group.getChildAt(i)).setChecked(i == pick);
                    }
                }
                return;
            }
            String asked = Session.readingLang(activity);
            String where = source.toUpperCase(Locale.ROOT) + " · " + lang.toUpperCase(Locale.ROOT);
            String extra = !lang.equalsIgnoreCase(asked) ? " · RICHEST LIST" : "";
            chapterCount.setText(external > 0
                    ? all.size() + " ON " + where + " (" + external + " OFFICIAL LINKS)" + extra
                    : all.size() + " ON " + where + extra);
            if (chapters.isEmpty()) {
                readControls.setVisibility(View.GONE);
                chaptersMore.setVisibility(View.GONE);
                status.empty(activity.getString(R.string.empty_chapters_lang, lang.toUpperCase(Locale.ROOT)));
            } else {
                status.hide();
                limit = PAGE;
                showWindow();
                boolean anyReadable = false;
                for (Chapter c : chapters) {
                    if (!c.isExternal()) {
                        anyReadable = true;
                        break;
                    }
                }
                readControls.setVisibility(anyReadable ? View.VISIBLE : View.GONE);
                if (anyReadable && host.takeContinue()) continueReading();
            }
        }, e -> {
            Trace.fail("chapters", e);
            status.error(activity.getString(R.string.error_backend), () -> load(source, seriesId, langHint));
        });
    }

    /** Anime have no pages; each row is "watched up to episode N" and sets shelf progress. */
    public void showEpisodes(Media m, @Nullable List<MediaDetail.GalleryImage> gallery) {
        ((TextView) activity.findViewById(R.id.chapters_title)).setText(R.string.detail_episodes);
        Integer total = m.total();
        chapters.clear();
        if (total == null || total == 0) {
            chapterCount.setText("");
            status.empty(activity.getString(R.string.anime_note));
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
            c.group = activity.getString(R.string.detail_episode_hint);
            c.pages = -1;
            chapters.add(c);
        }
        status.hide();
        chapterCount.setText(total + " EP  ·  " + activity.getString(R.string.anime_note_short));
        adapter.setEpisodes(true);
        adapter.submit(chapters);
    }

    private void showChips(List<Series> found) {
        hits = found;
        pick = 0;
        ChipGroup group = activity.findViewById(R.id.source_chips);
        group.removeAllViews();
        for (int i = 0; i < Math.min(found.size(), 12); i++) {
            Series s = found.get(i);
            Chip chip = new Chip(activity);
            chip.setId(View.generateViewId());
            String count = s.chapterCount != null ? s.chapterCount + "  " : "";
            String chipLang = s.language != null ? s.language.toUpperCase(Locale.ROOT) + "  " : "";
            chip.setText(count + chipLang + s.source);
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            chip.setEnsureMinTouchTargetSize(false);
            int[][] states = {{android.R.attr.state_checked}, {}};
            chip.setChipBackgroundColor(new ColorStateList(states,
                    new int[]{Ui.withAlpha(host.typeColor(), 0.2f), Ui.color(activity, R.color.ink_800)}));
            chip.setChipStrokeColor(new ColorStateList(states,
                    new int[]{host.typeColor(), Ui.color(activity, R.color.ink_600)}));
            chip.setChipStrokeWidth(Ui.dp(activity, 1));
            chip.setTextColor(Ui.color(activity, R.color.paper_200));
            chip.setOnCheckedChangeListener((c, checked) -> {
                if (checked) load(s.source, s.id, s.language);
            });
            group.addView(chip);
            if (i == 0) chip.setChecked(true);
        }
        activity.findViewById(R.id.source_scroll).setVisibility(found.size() > 1 ? View.VISIBLE : View.GONE);
    }

    private String aliases() {
        StringBuilder b = new StringBuilder();
        Media media = host.media();
        if (media != null && media.nativeTitle != null && !media.nativeTitle.isBlank()) {
            b.append(media.nativeTitle);
        }
        String romaji = host.romaji();
        if (romaji != null && !romaji.isBlank()
                && (media == null || !romaji.equalsIgnoreCase(media.nativeTitle))) {
            if (b.length() > 0) b.append('|');
            b.append(romaji);
        }
        return b.length() == 0 ? null : b.toString();
    }

    private void open(int position) {
        if (position < 0 || position >= chapters.size()) return;
        Chapter c = chapters.get(position);
        Media media = host.media();
        if (media != null && media.isAnime()) {
            host.shelf().setProgress(position + 1);
            adapter.setReadUpTo(position + 1);
            return;
        }
        if (c.isExternal()) {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(c.externalUrl)));
            return;
        }
        pin();
        List<Chapter> readable = new ArrayList<>();
        int index = 0;
        for (Chapter ch : chapters) {
            if (ch.isExternal()) continue;
            if (ch == c) index = readable.size();
            readable.add(ch);
        }
        ReaderActivity.open(activity, host.title(), host.type(), source,
                lang != null ? lang : Session.readingLang(activity),
                media != null ? media.id : -1, readable, index);
    }

    private void showWindow() {
        int shown = Math.min(limit, chapters.size());
        adapter.submit(new ArrayList<>(chapters.subList(0, shown)));
        ((RecyclerView) activity.findViewById(R.id.chapters)).scheduleLayoutAnimation();
        int remaining = chapters.size() - shown;
        if (remaining > 0) {
            chaptersMore.setVisibility(View.VISIBLE);
            chaptersMore.setText(remaining <= PAGE
                    ? activity.getString(R.string.detail_chapters_all, chapters.size())
                    : activity.getString(R.string.detail_chapters_more, PAGE));
        } else {
            chaptersMore.setVisibility(View.GONE);
        }
    }

    private void continueReading() {
        Media media = host.media();
        if (media == null || media.id <= 0) {
            openFirst();
            return;
        }
        Api.load(activity, Api.get().libraryEntry(Session.userId(activity), media.id), st -> {
            int progress = st != null ? st.progress : 0;
            for (int i = 0; i < chapters.size(); i++) {
                Chapter c = chapters.get(i);
                if (!c.isExternal() && ChapterAdapter.number(c) > progress) {
                    open(i);
                    return;
                }
            }
            openFirst();
        }, e -> openFirst());
    }

    private void openFirst() {
        for (int i = 0; i < chapters.size(); i++) {
            if (!chapters.get(i).isExternal()) {
                open(i);
                return;
            }
        }
    }

    private void openLast() {
        for (int i = chapters.size() - 1; i >= 0; i--) {
            if (!chapters.get(i).isExternal()) {
                open(i);
                return;
            }
        }
    }

    private void showJump() {
        final android.widget.EditText input = new android.widget.EditText(activity);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(R.string.detail_jump_hint);
        int pad = (int) Ui.dp(activity, 20);
        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(R.string.detail_jump_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> jump(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .show();
        input.setPadding(pad, pad / 2, pad, 0);
    }

    private void jump(String raw) {
        if (raw == null || raw.isEmpty()) return;
        double want;
        try {
            want = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return;
        }
        for (int i = 0; i < chapters.size(); i++) {
            if (Math.abs(ChapterAdapter.number(chapters.get(i)) - want) < 0.001) {
                open(i);
                return;
            }
        }
        android.widget.Toast.makeText(activity, activity.getString(R.string.detail_jump_missing, raw),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Puts the title on the shelf as CURRENT and pins the source, so Home can continue and updates can follow it. */
    private void pin() {
        Media media = host.media();
        if (media == null || media.id <= 0 || pinned) return;
        pinned = true;
        double known = 0;
        for (Chapter c : chapters) {
            double n = ChapterAdapter.number(c);
            if (n > known) known = n;
        }
        LibraryState.Update update = new LibraryState.Update("CURRENT", null, null)
                .source(source, seriesId, host.title(),
                        lang != null ? lang : Session.readingLang(activity), known > 0 ? known : null);
        Api.load(activity, Api.get().updateLibrary(Session.userId(activity), media.id, update), s -> {
            com.example.aniga.data.Progress.put(s);
            android.widget.Toast.makeText(activity,
                    activity.getString(R.string.detail_added_library, source.toUpperCase(Locale.ROOT)),
                    android.widget.Toast.LENGTH_SHORT).show();
        }, e -> {
        });
    }

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
}
