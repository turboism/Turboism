package dev.turboism.plugin.atlasnesting.layout;

import dev.turboism.plugin.atlasmaxrectsbssf.layout.CurrentPageTextureAtlasPlanner;
import dev.turboism.sdk.cubism.textureatlas.*;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Replays actual issued host contours; capture IO, validation and rendering are outside timing. */
public final class CapturedNestingBenchmark {
    record Input(List<TextureAtlasPolygonItem> items, TextureAtlasPolygonConstraints constraints) {}

    public static void main(String[] args) throws Exception {
        final Input input = read(Path.of(args[0]), args.length > 2 ? Double.parseDouble(args[2]) : -1);
        final var items = input.items();
        final var c = input.constraints();
        final var rects = items.stream()
                .map(i -> new TextureAtlasLayoutItem(i.textureId(), i.width(), i.height()))
                .toList();
        if (items.stream().anyMatch(i -> !i.policy().equals(TextureAtlasItemLayoutPolicy.participating(i.textureId()))))
            throw new IllegalArgumentException("Rectangle comparison requires unlocked participating input");
        final var rc = TextureAtlasLayoutConstraints.currentPage(
                c.pageWidth(),
                c.pageHeight(),
                c.margin(),
                c.rotationMode() != TextureAtlasRotationMode.NONE,
                c.requestedScale());
        final Path output = Path.of(args[1]);
        Files.createDirectories(output);
        final StringBuilder csv = new StringBuilder(
                "algorithm,items,pageWidth,pageHeight,requestedScale,medianMs,minMs,maxMs,scale,placed\n");
        for (final String algorithm : List.of("bssf", "nesting")) {
            final double[] times = new double[9];
            TextureAtlasPolygonPlan polygon = null;
            TextureAtlasLayoutPlan rectangle = null;
            for (int round = -5; round < times.length; round++) {
                final long start = System.nanoTime();
                if (algorithm.equals("nesting")) polygon = new BitmaskNestingPlanner(c.margin() * 2).plan(items, c);
                else rectangle = new CurrentPageTextureAtlasPlanner().plan(rects, rc, false);
                if (round >= 0) times[round] = (System.nanoTime() - start) / 1_000_000.0;
            }
            Arrays.sort(times);
            final String line = String.format(
                    Locale.ROOT,
                    "%s,%d,%d,%d,%.6f,%.3f,%.3f,%.3f,%.6f,%d%n",
                    algorithm,
                    items.size(),
                    c.pageWidth(),
                    c.pageHeight(),
                    c.requestedScale(),
                    times[4],
                    times[0],
                    times[8],
                    polygon == null ? rectangle.scale() : polygon.scale(),
                    polygon == null
                            ? rectangle.placements().size()
                            : polygon.placements().size());
            csv.append(line);
            System.out.print(line);
            if (polygon != null) {
                BitmaskNestingPlannerTest.assertValid(items, polygon, c, c.margin() * 2.0);
                draw(output.resolve("nesting.png"), input, polygon, null);
                Files.writeString(
                        output.resolve("nesting-diagnostics.txt"),
                        polygon.diagnostics().toString());
            } else draw(output.resolve("bssf.png"), input, null, rectangle);
        }
        Files.writeString(output.resolve("benchmark.csv"), csv);
    }

    static Input read(Path file, double requested) throws Exception {
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (!in.readUTF().equals("turboism.nesting.input.v1"))
                throw new IllegalArgumentException("Capture version");
            final int w = in.readInt(), h = in.readInt(), margin = in.readInt();
            final var rotation = TextureAtlasRotationMode.valueOf(in.readUTF());
            final double capturedScale = in.readDouble();
            final int count = in.readInt();
            final var items = new ArrayList<TextureAtlasPolygonItem>();
            for (int i = 0; i < count; i++) {
                final String id = in.readUTF();
                final int iw = in.readInt(), ih = in.readInt();
                final var source = TextureAtlasOutlineSource.valueOf(in.readUTF());
                final var policy = new TextureAtlasItemLayoutPolicy(
                        id, in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean());
                double[] matrix = null;
                if (in.readBoolean()) {
                    matrix = new double[6];
                    for (int j = 0; j < 6; j++) matrix[j] = in.readDouble();
                }
                final boolean placed = in.readBoolean();
                final var rings = new ArrayList<double[][]>();
                final int ringCount = in.readInt();
                for (int r = 0; r < ringCount; r++) {
                    final double[][] ring = new double[in.readInt()][2];
                    for (var p : ring) {
                        p[0] = in.readDouble();
                        p[1] = in.readDouble();
                    }
                    rings.add(ring);
                }
                items.add(new TextureAtlasPolygonItem(
                        id, iw, ih, new TextureAtlasOutline(rings), policy, source, matrix, placed));
            }
            if (in.read() != -1) throw new IllegalArgumentException("Trailing capture bytes");
            return new Input(
                    List.copyOf(items),
                    new TextureAtlasPolygonConstraints(
                            w,
                            h,
                            margin,
                            rotation,
                            requested < 0 ? capturedScale : requested,
                            TextureAtlasLayoutBackend.AUTO,
                            TextureAtlasLayoutQuality.BALANCED));
        }
    }

    static void draw(Path file, Input input, TextureAtlasPolygonPlan polygon, TextureAtlasLayoutPlan rectangle)
            throws Exception {
        final var c = input.constraints();
        final double display = Math.min(1, 1600.0 / Math.max(c.pageWidth(), c.pageHeight()));
        final var image = new BufferedImage(
                (int) Math.ceil(c.pageWidth() * display),
                (int) Math.ceil(c.pageHeight() * display),
                BufferedImage.TYPE_INT_RGB);
        final var g = image.createGraphics();
        g.setColor(new Color(24, 28, 35));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.scale(display, display);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (var item : input.items()) {
            final AffineTransform transform;
            if (polygon != null) {
                final var p = polygon.placementFor(item.textureId());
                if (p.isEmpty()) continue;
                transform = AffineTransform.getTranslateInstance(
                        p.get().x(), p.get().y());
                transform.rotate(Math.toRadians(p.get().angleDeg()));
                transform.scale(p.get().scale(), p.get().scale());
            } else {
                final var found = rectangle.placements().stream()
                        .filter(p -> p.textureId().equals(item.textureId()))
                        .findFirst();
                if (found.isEmpty()) continue;
                final var p = found.get();
                transform = AffineTransform.getTranslateInstance(p.x(), p.y());
                if (p.rotated()) {
                    transform.translate(item.height() * rectangle.scale(), 0);
                    transform.quadrantRotate(1);
                }
                transform.scale(rectangle.scale(), rectangle.scale());
                // The rectangle SDK carries dimensions but no material-local
                // source origin. Normalize the captured contour into that box
                // for this comparison drawing; this is not a host screenshot.
                final var bounds = OutlineRasterizer.shape(item.outline(), new AffineTransform())
                        .getBounds2D();
                transform.translate(-bounds.getMinX(), -bounds.getMinY());
            }
            final var shape = OutlineRasterizer.shape(item.outline(), transform);
            g.setColor(Color.getHSBColor(Math.floorMod(item.textureId().hashCode(), 360) / 360f, .48f, .92f));
            g.fill(shape);
            g.setColor(new Color(230, 235, 240));
            g.draw(shape);
        }
        g.dispose();
        ImageIO.write(image, "png", file.toFile());
    }
}
