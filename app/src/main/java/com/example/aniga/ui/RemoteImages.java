package com.example.aniga.ui;

import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;

/**
 * Some chapter CDNs answer 403 unless the request names the site that embedded the image.
 */
public final class RemoteImages {

    private RemoteImages() {
    }

    public static Object model(String url) {
        String referer = refererFor(url);
        if (referer == null) {
            return url;
        }
        return new GlideUrl(url, new LazyHeaders.Builder().addHeader("Referer", referer).build());
    }

    private static String refererFor(String url) {
        if (url == null) {
            return null;
        }
        String host = url.toLowerCase();
        if (host.contains("mangahere.") || host.contains("mangatown.")) {
            return "https://www.mangatown.com/";
        }
        if (host.contains("imgsrv") || host.contains("mgeko") || host.contains("cdn_mangaraw")) {
            return "https://www.mgeko.cc/";
        }
        if (host.contains("asura")) {
            return "https://asuracomic.net/";
        }
        return null;
    }
}
