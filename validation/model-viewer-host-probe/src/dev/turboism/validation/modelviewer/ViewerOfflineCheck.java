package dev.turboism.validation.modelviewer;
import dev.turboism.plugin.modelimport3d.viewer.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;

/** Local-only verifier. The private source model is never included in the repository. */
public final class ViewerOfflineCheck {
    private ViewerOfflineCheck() {}
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]), out = Path.of(args[1]);
        Files.createDirectories(out);
        byte[] bytes = Files.readAllBytes(input);
        long start = System.nanoTime();
        ViewerScene scene = GlbViewerLoader.load(bytes, () -> false);
        long loadMillis = (System.nanoTime() - start) / 1_000_000;
        Properties report = new Properties();
        report.setProperty("source.sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        report.setProperty("source.bytes", "" + bytes.length);
        report.setProperty("scene.stats", scene.stats().toString());
        report.setProperty("load.ms", "" + loadMillis);
        Set<Long> hashes = new HashSet<>();
        SoftwareModelRenderer renderer = new SoftwareModelRenderer();
        for (int i = 0; i < 3; i++) {
            start = System.nanoTime();
            BufferedImage image = renderer.render(scene, 640, 640, i * Math.PI / 2, 0, 1);
            report.setProperty("frame." + i + ".ms", "" + ((System.nanoTime() - start) / 1_000_000));
            ImageIO.write(image, "png", out.resolve("view-" + i + ".png").toFile());
            long hash = 1; int nonBackground = 0, background = image.getRGB(0, 0);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                int color = image.getRGB(x, y); hash = 31 * hash + color;
                if (color != background) ++nonBackground;
            }
            if (nonBackground < 1000) throw new AssertionError("Blank model frame " + i);
            hashes.add(hash);
            report.setProperty("frame." + i + ".pixels", "" + nonBackground);
            report.setProperty("frame." + i + ".hash", "" + hash);
        }
        for (int i = 0; i < 3; i++) {
            BufferedImage geometry = renderer.render(scene, 640, 640, i * Math.PI / 2, 0, 1,
                    SoftwareModelRenderer.Shading.GEOMETRY);
            ImageIO.write(geometry, "png", out.resolve("geometry-" + i + ".png").toFile());
        }
        if (hashes.size() != 3) throw new AssertionError("Rotation did not change the geometry");
        report.setProperty("status", "PASS");
        try (var stream = Files.newOutputStream(out.resolve("offline.properties"))) { report.store(stream, "Actual GLB viewer"); }
        report.forEach((key, value) -> System.out.println(key + "=" + value));
    }
}
