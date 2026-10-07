package com.example.aniga.ui;

import com.example.aniga.data.model.Media;
import com.example.aniga.data.model.Series;

/** What a cover tile needs, whether it came from our catalog or a live reading source. */
public class CoverItem {
    public final String title;
    public final String type;
    public final String coverUrl;
    public final CharSequence meta;
    public final Media media;
    public final Series series;
    /** Optional ribbon text, e.g. "93% match" or "EP 5 · 12:04". */
    public final String badge;

    private CoverItem(String title, String type, String coverUrl, CharSequence meta, Media media, Series series,
                      String badge) {
        this.title = title;
        this.type = type;
        this.coverUrl = coverUrl;
        this.meta = meta;
        this.media = media;
        this.series = series;
        this.badge = badge;
    }

    public static CoverItem of(Media m) {
        return of(m, null);
    }

    public static CoverItem of(Media m, String badge) {
        StringBuilder meta = new StringBuilder();
        if (m.averageScore != null) meta.append(m.averageScore).append("%");
        Integer count = m.total();
        if (count != null) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(count).append(m.isAnime() ? " EP" : " CH");
        }
        return new CoverItem(m.title, m.mediaType, m.coverUrl, meta, m, null, badge);
    }

    public static CoverItem of(Series s) {
        String meta = (s.year != null ? s.year + " · " : "") + (s.status != null ? s.status : "");
        return new CoverItem(s.title, s.contentType, s.coverUrl, meta, null, s, null);
    }

    /** A trace.moe scene we matched but don't hold in the catalog: shown with its preview frame, not tappable. */
    public static CoverItem ofScene(String title, String coverUrl, CharSequence meta, String badge) {
        return new CoverItem(title, "ANIME", coverUrl, meta, null, null, badge);
    }
}
