package dev.turboism.validation.atlastiming;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Live-JVM harness for the real {@code -javaagent} leg. Loads host-named fixture classes through
 * reflection — keeping a zero compile-time dependency on them — and drives every instrumented
 * method so the packaged agent must weave them at class load.
 */
public final class AtlasTimingAgentHarness {
    private static final String ATLAS =
        "com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas";
    private static final String DATA_MODEL =
        "com.live2d.cubism.doc.modeling.ui.atlasEditor.impl.TAE_DataModel";
    private static final String EDIT_LAYER =
        "com.live2d.cubism.doc.modeling.ui.atlasEditor.impl.TAE__EditLayer_ModelImage";
    private static final String MESH = "com.live2d.graphics3d.editableMesh.GEditableMesh2";
    private static final String KERNEL = "com.live2d.util.f.g";
    private static final String PROGRESS = "com.live2d.util.a.a";
    private static final String MESH_CONTEXT = "com.live2d.util.j.a";

    private AtlasTimingAgentHarness() {
    }

    public static void main(final String[] args) throws Exception {
        final Path output = Path.of(args[0]);
        final ClassLoader loader = AtlasTimingAgentHarness.class.getClassLoader();
        final Class<?> progress = Class.forName(PROGRESS, false, loader);
        final Class<?> meshContext = Class.forName(MESH_CONTEXT, false, loader);

        final Object atlas = Class.forName(ATLAS, true, loader)
            .getDeclaredConstructor().newInstance();
        atlas.getClass().getMethod("updateTexture", boolean.class, boolean.class, progress)
            .invoke(atlas, true, true, null);
        atlas.getClass().getMethod("updateTexture", boolean.class, boolean.class, progress)
            .invoke(atlas, false, true, null);
        atlas.getClass().getMethod("setupCacheImage$cubism", boolean.class, progress)
            .invoke(atlas, true, null);

        final Object dataModel = Class.forName(DATA_MODEL, true, loader)
            .getDeclaredConstructor().newInstance();
        dataModel.getClass().getMethod("v").invoke(dataModel);
        dataModel.getClass().getMethod("driveZ").invoke(dataModel);

        final Object layer = Class.forName(EDIT_LAYER, true, loader)
            .getDeclaredConstructor().newInstance();
        layer.getClass().getMethod("setupEditLayer").invoke(layer);
        layer.getClass().getMethod("setupEditLayer").invoke(layer);

        final Object mesh = Class.forName(MESH, true, loader)
            .getDeclaredConstructor().newInstance();
        mesh.getClass().getMethod("driveMesh", meshContext).invoke(mesh, new Object[]{null});

        final Object kernel = Class.forName(KERNEL, true, loader)
            .getDeclaredConstructor().newInstance();
        kernel.getClass().getMethod("a", BufferedImage.class, java.awt.Graphics.class,
                BufferedImage.class, int.class, int.class, double.class, boolean.class)
            .invoke(kernel, new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), null,
                new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), 0, 0, 1.0, true);

        final Path summary = output.resolve("timing-summary.properties");
        String body = "";
        final long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(summary)) {
                body = Files.readString(summary);
                if (complete(body)) break;
            }
            Thread.sleep(200);
        }
        if (!complete(body)) {
            System.out.println("ATLAS_TIMING_AGENT_HARNESS FAILED\n" + body);
            System.exit(1);
        }
        if (!body.contains("targets=") || body.contains("ABSENT") || body.contains("ABSTRACT")
            || body.contains("x0")) {
            System.out.println("ATLAS_TIMING_AGENT_HARNESS FAILED target states incomplete\n"
                + body);
            System.exit(1);
        }
        System.out.println("ATLAS_TIMING_AGENT_HARNESS PASS records all seven targets");
    }

    private static boolean complete(final String body) {
        return body.contains("metric.updateMesh.count=1")
            && body.contains("metric.drawModelImage.count=1")
            && body.contains("metric.updateTexture.count=2")
            && body.contains("metric.setupCacheImage.count=3")
            && body.contains("metric.setupEditLayer.count=2")
            && body.contains("metric.editorBatch.count=1")
            && body.contains("metric.editorInit.count=1");
    }
}
