package com.example.aniga.ui.discover;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.aniga.R;
import com.example.aniga.data.Api;
import com.example.aniga.data.Session;
import com.example.aniga.data.model.Media;
import com.example.aniga.data.model.SearchResults;
import com.example.aniga.data.model.Series;
import com.example.aniga.data.model.SourceView;
import com.example.aniga.ui.CoverAdapter;
import com.example.aniga.ui.CoverItem;
import com.example.aniga.ui.Ui;
import com.example.aniga.ui.widget.StatusView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

/**
 * Three ways to find something:
 *  TITLE — browse a reading source's popular list in your language, or search it by title
 *          ("Catalog" searches Aniga's own 900 AniList titles instead);
 *  STORY — describe it ("boy reborn as the villain in a novel"): BM25 over synopses, tags, characters;
 *  IMAGE — pick a screenshot or a cover: trace.moe finds the anime scene, dHash finds matching covers.
 */
public class DiscoverFragment extends Fragment {

    private static final String CATALOG = "catalog";

    private enum Mode { TITLE, STORY, IMAGE }

    private EditText query;
    private StatusView status;
    private RecyclerView grid;
    private TextView note;
    private TextView kanji;
    private MaterialButton langButton;
    private ChipGroup sourceChips;
    private View sourceRow;
    private final CoverAdapter adapter = new CoverAdapter(true);
    private final List<SourceView> sources = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Mode mode = Mode.TITLE;
    private String source = "mangadex";
    private int requestSeq;
    private ActivityResultLauncher<PickVisualMediaRequest> picker;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        picker = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) searchImage(uri);
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_discover, container, false);
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
        query = view.findViewById(R.id.query);
        status = view.findViewById(R.id.status);
        grid = view.findViewById(R.id.grid);
        note = view.findViewById(R.id.result_note);
        kanji = view.findViewById(R.id.kanji);
        langButton = view.findViewById(R.id.lang);
        sourceChips = view.findViewById(R.id.sources);
        sourceRow = view.findViewById(R.id.source_row);
        grid.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        grid.setAdapter(adapter);

        query.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                submit(v.getText().toString());
                return true;
            }
            return false;
        });
        MaterialButtonToggleGroup modes = view.findViewById(R.id.modes);
        modes.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            setMode(id == R.id.mode_story ? Mode.STORY : id == R.id.mode_image ? Mode.IMAGE : Mode.TITLE);
        });
        view.findViewById(R.id.field).setOnClickListener(v -> {
            if (mode == Mode.IMAGE) pickImage();
        });
        langButton.setText(Session.readingLang(requireContext()).toUpperCase(Locale.ROOT));
        langButton.setOnClickListener(v -> chooseLanguage());
        loadSources();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ---- modes ---------------------------------------------------------------------------------

    private void setMode(Mode next) {
        mode = next;
        String[] glyphs = {"探", "語", "絵"};
        kanji.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(120).withEndAction(() -> {
            kanji.setText(glyphs[next.ordinal()]);
            kanji.setRotation(-12f);
            kanji.animate().alpha(0.13f).scaleX(1f).scaleY(1f).rotation(0f).setDuration(380)
                    .setInterpolator(new OvershootInterpolator(2f)).start();
        }).start();
        query.setText("");
        note.setVisibility(View.GONE);
        boolean image = next == Mode.IMAGE;
        query.setFocusable(!image);
        query.setFocusableInTouchMode(!image);
        query.setInputType(image ? InputType.TYPE_NULL : InputType.TYPE_CLASS_TEXT);
        query.setOnClickListener(image ? v -> pickImage() : null);
        ((ImageView) requireView().findViewById(R.id.field_icon)).setImageResource(
                image ? R.drawable.ic_image : R.drawable.ic_nav_discover);
        query.setHint(next == Mode.TITLE ? R.string.discover_hint : next == Mode.STORY
                ? R.string.discover_hint_story : R.string.discover_hint_image);
        sourceRow.animate().alpha(next == Mode.TITLE ? 1f : 0.35f).setDuration(200).start();
        sourceRow.setEnabled(next == Mode.TITLE);
        if (next == Mode.TITLE) {
            showPopular();
        } else {
            adapter.submit(new ArrayList<>());
            status.empty(getString(next == Mode.STORY ? R.string.discover_idle_story : R.string.discover_idle_image));
        }
    }

    private void submit(String text) {
        String q = text.trim();
        hideKeyboard();
        if (mode == Mode.STORY) {
            if (!q.isEmpty()) searchStory(q);
        } else if (mode == Mode.TITLE) {
            if (q.isEmpty()) showPopular();
            else searchTitle(q);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = ContextCompat.getSystemService(requireContext(), InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
    }

    // ---- sources & language --------------------------------------------------------------------

    private void loadSources() {
        status.loading(R.string.loading_sources);
        Api.load(getViewLifecycleOwner(), Api.get().sources(), list -> {
            sources.clear();
            sources.addAll(list);
            buildSourceChips();
            showPopular();
        }, e -> status.error(getString(R.string.error_backend), this::loadSources));
    }

    private void buildSourceChips() {
        sourceChips.removeAllViews();
        addSourceChip(CATALOG, getString(R.string.source_catalog), null);
        for (SourceView s : sources) {
            String extra = s.catalogExtensions > 1 ? "  " + s.catalogExtensions : null;
            addSourceChip(s.info.code, s.info.displayName, extra);
        }
    }

    private void addSourceChip(String code, String name, @Nullable String extra) {
        Chip chip = new Chip(requireContext());
        chip.setId(View.generateViewId());
        chip.setText(extra == null ? name : name + extra);
        chip.setCheckable(true);
        chip.setCheckedIconVisible(false);
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChecked(code.equals(source));
        chip.setOnCheckedChangeListener((c, checked) -> {
            if (!checked) return;
            source = code;
            c.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100)
                    .withEndAction(() -> c.animate().scaleX(1f).scaleY(1f).setDuration(200).start()).start();
            String q = query.getText().toString().trim();
            if (q.isEmpty()) showPopular();
            else searchTitle(q);
        });
        sourceChips.addView(chip);
    }

    @Nullable
    private SourceView current() {
        for (SourceView s : sources) if (s.info.code.equals(source)) return s;
        return null;
    }

    private void chooseLanguage() {
        SourceView s = current();
        List<String> langs = new ArrayList<>();
        if (s != null && s.info.languages != null) langs.addAll(s.info.languages);
        if (langs.isEmpty()) langs.add("en");
        String[] items = langs.toArray(new String[0]);
        String cur = Session.readingLang(requireContext());
        int checked = Math.max(0, langs.indexOf(cur));
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.discover_language)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    Session.setReadingLang(requireContext(), items[which]);
                    langButton.setText(items[which].toUpperCase(Locale.ROOT));
                    d.dismiss();
                    showPopular();
                })
                .show();
    }

    // ---- TITLE ---------------------------------------------------------------------------------

    private void showPopular() {
        if (mode != Mode.TITLE) return;
        int seq = ++requestSeq;
        adapter.submit(new ArrayList<>());
        status.loading(R.string.loading_search);
        String lang = Session.readingLang(requireContext());
        if (CATALOG.equals(source)) {
            Api.load(getViewLifecycleOwner(), Api.get().browse(null, null, "popularity", 60, 0),
                    list -> showMedia(seq, list, getString(R.string.note_catalog)), e -> fail(seq, this::showPopular));
            return;
        }
        Api.load(getViewLifecycleOwner(), Api.get().popular(source, lang, 0), list -> showSeries(seq, list,
                getString(R.string.note_popular, nameOf(source), lang.toUpperCase(Locale.ROOT))), e -> fail(seq, this::showPopular));
    }

    private void searchTitle(String q) {
        int seq = ++requestSeq;
        adapter.submit(new ArrayList<>());
        status.loading(R.string.loading_search);
        if (CATALOG.equals(source)) {
            Api.load(getViewLifecycleOwner(), Api.get().media(q, null),
                    list -> showMedia(seq, list, null), e -> fail(seq, () -> searchTitle(q)));
            return;
        }
        String lang = Session.readingLang(requireContext());
        Api.load(getViewLifecycleOwner(), Api.get().searchSource(source, q, lang),
                list -> showSeries(seq, list, null), e -> fail(seq, () -> searchTitle(q)));
    }

    private String nameOf(String code) {
        SourceView s = current();
        return s != null && s.info.code.equals(code) ? s.info.displayName : code;
    }

    // ---- STORY ---------------------------------------------------------------------------------

    private void searchStory(String q) {
        int seq = ++requestSeq;
        adapter.submit(new ArrayList<>());
        status.loading(R.string.loading_story);
        Api.load(getViewLifecycleOwner(), Api.get().describe(q, null, 30), hits -> {
            if (seq != requestSeq) return;
            List<CoverItem> items = new ArrayList<>();
            for (SearchResults.Hit h : hits) {
                String badge = h.matched == null || h.matched.isEmpty() ? null
                        : String.join(" ", h.matched.subList(0, Math.min(2, h.matched.size())));
                items.add(CoverItem.of(h.media, badge));
            }
            show(seq, items, hits.isEmpty() ? null : getString(R.string.note_story, hits.size()));
        }, e -> fail(seq, () -> searchStory(q)));
    }

    // ---- IMAGE ---------------------------------------------------------------------------------

    private void pickImage() {
        picker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
    }

    /** Downscale to 1280 px JPEG off the main thread: phone screenshots are often 4-8 MB PNGs. */
    private void searchImage(Uri uri) {
        int seq = ++requestSeq;
        adapter.submit(new ArrayList<>());
        status.loading(R.string.loading_image);
        android.content.Context app = requireContext().getApplicationContext();
        io.execute(() -> {
            byte[] jpeg = null;
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                Bitmap b = BitmapFactory.decodeStream(in);
                if (b != null) {
                    float scale = Math.min(1f, 1280f / Math.max(b.getWidth(), b.getHeight()));
                    Bitmap s = scale < 1f ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale),
                            Math.round(b.getHeight() * scale), true) : b;
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    s.compress(Bitmap.CompressFormat.JPEG, 85, out);
                    jpeg = out.toByteArray();
                }
            } catch (Exception ignored) {
                // reported below as an unreadable image
            }
            byte[] bytes = jpeg;
            main.post(() -> {
                if (!isAdded() || seq != requestSeq) return;
                if (bytes == null) {
                    status.empty(getString(R.string.image_unreadable));
                    return;
                }
                RequestBody body = RequestBody.create(bytes, MediaType.parse("image/jpeg"));
                MultipartBody.Part part = MultipartBody.Part.createFormData("file", "query.jpg", body);
                Api.load(getViewLifecycleOwner(), Api.get().searchImage(part, true),
                        r -> showImageResults(seq, r), e -> fail(seq, () -> searchImage(uri)));
            });
        });
    }

    private void showImageResults(int seq, SearchResults.Image r) {
        if (seq != requestSeq) return;
        List<CoverItem> items = new ArrayList<>();
        List<Long> seen = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        if (r.scenes != null) {
            for (SearchResults.Scene s : r.scenes) {
                String ep = s.episode == null ? "" : "EP " + (int) Math.round(s.episode) + " · ";
                if (s.media != null) {
                    if (seen.contains(s.media.id)) continue;
                    seen.add(s.media.id);
                    items.add(CoverItem.of(s.media, ep + clock(s.from)));
                } else if (s.title != null) {
                    // Matched a frame we can identify but don't hold in the catalog — still show it.
                    items.add(CoverItem.ofScene(s.title, s.previewImage, ep + clock(s.from),
                            Math.round(s.similarity * 100) + "% scene"));
                } else {
                    continue;
                }
                if (text.length() == 0) {
                    text.append(getString(R.string.note_scene, s.title != null ? s.title : s.media.title,
                            ep + clock(s.from), Math.round(s.similarity * 100)));
                }
            }
        }
        if (r.covers != null) {
            for (SearchResults.Cover c : r.covers) {
                if (c.media == null || seen.contains(c.media.id)) continue;
                seen.add(c.media.id);
                items.add(CoverItem.of(c.media, Math.round(c.similarity * 100) + "% cover"));
            }
        }
        if (r.sceneError != null && text.length() == 0) text.append(getString(R.string.note_scene_error));
        show(seq, items, text.length() == 0 ? getString(R.string.note_covers, r.coversIndexed) : text.toString());
    }

    private static String clock(double seconds) {
        int s = (int) Math.round(seconds);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // ---- shared --------------------------------------------------------------------------------

    private void showSeries(int seq, List<Series> list, @Nullable String noteText) {
        List<CoverItem> items = new ArrayList<>();
        for (Series s : list) items.add(CoverItem.of(s));
        show(seq, items, noteText);
    }

    private void showMedia(int seq, List<Media> list, @Nullable String noteText) {
        List<CoverItem> items = new ArrayList<>();
        for (Media m : list) items.add(CoverItem.of(m));
        show(seq, items, noteText);
    }

    private void show(int seq, List<CoverItem> items, @Nullable String noteText) {
        if (seq != requestSeq || !isAdded()) return;
        if (noteText != null) {
            note.setText(noteText);
            note.setVisibility(View.VISIBLE);
            note.setAlpha(0f);
            note.animate().alpha(1f).setDuration(260).start();
        } else {
            note.setVisibility(View.GONE);
        }
        if (items.isEmpty()) {
            status.empty(getString(R.string.empty_search));
            return;
        }
        status.hide();
        adapter.submit(items);
        grid.scheduleLayoutAnimation();
    }

    private void fail(int seq, Runnable retry) {
        if (seq == requestSeq && isAdded()) status.error(getString(R.string.error_backend), retry);
    }
}
