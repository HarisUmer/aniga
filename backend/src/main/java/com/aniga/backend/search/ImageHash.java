package com.aniga.backend.search;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 64-bit difference hash (dHash): shrink to 9x8 grey, set bit i if pixel i is brighter than its right neighbour.
 * Survives resizing, JPEG recompression and mild colour shifts, so a screenshot of a cover still lands within a
 * small Hamming distance of the original. It is NOT a semantic search: a different illustration won't match.
 */
public final class ImageHash {

    private ImageHash() {
    }

    public static long dHash(BufferedImage src) {
        // Covers are 2:3; centre-crop the input to 2:3 first so phone photos with margins still line up.
        int w = src.getWidth();
        int h = src.getHeight();
        int cw = w;
        int ch = h;
        if (w * 3 > h * 2) {
            cw = h * 2 / 3;
        } else {
            ch = w * 3 / 2;
        }
        BufferedImage crop = src.getSubimage((w - cw) / 2, (h - ch) / 2, Math.max(cw, 1), Math.max(ch, 1));
        BufferedImage small = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(crop, 0, 0, 9, 8, null);
        g.dispose();
        long hash = 0;
        int bit = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = small.getRaster().getSample(x, y, 0);
                int right = small.getRaster().getSample(x + 1, y, 0);
                if (left > right) {
                    hash |= 1L << bit;
                }
                bit++;
            }
        }
        return hash;
    }

    public static int distance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /**
     * Query-side variants of one upload: as-is, mirrored, zoomed-in centre crops (photo of a cover lying on a desk),
     * and left/right 2:3 windows of wide images (cover shown at the side of a store page or a tweet). Stored cover
     * hashes stay single, so the index format does not change; the best (smallest) distance over variants wins.
     */
    public static long[] variants(BufferedImage upload) {
        BufferedImage src = trim(upload);
        int w = src.getWidth();
        int h = src.getHeight();
        java.util.List<BufferedImage> imgs = new java.util.ArrayList<>();
        imgs.add(src);
        imgs.add(mirror(src));
        if (src != upload) {
            imgs.add(upload);
        }
        for (double keep : new double[] {0.85, 0.7}) {
            int cw = Math.max(1, (int) (w * keep));
            int ch = Math.max(1, (int) (h * keep));
            imgs.add(src.getSubimage((w - cw) / 2, (h - ch) / 2, cw, ch));
        }
        if (w * 3 > h * 2 * 1.3) {
            int cw = Math.max(1, h * 2 / 3);
            imgs.add(src.getSubimage(0, 0, cw, h));
            imgs.add(src.getSubimage(w - cw, 0, cw, h));
        }
        return imgs.stream().mapToLong(ImageHash::dHash).toArray();
    }

    /** Second stored hash per cover: its centre 80%, so a query cropped tighter than the cover still lines up. */
    public static long zoomHash(BufferedImage cover) {
        int cw = Math.max(1, (int) (cover.getWidth() * 0.8));
        int ch = Math.max(1, (int) (cover.getHeight() * 0.8));
        return dHash(cover.getSubimage((cover.getWidth() - cw) / 2, (cover.getHeight() - ch) / 2, cw, ch));
    }

    /**
     * Cuts uniform borders (letterboxing, a dark store page or tweet around the cover): background = the colour at
     * least 40% of the outer border agrees on, content = pixels differing from it by more than 40 in any channel. Returns the input
     * unchanged when nothing meaningful can be trimmed.
     */
    static BufferedImage trim(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int step = Math.max(1, Math.max(w, h) / 256);
        // Background = the colour most of the outer border agrees on (a cover touching one edge must not win).
        java.util.List<Integer> edge = new java.util.ArrayList<>();
        for (int x = 0; x < w; x += step) {
            edge.add(src.getRGB(x, 0));
            edge.add(src.getRGB(x, h - 1));
        }
        for (int y = 0; y < h; y += step) {
            edge.add(src.getRGB(0, y));
            edge.add(src.getRGB(w - 1, y));
        }
        int bg = edge.get(0);
        int bestVotes = 0;
        int probe = Math.max(1, edge.size() / 48);
        for (int i = 0; i < edge.size(); i += probe) {
            int c = edge.get(i);
            int votes = 0;
            for (int o : edge) {
                votes += colourDiff(c, o) <= 40 ? 1 : 0;
            }
            if (votes > bestVotes) {
                bestVotes = votes;
                bg = c;
            }
        }
        if (bestVotes < edge.size() * 0.4) {
            return src;
        }
        int minX = w;
        int minY = h;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                if (colourDiff(src.getRGB(x, y), bg) > 40) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        if (maxX < 0 || (long) bw * bh < 0.08 * w * h || (long) bw * bh > 0.92 * w * h) {
            return src;
        }
        return src.getSubimage(minX, minY, Math.min(bw + step - 1, w - minX), Math.min(bh + step - 1, h - minY));
    }

    private static int colourDiff(int a, int b) {
        int dr = Math.abs(((a >> 16) & 0xff) - ((b >> 16) & 0xff));
        int dg = Math.abs(((a >> 8) & 0xff) - ((b >> 8) & 0xff));
        int db = Math.abs((a & 0xff) - (b & 0xff));
        return Math.max(dr, Math.max(dg, db));
    }

    private static BufferedImage mirror(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, src.getWidth(), 0, -src.getWidth(), src.getHeight(), null);
        g.dispose();
        return out;
    }
}
