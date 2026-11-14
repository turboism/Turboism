package dev.turboism.validation.deformerrename;

import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.DeformerRename;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.ModelNamingIndex;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.validation.shared.verdict.ProbeAssertions;
import dev.turboism.validation.shared.verdict.ProbeTerminal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/**
 * Measures deformer batch-rename read, write, and native Undo contracts on an exact Cubism host.
 *
 * <p>Reverts every temporary rename before finishing so the validation fixture remains unmodified.</p>
 */
public final class DeformerRenameHostValidationPlugin implements TurboismPlugin {

    private static final String EXERCISER_THREAD_NAME = "deformer-rename-host-exerciser";
    private static final String FLAG_TIMEOUT_MARKER = "DEFORMER_RENAME_PROBE_FLAG_TIMEOUT";
    private static final String RESULT_FILE_NAME = "deformer-rename-validation-result.properties";
    private static final String PLUGIN_ID = "dev.turboism.validation.deformer-rename";
    private static final String RUN_ID_PROPERTY = "turboism.validation.runId";
    private static final String HOST_VERSION_PROPERTY = "turboism.validation.hostVersion";
    private static final String UNKNOWN_VALUE = "unknown";
    private static final String STATUS_KEY = "status";
    private static final String RENAME_SUFFIX = " [probe]";

    private static final long MODEL_POLL_MILLIS = 1_000L;
    private static final long MODEL_AWAIT_MAX_MILLIS = 240_000L;
    private static final long EDT_TIMEOUT_MILLIS = 300_000L;
    private static final long SHUTDOWN_FLUSH_MILLIS = 3_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private static final int UNAVAILABLE_METRIC = -1;
    private static final int SINGLE_UNDO_STEP = 1;
    private static final int NORMAL_EXIT_CODE = 0;

    private PluginLogger logger;
    private PluginContext context;
    private Path stateDir;
    private final ProbeAssertions assertions = new ProbeAssertions();

    private String modelId = UNKNOWN_VALUE;
    private int deformerCount;
    private int artMeshCount;
    private int partCount;
    private int parameterCount;
    private long legacyReadMillis = UNAVAILABLE_METRIC;
    private long indexReadMillis = UNAVAILABLE_METRIC;
    private long perDeformerMillis = UNAVAILABLE_METRIC;
    private long batchMillis = UNAVAILABLE_METRIC;
    private int perDeformerRenamed;
    private int batchRenamed;
    private int undoStepsPerDeformer = UNAVAILABLE_METRIC;
    private int undoStepsBatch = UNAVAILABLE_METRIC;
    private boolean singleUndoRestoredAll;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread exerciser = new Thread(this::awaitTrigger, EXERCISER_THREAD_NAME);
        exerciser.setDaemon(true);
        exerciser.start();
        logger.info("DEFORMER_RENAME_PROBE_READY stateDir=" + stateDir);
    }

    @Override
    public void enable() {
        logger.info("DEFORMER_RENAME_PROBE_ENABLED");
    }

    @Override
    public void disable() {
        logger.info("DEFORMER_RENAME_PROBE_DISABLED");
    }

    @Override
    public void shutdown() {
        logger.info("DEFORMER_RENAME_PROBE_SHUTDOWN");
    }

    private void awaitTrigger() {
        dev.turboism.validation.SessionGate.trigger(stateDir)
            .timeoutMarker(FLAG_TIMEOUT_MARKER)
            .warn(logger::warn)
            .onTrigger(this::runProbe)
            .await();
    }

    private void runProbe() {
        final long startedNanos = System.nanoTime();
        try {
            final CubismModel model = awaitActiveModel();
            modelId = onHostThread(() -> model.id().value());
            deformerCount = onHostThread(() -> model.deformers().all().size());
            logger.info("DEFORMER_RENAME_MODEL_READY modelId=" + modelId + " deformerCount=" + deformerCount);

            if (deformerCount == 0) {
                record("model.deformers", "at least one deformer", "0", ProbeTerminal.BLOCKED);
            } else {
                measureLegacyRead(model);
                measureIndexRead(model);
                measureBatchRenameAndSingleUndo(model);
                measurePerDeformerRename(model);
                recordCostAssertions();
            }
        } catch (Exception failure) {
            record("probe.unexpectedFailure", "no exception", singleLine(failure), ProbeTerminal.FAIL);
            logger.error("DEFORMER_RENAME_PROBE_FAILED " + singleLine(failure), failure);
        }

        final String terminal = assertions.terminal().name();
        writeResultFile();
        logger.info("DEFORMER_RENAME_PROBE_RESULT status=" + terminal
                + " modelId=" + modelId
                + " deformerCount=" + deformerCount
                + " artMeshCount=" + artMeshCount
                + " legacyReadMillis=" + legacyReadMillis
                + " indexReadMillis=" + indexReadMillis
                + " perDeformerMillis=" + perDeformerMillis
                + " batchMillis=" + batchMillis
                + " perDeformerRenamed=" + perDeformerRenamed
                + " batchRenamed=" + batchRenamed
                + " undoStepsPerDeformer=" + undoStepsPerDeformer
                + " undoStepsBatch=" + undoStepsBatch
                + " singleUndoRestoredAll=" + singleUndoRestoredAll
                + " " + assertions.counts()
                + " durationMillis=" + ((System.nanoTime() - startedNanos) / NANOS_PER_MILLI));
        try {
            Thread.sleep(SHUTDOWN_FLUSH_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(NORMAL_EXIT_CODE);
    }

    /** Measures the pre-optimization live-handle read sequence across deformers, meshes, parts, and parameters. */
    private void measureLegacyRead(final CubismModel model) throws Exception {
        final long started = System.nanoTime();
        final int observed = onHostThread(() -> {
            final List<Deformer> deformers = model.deformers().all();
            final Set<DeformerId> typedIds = new HashSet<>();
            model.warpDeformers().all().forEach(deformer -> typedIds.add(deformer.id()));
            model.rotationDeformers().all().forEach(deformer -> typedIds.add(deformer.id()));
            for (Part part : model.parts().all()) {
                part.id();
                part.name();
            }
            for (Drawable drawable : model.drawables().all()) {
                drawable.parentDeformerId().ifPresent(ignored -> {
                    drawable.name();
                    drawable.parentPartId();
                });
            }
            int count = 0;
            for (Deformer deformer : deformers) {
                deformer.parentDeformerId();
                if (!typedIds.contains(deformer.id())) {
                    continue;
                }
                for (ParameterBinding binding : deformer.getParameterBindings()) {
                    resolveLegacyParameterName(model, binding.parameterId());
                }
                deformer.name();
                count++;
            }
            return count;
        });
        legacyReadMillis = (System.nanoTime() - started) / NANOS_PER_MILLI;
        logger.info("DEFORMER_RENAME_LEGACY_READ millis=" + legacyReadMillis + " deformers=" + observed);
    }

    private static String resolveLegacyParameterName(final CubismModel model, final ParameterId parameterId) {
        try {
            final Parameter parameter = model.parameters().find(parameterId);
            if (parameter != null) {
                return parameter.name().orElse(parameterId.value());
            }
        } catch (NoSuchElementException ignored) {
            return parameterId.value();
        }
        return parameterId.value();
    }

    /** Measures the single-pass naming index read that backs preview generation and style switching. */
    private void measureIndexRead(final CubismModel model) throws Exception {
        final long started = System.nanoTime();
        final ModelNamingIndex index = onHostThread(model::namingIndex);
        indexReadMillis = (System.nanoTime() - started) / NANOS_PER_MILLI;
        artMeshCount = index.artMeshes().size();
        partCount = index.parts().size();
        parameterCount = index.parameters().size();
        record(
                "read.namingIndex",
                "index lists every deformer",
                "deformers=" + index.deformers().size() + " artMeshes=" + artMeshCount,
                index.deformers().size() == deformerCount ? ProbeTerminal.PASS : ProbeTerminal.FAIL);
        logger.info("DEFORMER_RENAME_INDEX_READ millis=" + indexReadMillis
                + " deformers=" + index.deformers().size()
                + " parts=" + partCount
                + " parameters=" + parameterCount
                + " artMeshes=" + artMeshCount);
    }

    /**
     * Measures conditional batch rename and verifies that a single native Undo step restores every deformer name.
     */
    private void measureBatchRenameAndSingleUndo(final CubismModel model) throws Exception {
        final Map<DeformerId, String> originalNames = snapshotDeformerNames(model);
        final List<DeformerRename> renames = onHostThread(() -> {
            final List<DeformerRename> batch = new ArrayList<>();
            for (Deformer deformer : model.deformers().all()) {
                final String current = deformer.name();
                batch.add(new DeformerRename(deformer.id(), current, current + RENAME_SUFFIX));
            }
            return batch;
        });
        final int beforeUndoPos = undoPosition();
        final long started = System.nanoTime();
        onHostThread(() -> {
            model.renameDeformers(renames);
            return null;
        });
        batchMillis = (System.nanoTime() - started) / NANOS_PER_MILLI;
        batchRenamed = countMatchingNames(model, renames, true);
        final int afterUndoPos = undoPosition();
        if (beforeUndoPos >= 0 && afterUndoPos >= 0) {
            undoStepsBatch = afterUndoPos - beforeUndoPos;
        }
        logger.info("DEFORMER_RENAME_BATCH millis=" + batchMillis
                + " renamed=" + batchRenamed
                + " beforeUndoPos=" + beforeUndoPos
                + " afterUndoPos=" + afterUndoPos
                + " undoStepsDelta=" + undoStepsBatch);

        final HistoryMoveResult undoResult = onHostThread(() -> context.cubism().history().undo(SINGLE_UNDO_STEP));
        if (undoResult.outcome() == HistoryMoveResult.Outcome.MOVED) {
            singleUndoRestoredAll = namesMatchSnapshot(model, originalNames);
            record(
                    "write.batchSingleUndoRestoresAll",
                    "true",
                    String.valueOf(singleUndoRestoredAll),
                    singleUndoRestoredAll ? ProbeTerminal.PASS : ProbeTerminal.FAIL);
            if (!singleUndoRestoredAll) {
                revertBatch(model, renames);
            }
        } else {
            logger.warn("DEFORMER_RENAME_HISTORY_UNDO_UNAVAILABLE outcome=" + undoResult.outcome());
            revertBatch(model, renames);
            record(
                    "write.batchSingleUndoRestoresAll",
                    "MOVED",
                    undoResult.outcome().name(),
                    ProbeTerminal.INFO);
        }
    }

    /** Measures legacy one-by-one deformer renaming and reverts the fixture back to its initial names. */
    private void measurePerDeformerRename(final CubismModel model) throws Exception {
        final Map<DeformerId, String> originalNames = snapshotDeformerNames(model);
        final List<DeformerId> ids = onHostThread(() -> model.deformers().all().stream()
                .map(Deformer::id)
                .toList());
        final int beforeUndoPos = undoPosition();
        final long started = System.nanoTime();
        perDeformerRenamed = onHostThread(() -> {
            int renamed = 0;
            for (DeformerId id : ids) {
                final Deformer deformer = model.deformers().find(id);
                deformer.setName(deformer.name() + RENAME_SUFFIX);
                renamed++;
            }
            return renamed;
        });
        perDeformerMillis = (System.nanoTime() - started) / NANOS_PER_MILLI;
        final int afterUndoPos = undoPosition();
        if (beforeUndoPos >= 0 && afterUndoPos >= 0) {
            undoStepsPerDeformer = afterUndoPos - beforeUndoPos;
        }
        logger.info("DEFORMER_RENAME_PER_DEFORMER millis=" + perDeformerMillis
                + " renamed=" + perDeformerRenamed
                + " beforeUndoPos=" + beforeUndoPos
                + " afterUndoPos=" + afterUndoPos
                + " undoStepsDelta=" + undoStepsPerDeformer);

        if (undoStepsPerDeformer > 0) {
            final HistoryMoveResult undoResult =
                    onHostThread(() -> context.cubism().history().undo(undoStepsPerDeformer));
            if (undoResult.outcome() != HistoryMoveResult.Outcome.MOVED
                    || !namesMatchSnapshot(model, originalNames)) {
                revertToSnapshot(model, originalNames);
            }
        } else {
            revertToSnapshot(model, originalNames);
        }
        final boolean fixtureClean = namesMatchSnapshot(model, originalNames);
        record(
                "fixture.namesRestored",
                "true",
                String.valueOf(fixtureClean),
                fixtureClean ? ProbeTerminal.PASS : ProbeTerminal.FAIL);
    }

    private Map<DeformerId, String> snapshotDeformerNames(final CubismModel model) throws Exception {
        return onHostThread(() -> {
            final Map<DeformerId, String> snapshot = new HashMap<>();
            for (Deformer deformer : model.deformers().all()) {
                snapshot.put(deformer.id(), deformer.name());
            }
            return snapshot;
        });
    }

    private int countMatchingNames(
            final CubismModel model, final List<DeformerRename> renames, final boolean checkReplacement)
            throws Exception {
        final Map<DeformerId, String> current = snapshotDeformerNames(model);
        int matched = 0;
        for (DeformerRename rename : renames) {
            final String target = checkReplacement ? rename.replacementName() : rename.expectedName();
            if (target.equals(current.get(rename.deformerId()))) {
                matched++;
            }
        }
        return matched;
    }

    private boolean namesMatchSnapshot(final CubismModel model, final Map<DeformerId, String> expected)
            throws Exception {
        final Map<DeformerId, String> current = snapshotDeformerNames(model);
        return expected.equals(current);
    }

    private void revertBatch(final CubismModel model, final List<DeformerRename> applied) throws Exception {
        onHostThread(() -> {
            final List<DeformerRename> revert = new ArrayList<>();
            for (DeformerRename rename : applied) {
                revert.add(new DeformerRename(
                        rename.deformerId(), rename.replacementName(), rename.expectedName()));
            }
            model.renameDeformers(revert);
            return null;
        });
    }

    private void revertToSnapshot(final CubismModel model, final Map<DeformerId, String> expected) throws Exception {
        onHostThread(() -> {
            final List<DeformerRename> revert = new ArrayList<>();
            for (Deformer deformer : model.deformers().all()) {
                final String target = expected.get(deformer.id());
                if (target != null && !target.equals(deformer.name())) {
                    revert.add(new DeformerRename(deformer.id(), deformer.name(), target));
                }
            }
            if (!revert.isEmpty()) {
                model.renameDeformers(revert);
            }
            return null;
        });
    }

    /** Returns the active native Undo cursor position, or {@link #UNAVAILABLE_METRIC} when unavailable. */
    private int undoPosition() {
        try {
            return onHostThread(() -> {
                final HistorySnapshot snapshot = context.cubism().history().snapshot();
                if (snapshot.availability() != HistorySnapshot.Availability.AVAILABLE) {
                    return UNAVAILABLE_METRIC;
                }
                return snapshot.position();
            });
        } catch (Exception unavailable) {
            return UNAVAILABLE_METRIC;
        }
    }

    private void recordCostAssertions() {
        record(
                "read.indexFasterThanLegacy",
                "indexReadMillis <= legacyReadMillis",
                "indexReadMillis=" + indexReadMillis + " legacyReadMillis=" + legacyReadMillis,
                indexReadMillis < 0 || legacyReadMillis < 0
                        ? ProbeTerminal.INFO
                        : (indexReadMillis <= legacyReadMillis ? ProbeTerminal.PASS : ProbeTerminal.FAIL));
        record(
                "write.perDeformerUndoSteps",
                "one undo step per deformer",
                String.valueOf(undoStepsPerDeformer),
                undoStepsPerDeformer < 0
                        ? ProbeTerminal.INFO
                        : (undoStepsPerDeformer == perDeformerRenamed ? ProbeTerminal.PASS : ProbeTerminal.FAIL));
        record(
                "write.batchUndoSteps",
                "one undo step for the whole batch",
                String.valueOf(undoStepsBatch),
                undoStepsBatch < 0
                        ? ProbeTerminal.INFO
                        : (undoStepsBatch == SINGLE_UNDO_STEP ? ProbeTerminal.PASS : ProbeTerminal.FAIL));
        record(
                "write.batchFasterThanPerDeformer",
                "batch write faster than per-deformer writes",
                "batchMillis=" + batchMillis + " perDeformerMillis=" + perDeformerMillis,
                batchMillis < 0 || perDeformerMillis < 0
                        ? ProbeTerminal.INFO
                        : (batchMillis <= perDeformerMillis ? ProbeTerminal.PASS : ProbeTerminal.FAIL));
        record(
                "write.batchRenamedEveryDeformer",
                "every deformer renamed",
                "renamed=" + batchRenamed + " expected=" + deformerCount,
                batchRenamed == deformerCount ? ProbeTerminal.PASS : ProbeTerminal.FAIL);
    }

    private CubismModel awaitActiveModel() throws Exception {
        final long deadline = System.currentTimeMillis() + MODEL_AWAIT_MAX_MILLIS;
        Exception lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                final CubismModel model = onHostThread(() -> context.cubism().model().active());
                final boolean hasDeformers = onHostThread(() -> !model.deformers().all().isEmpty());
                if (model != null && hasDeformers) {
                    return model;
                }
            } catch (Exception exception) {
                lastFailure = exception;
            }
            Thread.sleep(MODEL_POLL_MILLIS);
        }
        throw new IllegalStateException(
                "No active model with deformers was observed within " + MODEL_AWAIT_MAX_MILLIS + " ms", lastFailure);
    }

    private <T> T onHostThread(final Callable<T> work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return work.call();
        }
        final Object[] result = new Object[1];
        final Exception[] failure = new Exception[1];
        final CountDownLatch done = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                result[0] = work.call();
            } catch (Exception exception) {
                failure[0] = exception;
            } finally {
                done.countDown();
            }
        });
        if (!done.await(EDT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("host thread did not answer within " + EDT_TIMEOUT_MILLIS + " ms");
        }
        if (failure[0] != null) {
            throw failure[0];
        }
        @SuppressWarnings("unchecked")
        final T value = (T) result[0];
        return value;
    }

    private void record(final String name, final Object expected, final Object actual, final ProbeTerminal status) {
        assertions.record(name, expected, actual, status);
    }

    private static String singleLine(final Object value) {
        return String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
    }

    private void writeResultFile() {
        final Path result = stateDir.getParent().resolve(RESULT_FILE_NAME);
        try {
            final StringBuilder report = new StringBuilder()
                    .append("schemaVersion=1\n")
                    .append("runId=")
                    .append(System.getProperty(RUN_ID_PROPERTY, UNKNOWN_VALUE))
                    .append('\n')
                    .append("pluginId=")
                    .append(PLUGIN_ID)
                    .append('\n')
                    .append("hostVersion=")
                    .append(System.getProperty(HOST_VERSION_PROPERTY, UNKNOWN_VALUE))
                    .append('\n')
                    .append("modelId=")
                    .append(modelId)
                    .append('\n')
                    .append("deformerCount=")
                    .append(deformerCount)
                    .append('\n')
                    .append("artMeshCount=")
                    .append(artMeshCount)
                    .append('\n')
                    .append("partCount=")
                    .append(partCount)
                    .append('\n')
                    .append("parameterCount=")
                    .append(parameterCount)
                    .append('\n')
                    .append("legacyReadMillis=")
                    .append(legacyReadMillis)
                    .append('\n')
                    .append("indexReadMillis=")
                    .append(indexReadMillis)
                    .append('\n')
                    .append("perDeformerMillis=")
                    .append(perDeformerMillis)
                    .append('\n')
                    .append("batchMillis=")
                    .append(batchMillis)
                    .append('\n')
                    .append("perDeformerRenamed=")
                    .append(perDeformerRenamed)
                    .append('\n')
                    .append("batchRenamed=")
                    .append(batchRenamed)
                    .append('\n')
                    .append("undoStepsPerDeformer=")
                    .append(undoStepsPerDeformer)
                    .append('\n')
                    .append("undoStepsBatch=")
                    .append(undoStepsBatch)
                    .append('\n')
                    .append("singleUndoRestoredAll=")
                    .append(singleUndoRestoredAll)
                    .append('\n');
            assertions.appendPublication(report, ProbeAssertions.Records.NAMED, STATUS_KEY);
            Files.createDirectories(result.getParent());
            Files.writeString(result, report.toString(), StandardCharsets.UTF_8);
            logger.info("DEFORMER_RENAME_RESULT_WRITTEN " + result);
        } catch (Exception failure) {
            logger.error("DEFORMER_RENAME_RESULT_WRITE_FAILED " + singleLine(failure), failure);
        }
    }
}
