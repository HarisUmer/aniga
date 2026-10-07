package com.example.aniga.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.aniga.MainActivity;
import com.example.aniga.R;
import com.example.aniga.data.Api;
import com.example.aniga.data.Session;
import com.example.aniga.data.model.TasteV2;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * First screen after sign-in: pick the kinds of books you reach for. Covers and colour do the
 * talking; the synopsis stays for later, on the title itself.
 */
public class TasteActivity extends AppCompatActivity {

    private static final String[][] TYPES = {
            {"MANHWA", "Manhwa"},
            {"MANGA", "Manga"},
            {"MANHUA", "Manhua"},
            {"ANIME", "Anime"}
    };
    private static final String[] GENRES = {
            "Action", "Romance", "Fantasy", "Comedy", "Drama", "Horror",
            "Slice of Life", "Thriller", "Supernatural", "Sports", "Mystery", "Psychological"
    };

    private final Set<String> types = new LinkedHashSet<>();
    private final Set<String> genres = new LinkedHashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_taste);
        View content = findViewById(R.id.taste_content);
        int pad = content.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            v.setPadding(v.getPaddingLeft(), pad + bars.top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        buildTypes();
        buildGenres();
        findViewById(R.id.taste_skip).setOnClickListener(v -> finishPicking());
        findViewById(R.id.taste_go).setOnClickListener(v -> save());
    }

    private void buildTypes() {
        LinearLayout grid = findViewById(R.id.type_grid);
        LinearLayout row = null;
        int cell = (int) Ui.dp(this, 8);
        for (int i = 0; i < TYPES.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) lp.topMargin = cell;
                grid.addView(row, lp);
            }
            String code = TYPES[i][0];
            TextView tile = new TextView(this);
            tile.setText(TYPES[i][1]);
            tile.setGravity(Gravity.CENTER);
            tile.setTextAppearance(R.style.TextAppearance_Aniga_Headline);
            tile.setMinHeight((int) Ui.dp(this, 92));
            int color = Ui.typeColor(this, code);
            tile.setTextColor(color);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(this, 4));
            bg.setColor(Ui.withAlpha(color, 0.16f));
            bg.setStroke((int) Ui.dp(this, 1), Ui.withAlpha(color, 0.35f));
            tile.setBackground(bg);
            tile.setOnClickListener(v -> {
                boolean on = types.contains(code);
                if (on) types.remove(code);
                else types.add(code);
                bg.setColor(Ui.withAlpha(color, types.contains(code) ? 0.55f : 0.16f));
                bg.setStroke((int) Ui.dp(this, types.contains(code) ? 2 : 1), color);
                tile.setTextColor(types.contains(code) ? Ui.color(this, R.color.ink_950) : color);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i % 2 == 1) lp.setMarginStart(cell);
            row.addView(tile, lp);
        }
    }

    private void buildGenres() {
        ChipGroup group = findViewById(R.id.genre_grid);
        int[] colors = {R.color.type_manhwa, R.color.type_manga, R.color.type_manhua, R.color.type_anime, R.color.fuji};
        for (int i = 0; i < GENRES.length; i++) {
            String genre = GENRES[i];
            int color = Ui.color(this, colors[i % colors.length]);
            Chip chip = new Chip(this);
            chip.setText(genre);
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            chip.setEnsureMinTouchTargetSize(false);
            chip.setChipStrokeWidth(Ui.dp(this, 1));
            chip.setChipStrokeColor(ColorStateList.valueOf(color));
            chip.setTextColor(color);
            chip.setChipBackgroundColor(ColorStateList.valueOf(Ui.withAlpha(color, 0.12f)));
            chip.setOnCheckedChangeListener((button, checked) -> {
                if (checked) genres.add(genre);
                else genres.remove(genre);
                Chip c = (Chip) button;
                c.setChipBackgroundColor(ColorStateList.valueOf(Ui.withAlpha(color, checked ? 0.55f : 0.12f)));
                c.setTextColor(checked ? Ui.color(this, R.color.ink_950) : color);
            });
            group.addView(chip);
        }
    }

    private void save() {
        findViewById(R.id.taste_go).setEnabled(false);
        List<TasteV2.Pref> prefs = new ArrayList<>();
        for (String type : types) prefs.add(new TasteV2.Pref("type:" + type, 1));
        for (String genre : genres) prefs.add(new TasteV2.Pref("g:" + genre, 1));
        send(prefs, 0);
    }

    private void send(List<TasteV2.Pref> prefs, int index) {
        if (index >= prefs.size()) {
            finishPicking();
            return;
        }
        long user = Session.userId(this);
        Api.load(this, Api.get().pref(user, prefs.get(index)),
                r -> send(prefs, index + 1),
                e -> send(prefs, index + 1));
    }

    private void finishPicking() {
        Session.setPrefsDone(this);
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }
}
