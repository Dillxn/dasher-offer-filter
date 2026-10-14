package com.local.dasherfilter;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * The pictures in the round (0.6.16, {@link Art}): ArtSprites' numbers are its pictures' own. A picture that decodes
 * to another size than its numbers say is never drawn (Art leaves that drawing flat), so a picture drawn again
 * without its numbers, or numbers without their picture, would quietly bring the flat drawing back.
 */
public class ArtSpritesTest {
    private static final File PICTURES = new File("src/main/res/drawable-nodpi");

    @Test
    public void everyPictureIsTheSizeItsNumbersSay() throws Exception {
        Set<String> named = new TreeSet<>();
        for (Field field : ArtSprites.class.getDeclaredFields()) {
            if (field.getType() != Art.Sprite.class || !Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Art.Sprite sprite = (Art.Sprite) field.get(null);
            String name = "art_" + field.getName().toLowerCase(Locale.ROOT);
            named.add(name);
            assertEquals(name + "'s resource", R.drawable.class.getField(name).getInt(null), sprite.res);
            int[] size = webpSize(new File(PICTURES, name + ".webp"));
            assertEquals(name + "'s width", sprite.w, size[0]);
            assertEquals(name + "'s height", sprite.h, size[1]);
            assertTrue(name + " is placed by a point inside it",
                    sprite.x >= 0 && sprite.x <= sprite.w && sprite.y >= 0 && sprite.y <= sprite.h);
            // Each is drawn by its model's unit, but for the chart's trees, only ever stretched to their tree.
            assertTrue(name + "'s unit", sprite.unit > 0 || name.startsWith("art_pine"));
        }
        Set<String> files = new TreeSet<>();
        String[] list = PICTURES.list();
        assertTrue("the pictures' folder", list != null);
        for (String file : list) {
            if (file.startsWith("art_") && file.endsWith(".webp")) files.add(file.substring(0, file.length() - 5));
        }
        // The launcher icon's foreground: Android draws it, from the adaptive icon (108 dp at 4 pixels to the dp).
        assertTrue(files.remove("art_icon"));
        int[] icon = webpSize(new File(PICTURES, "art_icon.webp"));
        assertEquals(432, icon[0]);
        assertEquals(432, icon[1]);
        assertEquals("a picture for every name in ArtSprites, and a name for every picture", files, named);
    }

    @Test
    public void theChartsPicturesHaveTheirPointsInOrder() {
        for (Art.Sprite block : new Art.Sprite[] {ArtSprites.BLOCK_KEEP, ArtSprites.BLOCK_REVIEW,
                ArtSprites.BLOCK_DECLINE, ArtSprites.BLOCK_KEEP_NIGHT, ArtSprites.BLOCK_REVIEW_NIGHT,
                ArtSprites.BLOCK_DECLINE_NIGHT}) {
            assertEquals(4, block.pointCount());
            assertTrue("its front's left edge left of its right", block.pointX(Art.LEFT) < block.pointX(Art.RIGHT));
            // Its roof above where the roof's picture ends, and the slice of its front below that.
            assertTrue(block.y < block.pointY(Art.CAP_END));
            assertTrue(block.pointY(Art.CAP_END) < block.pointY(Art.SLICE));
            assertTrue(block.pointY(Art.SLICE) + 1 <= block.h);
        }
        for (Art.Sprite pine : new Art.Sprite[] {ArtSprites.PINE, ArtSprites.PINE_NIGHT}) {
            assertEquals(3, pine.pointCount());
            assertTrue("its tip above its crown's foot", pine.y < pine.pointY(Art.FOOT));
            assertTrue(pine.pointX(Art.FOOT_LEFT) < pine.pointX(Art.FOOT_RIGHT));
        }
    }

    /** A WebP's size from its header: simple lossy, lossless or extended (with alpha, as these are). */
    static int[] webpSize(File file) throws IOException {
        byte[] b = Files.readAllBytes(file.toPath());
        assertTrue(file + " is a WebP", b.length > 30 && new String(b, 0, 4, US_ASCII).equals("RIFF")
                && new String(b, 8, 4, US_ASCII).equals("WEBP"));
        String chunk = new String(b, 12, 4, US_ASCII);
        switch (chunk) {
            case "VP8X":
                return new int[] {1 + u24(b, 24), 1 + u24(b, 27)};
            case "VP8L": {
                int bits = (b[21] & 0xff) | (b[22] & 0xff) << 8 | (b[23] & 0xff) << 16 | (b[24] & 0xff) << 24;
                return new int[] {1 + (bits & 0x3fff), 1 + (bits >>> 14 & 0x3fff)};
            }
            case "VP8 ":
                return new int[] {((b[26] & 0xff) | (b[27] & 0xff) << 8) & 0x3fff,
                        ((b[28] & 0xff) | (b[29] & 0xff) << 8) & 0x3fff};
            default:
                throw new AssertionError(file + ": no WebP image chunk (" + chunk + ")");
        }
    }

    private static int u24(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16;
    }
}
