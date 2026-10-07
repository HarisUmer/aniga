package com.example.aniga.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.ColorUtils;

import com.example.aniga.R;

public final class Ui {

    private Ui() {
    }

    public static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static int color(Context c, int res) {
        return ContextCompat.getColor(c, res);
    }

    public static Typeface display(Context c) {
        Typeface t = ResourcesCompat.getFont(c, R.font.anton_regular);
        return t != null ? t : Typeface.DEFAULT_BOLD;
    }

    public static Typeface mono(Context c) {
        Typeface t = ResourcesCompat.getFont(c, R.font.plex_mono_medium);
        return t != null ? t : Typeface.MONOSPACE;
    }

    public static int typeColor(Context c, String type) {
        if (type == null) return color(c, R.color.type_manga);
        switch (type) {
            case "ANIME":
                return color(c, R.color.type_anime);
            case "MANHWA":
                return color(c, R.color.type_manhwa);
            case "MANHUA":
                return color(c, R.color.type_manhua);
            case "WEBCOMIC":
                return color(c, R.color.type_webcomic);
            default:
                return color(c, R.color.type_manga);
        }
    }

    /** AniList's dominant cover colour if present, else the media-type colour. */
    public static int accent(Context c, String accentHex, String type) {
        if (accentHex != null && accentHex.matches("#[0-9a-fA-F]{6}")) {
            return Color.parseColor(accentHex);
        }
        return typeColor(c, type);
    }

    /** Pull an arbitrary accent toward the readable band against ink (avoid muddy or neon). */
    public static int tame(int accent) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(accent, hsl);
        hsl[1] = Math.min(hsl[1], 0.72f);
        hsl[2] = Math.max(0.52f, Math.min(hsl[2], 0.68f));
        return ColorUtils.HSLToColor(hsl);
    }

    public static int statusColor(Context c, String status) {
        if (status == null) return color(c, R.color.paper_500);
        switch (status) {
            case "CURRENT":
                return color(c, R.color.status_current);
            case "COMPLETED":
                return color(c, R.color.status_completed);
            case "PAUSED":
                return color(c, R.color.status_paused);
            case "DROPPED":
                return color(c, R.color.status_dropped);
            default:
                return color(c, R.color.status_planning);
        }
    }

    /** "CURRENT" reads as Watching for anime and Reading for comics. */
    public static String statusLabel(String status, boolean anime) {
        if (status == null) return "Add";
        switch (status) {
            case "CURRENT":
                return anime ? "Watching" : "Reading";
            case "COMPLETED":
                return "Completed";
            case "PAUSED":
                return "Paused";
            case "DROPPED":
                return "Dropped";
            case "PLANNING":
                return "Planning";
            default:
                return "Add";
        }
    }

    public static int withAlpha(int color, float alpha) {
        return ColorUtils.setAlphaComponent(color, Math.round(alpha * 255));
    }

    public static int mix(int a, int b, float ratioOfB) {
        return ColorUtils.blendARGB(a, b, ratioOfB);
    }
}
