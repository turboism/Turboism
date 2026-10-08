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
    private static final String MARKER_PRECHECK = "com.live2d.cubism.appCtrlImpl.ap";
    private static final String MARKER_COMMAND = "com.live2d.cubism.appCtrlImpl.al";
    private static final String MARKER_WORKER = "com.live2d.cubism.doc.model.exporter.w";
    private static final String STACK_MODE = "stacks";

    private AtlasTimingAgentHarness() {
    }

    public static void main(final String[] args) throws Exception {
        final Path output = Path.of(args[0]);
        final boolean stackMode = args.length > 1 && STACK_MODE.equals(args[1]);
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

        int expectedStacks = 5;
        if (stackMode) {
            // Marker fixtures: the stubs only place their owner.method names on the sampled
            // stacks — that is all the evidence means, no causal claim.
            final Object apMarker = Class.forName(MARKER_PRECHECK, true, loader)
                .getDeclaredConstructor().newInstance();
            apMarker.getClass().getMethod("a", atlas.getClass()).invoke(apMarker, atlas);
            final Object alMarker = Class.forName(MARKER_COMMAND, true, loader)
                .getDeclaredConstructor().newInstance();
            alMarker.getClass().getMethod("c", atlas.getClass()).invoke(alMarker, atlas);
            final Object workerMarker = Class.forName(MARKER_WORKER, true, loader)
                .getDeclaredConstructor().newInstance();
            final Thread worker = new Thread(() -> {
                try {
                    workerMarker.getClass().getMethod("a", atlas.getClass())
                        .invoke(workerMarker, atlas);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
            }, "t029-marker-worker");
            worker.start();
            worker.join();
            expectedStacks = 10;
        }

        final Path summary = output.resolve("timing-summary.properties");
        String body = "";
        final long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(summary)) {
                body = Files.readString(summary);
                if (complete(body, stackMode)) break;
            }
            Thread.sleep(200);
        }
        if (!complete(body, stackMode)) {
            System.out.println("ATLAS_TIMING_AGENT_HARNESS FAILED\n" + body);
            System.exit(1);
        }
        if (!body.contains("targets=") || body.contains("ABSENT") || body.contains("ABSTRACT")
            || body.contains("x0")) {
            System.out.println("ATLAS_TIMING_AGENT_HARNESS FAILED target states incomplete\n"
                + body);
            System.exit(1);
        }
        if (stackMode) {
            verifyStackEvidence(output, expectedStacks);
        }
        System.out.println("ATLAS_TIMING_AGENT_HARNESS PASS records driven metric counts"
            + (stackMode ? " stackSamples=" + expectedStacks : ""));
    }

    /**
     * Waits for the single-writer stack status to report {@code written}, then checks the
     * raw stack lines: marker frames recorded verbatim, unique reservation ids, worker
     * thread attribution. No caller classification is expected or asserted.
     */
    private static void verifyStackEvidence(final Path output, final int expectedStacks)
            throws Exception {
        final Path status = output.resolve("timing-stacks.status");
        final Path records = output.resolve("timing-stacks.txt");
        String statusBody = null;
        long writtenCount = -1L;
        final long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(status)) {
                statusBody = Files.readString(status);
                final java.util.Properties props = new java.util.Properties();
                props.load(new java.io.StringReader(statusBody));
                final String written = props.getProperty("written");
                if (written != null) {
                    writtenCount = Long.parseLong(written);
                    if (writtenCount >= expectedStacks) {
                        break;
                    }
                }
            }
            Thread.sleep(50);
        }
        if (statusBody == null || writtenCount != expectedStacks) {
            System.out.println("ATLAS_TIMING_AGENT_HARNESS FAILED stack status incomplete\n"
                + statusBody);
            System.exit(1);
        }
        if (!Files.isRegularFile(records)) {
            System.out.println("ATLAS_TIMING_AGENT_HARNESS FAILED stack records missing");
            System.exit(1);
        }
        final java.util.List<String> lines = Files.readAllLines(records);
        boolean sawPrecheck = false;
        boolean sawCommand = false;
        boolean sawWorker = false;
        boolean sawWorkerThread = false;
        final java.util.Set<String> seqs = new java.util.HashSet<>();
        for (final String line : lines) {
            final int seqAt = line.indexOf("seq=");
            if (seqAt >= 0) {
                seqs.add(line.substring(seqAt + 4, line.indexOf(' ', seqAt)));
            }
            if (line.contains("com.live2d.cubism.appCtrlImpl.ap.a")) {
                sawPrecheck = true;
            }
            if (line.contains("com.live2d.cubism.appCtrlImpl.al.c")) {
                sawCommand = true;
            }
            if (line.contains("com.live2d.cubism.doc.model.exporter.w.a")) {
                sawWorker = true;
            }
            if (line.contains("thread=\"t029-marker-worker\"")) {
                sawWorkerThread = true;
            }
        }
        if (!(sawPrecheck && sawCommand && sawWorker && sawWorkerThread)
                || seqs.size() != lines.size() || lines.size() != expectedStacks) {
            System.out.println("ATLAS_TIMING_AGENT_HARNESS FAILED stack evidence\n"
                + String.join("\n", lines));
            System.exit(1);
        }
    }

    private static boolean complete(final String body, final boolean stackMode) {
        final java.util.Properties summary = new java.util.Properties();
        try {
            summary.load(new java.io.StringReader(body));
        } catch (java.io.IOException failure) {
            return false;
        }
        final String updates = stackMode ? "4" : "2";
        final String setups = stackMode ? "6" : "3";
        final String[][] expectedCounts = {
            {"updateMesh", "1"}, {"drawModelImage", "1"}, {"updateTexture", updates},
            {"setupCacheImage", setups}, {"setupEditLayer", "2"}, {"editorBatch", "1"},
            {"editorInit", "1"}, {"updateVertices", "1"}, {"updateIndices", "1"},
            {"delaunayCompute", "1"}, {"delaunayApply", "1"}, {"autoTriangulate", "1"},
        };
        for (final String[] expected : expectedCounts) {
            if (!expected[1].equals(summary.getProperty("metric." + expected[0] + ".count"))) {
                return false;
            }
        }
        return true;
    }
}
