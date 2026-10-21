package dev.turboism.validation.nesting;

import dev.turboism.sdk.cubism.textureatlas.*;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Validation-only wrapper: captures issued geometry and calls the real registered plugin. */
public final class NestingCapturePlugin implements TurboismPlugin {
    private PluginContext context;

    @Override public void init(PluginContext context) { this.context = context; }

    @Override public void enable() {
        context.cubism().textureAtlasAlgorithms().register(new TextureAtlasLayoutAlgorithm(
            "nesting-validation", "Nesting Validation", false, false, new TextureAtlasPolygonLayoutPlanner() {
                @Override public TextureAtlasPolygonPlan planPolygon(TextureAtlasPolygonLayoutSnapshot snapshot, boolean parallel) {
                    Path directory = Path.of(System.getProperty("turboism.home")).resolve("state/atlas-validation");
                    try {
                        Files.createDirectories(directory);
                        long started = System.nanoTime();
                        captureInput(directory.resolve("nesting-input.bin"), snapshot);
                        long captureNanos = System.nanoTime() - started;
                        var production = context.cubism().textureAtlasAlgorithms().find("bitmask-nesting").orElseThrow().planner();
                        if (!(production instanceof TextureAtlasPolygonLayoutPlanner polygon))
                            throw new IllegalStateException("Real nesting plugin not registered");
                        var plan = polygon.planPolygon(snapshot, parallel);
                        capturePlan(directory.resolve("nesting-plan.bin"), plan);
                        Files.writeString(directory.resolve("nesting-capture.properties"),
                            "inputCaptureMs=" + captureNanos / 1_000_000.0 + "\nitems=" + snapshot.items().size() + "\n");
                        return plan;
                    } catch (IOException failure) { throw new IllegalStateException("Nesting capture failed", failure); }
                }
                @Override public TextureAtlasLayoutPlan plan(List<TextureAtlasLayoutItem> items, TextureAtlasLayoutConstraints c) {
                    throw new UnsupportedOperationException("Validation requires real contour snapshots");
                }
            }));
        context.logger().info("NESTING_CAPTURE_READY");
    }

    private static void captureInput(Path file, TextureAtlasPolygonLayoutSnapshot snapshot) throws IOException {
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            var c = snapshot.constraints();
            out.writeUTF("turboism.nesting.input.v1");
            out.writeInt(c.pageWidth()); out.writeInt(c.pageHeight()); out.writeInt(c.margin());
            out.writeUTF(c.rotationMode().name()); out.writeDouble(c.requestedScale());
            out.writeInt(snapshot.items().size());
            for (var item : snapshot.items()) {
                out.writeUTF(item.textureId()); out.writeInt(item.width()); out.writeInt(item.height());
                out.writeUTF(item.outlineSource().name());
                var policy = item.policy();
                out.writeBoolean(policy.participate()); out.writeBoolean(policy.preserveAngle());
                out.writeBoolean(policy.preserveScale()); out.writeBoolean(policy.preservePosition());
                var matrix = item.currentMatrix();
                out.writeBoolean(matrix != null);
                if (matrix != null) for (double value : matrix) out.writeDouble(value);
                out.writeBoolean(item.currentlyPlaced());
                out.writeInt(item.outline().rings().size());
                for (var ring : item.outline().rings()) {
                    out.writeInt(ring.length);
                    for (var p : ring) { out.writeDouble(p[0]); out.writeDouble(p[1]); }
                }
            }
        }
    }

    private static void capturePlan(Path file, TextureAtlasPolygonPlan plan) throws IOException {
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeUTF("turboism.nesting.plan.v1");
            out.writeDouble(plan.scale()); out.writeInt(plan.placements().size());
            for (var p : plan.placements()) {
                out.writeUTF(p.textureId()); out.writeDouble(p.x()); out.writeDouble(p.y());
                out.writeDouble(p.angleDeg()); out.writeDouble(p.scale());
            }
            out.writeInt(plan.overflowTextureIds().size());
            for (String id : plan.overflowTextureIds()) out.writeUTF(id);
        }
    }
}
