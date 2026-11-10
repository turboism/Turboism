package dev.turboism.plugin.turbomesh.domain.regions;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Writes a four panel overview so a reviewer can compare the ported result with the prototype.
 *
 * <p>The dump runs only when {@code -Dturbomesh.regionDump=<dir>} is set, because it is a
 * diagnostic aid rather than part of the planner contract.</p>
 */
final class RegionDumpWriter {

    /** System property that selects the dump directory. */
    static final String DUMP_PROPERTY = "turbomesh.regionDump";

    /** Panel scale for small samples; large samples stay at native resolution. */
    static final int PANEL_SCALE = 2;

    /** Largest sample edge that still receives {@link #PANEL_SCALE} magnification. */
    static final int MAGNIFY_LIMIT_PX = 400;

    private static final int CHECKER_SIZE = 8;

    private static final int HEADER_HEIGHT = 18;

    private static final Color BACKGROUND = new Color(21, 28, 39);

    private static final Color CHECKER_LIGHT = new Color(48, 48, 48);

    private static final Color CHECKER_DARK = new Color(36, 36, 36);

    private static final Color OUTLINE_COLOR = new Color(255, 209, 102);

    private static final Color TEXTURE_COLOR = new Color(68, 227, 223);

    private static final Color DIRECTION_COLOR = new Color(185, 161, 255);

    private static final Color MESH_COLOR = new Color(137, 150, 168);

    private static final Color VERTEX_COLOR = new Color(255, 112, 187);

    private static final Color[] REGION_PALETTE = {
        new Color(233, 96, 96), new Color(96, 180, 233), new Color(140, 214, 120), new Color(233, 190, 96),
        new Color(206, 130, 233), new Color(96, 214, 178), new Color(233, 143, 96), new Color(150, 150, 233),
        new Color(214, 214, 120), new Color(120, 214, 214), new Color(233, 120, 189), new Color(170, 214, 96)
    };

    private RegionDumpWriter() {}

    /** Writes an overview PNG when the dump property is set; does nothing otherwise. */
    static void dumpIfRequested(
            final RegionPlacementPlan plan, final TextureRegionPlannerTest.Sample sample, final String name) {
        final String directory = System.getProperty(DUMP_PROPERTY);
        if (directory == null || directory.isBlank()) {
            return;
        }
        try {
            write(plan, sample, name, Path.of(directory));
        } catch (final IOException error) {
            throw new IllegalStateException("cannot write region dump for " + name, error);
        }
    }

    private static void write(
            final RegionPlacementPlan plan,
            final TextureRegionPlannerTest.Sample sample,
            final String name,
            final Path directory)
            throws IOException {
        final int width = sample.width();
        final int height = sample.height();
        final int scale = Math.max(width, height) <= MAGNIFY_LIMIT_PX ? PANEL_SCALE : 1;
        final BufferedImage overview =
                new BufferedImage(width * scale * 4, height * scale + HEADER_HEIGHT, BufferedImage.TYPE_INT_RGB);
        final Graphics2D graphics = overview.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(BACKGROUND);
        graphics.fillRect(0, 0, overview.getWidth(), overview.getHeight());
        graphics.setColor(new Color(240, 243, 252));
        graphics.drawString(
                name + "  regions=" + plan.regions().regionCount() + "  vertices="
                        + plan.vertexCountAfterMerge() + " (" + plan.vertexCountBeforeMerge()
                        + ")  triangles=" + plan.triangleCount() + "  triangleIou="
                        + String.format("%.4f", plan.triangleIou())
                        + "  " + plan.shapeFamily() + "  fallback=" + plan.fallback(),
                6,
                13);
        graphics.drawImage(sourcePanel(sample, scale), 0, HEADER_HEIGHT, null);
        graphics.drawImage(regionPanel(plan, sample, scale), width * scale, HEADER_HEIGHT, null);
        graphics.drawImage(constraintPanel(plan, sample, scale), width * scale * 2, HEADER_HEIGHT, null);
        graphics.drawImage(meshPanel(plan, sample, scale), width * scale * 3, HEADER_HEIGHT, null);
        graphics.dispose();
        final Path target = directory.resolve("overview-" + name + ".png");
        directory.toFile().mkdirs();
        ImageIO.write(overview, "png", new File(target.toString()));
    }

    private static BufferedImage sourcePanel(final TextureRegionPlannerTest.Sample sample, final int scale) {
        return compose(sample, scale, null);
    }

    private static BufferedImage regionPanel(
            final RegionPlacementPlan plan, final TextureRegionPlannerTest.Sample sample, final int scale) {
        final BufferedImage panel = compose(sample, scale, null);
        final Graphics2D graphics = panel.createGraphics();
        final RegionPlacementPlan.RegionGrid grid = plan.regions();
        final int[] labels = grid.labels();
        for (int y = 0; y < grid.height(); y++) {
            for (int x = 0; x < grid.width(); x++) {
                final int label = labels[y * grid.width() + x];
                if (label == 0) {
                    continue;
                }
                graphics.setColor(REGION_PALETTE[Math.floorMod(label, REGION_PALETTE.length)]);
                graphics.fillRect((grid.originX() + x) * scale, (grid.originY() + y) * scale, scale, scale);
            }
        }
        graphics.dispose();
        return panel;
    }

    private static BufferedImage constraintPanel(
            final RegionPlacementPlan plan, final TextureRegionPlannerTest.Sample sample, final int scale) {
        final BufferedImage panel = compose(sample, scale, new Color(0, 0, 0, 110));
        drawConstraints(panel.createGraphics(), plan, scale, true);
        return panel;
    }

    private static BufferedImage meshPanel(
            final RegionPlacementPlan plan, final TextureRegionPlannerTest.Sample sample, final int scale) {
        final BufferedImage panel = compose(sample, scale, new Color(0, 0, 0, 150));
        final Graphics2D graphics = panel.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        final int[] indices = plan.triangleIndices();
        graphics.setColor(MESH_COLOR);
        for (int t = 0; t + 2 < indices.length; t += 3) {
            drawSegment(graphics, plan, indices[t], indices[t + 1], scale, 1);
            drawSegment(graphics, plan, indices[t + 1], indices[t + 2], scale, 1);
            drawSegment(graphics, plan, indices[t + 2], indices[t], scale, 1);
        }
        drawConstraints(graphics, plan, scale, false);
        graphics.setColor(VERTEX_COLOR);
        for (final dev.turboism.sdk.cubism.model.Point2 vertex : plan.vertices()) {
            final int radius = 2;
            graphics.fillOval(
                    (int) Math.round(vertex.x() * scale) - radius,
                    (int) Math.round(vertex.y() * scale) - radius,
                    2 * radius + 1,
                    2 * radius + 1);
        }
        graphics.dispose();
        return panel;
    }

    private static void drawConstraints(
            final Graphics2D graphics, final RegionPlacementPlan plan, final int scale, final boolean thick) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        final List<RegionPlacementPlan.Constraint> constraints = plan.constraints();
        for (final RegionPlacementPlan.Constraint constraint : constraints) {
            graphics.setColor(colorOf(constraint.role()));
            drawSegment(graphics, plan, constraint.a(), constraint.b(), scale, thick ? 2 : 1);
        }
    }

    private static void drawSegment(
            final Graphics2D graphics,
            final RegionPlacementPlan plan,
            final int a,
            final int b,
            final int scale,
            final int thickness) {
        final dev.turboism.sdk.cubism.model.Point2 first = plan.vertices().get(a);
        final dev.turboism.sdk.cubism.model.Point2 second = plan.vertices().get(b);
        for (int i = 0; i < thickness; i++) {
            graphics.drawLine(
                    (int) Math.round(first.x() * scale) + i,
                    (int) Math.round(first.y() * scale),
                    (int) Math.round(second.x() * scale) + i,
                    (int) Math.round(second.y() * scale));
        }
    }

    private static Color colorOf(final ConstraintRole role) {
        return switch (role) {
            case OUTLINE -> OUTLINE_COLOR;
            case TEXTURE_LINE -> TEXTURE_COLOR;
            case DIRECTION_LINE -> DIRECTION_COLOR;
        };
    }

    private static BufferedImage compose(
            final TextureRegionPlannerTest.Sample sample, final int scale, final Color overlay) {
        final int width = sample.width() * scale;
        final int height = sample.height() * scale;
        final BufferedImage panel = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D graphics = panel.createGraphics();
        for (int y = 0; y < height; y += CHECKER_SIZE) {
            for (int x = 0; x < width; x += CHECKER_SIZE) {
                graphics.setColor(((x / CHECKER_SIZE) + (y / CHECKER_SIZE)) % 2 == 0 ? CHECKER_LIGHT : CHECKER_DARK);
                graphics.fillRect(x, y, CHECKER_SIZE, CHECKER_SIZE);
            }
        }
        final int[] argb = sample.argb();
        for (int y = 0; y < sample.height(); y++) {
            for (int x = 0; x < sample.width(); x++) {
                final int pixel = argb[y * sample.width() + x];
                final int alpha = (pixel >>> 24) & 0xFF;
                if (alpha == 0) {
                    continue;
                }
                final Color source = new Color((pixel >>> 16) & 0xFF, (pixel >>> 8) & 0xFF, pixel & 0xFF);
                graphics.setColor(new Color(source.getRed(), source.getGreen(), source.getBlue(), alpha));
                graphics.fillRect(x * scale, y * scale, scale, scale);
            }
        }
        if (overlay != null) {
            graphics.setColor(overlay);
            graphics.fillRect(0, 0, width, height);
        }
        graphics.dispose();
        return panel;
    }
}
