package dev.turboism.plugin.artmeshmirror;

import dev.turboism.sdk.cubism.mesh.ArtMeshMirrorService;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.CanvasHintHandle;
import dev.turboism.sdk.ui.CanvasHintNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.resource.UiRasterImage;
import dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Map;

/** Dedicated modeling-view entry for ArtMesh mirror editing. */
public final class ArtMeshMirrorPlugin implements TurboismPlugin {
    private static final String BUTTON = "artmesh-mirror.toggle";
    /** Stable strip position independent of plugin load order. */
    private static final int STRIP_ORDER = 10;

    private static final int ICON_SIZE = 96;
    private static final float ICON_STROKE_WIDTH = 5.0f;
    private static final int AXIS_X = 48;
    private static final int AXIS_TOP_Y = 10;
    private static final int AXIS_BOTTOM_Y = 86;
    private static final int LEFT_TOP_X = 20;
    private static final int LEFT_BOTTOM_X = 30;
    private static final int RIGHT_TOP_X = 76;
    private static final int RIGHT_BOTTOM_X = 66;
    private static final int MESH_TOP_Y = 24;
    private static final int MESH_BOTTOM_Y = 70;
    private static final int VERTEX_HALF = 5;
    private static final int VERTEX_SIZE = 10;
    private static final int PIVOT_OFFSET = 42;
    private static final int PIVOT_SIZE = 12;
    private static final Color ENABLED_COLOR = new Color(33, 157, 210);
    private static final Color OFF_COLOR = new Color(128, 128, 128);

    private static final float BADGE_WIDTH_RATIO = 0.28f;
    private static final int BADGE_MIN_SIZE = 7;
    private static final float BADGE_MARGIN_DIVISOR = 32.0f;
    private static final int BADGE_SUPERSAMPLE = 8;
    private static final int BADGE_SIZE = Math.max(BADGE_MIN_SIZE, Math.round(ICON_SIZE * BADGE_WIDTH_RATIO));
    private static final int BADGE_MARGIN = Math.max(1, Math.round(ICON_SIZE / BADGE_MARGIN_DIVISOR));
    private static final int BADGE_X = ICON_SIZE - BADGE_SIZE - BADGE_MARGIN;
    private static final int BADGE_Y = ICON_SIZE - BADGE_SIZE - BADGE_MARGIN;
    private static final Color BADGE_KEYLINE = new Color(0xF4, 0xF3, 0xFF);
    private static final Color BADGE_BACKGROUND = new Color(0x67, 0x52, 0xC7);
    private static final Color BADGE_FOREGROUND = Color.WHITE;
    private static final float BADGE_INNER_MIN = 0.10f;
    private static final float BADGE_INNER_SIZE = 0.80f;
    private static final float BADGE_T_BAR_LEFT = 0.22f;
    private static final float BADGE_T_BAR_TOP = 0.23f;
    private static final float BADGE_T_BAR_WIDTH = 0.56f;
    private static final float BADGE_T_BAR_HEIGHT = 0.16f;
    private static final float BADGE_T_STEM_LEFT = 0.42f;
    private static final float BADGE_T_STEM_TOP = 0.36f;
    private static final float BADGE_T_STEM_WIDTH = 0.16f;
    private static final float BADGE_T_STEM_HEIGHT = 0.42f;

    private PluginContext context;
    private ArtMeshMirrorService mirror;
    private CanvasHintHandle hint;

    @Override
    public void init(PluginContext context) {
        this.context = context;
    }

    @Override
    public void enable() {
        mirror = context.services().require(ArtMeshMirrorService.class);
        context.disposableScope().register(mirror.participate());
        context.disposableScope()
                .register(context.services()
                        .require(ViewContextMenuRegistry.class)
                        .contributeStateButtons(
                                new ViewContextMenuRegistry.StateButtonContribution(
                                        BUTTON,
                                        Map.of(0, icon(false), 1, icon(true)),
                                        mirror.isEnabled() ? 1 : 0,
                                        ignored -> toggle()),
                                STRIP_ORDER));
        context.logger().info("ArtMesh mirror button installed independently of Warp Deformer symmetry");
    }

    private void toggle() {
        boolean enabled = !mirror.isEnabled();
        mirror.setEnabled(enabled);
        context.services().require(ViewContextMenuRegistry.class).updateButtonState(BUTTON, enabled ? 1 : 0);
        clearHint();
        if (enabled)
            hint = context.services()
                    .require(UiHostCapabilityService.class)
                    .notifyCanvasHint(new CanvasHintNotification(
                            "artmesh-mirror.hint",
                            context.localization().text("mirror.hint"),
                            CanvasHintNotification.UNTIL_DISMISSED));
        context.logger().info("ARTMESH_MIRROR enabled=" + enabled);
    }

    private void clearHint() {
        if (hint != null) {
            hint.close();
            hint = null;
        }
    }

    @Override
    public void disable() {
        if (mirror != null) mirror.setEnabled(false);
        clearHint();
    }

    static UiRasterImage icon(boolean enabled) {
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(enabled ? ENABLED_COLOR : OFF_COLOR);
            graphics.setStroke(new BasicStroke(ICON_STROKE_WIDTH));
            graphics.drawLine(AXIS_X, AXIS_TOP_Y, AXIS_X, AXIS_BOTTOM_Y);
            graphics.drawLine(LEFT_TOP_X, MESH_TOP_Y, LEFT_BOTTOM_X, MESH_BOTTOM_Y);
            graphics.drawLine(RIGHT_TOP_X, MESH_TOP_Y, RIGHT_BOTTOM_X, MESH_BOTTOM_Y);
            graphics.drawLine(LEFT_TOP_X, MESH_TOP_Y, LEFT_BOTTOM_X, MESH_TOP_Y);
            graphics.drawLine(RIGHT_BOTTOM_X, MESH_TOP_Y, RIGHT_TOP_X, MESH_TOP_Y);
            for (int[] point : new int[][] {
                {LEFT_TOP_X, MESH_TOP_Y},
                {LEFT_BOTTOM_X, MESH_BOTTOM_Y},
                {RIGHT_TOP_X, MESH_TOP_Y},
                {RIGHT_BOTTOM_X, MESH_BOTTOM_Y}
            }) {
                graphics.fillRect(point[0] - VERTEX_HALF, point[1] - VERTEX_HALF, VERTEX_SIZE, VERTEX_SIZE);
            }
            graphics.fillOval(PIVOT_OFFSET, PIVOT_OFFSET, PIVOT_SIZE, PIVOT_SIZE);
            drawCornerBadge(graphics);
        } finally {
            graphics.dispose();
        }
        return new UiRasterImage(ICON_SIZE, ICON_SIZE, image.getRGB(0, 0, ICON_SIZE, ICON_SIZE, null, 0, ICON_SIZE));
    }

    /** Draws the Turboism first-party T badge in the bottom-right corner. */
    private static void drawCornerBadge(Graphics2D target) {
        int supersampled = BADGE_SIZE * BADGE_SUPERSAMPLE;
        BufferedImage badge = new BufferedImage(supersampled, supersampled, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = badge.createGraphics();
        try {
            g.setColor(BADGE_KEYLINE);
            g.fillRect(0, 0, supersampled, supersampled);
            g.setColor(BADGE_BACKGROUND);
            fillBadgeRect(g, supersampled, BADGE_INNER_MIN, BADGE_INNER_MIN, BADGE_INNER_SIZE, BADGE_INNER_SIZE);
            g.setColor(BADGE_FOREGROUND);
            fillBadgeRect(g, supersampled, BADGE_T_BAR_LEFT, BADGE_T_BAR_TOP, BADGE_T_BAR_WIDTH, BADGE_T_BAR_HEIGHT);
            fillBadgeRect(
                    g, supersampled, BADGE_T_STEM_LEFT, BADGE_T_STEM_TOP, BADGE_T_STEM_WIDTH, BADGE_T_STEM_HEIGHT);
        } finally {
            g.dispose();
        }
        target.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        target.drawImage(badge, BADGE_X, BADGE_Y, BADGE_SIZE, BADGE_SIZE, null);
    }

    private static void fillBadgeRect(Graphics2D g, int scale, float x, float y, float w, float h) {
        g.fillRect(Math.round(x * scale), Math.round(y * scale), Math.round(w * scale), Math.round(h * scale));
    }
}
