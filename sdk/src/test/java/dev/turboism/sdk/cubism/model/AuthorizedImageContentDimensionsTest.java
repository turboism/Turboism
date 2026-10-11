package dev.turboism.sdk.cubism.model;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AuthorizedImageContentDimensionsTest {
    private static byte[] png(int w, int h) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "PNG", out);
        return out.toByteArray();
    }

    @Test
    void explicitPaddingDimensionsPreserveLegacyContractAndByteOwnership() throws Exception {
        var bytes = png(512, 256);
        assertThrows(IllegalArgumentException.class, () -> AuthorizedImageContent.of(bytes, "legacy"));
        var grant = AuthorizedImageContent.ofDimensions(bytes, "padded", 512, 256);
        assertEquals(512, grant.width());
        assertEquals(256, grant.height());
        bytes[0] = 0;
        assertNotEquals(0, grant.copyBytes()[0]);
        assertThrows(
                IllegalArgumentException.class,
                () -> AuthorizedImageContent.ofDimensions(grant.copyBytes(), "wrong", 256, 128));
        assertThrows(
                IllegalArgumentException.class,
                () -> AuthorizedImageContent.ofDimensions(
                        grant.copyBytes(), "huge", Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(
                IllegalArgumentException.class,
                () -> AuthorizedImageContent.ofDimensions(grant.copyBytes(), "zero", 0, 256));
        var old = AuthorizedImageContent.of(png(256, 128), "original");
        assertEquals(256, old.width());
        assertEquals(128, old.height());
    }

    @Test
    void acceptsUnscaledNonPowerOfTwoSourceImageAndKeepsDecodedBudget() throws Exception {
        var bytes = png(520, 520);
        var grant = AuthorizedImageContent.ofDimensions(bytes, "losslessly cropped", 520, 520);
        assertEquals(520, grant.width());
        assertEquals(520, grant.height());
        assertArrayEquals(bytes, grant.copyBytes());
        assertThrows(
                IllegalArgumentException.class,
                () -> AuthorizedImageContent.ofDimensions(bytes, "wrong height", 520, 519));
        assertThrows(
                IllegalArgumentException.class,
                () -> AuthorizedImageContent.ofDimensions(bytes, "decoded overflow", 1025, 1024));
        grant.claim();
        assertTrue(grant.consumed());
        assertThrows(IllegalStateException.class, grant::claim);
    }
}
