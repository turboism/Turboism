package dev.turboism.sdk.cubism.model;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CubismMocExportTest {
    private static final CubismMocExportSettings SETTINGS = new CubismMocExportSettings(
        512, 256, 100, false, false, false, false, false);

    @Test void ownsBytesPixelsAndTextureOrder() {
        byte[] bytes = moc();
        int[] pixels = {0xff123456};
        var texture = new CubismExportTexture(1, 1, pixels);
        var textures = new ArrayList<>(List.of(texture));
        var export = new CubismMocExport(bytes, textures, SETTINGS);
        bytes[0] = 0; pixels[0] = 0; textures.clear();
        export.moc3()[0] = 0; export.textures().get(0).argb()[0] = 0;
        assertEquals('M', export.moc3()[0]);
        assertEquals(0xff123456, export.textures().get(0).argb()[0]);
        assertThrows(UnsupportedOperationException.class, () -> export.textures().clear());
        var equal = new CubismMocExport(moc(), List.of(new CubismExportTexture(1, 1, new int[]{0xff123456})), SETTINGS);
        assertEquals(equal, export);
        assertEquals(equal.hashCode(), export.hashCode());
    }

    @Test void rejectsInvalidOriginScaleAndHeader() {
        assertThrows(IllegalArgumentException.class, () -> new CubismMocExportSettings(
            Float.NaN, 0, 100, false, false, false, false, false));
        for (float scale : new float[]{0, -1, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new CubismMocExportSettings(
                0, 0, scale, false, false, false, false, false));
        }
        assertThrows(IllegalArgumentException.class, () -> new CubismMocExport(new byte[64], List.of(), SETTINGS));
        assertThrows(IllegalArgumentException.class, () -> new CubismMocExport(new byte[0], List.of(), SETTINGS));
    }

    @Test void enforcesTextureBudgetsBeforeCopyingData() {
        assertThrows(IllegalArgumentException.class, () -> new CubismExportTexture(8193, 1, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new CubismExportTexture(8192, 8192, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new CubismExportTexture(2, 2, new int[1]));
        var texture = new CubismExportTexture(1, 1, new int[1]);
        assertThrows(IllegalArgumentException.class, () -> new CubismMocExport(moc(),
            java.util.Collections.nCopies(33, texture), SETTINGS));
    }

    @Test void admitsTwoPageAggregateAndRejectsOverflowWithoutChangingPerPageLimit() {
        // Reuse immutable texture values to exercise the aggregate arithmetic
        // without allocating two full 4096-square pages in the unit test.
        var small = new CubismExportTexture(1024, 1024, new int[1024 * 1024]);
        var result = new CubismMocExport(moc(), java.util.Collections.nCopies(32, small), SETTINGS);
        assertEquals(32, result.textures().size());
        var larger = new CubismExportTexture(1024, 2048, new int[1024 * 2048]);
        assertThrows(IllegalArgumentException.class, () -> new CubismMocExport(
            moc(), java.util.Collections.nCopies(17, larger), SETTINGS));
        assertThrows(IllegalArgumentException.class, () -> new CubismExportTexture(
            8192, 8192, new int[0]));
    }

    private static byte[] moc() {
        byte[] bytes = new byte[64]; bytes[0] = 'M'; bytes[1] = 'O'; bytes[2] = 'C'; bytes[3] = '3';
        return bytes;
    }
}
