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
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Map;

/** Dedicated modeling-view entry for ArtMesh mirror editing. */
public final class ArtMeshMirrorPlugin implements TurboismPlugin {
    private static final String BUTTON = "artmesh-mirror.toggle";
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
                        .contributeStateButtons(new ViewContextMenuRegistry.StateButtonContribution(
                                BUTTON,
                                Map.of(0, icon(false), 1, icon(true)),
                                mirror.isEnabled() ? 1 : 0,
                                ignored -> toggle())));
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

    private static UiRasterImage icon(boolean enabled) {
        BufferedImage image = new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(enabled ? new Color(33, 157, 210) : new Color(128, 128, 128));
            graphics.setStroke(new BasicStroke(5));
            graphics.drawLine(48, 10, 48, 86);
            graphics.drawLine(20, 24, 30, 70);
            graphics.drawLine(76, 24, 66, 70);
            graphics.drawLine(20, 24, 30, 24);
            graphics.drawLine(66, 24, 76, 24);
            for (int[] point : new int[][] {{20, 24}, {30, 70}, {76, 24}, {66, 70}})
                graphics.fillRect(point[0] - 5, point[1] - 5, 10, 10);
            graphics.fillOval(42, 42, 12, 12);
        } finally {
            graphics.dispose();
        }
        return new UiRasterImage(96, 96, image.getRGB(0, 0, 96, 96, null, 0, 96));
    }
}
