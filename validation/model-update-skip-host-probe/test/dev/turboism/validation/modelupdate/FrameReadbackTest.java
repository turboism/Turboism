package dev.turboism.validation.modelupdate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/** Ensure parity uses actual nonuniform readback and excludes row padding. */
public final class FrameReadbackTest {
    public static void main(String[] args) throws Exception {
        IntBuffer padded = IntBuffer.wrap(new int[]{99, 1, 2, 88, 3, 4, 77});
        padded.position(1);
        FrameReadback image = FrameReadback.capture(padded, 2, 2, 32993, 5121, 3, 4, 0, 0);
        check(padded.position() == 1, "position preserved");
        check(image.distinctPixels() == 4 && image.pixels() == 4, "padding ignored");
        IntBuffer contiguous = IntBuffer.wrap(new int[]{1, 2, 3, 4});
        check(image.samePixels(FrameReadback.capture(contiguous, 2, 2, 32993, 5121, 0, 4, 0, 0)), "row packing independent");
        FrameReadback black = FrameReadback.capture(IntBuffer.wrap(new int[4]), 2, 2, 32993, 5121, 0, 4, 0, 0);
        check(black.distinctPixels() == 1, "black capture detected");
        ByteBuffer bytes = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        bytes.asIntBuffer().put(new int[]{1, 2, 3, 4});
        check(image.samePixels(FrameReadback.capture(bytes, 2, 2, 32993, 5121, 0, 4, 0, 0)), "byte and int payload agree");
        boolean rejected = false;
        try { FrameReadback.capture(IntBuffer.wrap(new int[1]), 2, 2, 32993, 5121, 0, 4, 0, 0); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "short buffer rejected");
        FrameReadback a = FrameReadback.fromArgb(2, 2, new int[]{0xff000000, 0xff000001, 0xff000002, 0xff000003});
        FrameReadback b = FrameReadback.fromArgb(2, 2, new int[]{0xff000000, 0xff000002, 0xff000002, 0xff000013});
        String difference = a.difference(b);
        check(difference.contains("changedPixels=2\n") && difference.contains("bounds=1,0:1,1\n"), "exact difference extent");
        check(difference.contains("maximumChannelDelta=16\n"), "channel delta");
        check(a.difference(a).contains("changedPixels=0\n"), "identical images have no differences");
        check(!a.samePixels(b), "one-bit pixel differences remain failures");
        rejected = false;
        try { a.difference(FrameReadback.fromArgb(4, 1, new int[4])); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "difference rejects incompatible layout");
        java.nio.file.Path png = java.nio.file.Files.createTempFile("frame-readback-", ".png");
        try {
            int[] argb = {0x00112233, 0x7f234567, 0xff89abcd, 0xff012345};
            FrameReadback exact = FrameReadback.fromArgb(2, 2, argb);
            exact.writeArgbPng(png);
            java.awt.image.BufferedImage decoded = javax.imageio.ImageIO.read(png.toFile());
            check(exact.samePixels(FrameReadback.fromArgb(decoded.getWidth(), decoded.getHeight(),
                decoded.getRGB(0, 0, 2, 2, null, 0, 2))), "diagnostic PNG preserves every ARGB bit");
            rejected = false;
            try { image.writeArgbPng(png); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "raw GL layout must not be mislabeled as ARGB");
        } finally {
            java.nio.file.Files.deleteIfExists(png);
        }
        System.out.println("FrameReadbackTest PASS (row padding, position, types, non-vacuous pixels, bounds, exact diagnostics)");
    }
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
}
