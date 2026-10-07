package com.example.aniga.ui.shelf;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;

import com.example.aniga.R;
import com.example.aniga.data.Api;
import com.example.aniga.data.Progress;
import com.example.aniga.data.Session;
import com.example.aniga.data.model.LibraryState;
import com.example.aniga.data.model.Media;
import com.example.aniga.ui.Ui;
import com.example.aniga.ui.widget.HankoRatingView;
import com.example.aniga.ui.widget.PlaneProgressView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Shelf editor for one title: status, episodes watched / chapters read, and a 1-10 score.
 * Every change is saved immediately (PUT /api/users/{id}/library/{mediaId}); the stepper is debounced
 * by 450 ms so tapping "+" five times is one request. The server is the source of truth: when it
 * auto-completes (progress == total) the chips update from its reply.
 */
public class ShelfPanel extends LinearLayout {

    private static final String[] STATUSES = {"PLANNING", "CURRENT", "COMPLETED", "PAUSED", "DROPPED"};

    private final ChipGroup chips;
    private final TextView progressLabel;
    private final PlaneProgressView plane;
    private final HankoRatingView rating;
    private final TextView ratingLabel;
    private final View progressRow;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LifecycleOwner owner;
    private Media media;
    private LibraryState state = new LibraryState();
    private Integer pendingProgress;
    private Consumer<LibraryState> onChange;
    private boolean binding;

    public ShelfPanel(Context context) {
        this(context, null);
    }

    public ShelfPanel(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        int pad = (int) Ui.dp(context, 2);
        setPadding(0, pad, 0, pad);

        addView(kicker(context, R.string.shelf_status));
        chips = new ChipGroup(context);
        chips.setSingleSelection(true);
        chips.setSelectionRequired(false);
        addView(chips, new LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton minus = stepper(context, "−");
        MaterialButton plus = stepper(context, "+");
        plane = new PlaneProgressView(context);
        progressLabel = new TextView(context);
        progressLabel.setTextAppearance(R.style.TextAppearance_Aniga_Meta);
        progressLabel.setTextColor(Ui.color(context, R.color.paper_200));
        progressLabel.setGravity(Gravity.END);
        row.addView(minus, new LayoutParams((int) Ui.dp(context, 44), (int) Ui.dp(context, 44)));
        LayoutParams planeLp = new LayoutParams(0, (int) Ui.dp(context, 26), 1f);
        planeLp.setMargins((int) Ui.dp(context, 8), 0, (int) Ui.dp(context, 8), 0);
        row.addView(plane, planeLp);
        row.addView(plus, new LayoutParams((int) Ui.dp(context, 44), (int) Ui.dp(context, 44)));
        row.addView(progressLabel, new LayoutParams((int) Ui.dp(context, 92), -2));
        progressRow = row;
        LayoutParams rowLp = new LayoutParams(-1, -2);
        rowLp.topMargin = (int) Ui.dp(context, 10);
        addView(row, rowLp);
        minus.setOnClickListener(v -> step(-1));
        plus.setOnClickListener(v -> step(+1));
        plus.setOnLongClickListener(v -> {
            Integer total = media == null ? null : media.total();
            if (total != null) setProgress(total);
            return true;
        });

        LinearLayout ratingHead = new LinearLayout(context);
        ratingHead.setOrientation(HORIZONTAL);
        ratingHead.setGravity(Gravity.BOTTOM);
        TextView rk = kicker(context, R.string.shelf_score);
        ratingHead.addView(rk, new LayoutParams(0, -2, 1f));
        ratingLabel = new TextView(context);
        ratingLabel.setTextAppearance(R.style.TextAppearance_Aniga_Meta);
        ratingLabel.setOnClickListener(v -> {
            if (state.score > 0) saveScore(0);
        });
        ratingHead.addView(ratingLabel);
        LayoutParams headLp = new LayoutParams(-1, -2);
        headLp.topMargin = (int) Ui.dp(context, 14);
        addView(ratingHead, headLp);
        rating = new HankoRatingView(context);
        addView(rating, new LayoutParams(-1, -2));
        rating.setOnRate(this::saveScore);
    }

    private static TextView kicker(Context c, int text) {
        TextView t = new TextView(c);
        t.setTextAppearance(R.style.TextAppearance_Aniga_Kicker);
        t.setText(text);
        return t;
    }

    private static MaterialButton stepper(Context c, String label) {
        MaterialButton b = new MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(label);
        b.setTextSize(18);
        b.setPadding(0, 0, 0, 0);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setCornerRadius((int) Ui.dp(c, 2));
        b.setTextColor(Ui.color(c, R.color.paper_100));
        b.setStrokeColor(ColorStateList.valueOf(Ui.color(c, R.color.ink_600)));
        return b;
    }

    public void setOnChange(Consumer<LibraryState> l) {
        onChange = l;
    }

    /** Fetches the current entry and renders it. */
    public void bind(LifecycleOwner owner, Media m) {
        this.owner = owner;
        this.media = m;
        buildChips();
        progressRow.setVisibility(View.VISIBLE);
        int typeColor = Ui.typeColor(getContext(), m.mediaType);
        plane.setColors(Ui.color(getContext(), R.color.ink_600), typeColor, Ui.color(getContext(), R.color.paper_100));
        render(state);
        Api.load(owner, Api.get().libraryEntry(Session.userId(getContext()), m.id), this::render, e -> {
        });
    }

    private void buildChips() {
        chips.removeAllViews();
        for (String s : STATUSES) {
            Chip chip = new Chip(getContext());
            chip.setId(View.generateViewId());
            chip.setTag(s);
            chip.setText(Ui.statusLabel(s, media.isAnime()));
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            chip.setEnsureMinTouchTargetSize(false);
            int color = Ui.statusColor(getContext(), s);
            int[][] states = {{android.R.attr.state_checked}, {}};
            chip.setChipBackgroundColor(new ColorStateList(states,
                    new int[]{Ui.withAlpha(color, 0.22f), Ui.color(getContext(), R.color.ink_800)}));
            chip.setChipStrokeColor(new ColorStateList(states, new int[]{color, Ui.color(getContext(), R.color.ink_600)}));
            chip.setChipStrokeWidth(Ui.dp(getContext(), 1));
            chip.setTextColor(new ColorStateList(states, new int[]{color, Ui.color(getContext(), R.color.paper_300)}));
            chip.setOnCheckedChangeListener((c, checked) -> {
                if (binding || !checked) return;
                c.animate().scaleX(1.08f).scaleY(1.08f).setDuration(90)
                        .withEndAction(() -> c.animate().scaleX(1f).scaleY(1f).setDuration(160).start()).start();
                save(new LibraryState.Update(s, null, null));
            });
            chips.addView(chip);
        }
    }

    private void render(LibraryState s) {
        state = s;
        binding = true;
        for (int i = 0; i < chips.getChildCount(); i++) {
            Chip c = (Chip) chips.getChildAt(i);
            c.setChecked(c.getTag().equals(s.status));
        }
        binding = false;
        renderProgress(s.progress);
        rating.setScore(s.score);
        ratingLabel.setText(s.score > 0 ? getContext().getString(R.string.shelf_score_clear, s.score)
                : getContext().getString(R.string.shelf_score_none));
        ratingLabel.setTextColor(Ui.color(getContext(), s.score > 0 ? R.color.hanko : R.color.paper_500));
        if (onChange != null) onChange.accept(s);
    }

    private void renderProgress(int progress) {
        Integer total = media == null ? null : media.total();
        String unit = media != null && media.isAnime() ? "EP" : "CH";
        progressLabel.setText(total != null
                ? String.format(Locale.ROOT, "%s %d / %d", unit, progress, total)
                : String.format(Locale.ROOT, "%s %d / ?", unit, progress));
        float f = total != null && total > 0 ? Math.min(1f, progress / (float) total) : (progress > 0 ? 0.5f : 0f);
        plane.setProgress(f);
    }

    private void step(int delta) {
        int base = pendingProgress != null ? pendingProgress : state.progress;
        setProgress(base + delta);
    }

    public void setProgress(int value) {
        if (media == null) return;
        Integer total = media.total();
        int next = Math.max(0, total != null ? Math.min(total, value) : value);
        pendingProgress = next;
        renderProgress(next);
        progressRow.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            Integer p = pendingProgress;
            pendingProgress = null;
            if (p != null) save(new LibraryState.Update(null, p, null));
        }, 450);
    }

    private void saveScore(int score) {
        save(new LibraryState.Update(null, null, score));
    }

    private void save(LibraryState.Update update) {
        if (owner == null || media == null) return;
        Api.load(owner, Api.get().updateLibrary(Session.userId(getContext()), media.id, update), s -> {
                    Progress.put(s);
                    render(s);
                },
                e -> Toast.makeText(getContext(), R.string.shelf_save_failed, Toast.LENGTH_SHORT).show());
    }

    /** Called by the reader when a chapter is finished. */
    public static void markProgress(LifecycleOwner owner, Context c, long mediaId, int progress) {
        Api.load(owner, Api.get().updateLibrary(Session.userId(c), mediaId,
                new LibraryState.Update(null, progress, null)), Progress::put, e -> {
        });
    }
}
