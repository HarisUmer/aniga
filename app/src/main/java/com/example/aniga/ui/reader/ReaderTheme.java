package com.example.aniga.ui.reader;

import android.content.Context;

import com.example.aniga.R;
import com.example.aniga.ui.Ui;

/**
 * Page tones. Night Ink is a warm near-black (not #000) with off-white text to avoid halation;
 * Sepia and Paper keep text far from pure black for low glare; OLED is true black for AMOLED
 * battery saving. "light" flips status-bar icons dark.
 */
public enum ReaderTheme {
    NIGHT("Night ink", R.color.reader_night_bg, R.color.reader_night_chrome, R.color.reader_night_text, 0xFFE5532D, false),
    SEPIA("Sepia", R.color.reader_sepia_bg, R.color.reader_sepia_chrome, R.color.reader_sepia_text, 0xFFA8461F, true),
    PAPER("Paper", R.color.reader_paper_bg, R.color.reader_paper_chrome, R.color.reader_paper_text, 0xFFC8401E, true),
    OLED("OLED", R.color.reader_oled_bg, R.color.reader_oled_chrome, R.color.reader_oled_text, 0xFFD9542F, false);

    public final String label;
    private final int bgRes;
    private final int chromeRes;
    private final int textRes;
    public final int accent;
    public final boolean light;

    ReaderTheme(String label, int bgRes, int chromeRes, int textRes, int accent, boolean light) {
        this.label = label;
        this.bgRes = bgRes;
        this.chromeRes = chromeRes;
        this.textRes = textRes;
        this.accent = accent;
        this.light = light;
    }

    public int bg(Context c) {
        return Ui.color(c, bgRes);
    }

    public int chrome(Context c) {
        return Ui.color(c, chromeRes);
    }

    public int text(Context c) {
        return Ui.color(c, textRes);
    }

    public int muted(Context c) {
        return Ui.mix(text(c), bg(c), 0.45f);
    }

    /** Slightly lifted surface for page placeholders so a loading page is visible but quiet. */
    public int placeholder(Context c) {
        return Ui.mix(bg(c), text(c), 0.06f);
    }

    public static ReaderTheme from(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return NIGHT;
        }
    }
}
