package dev.turboism.plugin.atlasnesting.layout;

import dev.turboism.plugin.atlasmaxrectsbssf.layout.CurrentPageTextureAtlasPlanner;
import dev.turboism.sdk.cubism.textureatlas.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Standalone offline benchmark. Checks are outside timed regions. */
public final class NestingBenchmark {
    public static void main(final String[] args) throws Exception {
        final StringBuilder csv =
                new StringBuilder("shape,count,page,scaleMode,algorithm,medianMs,minMs,maxMs,scale,placed\n");
        for (final String kind : List.of("rectangle", "concave")) {
            for (final int count : new int[] {100, 500}) {
                for (final int page : new int[] {2048, 4096}) {
                    final var items = items(kind, count);
                    final var rects = items.stream()
                            .map(i -> new TextureAtlasLayoutItem(i.textureId(), i.width(), i.height()))
                            .toList();
                    for (final double requested : new double[] {1, 0}) {
                        final String caseName =
                                kind + "-" + count + "-" + page + "-" + (requested == 0 ? "auto" : "fixed");
                        if (args.length > 1 && !args[1].equals(caseName)) continue;
                        final var c = BitmaskNestingPlannerTest.constraints(
                                page, page, 3, TextureAtlasRotationMode.FREE, requested);
                        final var rc = TextureAtlasLayoutConstraints.currentPage(page, page, 3, true, requested);
                        for (final String algorithm : List.of("bssf", "nesting")) {
                            final double[] times = new double[9];
                            TextureAtlasPolygonPlan polygon = null;
                            TextureAtlasLayoutPlan rectangle = null;
                            for (int round = -5; round < times.length; round++) {
                                final long start = System.nanoTime();
                                if (algorithm.equals("nesting")) polygon = new BitmaskNestingPlanner(6).plan(items, c);
                                else rectangle = new CurrentPageTextureAtlasPlanner().plan(rects, rc, false);
                                final double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                                if (round >= 0) times[round] = elapsed;
                            }
                            if (polygon != null) BitmaskNestingPlannerTest.assertValid(items, polygon, c, 6);
                            Arrays.sort(times);
                            final String line = String.format(
                                    Locale.ROOT,
                                    "%s,%d,%d,%s,%s,%.3f,%.3f,%.3f,%.6f,%d%n",
                                    kind,
                                    count,
                                    page,
                                    requested == 0 ? "auto" : "fixed",
                                    algorithm,
                                    times[4],
                                    times[0],
                                    times[8],
                                    polygon != null ? polygon.scale() : rectangle.scale(),
                                    polygon != null
                                            ? polygon.placements().size()
                                            : rectangle.placements().size());
                            csv.append(line);
                            System.out.print(line);
                            if (caseName.equals("concave-500-2048-fixed")) {
                                final Path destination =
                                        Path.of(args[0]).getParent().resolve("nesting-replay");
                                Files.createDirectories(destination);
                                CapturedNestingBenchmark.draw(
                                        destination.resolve("synthetic-concave-500-" + algorithm + ".png"),
                                        new CapturedNestingBenchmark.Input(items, c),
                                        polygon,
                                        rectangle);
                            }
                        }
                    }
                }
            }
        }
        final Path destination = Path.of(args[0]);
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, csv);
    }

    private static List<TextureAtlasPolygonItem> items(final String kind, final int count) {
        final var items = new ArrayList<TextureAtlasPolygonItem>();
        final Random random = new Random(51);
        for (int i = 0; i < count; i++) {
            final int w = 30 + random.nextInt(150), h = 30 + random.nextInt(170);
            final double[][] ring = kind.equals("rectangle")
                    ? new double[][] {{0, 0}, {w, 0}, {w, h}, {0, h}}
                    : new double[][] {{0, 0}, {w, 0}, {w, h * .25}, {w * .25, h * .25}, {w * .25, h}, {0, h}};
            items.add(BitmaskNestingPlannerTest.item("item-" + i, ring));
        }
        return items;
    }

    private NestingBenchmark() {}
}
