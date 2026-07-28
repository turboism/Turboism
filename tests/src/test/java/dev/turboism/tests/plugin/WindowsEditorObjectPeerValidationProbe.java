package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.plugin.PluginContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Manual-test-only peer plugin proving a second plugin remains usable after the primary scope closes. */
public final class WindowsEditorObjectPeerValidationProbe implements CubismPlugin {

    private PluginContext context;
    private Thread validationThread;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
    }

    @Override
    public void enable() {
        if (!"plugin-scope-close".equals(System.getProperty("turboism.editorObjectValidation.mode"))) return;
        validationThread = new Thread(this::runPeerValidation, "turboism-editor-object-peer-validation");
        validationThread.setDaemon(true);
        validationThread.start();
    }

    @Override
    public void disable() {
        if (validationThread != null) validationThread.interrupt();
    }

    private void runPeerValidation() {
        final Path home = Path.of(System.getProperty("turboism.home"));
        final Path request = home.resolve("state").resolve("editor-object-peer-request.txt");
        final Path artifact = home.resolve("logs").resolve("editor-object-peer-scope-close.txt");
        try {
            for (int attempt = 0; attempt < 600 && !Files.exists(request); attempt++) {
                Thread.sleep(100L);
            }
            if (!Files.exists(request)) throw new IllegalStateException("Primary plugin close request was not observed");
            final CubismModel model = awaitModel();
            final boolean modelUsable = model.id() != null;
            final boolean meshUsable = !model.drawables().all().isEmpty() && model.drawables().all().get(0).geometry() != null;
            final boolean warpUsable = !model.warpDeformers().all().isEmpty() && model.warpDeformers().all().get(0).grid() != null;
            final boolean rotationUsable = !model.rotationDeformers().all().isEmpty()
                && model.rotationDeformers().all().get(0).form() != null;
            final boolean passed = modelUsable && meshUsable && warpUsable && rotationUsable;
            Files.createDirectories(artifact.getParent());
            Files.writeString(
                artifact,
                "status=" + (passed ? "PASS" : "FAIL")
                    + "\nphase=peer-after-primary-scope-close"
                    + "\nsecondPluginUsable=" + passed
                    + "\nmodelUsable=" + modelUsable
                    + "\nmeshUsable=" + meshUsable
                    + "\nwarpUsable=" + warpUsable
                    + "\nrotationUsable=" + rotationUsable + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception exception) {
            try {
                Files.createDirectories(artifact.getParent());
                Files.writeString(
                    artifact,
                    "status=FAIL\nphase=peer-after-primary-scope-close\nerror=" + exception + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                );
            } catch (Exception ignored) {
                // Manual evidence probe cannot recover when its artifact path is unavailable.
            }
        }
    }

    private CubismModel awaitModel() throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 600; attempt++) {
            try {
                final CubismModel model = context.cubism().model().active();
                if (!model.drawables().all().isEmpty()
                    && !model.warpDeformers().all().isEmpty()
                    && !model.rotationDeformers().all().isEmpty()) {
                    return model;
                }
            } catch (Exception exception) {
                lastFailure = exception;
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("Peer plugin did not observe an active Editor model", lastFailure);
    }
}
