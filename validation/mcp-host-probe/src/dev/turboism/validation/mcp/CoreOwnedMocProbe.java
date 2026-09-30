package dev.turboism.validation.mcp;

import dev.turboism.sdk.cubism.core.*;
import dev.turboism.sdk.plugin.PluginContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/** Production SDK owned-MOC checks, independent of an Editor-to-Core model bridge. */
public final class CoreOwnedMocProbe {
    static final String MOC_SHA256 = "aa59f7a3dc3b30b23b5f10afd9192ed755e4d01dc9a12442d99b972a418e2543";
    private CoreOwnedMocProbe() {}

    /** Records startup failure separately from any native Core assertion. */
    public static void notStarted(final PluginContext context, final Path stateRoot, final String reason) {
        final Properties result = new Properties();
        result.setProperty("schemaVersion", "1");
        result.setProperty("runId", System.getProperty("turboism.validation.runId", ""));
        result.setProperty("hostVersion", System.getProperty("turboism.validation.hostVersion", ""));
        result.setProperty("status", "FAIL");
        result.setProperty("nativeAssertions", "NOT_STARTED");
        result.setProperty("error", reason);
        publish(context, stateRoot, result);
    }

    public static boolean run(final PluginContext context, final Path stateRoot) {
        boolean nativeStateKnown = true;
        final Properties result = new Properties();
        result.setProperty("schemaVersion", "1");
        result.setProperty("runId", System.getProperty("turboism.validation.runId", ""));
        result.setProperty("hostVersion", System.getProperty("turboism.validation.hostVersion", ""));
        result.setProperty("status", "FAIL");
        result.setProperty("fixtureSha256", MOC_SHA256);
        result.setProperty("scope", "Production SDK owned MOC; not Editor model acquisition or all framework capabilities");
        try {
            final byte[] bytes;
            try (var input = CoreOwnedMocProbe.class.getResourceAsStream("/validation/moc/Haru.moc3")) {
                require(input != null, "Pinned MOC fixture is not packaged");
                bytes = input.readNBytes(1024 * 1024 + 1);
            }
            require(bytes.length > 0 && bytes.length <= 1024 * 1024, "Invalid MOC fixture size");
            require(MOC_SHA256.equals(digest(bytes)), "Pinned MOC fixture hash mismatch");
            final String expected = System.getProperty("turboism.validation.expectedCoreVersion", "");
            require(!expected.isBlank(), "Exact observed native Core tuple must be supplied");
            final FutureTask<Void> task = new FutureTask<>(() -> {
                verify(context.cubism().coreRuntime(), MocData.copyOf(bytes), expected, result);
                return null;
            });
            SwingUtilities.invokeLater(task);
            task.get(120, TimeUnit.SECONDS);
            result.setProperty("status", "PASS");
        } catch (Exception failure) {
            if (failure instanceof java.util.concurrent.TimeoutException || failure instanceof InterruptedException) {
                nativeStateKnown = false;
                result.setProperty("nativeCompletion", "UNCERTAIN_NO_FURTHER_WATCHER_HOST_ACTIONS");
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            result.setProperty("error", failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage())
                    .replace('\n', ' ').replace('\r', ' '));
            context.logger().error("SDK owned MOC probe failed", failure);
        }
        publish(context, stateRoot, result);
        return nativeStateKnown;
    }

    private static void publish(final PluginContext context, final Path stateRoot, final Properties result) {
        try {
            final Path pending = stateRoot.resolve("core-owned-moc-result.properties.pending");
            try (var output = Files.newOutputStream(pending)) { result.store(output, "Production SDK owned MOC evidence"); }
            Files.move(pending, stateRoot.resolve("core-owned-moc-result.properties"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            context.logger().info("CORE_OWNED_MOC_RESULT status=" + result.getProperty("status"));
        } catch (Exception failure) {
            context.logger().error("SDK owned MOC evidence publication failed", failure);
        }
    }

    static void verify(final CoreRuntimeInfo runtime, final MocData data, final String expected, final Properties result)
            throws Exception {
        require(SwingUtilities.isEventDispatchThread(), "Core SDK checks must run on the EDT");
        require(expected.equals(runtime.version().toString()), "Observed Core tuple differs from expected tuple");
        result.setProperty("nativeCoreVersion", runtime.version().toString());
        result.setProperty("capabilities", runtime.capabilities().toString());
        final MocInspector inspector = runtime.mocInspector();
        require(inspector.latestVersion() != MocVersion.UNKNOWN, "Latest MOC version is unknown");
        final MocInfo inspection = inspector.inspect(data);
        require(inspection.version() != MocVersion.UNKNOWN && inspection.consistency() == MocConsistency.CONSISTENT,
                "Official MOC is not known and consistent");
        result.setProperty("mocVersion", inspection.version().name());
        result.setProperty("latestMocVersion", inspector.latestVersion().name());
        try (OwnedMoc moc = runtime.mocLoader().load(data)) {
            require(moc.nativeHandle() != 0, "MOC native handle is zero");
            require(moc.version() == inspection.version() && moc.consistency() == inspection.consistency(),
                    "Loader/inspector diagnostics differ");
            final OwnedModel first = moc.instantiateModel();
            try (first; OwnedModel second = moc.instantiateModel()) {
                require(first.nativeHandle() != 0 && second.nativeHandle() != 0
                        && first.nativeHandle() != second.nativeHandle(), "Independent model handles required");
                first.update();
                second.update();
                final Snapshot saved = snapshot(first);
                require(saved.equals(snapshot(first)), "Repeated reads changed owned model state");
                require(saved.equals(snapshot(second)), "Independent models disagree for the same MOC");
                require(saved.canvas.widthPixels() > 0 && saved.canvas.heightPixels() > 0
                        && saved.canvas.pixelsPerUnit() > 0, "Invalid canvas metrics");
                require(!saved.parameters.isEmpty() && !saved.parts.isEmpty() && !saved.drawables.isEmpty(),
                        "Fixture lacks populated structural evidence");
                for (OwnedDrawable drawable : saved.drawables) {
                    require(!drawable.vertexPositions().isEmpty()
                            && drawable.vertexPositions().size() == drawable.vertexUvs().size()
                            && drawable.vertexPositions().size() % 2 == 0, "Invalid drawable vertex/UV projection");
                    for (int index : drawable.indices()) require(index >= 0
                            && index < drawable.vertexPositions().size() / 2, "Invalid drawable index");
                    requireImmutable(drawable.vertexPositions());
                }
                requireImmutable(saved.parameters);
                requireImmutable(saved.parts);
                requireImmutable(saved.drawables);
                requireImmutable(saved.glues);
                requireImmutable(saved.deformers);
                final String before = digest(saved.toString().getBytes(StandardCharsets.UTF_8));
                first.update();
                snapshot(first);
                require(before.equals(digest(saved.toString().getBytes(StandardCharsets.UTF_8))),
                        "Previously copied snapshot changed after native evaluation");
                result.setProperty("snapshotSha256", before);
                result.setProperty("parameterCount", String.valueOf(saved.parameters.size()));
                result.setProperty("partCount", String.valueOf(saved.parts.size()));
                result.setProperty("drawableCount", String.valueOf(saved.drawables.size()));
                result.setProperty("glueCount", String.valueOf(saved.glues.size()));
                result.setProperty("deformerCount", String.valueOf(saved.deformers.size()));
                result.setProperty("immutableCopies", "PASS");
                result.setProperty("independentInstances", "PASS");
                result.setProperty("allStructuralReadMethods", "PASS");
            }
            requireClosed(first::nativeHandle);
            requireClosed(first::canvasInfo);
            requireClosed(first::parameters);
            requireClosed(first::parts);
            requireClosed(first::drawables);
            requireClosed(first::glues);
            requireClosed(first::deformers);
            requireClosed(first::update);
            first.close();
            moc.close();
            requireClosed(moc::nativeHandle);
            requireClosed(moc::instantiateModel);
            result.setProperty("explicitChildThenParentClose", "PASS");
            result.setProperty("useAfterCloseRejected", "PASS");
            result.setProperty("limitation", "Empty Glue/Deformer lists cover getters only");
        }
        try (OwnedMoc parent = runtime.mocLoader().load(data);
                OwnedModel first = parent.instantiateModel();
                OwnedModel second = parent.instantiateModel()) {
            require(first.nativeHandle() != 0 && second.nativeHandle() != 0
                    && first.nativeHandle() != second.nativeHandle(), "Cascade requires two independent live children");
            first.update();
            second.update();
            snapshot(first);
            snapshot(second);
            parent.close();
            requireClosed(first::nativeHandle);
            requireClosed(first::parameters);
            requireClosed(first::update);
            requireClosed(second::nativeHandle);
            requireClosed(second::drawables);
            requireClosed(second::update);
            requireClosed(parent::instantiateModel);
            result.setProperty("parentCascadeClose", "PASS");
        }
    }

    private static Snapshot snapshot(final OwnedModel model) {
        return new Snapshot(model.canvasInfo(), model.parameters(), model.parts(), model.drawables(), model.glues(), model.deformers());
    }

    private static void requireImmutable(final List<?> values) {
        try { values.clear(); throw new IllegalStateException("Mutable SDK projection"); }
        catch (UnsupportedOperationException expected) { }
    }

    private static void requireClosed(final Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new IllegalStateException("Closed native handle accepted a host operation");
    }

    private static String digest(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Snapshot(OwnedCanvasInfo canvas, List<OwnedParameter> parameters, List<OwnedPart> parts,
            List<OwnedDrawable> drawables, List<OwnedGlue> glues, List<OwnedDeformer> deformers) { }
}
