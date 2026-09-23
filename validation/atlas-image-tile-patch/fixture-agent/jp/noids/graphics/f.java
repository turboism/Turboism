package jp.noids.graphics;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

/** Fixture replica of jp.noids.graphics.f (5303-verified semantics). */
public class f {

    /** a(src, dst, mode): raw raster copy, per-row System.arraycopy. */
    public static void a(BufferedImage src, BufferedImage dst, int mode) {
        DataBuffer sb = src.getRaster().getDataBuffer();
        DataBuffer db = dst.getRaster().getDataBuffer();
        int w = Math.min(src.getWidth(), dst.getWidth());
        int h = Math.min(src.getHeight(), dst.getHeight());
        if (sb instanceof DataBufferInt && db instanceof DataBufferInt) {
            int[] s = ((DataBufferInt) sb).getData();
            int[] d = ((DataBufferInt) db).getData();
            for (int r = 0; r < h; r++)
                System.arraycopy(s, r * src.getWidth(), d, r * dst.getWidth(), w);
        } else if (sb instanceof DataBufferByte && db instanceof DataBufferByte) {
            byte[] s = ((DataBufferByte) sb).getData();
            byte[] d = ((DataBufferByte) db).getData();
            for (int r = 0; r < h; r++)
                System.arraycopy(s, r * src.getWidth(), d, r * dst.getWidth(), w);
        }
    }

    /** a(bi, v): fill raster with v. */
    public static void a(BufferedImage bi, int v) {
        DataBuffer db = bi.getRaster().getDataBuffer();
        if (db instanceof DataBufferInt) {
            Arrays.fill(((DataBufferInt) db).getData(), v);
        } else if (db instanceof DataBufferByte) {
            byte[] d = ((DataBufferByte) db).getData();
            Arrays.fill(d, (byte) (v & 255));
        }
    }

    /** c(bi): int raster backing, null if not int-backed. */
    public static int[] c(BufferedImage bi) {
        DataBuffer db = bi.getRaster().getDataBuffer();
        return db instanceof DataBufferInt ? ((DataBufferInt) db).getData() : null;
    }

    /** f(bi): byte raster backing, null if not byte-backed. */
    public static byte[] f(BufferedImage bi) {
        DataBuffer db = bi.getRaster().getDataBuffer();
        return db instanceof DataBufferByte ? ((DataBufferByte) db).getData() : null;
    }
}
