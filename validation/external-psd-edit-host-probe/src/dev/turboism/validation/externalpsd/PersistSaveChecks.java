package dev.turboism.validation.externalpsd;

import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperationType;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.TextureInputBinding;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import dev.turboism.sdk.cubism.event.ProjectFileLifecycleEvent;
import dev.turboism.sdk.cubism.id.RawImageId;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Mediated SAVE_AS admission, lifecycle-event confirmation, and save-prompt dialog proof.
 *
 * <p>The persist tail accepts a save only when the lifecycle event, the fresh document
 * projection, and the reviewed prompt answer agree on one target identity.</p>
 */
final class PersistSaveChecks {
    static final int EXIT_DIAGNOSTIC_MAX_THREADS = 256;
    static final int EXIT_DIAGNOSTIC_MAX_STACK_FRAMES = 128;
    static final int EXIT_DIAGNOSTIC_MAX_CHARS = 256 * 1024;

    static final String PERSISTED_DOCUMENT_BASENAME = "persisted-document.cmo3";

    static final String UNUSED_RAW_MESSAGE_KEY = "CUB3-3054";
    static final String OFFICIAL_LOCALIZER_CLASS = "b.c";
    static final String OFFICIAL_OPTION_CLASS = "com.live2d.util.UUOption";
    static final String OFFICIAL_ACTION_RESULT_CLASS = "com.live2d.util.aM";
    static final String OFFICIAL_WINDOW_CLASS = "com.live2d.ui.window.V";
    static final String OFFICIAL_MODELING_DOCUMENT_CLASS =
        "com.live2d.cubism.doc.modeling.CModelingDocument";
    static final String OFFICIAL_ACTION_CLASS = "com.live2d.util.Q";
    static final String OFFICIAL_KEEP_ACTION_TOKEN =
        "JOptionPane.options[1].WHEN_IN_FOCUSED_WINDOW[N]->Click:" + OFFICIAL_ACTION_CLASS;

    /**
     * Daemon watchdog armed after the result is recorded: if native shutdown stalls, it writes one
     * bounded thread-stack diagnostic.  Cleanup and process termination remain the Supervisor's
     * responsibility; this probe never terminates its own JVM.
     */
    static void armExitWatchdog(final Path stateDir) {
        try {
            Files.writeString(
                stateDir.resolve("external-psd-exit-armed.txt"),
                "watchdog armed " + java.time.Instant.now());
        } catch (Throwable ignored) {
        }
        final Thread dumper = new Thread(() -> {
            try {
                Thread.sleep(15000);
                // Per-thread stack traces use handshakes, not a global safepoint, so a thread
                // wedged in native code cannot stall the dump itself.
                final String text = boundedExitThreadDiagnostic();
                Files.writeString(stateDir.resolve("external-psd-exit-threads.txt"), text);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignored) {
            }
        }, "external-psd-exit-dump");
        dumper.setDaemon(true);
        dumper.start();
    }

    static String boundedExitThreadDiagnostic() {
        final StringBuilder text = new StringBuilder("JVM exit watchdog: shutdown stalled\n");
        int includedThreads = 0;
        for (final Thread thread : allThreads()) {
            if (includedThreads >= EXIT_DIAGNOSTIC_MAX_THREADS) {
                appendBounded(text, "\n[thread list truncated]");
                break;
            }
            if (!appendBounded(text, "\n\"" + thread.getName() + "\" "
                + thread.getState())) break;
            includedThreads++;
            int includedFrames = 0;
            for (final StackTraceElement frame : thread.getStackTrace()) {
                if (includedFrames >= EXIT_DIAGNOSTIC_MAX_STACK_FRAMES) {
                    appendBounded(text, "\n    [... stack truncated]");
                    break;
                }
                if (!appendBounded(text, "\n    at " + frame)) return text.toString();
                includedFrames++;
            }
        }
        return text.toString();
    }

    static String exitThreadDiagnosticForTest() {
        return boundedExitThreadDiagnostic();
    }

    static boolean appendBounded(final StringBuilder text, final String value) {
        if (text.length() >= EXIT_DIAGNOSTIC_MAX_CHARS) return false;
        final int remaining = EXIT_DIAGNOSTIC_MAX_CHARS - text.length();
        if (value.length() <= remaining) {
            text.append(value);
            return true;
        }
        text.append(value, 0, remaining);
        return false;
    }

    static List<Thread> allThreads() {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        while (group.getParent() != null) group = group.getParent();
        Thread[] threads = new Thread[group.activeCount() + 16];
        int count;
        while ((count = group.enumerate(threads, true)) == threads.length) {
            threads = new Thread[threads.length * 2];
        }
        final List<Thread> alive = new ArrayList<>(count);
        for (int index = 0; index < count; index++) alive.add(threads[index]);
        return alive;
    }

    /** Blocked signal: the evidence condition could not be reached, distinct from a failure. */
    static final class Blocked extends IllegalStateException {
        final String expected;
        Blocked(final String expected, final String actual) {
            super(actual);
            this.expected = expected;
        }
    }

    /**
     * Final same-EDT SAVE_AS admission. A document or window change is a hard rejection and the
     * caller must not invoke the command after this result is rejected.
     */
    static SaveAsAdmission saveAsAdmission(final TargetResolution.TargetIdentity expectedTarget,
        final String expectedWindowIdentity, final PersistDocumentState current,
        final boolean stopped) {
        if (stopped) return SaveAsAdmission.rejected("probe is stopped");
        if (current == null) {
            return SaveAsAdmission.rejected("current document state is unavailable");
        }
        if (!TargetResolution.targetIdentityMatches(expectedTarget, current.targetIdentity())) {
            return SaveAsAdmission.rejected(
                "document/model/binding/generation/model-image/ArtMesh/raw changed before SAVE_AS");
        }
        if (expectedWindowIdentity == null || expectedWindowIdentity.isBlank()
            || "null".equals(expectedWindowIdentity)) {
            return SaveAsAdmission.rejected("bound task window identity is unavailable");
        }
        if (!expectedWindowIdentity.equals(current.windowIdentity())) {
            return SaveAsAdmission.rejected("bound task window changed before SAVE_AS");
        }
        if (!current.windowShowing() || !current.windowDisplayable()) {
            return SaveAsAdmission.rejected(
                "bound task window is not showing/displayable before SAVE_AS");
        }
        return SaveAsAdmission.admitted(
            "same EDT document/model/relation anchor and bound window verified");
    }

    /**
     * The single admission-plus-command seam used by production and focused tests.  The state
     * supplier is read again immediately before the command so an EDT-queued SAVE_AS cannot use
     * a document/window snapshot that became stale while it was waiting in the queue.
     */
    static SaveAsAdmission admitAndExecuteSaveAsOnEdt(
        final TargetResolution.TargetIdentity expectedTarget, final String expectedWindowIdentity,
        final Supplier<PersistDocumentState> currentState, final BooleanSupplier stopped,
        final Runnable command) {
        if (!SwingUtilities.isEventDispatchThread()) {
            return SaveAsAdmission.rejected("SAVE_AS admission must run on EDT");
        }
        Objects.requireNonNull(currentState, "currentState");
        Objects.requireNonNull(stopped, "stopped");
        if (stopped.getAsBoolean()) return SaveAsAdmission.rejected("probe is stopped");
        final PersistDocumentState first = currentState.get();
        final SaveAsAdmission initial = saveAsAdmission(
            expectedTarget, expectedWindowIdentity, first, false);
        if (!initial.admitted()) return initial;

        // This second read is intentional.  A caller may have queued this exact seam while the
        // document or task stop state changed before the EDT callback reached this point.
        if (stopped.getAsBoolean()) return SaveAsAdmission.rejected("probe is stopped");
        final PersistDocumentState finalState = currentState.get();
        final SaveAsAdmission finalAdmission = saveAsAdmission(
            expectedTarget, expectedWindowIdentity, finalState, false);
        if (!finalAdmission.admitted()) return SaveAsAdmission.rejected(
            "SAVE_AS state changed while queued: " + finalAdmission.diagnostic());
        if (stopped.getAsBoolean()) return SaveAsAdmission.rejected("probe is stopped");
        if (command == null) return SaveAsAdmission.rejected("SAVE_AS command is unavailable");
        command.run();
        return finalAdmission;
    }

    /** Focused seam proving that rejected states execute no SAVE_AS command. */
    static SaveAsAdmission executeSaveAsIfAdmittedOnEdtForTest(
        final TargetResolution.TargetIdentity expectedTarget, final String expectedWindowIdentity,
        final PersistDocumentState current, final boolean stopped, final Runnable command) {
        return executeSaveAsIfAdmittedOnEdtForTest(expectedTarget, expectedWindowIdentity,
            () -> current, () -> stopped, command);
    }

    /** Same production seam with a mutable state/stop supplier for queued-callback regressions. */
    static SaveAsAdmission executeSaveAsIfAdmittedOnEdtForTest(
        final TargetResolution.TargetIdentity expectedTarget, final String expectedWindowIdentity,
        final Supplier<PersistDocumentState> currentState, final BooleanSupplier stopped,
        final Runnable command) {
        return admitAndExecuteSaveAsOnEdt(expectedTarget, expectedWindowIdentity, currentState,
            stopped, command);
    }

    /**
     * Validates one post-command SAVE event against the sequence captured immediately before the
     * EDT command and two fresh document projections. Request filename is deliberately absent
     * from this gate: SAVE_AS may report the old filename, or no filename for a first save.
     */
    static PersistSaveValidation validatePersistSaveAfter(final long executeBeforeSequence,
        final PersistDocumentState before, final PersistDocumentState after,
        final List<PersistSaveEvent> observedEvents, final String expectedBasename) {
        final List<String> reasons = new ArrayList<>();
        if (executeBeforeSequence < 0L) reasons.add("execute-before sequence is negative");
        if (before == null) reasons.add("fresh before document state is unavailable");
        if (after == null) reasons.add("fresh after document state is unavailable");
        if (before != null && after != null) {
            if (!TargetResolution.targetIdentityMatches(before.targetIdentity(), after.targetIdentity())) {
                reasons.add("fresh before/after target identity changed");
            }
            if (before.windowIdentity().isBlank()
                || !before.windowIdentity().equals(after.windowIdentity())) {
                reasons.add("fresh before/after bound window changed");
            }
            if (!after.windowShowing() || !after.windowDisplayable()) {
                reasons.add("fresh after bound window is not showing/displayable");
            }
            if (after.contentId().isEmpty()) {
                reasons.add("fresh after document contentId is unavailable");
            }
            if (before.contentId().isEmpty()) {
                reasons.add("fresh before document contentId is unavailable");
            }
            if (!Objects.equals(pathBasename(after.relativePath()), expectedBasename)) {
                reasons.add("fresh after document basename is not " + expectedBasename
                    + ": " + after.relativePath());
            }
        }
        final List<PersistSaveEvent> events = observedEvents == null
            ? List.of() : List.copyOf(observedEvents);
        final List<PersistSaveEvent> afterEvents = events.stream()
            .filter(event -> event != null && event.sequence() > executeBeforeSequence)
            .toList();
        if (afterEvents.size() != 1) {
            reasons.add("expected exactly one post-execute SAVE After, observed "
                + afterEvents.size());
        }
        final PersistSaveEvent event = afterEvents.size() == 1 ? afterEvents.get(0) : null;
        if (event != null) {
            if (event.operation() != ProjectFileOperationType.SAVE) {
                reasons.add("post-execute event is not SAVE: " + event.operation());
            }
            if (!event.succeeded()) reasons.add("post-execute SAVE did not succeed");
            if (before != null && !Objects.equals(event.requestContentId(), before.contentId())) {
                reasons.add("SAVE request contentId does not match fresh before document");
            }
            if (after != null && (!Objects.equals(event.resultContentId(), after.contentId())
                || event.resultContentId().isEmpty())) {
                reasons.add("SAVE result contentId does not match fresh after document");
            }
        }
        if (!reasons.isEmpty()) {
            return PersistSaveValidation.rejected(String.join("; ", reasons));
        }
        return PersistSaveValidation.accepted(event,
            "post-execute SAVE sequence/contentId and fresh persisted-document.cmo3 state match; "
                + "request filename retained for diagnostics only");
    }

    /**
     * Accepts one public APPLIED completion only when the fresh relation snapshot proves the
     * explicit {@code after} raw is now the current raw of the same stable model image.  This is
     * the sole production helper that advances a target's raw lineage; a raw-set difference or a
     * wrapper replacement flag cannot advance it.
     */
    static TargetResolution.TargetIdentity acceptAppliedTargetLineage(final TargetResolution.TargetIdentity before,
        final PsdFileRevision expectedRevision, final PsdReplaceResult replaced,
        final TextureRelationsSnapshot afterRelations, final TargetResolution.TargetIdentity observedAfter) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        Objects.requireNonNull(replaced, "replaced");
        Objects.requireNonNull(afterRelations, "afterRelations");
        Objects.requireNonNull(observedAfter, "observedAfter");
        final List<String> reasons = new ArrayList<>();
        if (replaced.status() != PsdReplaceResult.Status.APPLIED) {
            reasons.add("public replacement status=" + replaced.status());
        }
        if (replaced.consumedRevision().isEmpty()) {
            reasons.add("replacement revision was not consumed");
        } else if (replaced.consumedRevision().orElseThrow() != expectedRevision) {
            reasons.add("replacement consumed a different revision than the pending save");
        }
        if (replaced.after().isEmpty()) {
            reasons.add("replacement did not report an after raw");
        }
        if (!before.rawId().equals(replaced.before().value())) {
            reasons.add("replacement before raw does not match the current lineage");
        }
        if (!afterRelations.isAvailable()) {
            reasons.add("after relations are unavailable");
        } else {
            if (!before.binding().equals(afterRelations.binding())) {
                reasons.add("after relation binding changed from the stable anchor");
            }
            if (before.generation() < 0L
                || before.generation() != afterRelations.generation()) {
                reasons.add("after relation generation changed from the stable anchor");
            }
        }
        if (!TargetResolution.stableTargetIdentityMatches(before, observedAfter)) {
            reasons.add("document/model/binding/generation/model-image/ArtMesh anchor changed");
        }
        if (replaced.after().isPresent()) {
            final String afterRaw = replaced.after().orElseThrow().value();
            if (!afterRaw.equals(observedAfter.rawId())) {
                reasons.add("observed current raw does not equal public after raw");
            }
            if (afterRelations.isAvailable()) {
                final RawImageId afterId = replaced.after().orElseThrow();
                final long rawCount = afterRelations.rawImages().stream()
                    .filter(raw -> raw != null && afterId.equals(raw.id())).count();
                if (rawCount != 1L) {
                    reasons.add("after raw occurrence count=" + rawCount
                        + " (expected exactly one)");
                }
                final List<ModelImageRelation> modelImages = afterRelations.modelImages().stream()
                    .filter(relation -> relation != null
                        && before.modelImageId().equals(relation.id().value()))
                    .toList();
                if (modelImages.size() != 1) {
                    reasons.add("stable model-image occurrence count=" + modelImages.size()
                        + " (expected exactly one)");
                } else {
                    final String currentRaw = modelImages.get(0).currentRawImageId()
                        .map(RawImageId::value).orElse(RawExportCoordination.UNAVAILABLE_VALUE);
                    if (!afterRaw.equals(currentRaw)) {
                        reasons.add("stable model-image current raw is not public after raw");
                    }
                }
                if (!RawExportCoordination.UNAVAILABLE_VALUE.equals(before.artMeshId())) {
                    final long artMeshCount = afterRelations.artMeshInputs().stream()
                        .filter(mesh -> mesh != null
                            && before.artMeshId().equals(mesh.id().value())
                            && mesh.currentInputIndex().isPresent())
                        .filter(mesh -> {
                            final TextureInputBinding input = mesh.inputs().get(
                                mesh.currentInputIndex().getAsInt());
                            return input.kind() == TextureInputBinding.Kind.MODEL_IMAGE
                                && input.isResolved() && input.modelImageId().isPresent()
                                && before.modelImageId().equals(
                                    input.modelImageId().orElseThrow().value());
                        })
                        .count();
                    if (artMeshCount != 1L) {
                        reasons.add("stable ArtMesh occurrence count=" + artMeshCount
                            + " (expected exactly one)");
                    }
                }
            }
        }
        if (!reasons.isEmpty()) {
            throw new IllegalStateException("APPLIED lineage transition rejected: "
                + String.join("; ", reasons));
        }
        return observedAfter;
    }

    /**
     * Matches a PSD snapshot to the raw image it describes. The public snapshot calls this
     * field {@code documentId}, but the editor adapter fills it from the CLayeredImage GUID;
     * the raw-image adapter uses that same GUID value as {@code RawImageId}. It is therefore
     * deliberately compared with {@link TargetIdentity#rawId()}, never with the CMO project
     * document ID held by {@link TargetIdentity#documentId()}.
     */
    static boolean hasUniquePsdSnapshotForRawImage(
        final List<PsdClipMaskDocumentSnapshot> documents, final String rawId) {
        if (documents == null || rawId == null || rawId.isBlank()
            || RawExportCoordination.UNAVAILABLE_VALUE.equals(rawId)) {
            return false;
        }
        int matches = 0;
        for (final PsdClipMaskDocumentSnapshot document : documents) {
            if (document == null) return false;
            if (rawId.equals(document.documentId())) matches++;
        }
        return matches == 1;
    }

    static DiagnosticObservationRecorder.DiagnosticStatus diagnosticStatusForTarget(final TargetResolution.TargetIdentity expected,
        final TargetResolution.TargetIdentity actual, final boolean relationsAvailable,
        final boolean livePsdAvailable,
        final PsdValidationContent.Fingerprint fingerprint) {
        return diagnosticStatusForTarget(
            expected, actual, relationsAvailable, livePsdAvailable, fingerprint, true);
    }

    static DiagnosticObservationRecorder.DiagnosticStatus diagnosticStatusForTarget(final TargetResolution.TargetIdentity expected,
        final TargetResolution.TargetIdentity actual, final boolean relationsAvailable,
        final boolean livePsdAvailable,
        final PsdValidationContent.Fingerprint fingerprint,
        final boolean targetRawSnapshotAvailable) {
        return relationsAvailable && livePsdAvailable && targetRawSnapshotAvailable
            && fingerprint != null && TargetResolution.targetIdentityMatches(expected, actual)
            ? DiagnosticObservationRecorder.DiagnosticStatus.AVAILABLE : DiagnosticObservationRecorder.DiagnosticStatus.UNAVAILABLE;
    }

    static TargetResolution.TargetIdentity targetIdentityFromRelationsOnEdt(
        final TargetResolution.TargetIdentity expected, final String documentId, final CubismModel model,
        final TextureRelationsSnapshot relations) {
        if (expected == null) throw new IllegalArgumentException("expected target is required");
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalStateException("active document identity unavailable");
        }
        if (model == null || model.id() == null || model.id().value().isBlank()) {
            throw new IllegalStateException("active model identity unavailable");
        }
        if (relations == null || !relations.isAvailable()) {
            throw new IllegalStateException("relations unavailable");
        }
        final List<ModelImageRelation> modelImages = relations.modelImages().stream()
            .filter(relation -> relation != null
                && expected.modelImageId().equals(relation.id().value()))
            .toList();
        if (modelImages.size() != 1) {
            throw new IllegalStateException("target model-image occurrence count="
                + modelImages.size() + " (expected exactly one)");
        }
        final ModelImageRelation targetRelation = modelImages.get(0);
        final String artMeshId = matchingArtMeshIdOnEdt(expected, relations);
        return new TargetResolution.TargetIdentity(
            documentId,
            model.id().value(),
            relations.binding(),
            relations.generation(),
            targetRelation.id().value(),
            artMeshId,
            targetRelation.currentRawImageId().map(RawImageId::value)
                .orElse(RawExportCoordination.UNAVAILABLE_VALUE));
    }

    static String matchingArtMeshIdOnEdt(final TargetResolution.TargetIdentity expected,
        final TextureRelationsSnapshot relations) {
        if (!RawExportCoordination.UNAVAILABLE_VALUE.equals(expected.artMeshId())) {
            final long matches = relations.artMeshInputs().stream()
                .filter(mesh -> mesh != null && mesh.id() != null
                    && expected.artMeshId().equals(mesh.id().value())
                    && mesh.currentInputIndex().isPresent())
                .filter(mesh -> {
                    final TextureInputBinding input = mesh.inputs().get(
                        mesh.currentInputIndex().getAsInt());
                    return input.kind() == TextureInputBinding.Kind.MODEL_IMAGE
                        && input.isResolved() && input.modelImageId().isPresent()
                        && expected.modelImageId().equals(
                            input.modelImageId().orElseThrow().value());
                })
                .count();
            if (matches != 1L) {
                throw new IllegalStateException("target ArtMesh occurrence count=" + matches
                    + " (expected exactly one)");
            }
            return expected.artMeshId();
        }
        return RawExportCoordination.UNAVAILABLE_VALUE;
    }

    static void recordTargetBindingCheck(final Properties result, final String prefix,
        final TargetResolution.TargetIdentity expected, final TargetResolution.TargetIdentity actual) {
        recordExpectedTargetIdentity(result, prefix, expected);
        result.setProperty(prefix + ".observed.identityVerified",
            Boolean.toString(TargetResolution.targetIdentityMatches(expected, actual)));
        result.setProperty(prefix + ".observed.stableIdentityVerified",
            Boolean.toString(TargetResolution.stableTargetIdentityMatches(expected, actual)));
        recordTargetIdentity(result, prefix + ".observed", actual);
    }

    static void recordStableTargetBindingCheck(final Properties result,
        final String prefix, final TargetResolution.TargetIdentity expected, final TargetResolution.TargetIdentity actual) {
        recordExpectedTargetIdentity(result, prefix, expected);
        result.setProperty(prefix + ".observed.identityVerified", "false");
        result.setProperty(prefix + ".observed.stableIdentityVerified",
            Boolean.toString(TargetResolution.stableTargetIdentityMatches(expected, actual)));
        recordTargetIdentity(result, prefix + ".observed", actual);
    }

    static void recordExpectedTargetIdentity(final Properties result, final String prefix,
        final TargetResolution.TargetIdentity identity) {
        recordTargetIdentity(result, prefix + ".target.expected", identity);
    }

    static void recordTargetIdentity(final Properties result, final String prefix,
        final TargetResolution.TargetIdentity identity) {
        final TargetResolution.TargetIdentity value = identity == null ? TargetResolution.TargetIdentity.unavailable() : identity;
        result.setProperty(prefix + ".documentId", value.documentId());
        result.setProperty(prefix + ".modelId", value.modelId());
        result.setProperty(prefix + ".binding", value.binding());
        result.setProperty(prefix + ".generation", Long.toString(value.generation()));
        result.setProperty(prefix + ".modelImageId", value.modelImageId());
        result.setProperty(prefix + ".artMeshId", value.artMeshId());
        result.setProperty(prefix + ".rawId", value.rawId());
    }

    /** Requires the native history move that the following fresh export is meant to observe. */
    static void requireHistoryMoved(
        final dev.turboism.sdk.cubism.history.HistoryMoveResult move, final String direction) {
        final String name = direction == null || direction.isBlank() ? "history" : direction;
        if (move == null || move.outcome()
            != dev.turboism.sdk.cubism.history.HistoryMoveResult.Outcome.MOVED) {
            throw new IllegalStateException(name + " did not move native history: "
                + (move == null ? "null result" : move.outcome()));
        }
    }

    /**
     * Final persistence evidence gate owned by the probe before it reports a successful phase.
     * The runner repeats the same contract while binding the result to its Supervisor job.
     */
    static void requirePersistEvidence(final Properties result) {
        if (result == null) throw new IllegalStateException("persistence evidence is missing");
        final String baseline = result.getProperty("persist.baselineTargetRgbSha256");
        final String second = result.getProperty("persist.baselineSecondTargetRgbSha256");
        final String post = result.getProperty("persist.postEditTargetRgbSha256");
        if (!RawExportCoordination.isCanonicalSha256(baseline) || !RawExportCoordination.isCanonicalSha256(second)
            || !RawExportCoordination.isCanonicalSha256(post)) {
            throw new IllegalStateException(
                "persistence target RGB hashes are missing or not canonical lowercase SHA-256");
        }
        if (!baseline.equals(second)) {
            throw new IllegalStateException(
                "independent native baseline target RGB fingerprints are unstable");
        }
        if (baseline.equals(post)) {
            throw new IllegalStateException(
                "fresh native post-edit target RGB fingerprint did not change");
        }
        requireExactProperty(result, "persist.targetContentChanged", "true");
        requireExactProperty(result, "persist.saveSucceeded", "true");
        requireExactProperty(result, "persist.tempQuarantine.status", "MOVED");
        requireExactProperty(result, "persist.tempQuarantine.taskOwned", "true");
        requireExactProperty(result, "persist.tempQuarantine.sourceMissing", "true");
    }

    static void requireExactProperty(final Properties result, final String key,
        final String expected) {
        if (!expected.equals(result.getProperty(key))) {
            throw new IllegalStateException(
                "persistence evidence " + key + " must be " + expected);
        }
    }

    static void requireStableBaseline(final PsdValidationContent.Fingerprint first,
        final PsdValidationContent.Fingerprint second) {
        if (first == null || second == null || !RawExportCoordination.isCanonicalSha256(first.sha256())
            || !RawExportCoordination.isCanonicalSha256(second.sha256())) {
            throw new IllegalStateException("native baseline target RGB evidence is missing or invalid");
        }
        if (!first.equals(second)) {
            throw new IllegalStateException(
                "independent native baseline target RGB fingerprints are unstable");
        }
    }

    static void requireChangedPost(final PsdValidationContent.Fingerprint baseline,
        final PsdValidationContent.Fingerprint post) {
        if (baseline == null || post == null || !RawExportCoordination.isCanonicalSha256(baseline.sha256())
            || !RawExportCoordination.isCanonicalSha256(post.sha256())) {
            throw new IllegalStateException("native post-edit target RGB evidence is missing or invalid");
        }
        if (baseline.sha256().equals(post.sha256())) {
            throw new IllegalStateException(
                "fresh native post-edit target RGB fingerprint did not change");
        }
    }

    static void requireReopenTarget(final String expected,
        final PsdValidationContent.Fingerprint actual) {
        if (!RawExportCoordination.isCanonicalSha256(expected) || actual == null
            || !RawExportCoordination.isCanonicalSha256(actual.sha256())) {
            throw new IllegalStateException(
                "reopen target RGB evidence is missing or not canonical lowercase SHA-256");
        }
        if (!expected.equals(actual.sha256())) {
            throw new IllegalStateException(
                "reopened document target RGB fingerprint does not match the saved post-edit fingerprint");
        }
    }

    /**
     * Attribution-only undo-release probe: after the cycle loop, pop the top history entry
     * via the official undo and re-measure the post-GC heap, then redo. A drop confirms the
     * retention owner is the host undo history (legitimate retained state); no drop means a
     * different owner and the diagnostic stays inconclusive. This run is never acceptance.
     */
    static void runAttributionUndoReleaseProbe(final Properties result) {
        if (!PerformanceEvidence.memoryAttributionRequested()) return;
        result.setProperty("attribution.mode", "DIAGNOSTIC_ONLY_NOT_ACCEPTANCE");
        try {
            final OfficialSecondDocumentOpen.Host host = OfficialSecondDocumentOpen.newNativeHost();
            driveAttributionCommand(result, host, true, "attribution.afterOfficialUndo.");
            driveAttributionCommand(result, host, false, "attribution.afterOfficialRedo.");
            result.setProperty("attribution.undoReleaseProbe", "COMPLETED");
        } catch (Throwable failure) {
            result.setProperty("attribution.undoReleaseProbe", "UNAVAILABLE: " + failure);
        }
    }

    static void driveAttributionCommand(final Properties result,
        final OfficialSecondDocumentOpen.Host host, final boolean undo, final String prefix) {
        final AtomicReference<Throwable> dispatchFailure = new AtomicReference<>();
        final Runnable command = () -> {
            try {
                if (undo) host.undo(host.currentNativeDocument());
                else host.redo(host.currentNativeDocument());
            } catch (Throwable error) {
                dispatchFailure.set(error);
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) command.run();
            else SwingUtilities.invokeAndWait(command);
        } catch (Exception failure) {
            dispatchFailure.set(failure);
        }
        if (dispatchFailure.get() != null) {
            result.setProperty(prefix + "status", "FAILED: " + dispatchFailure.get());
        } else {
            result.setProperty(prefix + "status", "EXECUTED");
            recordMemoryAttribution(result, prefix, true);
        }
    }

    static void recordMemoryAttribution(final Properties result, final String prefix,
        final boolean forceGc) {
        if (!PerformanceEvidence.memoryAttributionRequested()) return;
        PerformanceEvidence.recordMemoryPools(result, prefix + "pools.");
        if (forceGc) {
            System.gc();
            try {
                Thread.sleep(150L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            PerformanceEvidence.recordMemoryPools(result, prefix + "afterGc.");
        }
    }

    static void recordEnvironment(final Properties result) throws Exception {
        final Runtime runtime = Runtime.getRuntime();
        result.setProperty("env.processors", Integer.toString(runtime.availableProcessors()));
        result.setProperty("env.heapMaxBytes", Long.toString(runtime.maxMemory()));
        result.setProperty("env.heapUsedBytes",
            Long.toString(runtime.totalMemory() - runtime.freeMemory()));
        final long start = System.nanoTime();
        SwingUtilities.invokeAndWait(() -> { });
        result.setProperty("env.edtDispatchMs",
            Long.toString((System.nanoTime() - start) / 1_000_000));
    }

    static void recordPersistDocumentState(final Properties result, final String prefix,
        final PersistDocumentState state) {
        result.setProperty(prefix + ".documentId", state.targetIdentity().documentId());
        result.setProperty(prefix + ".modelId", state.targetIdentity().modelId());
        result.setProperty(prefix + ".binding", state.targetIdentity().binding());
        result.setProperty(prefix + ".generation",
            Long.toString(state.targetIdentity().generation()));
        result.setProperty(prefix + ".modelImageId", state.targetIdentity().modelImageId());
        result.setProperty(prefix + ".artMeshId", state.targetIdentity().artMeshId());
        result.setProperty(prefix + ".rawId", state.targetIdentity().rawId());
        result.setProperty(prefix + ".contentId", state.contentId().orElse(RawExportCoordination.UNAVAILABLE_VALUE));
        result.setProperty(prefix + ".relativePath", state.relativePath());
        result.setProperty(prefix + ".window", state.windowIdentity());
        result.setProperty(prefix + ".windowShowing", Boolean.toString(state.windowShowing()));
        result.setProperty(prefix + ".windowDisplayable",
            Boolean.toString(state.windowDisplayable()));
    }

    static String pathBasename(final String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return "";
        final String normalized = relativePath.replace('\\', '/');
        final int slash = normalized.lastIndexOf('/');
        return normalized.substring(slash + 1);
    }

    static Class<?> loadExactClass(final ClassLoader loader, final String name)
        throws ClassNotFoundException {
        final Class<?> type = Class.forName(name, false, loader);
        if (!name.equals(type.getName())) {
            throw new ClassNotFoundException("class name mismatch for " + name);
        }
        return type;
    }

    static Method exactMethod(final Class<?> owner, final String name,
        final Class<?> returnType, final boolean staticRequired, final boolean finalRequired,
        final Class<?>... parameters) throws ReflectiveOperationException {
        final Method method = owner.getDeclaredMethod(name, parameters);
        final int modifiers = method.getModifiers();
        if (method.getReturnType() != returnType
            || Modifier.isStatic(modifiers) != staticRequired
            || Modifier.isFinal(modifiers) != finalRequired) {
            throw new IllegalArgumentException("official method shape is not exact: "
                + owner.getName() + '.' + name);
        }
        return method;
    }

    static Field exactField(final Class<?> owner, final String name,
        final Class<?> type, final boolean staticRequired, final boolean finalRequired,
        final boolean publicRequired) throws ReflectiveOperationException {
        final Field field = owner.getDeclaredField(name);
        final int modifiers = field.getModifiers();
        if (field.getType() != type || Modifier.isStatic(modifiers) != staticRequired
            || Modifier.isFinal(modifiers) != finalRequired
            || (publicRequired && !Modifier.isPublic(modifiers))) {
            throw new IllegalArgumentException("official field shape is not exact: "
                + owner.getName() + '.' + name);
        }
        return field;
    }

    static void exactActionResultField(final Class<?> actionResult, final String name,
        final String expectedName) throws ReflectiveOperationException {
        final Field field = exactField(actionResult, name, actionResult, true, true, true);
        final Object value = field.get(null);
        if (!(value instanceof Enum<?> enumValue) || !expectedName.equals(enumValue.name())) {
            throw new IllegalStateException("official aM." + name + " is not " + expectedName);
        }
    }

    static Path codeSourcePath(final Class<?> type) throws Exception {
        final CodeSource source = type.getProtectionDomain() == null
            ? null : type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("official class has no code source: " + type.getName());
        }
        try {
            return Path.of(source.getLocation().toURI()).toRealPath();
        } catch (URISyntaxException | IllegalArgumentException failure) {
            throw new IllegalStateException("official class code source is not a file: "
                + type.getName(), failure);
        }
    }

    /** Snapshot of visible top-level windows; all Swing reads are performed on the EDT. */
    static String describeWindows() {
        if (SwingUtilities.isEventDispatchThread()) return describeWindowsOnEdt();
        final AtomicReference<String> captured = new AtomicReference<>("none");
        try {
            SwingUtilities.invokeAndWait(() -> captured.set(describeWindowsOnEdt()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "EDT window inspection interrupted";
        } catch (InvocationTargetException failure) {
            return "EDT window inspection failed: " + failure.getCause();
        }
        return captured.get();
    }

    static String describeWindowsOnEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("window description must run on EDT");
        }
        final StringBuilder text = new StringBuilder();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isShowing()) continue;
            if (text.length() > 0) text.append(" | ");
            text.append(window.getClass().getSimpleName());
            final String title = window instanceof java.awt.Dialog dialog ? dialog.getTitle()
                : window instanceof java.awt.Frame frame ? frame.getTitle() : null;
            if (title != null && !title.isBlank()) text.append('\'').append(title).append('\'');
            if (window instanceof java.awt.Dialog) {
                final List<String> buttons = new ArrayList<>();
                final List<String> labels = new ArrayList<>();
                collectDialogText(window, buttons, labels, 0);
                if (!buttons.isEmpty()) text.append(" buttons=").append(buttons);
                if (!labels.isEmpty()) text.append(" text=").append(labels);
            }
        }
        return text.length() == 0 ? "none" : text.toString();
    }

    static void collectDialogText(final java.awt.Component component,
        final List<String> buttons, final List<String> labels, final int depth) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("dialog collection must run on EDT");
        }
        if (depth > 6) return;
        if (component instanceof javax.swing.AbstractButton button
            && button.getText() != null && !button.getText().isBlank()) {
            buttons.add(button.getText().trim());
        }
        if (component instanceof javax.swing.JLabel label
            && label.getText() != null && !label.getText().isBlank()) {
            labels.add(label.getText().replaceAll("\\s+", " ").trim());
        }
        if (component instanceof javax.swing.text.JTextComponent textComponent
            && textComponent.getText() != null && !textComponent.getText().isBlank()) {
            labels.add(textComponent.getText().replaceAll("\\s+", " ").trim());
        }
        if (component instanceof java.awt.Container container) {
            for (final java.awt.Component child : container.getComponents()) {
                collectDialogText(child, buttons, labels, depth + 1);
            }
        }
    }

    static void collectDialogActionCommands(final java.awt.Component component,
        final List<String> actionCommands, final int depth) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("dialog action collection must run on EDT");
        }
        if (depth > 6) return;
        if (component instanceof javax.swing.AbstractButton button
            && button.getActionCommand() != null && !button.getActionCommand().isBlank()) {
            actionCommands.add(button.getActionCommand().trim());
        }
        if (component instanceof java.awt.Container container) {
            for (final java.awt.Component child : container.getComponents()) {
                collectDialogActionCommands(child, actionCommands, depth + 1);
            }
        }
    }

    /** Worker-issued proof consumed only by the EDT dialog observer. */
    record UnusedRawDialogProof(boolean available, Path artifact, ClassLoader loader,
        String messageTemplate, int keepOptionIndex, String keepResultName,
        String actionClassName, String diagnostic) {
        UnusedRawDialogProof {
            messageTemplate = messageTemplate == null ? "" : messageTemplate;
            keepResultName = keepResultName == null ? "" : keepResultName;
            actionClassName = actionClassName == null ? "" : actionClassName;
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static UnusedRawDialogProof unavailable(final String diagnostic) {
            return new UnusedRawDialogProof(false, null, null, "", -1, "", "",
                diagnostic);
        }

        static UnusedRawDialogProof proven(final Path artifact, final ClassLoader loader,
            final String messageTemplate, final String diagnostic) {
            return new UnusedRawDialogProof(true, Objects.requireNonNull(artifact, "artifact"),
                Objects.requireNonNull(loader, "loader"), messageTemplate, 1, "NO",
                OFFICIAL_ACTION_CLASS, diagnostic);
        }

        /** Test-only constructor seam; it still exercises the production EDT shape checker. */
        static UnusedRawDialogProof forTest(final String messageTemplate,
            final String actionClassName) {
            return new UnusedRawDialogProof(true, null, null, messageTemplate, 1, "NO",
                actionClassName, "test proof");
        }

        boolean messageMatchesOnEdt(final String message) {
            if (!SwingUtilities.isEventDispatchThread()) {
                throw new IllegalStateException("dialog message proof must run on EDT");
            }
            return available && formattedMessageMatches(messageTemplate, message);
        }

        DialogButtonEvidence buttonsOnEdt(final JOptionPane pane) {
            if (!SwingUtilities.isEventDispatchThread()) {
                throw new IllegalStateException("dialog button proof must run on EDT");
            }
            if (!available || pane == null) {
                return DialogButtonEvidence.unavailable("official dialog proof is unavailable");
            }
            final Object[] options = pane.getOptions();
            if (options == null || options.length != 3) {
                return DialogButtonEvidence.unavailable(
                    "official dialog does not expose exactly three JOptionPane options");
            }
            final List<String> expectedLabels = officialOptionButtonLabelsOnEdt();
            final List<String> actualLabels = new ArrayList<>();
            for (final Object option : options) {
                if (!(option instanceof JButton button)) {
                    return DialogButtonEvidence.unavailable(
                        "official dialog option is not a JButton: " + option);
                }
                actualLabels.add(button.getText() == null ? "" : button.getText());
            }
            if (!actualLabels.equals(expectedLabels)) {
                return DialogButtonEvidence.unavailable("official option labels differ expected="
                    + expectedLabels + " actual=" + actualLabels);
            }
            if (keepOptionIndex < 0 || keepOptionIndex >= options.length
                || !(options[keepOptionIndex] instanceof JButton keep)) {
                return DialogButtonEvidence.unavailable(
                    "official aM." + keepResultName + " option index is unavailable");
            }
            final javax.swing.InputMap inputMap = keep.getInputMap(
                JComponent.WHEN_IN_FOCUSED_WINDOW);
            final Object clickKey = inputMap == null ? null
                : inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_N, 0));
            final javax.swing.Action clickAction = keep.getActionMap().get("Click");
            if (!"Click".equals(clickKey) || clickAction == null
                || !actionClassName.equals(clickAction.getClass().getName())) {
                return DialogButtonEvidence.unavailable(
                    "official aM." + keepResultName
                        + " N->Click/action proof is unavailable key=" + clickKey
                        + " action=" + (clickAction == null ? "null"
                            : clickAction.getClass().getName()));
            }
            return DialogButtonEvidence.proven(keep, OFFICIAL_KEEP_ACTION_TOKEN,
                "JOptionPane options[1] label/action shape proven; aM." + keepResultName
                    + " maps to the live N->Click action " + actionClassName);
        }
    }

    record DialogButtonEvidence(boolean proven, JButton keepButton, String actionToken,
        String diagnostic) {
        DialogButtonEvidence {
            actionToken = actionToken == null ? "" : actionToken;
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static DialogButtonEvidence unavailable(final String diagnostic) {
            return new DialogButtonEvidence(false, null, "", diagnostic);
        }

        static DialogButtonEvidence proven(final JButton keepButton, final String actionToken,
            final String diagnostic) {
            return new DialogButtonEvidence(true, Objects.requireNonNull(keepButton,
                "keepButton"), actionToken, diagnostic);
        }
    }

    record VerifiedHostArtifact(ClassLoader loader, Path artifact) {
        VerifiedHostArtifact {
            Objects.requireNonNull(loader, "loader");
            Objects.requireNonNull(artifact, "artifact");
        }
    }

    static List<String> officialOptionButtonLabelsOnEdtForTest() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("official option labels must be read on EDT");
        }
        return officialOptionButtonLabelsOnEdt();
    }

    static List<String> officialOptionButtonLabelsOnEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("official option labels must be read on EDT");
        }
        final String yes = UIManager.getString("OptionPane.yesButtonText");
        final String no = UIManager.getString("OptionPane.noButtonText");
        final String cancel = UIManager.getString("OptionPane.cancelButtonText");
        if (yes == null || no == null || cancel == null) return List.of("", "", "");
        return List.of(yes.replace("(Y)", "") + "(Y)",
            no.replace("(N)", "") + "(N)", cancel + "(C)");
    }

    static boolean formattedMessageMatches(final String template,
        final String actual) {
        if (template == null || template.isBlank() || actual == null || actual.isBlank()) {
            return false;
        }
        final String normalizedTemplate = normalizeDialogText(template);
        final String normalizedActual = normalizeDialogText(actual);
        final StringBuilder regex = new StringBuilder("^");
        int literalStart = 0;
        for (int index = 0; index < normalizedTemplate.length(); index++) {
            if (normalizedTemplate.charAt(index) != '%') continue;
            int conversion = index + 1;
            while (conversion < normalizedTemplate.length()
                && (Character.isDigit(normalizedTemplate.charAt(conversion))
                    || normalizedTemplate.charAt(conversion) == '$')) {
                conversion++;
            }
            if (conversion >= normalizedTemplate.length()
                || (normalizedTemplate.charAt(conversion) != 'd'
                    && normalizedTemplate.charAt(conversion) != 's')) continue;
            regex.append(Pattern.quote(normalizedTemplate.substring(literalStart, index)));
            regex.append(normalizedTemplate.charAt(conversion) == 'd' ? "\\d+" : ".+?");
            index = conversion;
            literalStart = conversion + 1;
        }
        regex.append(Pattern.quote(normalizedTemplate.substring(literalStart))).append('$');
        return Pattern.compile(regex.toString(), Pattern.DOTALL)
            .matcher(normalizedActual).matches();
    }

    static String normalizeDialogText(final String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    enum SaveDialogOutcome {
        KEEP,
        OBSERVE_ONLY,
        REJECTED
    }

    record SaveDialogSnapshot(String dialogIdentity, String boundWindowIdentity,
        List<String> ownerChain, boolean ownerChainMatches, boolean showing, boolean displayable,
        String documentId, String expectedDocumentId, long generation, long expectedGeneration,
        boolean taskIdentityMatches, String messageKey, boolean messageTemplateProven,
        String keepActionCommand, boolean keepActionProven, List<String> messages,
        List<String> buttonLabels, List<String> actionCommands, int candidateCount,
        boolean stopped, boolean closed) {
        SaveDialogSnapshot {
            dialogIdentity = dialogIdentity == null ? "" : dialogIdentity;
            boundWindowIdentity = boundWindowIdentity == null ? "" : boundWindowIdentity;
            ownerChain = ownerChain == null ? List.of() : List.copyOf(ownerChain);
            documentId = documentId == null ? "" : documentId;
            expectedDocumentId = expectedDocumentId == null ? "" : expectedDocumentId;
            messageKey = messageKey == null ? "" : messageKey;
            keepActionCommand = keepActionCommand == null ? "" : keepActionCommand;
            messages = messages == null ? List.of() : List.copyOf(messages);
            buttonLabels = buttonLabels == null ? List.of() : List.copyOf(buttonLabels);
            actionCommands = actionCommands == null ? List.of() : List.copyOf(actionCommands);
        }

        String diagnostic() {
            return "dialog=" + dialogIdentity + " bound=" + boundWindowIdentity
                + " ownerChain=" + ownerChain + " ownerChainMatches=" + ownerChainMatches
                + " showing=" + showing + " displayable=" + displayable
                + " document=" + documentId + "/" + expectedDocumentId
                + " generation=" + generation + "/" + expectedGeneration
                + " taskIdentityMatches=" + taskIdentityMatches
                + " messageKey=" + messageKey + " messageTemplateProven="
                + messageTemplateProven + " keepActionCommand=" + keepActionCommand
                + " keepActionProven=" + keepActionProven + " messages=" + messages
                + " buttons=" + buttonLabels + " actionCommands=" + actionCommands
                + " candidates=" + candidateCount + " stopped=" + stopped
                + " closed=" + closed;
        }
    }

    record SaveDialogDecision(SaveDialogOutcome outcome, String diagnostic) {
        SaveDialogDecision {
            outcome = Objects.requireNonNull(outcome, "outcome");
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }

    static SaveDialogDecision classifySaveDialogForTest(final SaveDialogSnapshot snapshot) {
        if (snapshot == null) return new SaveDialogDecision(
            SaveDialogOutcome.REJECTED, "save dialog snapshot is unavailable");
        if (snapshot.stopped() || snapshot.closed()) return new SaveDialogDecision(
            SaveDialogOutcome.REJECTED, "save dialog action is cancelled after stop/close: "
                + snapshot.diagnostic());
        if (snapshot.candidateCount() != 1) return new SaveDialogDecision(
            SaveDialogOutcome.REJECTED, "save dialog candidate count is not one: "
                + snapshot.diagnostic());
        if (snapshot.dialogIdentity().isBlank() || snapshot.boundWindowIdentity().isBlank()
            || !snapshot.ownerChainMatches()
            || !snapshot.ownerChain().contains(snapshot.boundWindowIdentity())) {
            return new SaveDialogDecision(SaveDialogOutcome.REJECTED,
                "save dialog owner chain is not the exact task window: " + snapshot.diagnostic());
        }
        if (!snapshot.showing() || !snapshot.displayable()) return new SaveDialogDecision(
            SaveDialogOutcome.REJECTED, "save dialog is not showing/displayable: "
                + snapshot.diagnostic());
        if (!snapshot.taskIdentityMatches()
            || snapshot.documentId().isBlank()
            || !snapshot.documentId().equals(snapshot.expectedDocumentId())
            || snapshot.generation() < 0L
            || snapshot.generation() != snapshot.expectedGeneration()) {
            return new SaveDialogDecision(SaveDialogOutcome.REJECTED,
                "save dialog task document/generation is stale: " + snapshot.diagnostic());
        }
        if (!snapshot.messageTemplateProven()
            || !UNUSED_RAW_MESSAGE_KEY.equals(snapshot.messageKey())) {
            return new SaveDialogDecision(SaveDialogOutcome.OBSERVE_ONLY,
                "official " + UNUSED_RAW_MESSAGE_KEY
                    + " formatted unused-raw message is not proven; no generic label action: "
                    + snapshot.diagnostic());
        }
        if (!snapshot.keepActionProven() || snapshot.keepActionCommand().isBlank()
            || !snapshot.actionCommands().contains(snapshot.keepActionCommand())) {
            return new SaveDialogDecision(SaveDialogOutcome.OBSERVE_ONLY,
                "official keep/no action is not proven from UUOption/aM.b evidence; "
                    + "no action: " + snapshot.diagnostic());
        }
        return new SaveDialogDecision(SaveDialogOutcome.KEEP,
            "proven CUB3-3054 keep action for the exact task dialog: "
                + snapshot.diagnostic());
    }

    static List<JOptionPane> findOptionPanes(final Component component, final int depth) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("JOptionPane lookup must run on EDT");
        }
        if (component == null || depth > 8) return List.of();
        final List<JOptionPane> panes = new ArrayList<>();
        if (component instanceof JOptionPane pane) panes.add(pane);
        if (component instanceof Container container) {
            for (final Component child : container.getComponents()) {
                panes.addAll(findOptionPanes(child, depth + 1));
            }
        }
        return panes;
    }

    static String optionPaneMessageOnEdt(final JOptionPane pane) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("JOptionPane message lookup must run on EDT");
        }
        final Object message = pane == null ? null : pane.getMessage();
        return message instanceof String value ? value : "";
    }

    static List<String> ownerChain(final Dialog dialog) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("dialog owner-chain inspection must run on EDT");
        }
        final List<String> chain = new ArrayList<>();
        final Set<Window> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Window current = dialog;
        while (current != null && seen.add(current)) {
            chain.add(ExactHostRowDispatch.objectIdentity(current));
            current = current.getOwner();
        }
        return List.copyOf(chain);
    }

    static javax.swing.AbstractButton findButtonByActionCommand(
        final java.awt.Component component, final String actionCommand, final int depth) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("dialog action lookup must run on EDT");
        }
        if (actionCommand == null || actionCommand.isBlank() || depth > 6
            || !(component instanceof java.awt.Container container)) return null;
        for (final java.awt.Component child : container.getComponents()) {
            if (child instanceof javax.swing.AbstractButton button
                && actionCommand.equals(button.getActionCommand())) return button;
            final javax.swing.AbstractButton nested = findButtonByActionCommand(
                child, actionCommand, depth + 1);
            if (nested != null) return nested;
        }
        return null;
    }

    static ExactHostRowDispatch.ReviewedTables visibleReviewedTables() throws Exception {
        final AtomicReference<ExactHostRowDispatch.ReviewedTables> found = new AtomicReference<>(
            ExactHostRowDispatch.ReviewedTables.unavailable("active target window was not observed"));
        SwingUtilities.invokeAndWait(() -> {
            try {
                final Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .getActiveWindow();
                if (active == null) {
                    found.set(ExactHostRowDispatch.ReviewedTables.unavailable(
                        "active target window cannot be proven: AWT active window is null"));
                    return;
                }
                if (!active.isShowing() || !active.isDisplayable()) {
                    found.set(ExactHostRowDispatch.ReviewedTables.unavailable(
                        "active target window cannot be proven showing/displayable: "
                            + GuiRowDispatch.componentIdentity(active)));
                    return;
                }
                final List<ExactHostRowDispatch.ExactTableRef> tables = new ArrayList<>();
                ExactHostRowDispatch.collectReviewedTables(active, active, tables);
                final String windowIdentity = GuiRowDispatch.componentIdentity(active);
                found.set(new ExactHostRowDispatch.ReviewedTables(List.copyOf(tables), windowIdentity, active, true,
                    "activeTargetWindow=" + windowIdentity + " tables=" + tables.size()));
            } catch (RuntimeException failure) {
                found.set(ExactHostRowDispatch.ReviewedTables.unavailable(
                    "active target window discovery failed: " + failure));
            }
        });
        return found.get();
    }

    record PersistDocumentState(TargetResolution.TargetIdentity targetIdentity, Optional<String> contentId,
        String relativePath, String windowIdentity, boolean windowShowing,
        boolean windowDisplayable) {
        PersistDocumentState {
            targetIdentity = Objects.requireNonNull(targetIdentity, "targetIdentity");
            contentId = Objects.requireNonNull(contentId, "contentId");
            relativePath = relativePath == null ? "" : relativePath;
            windowIdentity = windowIdentity == null ? "" : windowIdentity;
        }
    }

    record BoundTaskWindowEvidence(Window window, String identity, int exactMatches) {
        BoundTaskWindowEvidence {
            Objects.requireNonNull(window, "window");
            identity = identity == null ? "" : identity;
            if (exactMatches < 1) throw new IllegalArgumentException("exactMatches must be positive");
        }
    }

    record HostWindowAccess(Path artifact, Method appInstance,
        Method mainFrameController, Method mainFrame, Method swingWindow, Method swingFrame) {
        HostWindowAccess {
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(appInstance, "appInstance");
            Objects.requireNonNull(mainFrameController, "mainFrameController");
            Objects.requireNonNull(mainFrame, "mainFrame");
            Objects.requireNonNull(swingWindow, "swingWindow");
            Objects.requireNonNull(swingFrame, "swingFrame");
        }
    }

    record PersistSaveEvent(long sequence, ProjectFileOperationType operation,
        boolean succeeded, Optional<String> requestContentId, Optional<String> resultContentId,
        Optional<String> requestFileName) {
        PersistSaveEvent {
            if (sequence < 0L) throw new IllegalArgumentException("sequence must not be negative");
            operation = Objects.requireNonNull(operation, "operation");
            requestContentId = Objects.requireNonNull(requestContentId, "requestContentId");
            resultContentId = Objects.requireNonNull(resultContentId, "resultContentId");
            requestFileName = Objects.requireNonNull(requestFileName, "requestFileName");
        }

        static PersistSaveEvent from(final long sequence,
            final ProjectFileLifecycleEvent.After event) {
            final var result = event.result();
            return new PersistSaveEvent(
                sequence,
                event.operation().operation(),
                result.succeeded(),
                result.request().contentId(),
                result.content().map(content -> content.contentId()),
                result.request().fileName());
        }
    }

    record PersistSaveValidation(boolean accepted, String diagnostic,
        PersistSaveEvent event) {
        PersistSaveValidation {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static PersistSaveValidation accepted(final PersistSaveEvent event,
            final String diagnostic) {
            return new PersistSaveValidation(true, diagnostic, Objects.requireNonNull(event));
        }

        static PersistSaveValidation rejected(final String diagnostic) {
            return new PersistSaveValidation(false, diagnostic, null);
        }
    }

    record SaveAsAdmission(boolean admitted, String diagnostic) {
        SaveAsAdmission {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static SaveAsAdmission admitted(final String diagnostic) {
            return new SaveAsAdmission(true, diagnostic);
        }

        static SaveAsAdmission rejected(final String diagnostic) {
            return new SaveAsAdmission(false, diagnostic);
        }
    }

    record SaveAsExecution(EditorCommandResult result, PersistDocumentState before,
        long executeBeforeSequence) {
        SaveAsExecution {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(before, "before");
            if (executeBeforeSequence < 0L) {
                throw new IllegalArgumentException("executeBeforeSequence must not be negative");
            }
        }
    }
}
