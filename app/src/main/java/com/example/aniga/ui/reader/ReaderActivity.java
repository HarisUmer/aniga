package com.example.aniga.ui.reader;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.aniga.R;
import com.example.aniga.data.Api;
import com.example.aniga.data.Session;
import com.example.aniga.data.model.Chapter;
import com.example.aniga.ui.Ui;
import com.example.aniga.ui.widget.PlaneProgressView;
import com.example.aniga.ui.widget.StatusView;
import com.google.android.material.button.MaterialButton;

import java.util.List;

/**
 * Vertical-first reader. Pages stack in one RecyclerView (webtoons get zero gap so the strip is
 * continuous; manga pages get a thin gutter). Chrome hides while scrolling forward and on tap.
 */
public class ReaderActivity extends AppCompatActivity {

    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_IDS = "ids";
    private static final String EXTRA_LABELS = "labels";
    private static final String EXTRA_INDEX = "index";
    private static final String EXTRA_SOURCE = "source";
    private static final String EXTRA_LANG = "lang";
    private static final String EXTRA_MEDIA_ID = "media_id";

    private String[] ids;
    private String[] labels;
    private int index;
    private String source;
    private String lang;
    private long mediaId;
    private int lastSavedProgress = -1;
    private ReaderTheme theme;
    private PageAdapter adapter;
    private RecyclerView pages;
    private LinearLayoutManager layout;
    private StatusView status;
    private View topChrome;
    private View bottomChrome;
    private LinearLayout themePanel;
    private PlaneProgressView progress;
    private TextView pageIndicator;
    private boolean chromeVisible = true;
    private WindowInsetsControllerCompat insetsController;
    private final PathInterpolator emphasized = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /** mediaId < 0 when the series is not in the catalog (then progress is not saved). */
    public static void open(Context from, String title, String type, String source, String lang, long mediaId,
                            List<Chapter> chapters, int index) {
        String[] ids = new String[chapters.size()];
        String[] labels = new String[chapters.size()];
        for (int i = 0; i < chapters.size(); i++) {
            ids[i] = chapters.get(i).id;
            labels[i] = chapters.get(i).label();
        }
        from.startActivity(new Intent(from, ReaderActivity.class)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_LANG, lang)
                .putExtra(EXTRA_MEDIA_ID, mediaId)
                .putExtra(EXTRA_IDS, ids)
                .putExtra(EXTRA_LABELS, labels)
                .putExtra(EXTRA_INDEX, index));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_reader);
        Intent in = getIntent();
        ids = in.getStringArrayExtra(EXTRA_IDS);
        labels = in.getStringArrayExtra(EXTRA_LABELS);
        index = savedInstanceState != null ? savedInstanceState.getInt(EXTRA_INDEX) : in.getIntExtra(EXTRA_INDEX, 0);
        String type = in.getStringExtra(EXTRA_TYPE);
        source = in.getStringExtra(EXTRA_SOURCE);
        lang = in.getStringExtra(EXTRA_LANG);
        mediaId = in.getLongExtra(EXTRA_MEDIA_ID, -1);
        theme = ReaderTheme.from(Session.readerTheme(this));
        insetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        topChrome = findViewById(R.id.top_chrome);
        bottomChrome = findViewById(R.id.bottom_chrome);
        themePanel = findViewById(R.id.theme_panel);
        progress = findViewById(R.id.progress);
        pageIndicator = findViewById(R.id.page_indicator);
        status = findViewById(R.id.status);
        ((TextView) findViewById(R.id.reader_title)).setText(in.getStringExtra(EXTRA_TITLE));

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.reader_root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            topChrome.setPadding(bars.left, bars.top, bars.right, 0);
            bottomChrome.setPadding(bars.left + (int) Ui.dp(this, 8), (int) Ui.dp(this, 6),
                    bars.right + (int) Ui.dp(this, 8), bars.bottom + (int) Ui.dp(this, 6));
            return insets;
        });

        boolean webtoon = "MANHWA".equals(type) || "MANHUA".equals(type);
        int gap = webtoon ? 0 : (int) Ui.dp(this, 6);
        pages = findViewById(R.id.pages);
        layout = new LinearLayoutManager(this);
        pages.setLayoutManager(layout);
        adapter = new PageAdapter(theme, gap, () -> go(index + 1), this::chapterFinished);
        pages.setAdapter(adapter);
        pages.setItemAnimator(null);
        pages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                updateProgress();
                if (dy > 14 && chromeVisible && themePanel.getVisibility() != View.VISIBLE) setChrome(false);
            }
        });
        GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                setChrome(!chromeVisible);
                return false;
            }
        });
        pages.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                taps.onTouchEvent(e);
                return false;
            }
        });

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.theme_toggle).setOnClickListener(v -> toggleThemePanel());
        findViewById(R.id.prev).setOnClickListener(v -> go(index - 1));
        findViewById(R.id.next).setOnClickListener(v -> go(index + 1));
        buildThemePanel();
        applyTheme();
        loadChapter();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(EXTRA_INDEX, index);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving the reader (back, home, app switch) banks the chapter you were on, not only the end card.
        chapterFinished();
    }

    private void go(int next) {
        if (next < 0 || next >= ids.length) return;
        index = next;
        loadChapter();
    }

    private void loadChapter() {
        ((TextView) findViewById(R.id.reader_chapter)).setText("Chapter " + labels[index] + "  ·  " + (index + 1) + " / " + ids.length);
        findViewById(R.id.prev).setEnabled(index > 0);
        findViewById(R.id.next).setEnabled(index < ids.length - 1);
        adapter.submit(java.util.Collections.emptyList(), null, null, null, labels[index], false);
        progress.setProgress(0f);
        pageIndicator.setText("");
        status.loading(R.string.loading_pages);
        String chapterId = ids[index];
        Api.load(this, Api.get().pages(source, chapterId, lang), result -> {
            if (!chapterId.equals(ids[index])) return;
            status.hide();
            adapter.submit(result.urls, result.fallbackUrls, result.captions, result.credit, labels[index],
                    index < ids.length - 1);
            layout.scrollToPositionWithOffset(0, 0);
            pages.post(this::updateProgress);
            // The backend records the parse outcome for this chapter on the pages request itself.
        }, e -> status.error(getString(R.string.error_backend), this::loadChapter, this::sendReport));
    }

    private void sendReport() {
        com.example.aniga.data.model.SourceReport body =
                new com.example.aniga.data.model.SourceReport(source, ids[index], "pages failed to load");
        Api.load(this, Api.get().report(body), posted ->
                android.widget.Toast.makeText(this, R.string.report_sent, android.widget.Toast.LENGTH_SHORT).show(),
                err -> android.widget.Toast.makeText(this, R.string.report_failed, android.widget.Toast.LENGTH_SHORT).show());
    }

    /**
     * The end card scrolled into view: this chapter counts as read. Progress is the chapter number
     * (floored, so "12.5" counts as 12) because the shelf counts chapters, not list positions.
     * Never moves progress backwards when re-reading an older chapter.
     */
    private void chapterFinished() {
        if (mediaId < 0) return;
        int n;
        try {
            n = (int) Math.floor(Double.parseDouble(labels[index]));
        } catch (NumberFormatException e) {
            n = index + 1;
        }
        if (n <= lastSavedProgress) return;
        final int value = n;
        lastSavedProgress = value;
        Api.load(this, Api.get().libraryEntry(Session.userId(this), mediaId), current -> {
            if (current.progress >= value) return;
            com.example.aniga.ui.shelf.ShelfPanel.markProgress(this, this, mediaId, value);
            android.widget.Toast.makeText(this, getString(R.string.reader_saved, labels[index]),
                    android.widget.Toast.LENGTH_SHORT).show();
        }, e -> {
        });
    }

    private void updateProgress() {
        int count = adapter.pageCount();
        if (count == 0) return;
        int first = Math.max(0, layout.findFirstVisibleItemPosition());
        int range = pages.computeVerticalScrollRange() - pages.computeVerticalScrollExtent();
        float fraction = range > 0 ? (float) pages.computeVerticalScrollOffset() / range : 0f;
        progress.setProgress(fraction);
        pageIndicator.setText(Math.min(first + 1, count) + " / " + count);
    }

    private void setChrome(boolean visible) {
        chromeVisible = visible;
        float topShift = visible ? 0 : -(topChrome.getHeight() - progress.getHeight());
        topChrome.animate().translationY(topShift).setDuration(280).setInterpolator(emphasized).start();
        bottomChrome.animate().translationY(visible ? 0 : bottomChrome.getHeight())
                .alpha(visible ? 1f : 0f).setDuration(280).setInterpolator(emphasized).start();
        if (visible) insetsController.show(WindowInsetsCompat.Type.systemBars());
        else insetsController.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void toggleThemePanel() {
        boolean show = themePanel.getVisibility() != View.VISIBLE;
        themePanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            themePanel.setAlpha(0f);
            themePanel.setTranslationY(-Ui.dp(this, 8));
            themePanel.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(emphasized).start();
        }
    }

    private void buildThemePanel() {
        themePanel.removeAllViews();
        for (ReaderTheme t : ReaderTheme.values()) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = (int) Ui.dp(this, 6);
            item.setPadding(pad, pad, pad, pad);
            item.setTag(t);

            TextView swatch = new TextView(this);
            swatch.setGravity(Gravity.CENTER);
            swatch.setText("Aa");
            swatch.setTypeface(Ui.display(this));
            swatch.setTextSize(15);
            swatch.setTextColor(t.text(this));
            int size = (int) Ui.dp(this, 44);
            item.addView(swatch, new LinearLayout.LayoutParams(size, size));

            TextView label = new TextView(this);
            label.setTextAppearance(R.style.TextAppearance_Aniga_Meta);
            label.setTextSize(10);
            label.setText(t.label);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = (int) Ui.dp(this, 6);
            item.addView(label, lp);

            item.setOnClickListener(v -> {
                theme = t;
                Session.setReaderTheme(this, t.name());
                applyTheme();
                adapter.setTheme(t);
            });
            themePanel.addView(item, new LinearLayout.LayoutParams(0, -2, 1f));
        }
    }

    private void applyTheme() {
        int bg = theme.bg(this);
        int chrome = theme.chrome(this);
        int text = theme.text(this);
        int muted = theme.muted(this);
        findViewById(R.id.reader_root).setBackgroundColor(bg);
        getWindow().getDecorView().setBackgroundColor(bg);
        topChrome.setBackgroundColor(chrome);
        bottomChrome.setBackgroundColor(chrome);
        ((TextView) findViewById(R.id.reader_title)).setTextColor(text);
        ((TextView) findViewById(R.id.reader_chapter)).setTextColor(muted);
        pageIndicator.setTextColor(muted);
        ColorStateList tint = ColorStateList.valueOf(text);
        ((ImageButton) findViewById(R.id.back)).setImageTintList(tint);
        ((ImageButton) findViewById(R.id.theme_toggle)).setImageTintList(tint);
        for (int id : new int[]{R.id.prev, R.id.next}) {
            MaterialButton b = findViewById(id);
            b.setTextColor(tint);
            b.setIconTint(tint);
        }
        progress.setColors(muted, theme.accent, text);
        insetsController.setAppearanceLightStatusBars(theme.light);
        insetsController.setAppearanceLightNavigationBars(theme.light);

        for (int i = 0; i < themePanel.getChildCount(); i++) {
            LinearLayout item = (LinearLayout) themePanel.getChildAt(i);
            ReaderTheme t = (ReaderTheme) item.getTag();
            GradientDrawable sw = new GradientDrawable();
            sw.setShape(GradientDrawable.OVAL);
            sw.setColor(t.bg(this));
            boolean selected = t == theme;
            sw.setStroke((int) Ui.dp(this, selected ? 3 : 1), selected ? theme.accent : muted);
            item.getChildAt(0).setBackground(sw);
            TextView label = (TextView) item.getChildAt(1);
            label.setTextColor(selected ? text : muted);
        }
    }
}
