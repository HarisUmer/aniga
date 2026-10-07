package com.aniga.backend.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ImageHashTest {

    /** A 200×300 "cover": random blocks so the gradient hash has structure. */
    private static BufferedImage cover(long seed) {
        BufferedImage img = new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        Random r = new Random(seed);
        for (int y = 0; y < 300; y += 25) {
            for (int x = 0; x < 200; x += 25) {
                g.setColor(new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256)));
                g.fillRect(x, y, 25, 25);
            }
        }
        g.dispose();
        return img;
    }

    private static BufferedImage onPage(BufferedImage cover, int pageW, int pageH, int x, int y, Color bg) {
        BufferedImage page = new BufferedImage(pageW, pageH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setColor(bg);
        g.fillRect(0, 0, pageW, pageH);
        g.drawImage(cover, x, y, null);
        g.dispose();
        return page;
    }

    private static int best(long[] variants, long target) {
        return Arrays.stream(variants).mapToInt(v -> ImageHash.distance(v, target)).min().orElse(64);
    }

    @Test
    void trimFindsTheCoverOnAPlainPage() {
        BufferedImage page = onPage(cover(1), 600, 400, 40, 50, new Color(18, 18, 22));
        BufferedImage cut = ImageHash.trim(page);
        assertTrue(Math.abs(cut.getWidth() - 200) <= 4, "width " + cut.getWidth());
        assertTrue(Math.abs(cut.getHeight() - 300) <= 4, "height " + cut.getHeight());
    }

    @Test
    void trimLeavesFullBleedImagesAlone() {
        BufferedImage c = cover(2);
        assertSame(c, ImageHash.trim(c));
    }

    @Test
    void variantsMatchMirroredAndEmbeddedCovers() {
        BufferedImage c = cover(3);
        long target = ImageHash.dHash(c);
        BufferedImage mirrored = new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = mirrored.createGraphics();
        g.drawImage(c, 200, 0, -200, 300, null);
        g.dispose();
        assertTrue(best(ImageHash.variants(mirrored), target) <= 2);

        BufferedImage wide = onPage(c, 700, 320, 480, 10, Color.WHITE);
        assertTrue(best(ImageHash.variants(wide), target) <= 8);

        long other = ImageHash.dHash(cover(99));
        assertTrue(best(ImageHash.variants(mirrored), other) > 18, "an unrelated cover must stay above the threshold");
    }

    @Test
    void zoomHashMatchesATightCrop() {
        BufferedImage c = cover(4);
        BufferedImage crop = c.getSubimage(20, 30, 160, 240);
        assertEquals(0, ImageHash.distance(ImageHash.dHash(crop), ImageHash.zoomHash(c)), 4);
    }
}
