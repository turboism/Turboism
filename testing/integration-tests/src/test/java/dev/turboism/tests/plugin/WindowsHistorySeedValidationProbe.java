package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.ModelObjectDescriptor;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.plugin.PluginContext;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Manual-test-only SDK writer that creates and restores Parameter and native Artmesh Undo items. */
public final class WindowsHistorySeedValidationProbe implements CubismPlugin {

    private static final long MAX_EVIDENCE_BYTES = WindowsHistoryManagerValidationProbe.MAX_EVIDENCE_BYTES;
    private static final long TERMINAL_RESERVE_BYTES = 2_048L;
    private static final int MAX_PAIRED_SAMPLES = 24;
    private static final String INTERNAL_ROOT_PART = "__RootPart__";
    private static final List<String> REQUIRED_PAIRED_PHASES = List.of(
        "baseline",
        "write-1",
        "write-2",
        "third-write",
        "group",
        "undo",
        "redo",
        "restored",
        "artmesh-baseline",
        "artmesh-write-1",
        "artmesh-write-2",
        "artmesh-third-write",
        "artmesh-undo",
        "artmesh-redo",
        "relation-baseline",
        "relation-write-1",
        "relation-write-2",
        "relation-undo",
        "relation-redo",
        "relation-mcp-baseline",
        "relation-mcp-write",
        "relation-mcp-undo"
    );

    private PluginContext context;
    private Thread worker;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        context.logger().info("History seed validation probe initialized");
    }

    @Override
    public void enable() {
        worker = new Thread(this::run, "turboism-history-seed-validation");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void disable() {
        if (worker != null) worker.interrupt();
    }

    private void run() {
        final Path artifact = context.paths().dataDir().resolve("history-seed.jsonl");
        try {
            Files.createDirectories(artifact.getParent());
            if (Files.exists(artifact)) {
                throw new IllegalStateException("History seed evidence already exists");
            }
            final Evidence evidence = new Evidence(artifact);
            evidence.event("phase", "await-model");

            final Parameter parameter = awaitParameter();
            final String id = onEdt(() -> parameter.id().value());
            final float before = onEdt(parameter::getValue);
            final float minimum = onEdt(parameter::getMinimumValue);
            final float maximum = onEdt(parameter::getMaximumValue);
            final float first = valueAt(minimum, maximum, 0.17F);
            final float second = valueAt(minimum, maximum, 0.43F);
            final float third = valueAt(minimum, maximum, 0.71F);
            capturePaired(evidence, "baseline");

            writeValue(parameter, evidence, "write-1", "write-1", id, first);
            writeValue(parameter, evidence, "write-2", "write-2", id, second);
            writeValue(parameter, evidence, "write-3", "third-write", id, third);
            final HistorySnapshot history = context.cubism().history().snapshot();
            Thread.sleep(5_000L);
            final HistorySnapshot navigationHistory = context.cubism().history().snapshot();

            final HistoryMoveResult attempted = context.cubism().history().moveTo(
                navigationHistory.generation(),
                navigationHistory.revision(),
                1
            );
            final float afterAttempt = onEdt(parameter::getValue);
            evidence.check(
                "move-restores-first-value",
                attempted.outcome() == HistoryMoveResult.Outcome.MOVED && same(afterAttempt, first),
                "outcome=MOVED,value=" + first,
                "outcome=" + attempted.outcome().name() + ",value=" + afterAttempt
            );
            evidence.check(
                "move-keeps-history-available",
                attempted.snapshot().availability() == HistorySnapshot.Availability.AVAILABLE,
                HistorySnapshot.Availability.AVAILABLE.name(),
                attempted.snapshot().availability().name()
            );
            capturePaired(evidence, "undo");

            final HistorySnapshot movedSnapshot = attempted.snapshot();
            final HistoryMoveResult returnedToTip = context.cubism().history().moveTo(
                movedSnapshot.generation(),
                movedSnapshot.revision(),
                movedSnapshot.entries().size()
            );
            final float afterReturn = onEdt(parameter::getValue);
            evidence.check(
                "move-returns-to-tip",
                returnedToTip.outcome() == HistoryMoveResult.Outcome.MOVED && same(afterReturn, third),
                "outcome=MOVED,value=" + third,
                "outcome=" + returnedToTip.outcome().name() + ",value=" + afterReturn
            );
            capturePaired(evidence, "redo");

            final AuthoringTransactionResult<Void> grouped = onEdt(() ->
                context.cubism().authoringTransactions().execute(
                    AuthoringTransactionOptions.of("Semantic history grouped parameter edit"),
                    () -> {
                        parameter.setValue(first);
                        parameter.setValue(second);
                        return null;
                    }
                )
            );
            final HistorySnapshot groupedHistory = context.cubism().history().snapshot();
            capturePaired(evidence, "group");
            final String groupedEntryId = grouped.receipt()
                .flatMap(receipt -> receipt.historyEntryId())
                .orElse("");
            final Optional<HistoryEntry> groupedEntry = groupedHistory.entries().stream()
                .filter(entry -> entry.entryId().map(value -> value.value().equals(groupedEntryId)).orElse(false))
                .findFirst();

            evidence.check(
                "grouped-transaction-committed",
                grouped.outcome() == AuthoringTransactionOutcome.COMMITTED,
                AuthoringTransactionOutcome.COMMITTED.name(),
                grouped.outcome().name() + ",diagnostic=" + grouped.diagnosticId().orElse("")
            );
            evidence.check(
                "grouped-entry-correlated-by-stable-id",
                !groupedEntryId.isBlank() && groupedEntry.isPresent(),
                "non-empty receipt historyEntryId present in fresh snapshot",
                groupedEntryId.isBlank() ? "missing receipt historyEntryId" : "entryPresent=" + groupedEntry.isPresent()
            );
            if (groupedEntry.isPresent()) {
                final HistoryEntry entry = groupedEntry.orElseThrow();
                evidence.entry("grouped-entry", entry);
                evidence.check(
                    "grouped-entry-structure",
                    entry.transactionId().isPresent()
                        && entry.detail().group().isPresent()
                        && entry.detail().group().orElseThrow().children().size() == 2
                        && entry.detail().origin().kind()
                            == dev.turboism.sdk.cubism.history.HistoryOrigin.Kind.TURBOISM,
                    "transactionId,group with 2 children,origin=TURBOISM",
                    "transactionId=" + entry.transactionId().isPresent()
                        + ",group=" + entry.detail().group().isPresent()
                        + ",children=" + entry.detail().group().map(value -> value.children().size()).orElse(0)
                        + ",origin=" + entry.detail().origin().kind().name()
                );
                entry.detail().group().ifPresent(group -> {
                    for (int index = 0; index < group.children().size(); index++) {
                        evidence.detail("grouped-child-" + index, group.children().get(index));
                    }
                });
            }

            onEdt(() -> { parameter.setValue(before); return null; });
            final float restored = awaitValue(parameter, before);
            evidence.check("fixture-value-restored", same(restored, before), Float.toString(before), Float.toString(restored));
            Thread.sleep(5_000L);

            evidence.check(
                "seed-history-shape",
                history.availability() == HistorySnapshot.Availability.AVAILABLE
                    && history.position() == 3
                    && history.entries().size() == 3,
                "availability=AVAILABLE,position=3,entries=3",
                "availability=" + history.availability().name()
                    + ",position=" + history.position()
                    + ",entries=" + history.entries().size()
            );
            for (int index = 0; index < history.entries().size(); index++) {
                final HistoryEntry entry = history.entries().get(index);
                evidence.entry("seed-entry-" + index, entry);
                assertParameterSemanticDetail(evidence, "seed-entry-" + index, entry, id);
            }
            validateRelationCapturedSemantic(evidence);
            validateArtMeshCapturedSemantic(evidence);
            evidence.summary();
        } catch (Exception exception) {
            try {
                append(artifact,
                    "{\"type\":\"error\",\"class\":\"" + json(exception.getClass().getName())
                        + "\",\"message\":\"" + json(exception.getMessage()) + "\"}\n"
                        + "{\"type\":\"summary\",\"status\":\"FAIL\"}\n", true);
            } catch (Exception ignored) {
                context.logger().error("History seed evidence could not be written", exception);
            }
        }
    }

    private void capturePaired(final Evidence evidence, final String phase) {
        try {
            final WindowsHistoryManagerValidationProbe.Snapshot snapshot = onEdt(
                () -> WindowsHistoryManagerValidationProbe.sample(context)
            );
            evidence.pairedSample(phase, snapshot);
        } catch (Exception exception) {
            evidence.pairedFailure(phase, exception);
        }
    }

    private static void assertParameterSemanticDetail(
        final Evidence evidence,
        final String checkPrefix,
        final HistoryEntry entry,
        final String parameterId
    ) {
        final Optional<HistoryAction> action = entry.action();
        final boolean valid = entry.entryId().isPresent()
            && entry.detailLevel() == HistoryAction.DetailLevel.FULL
            && action.isPresent()
            && action.orElseThrow().kind() == HistoryAction.Kind.SET_PARAMETER_VALUE
            && action.orElseThrow().targetType().equals("PARAMETER")
            && action.orElseThrow().targetId().equals(parameterId)
            && action.orElseThrow().property().equals("value")
            && action.orElseThrow().before().isPresent()
            && action.orElseThrow().after().isPresent()
            && entry.detail().origin().kind()
                == dev.turboism.sdk.cubism.history.HistoryOrigin.Kind.TURBOISM
            && entry.detail().targets().size() == 1
            && entry.detail().targets().get(0).type().equals("PARAMETER")
            && entry.detail().targets().get(0).id().filter(parameterId::equals).isPresent()
            && entry.detail().changes().size() == 1
            && semanticParameterChange(entry.detail().changes().get(0));
        evidence.check(
            checkPrefix + "-semantic-detail",
            valid,
            "stableId,FULL,SET_PARAMETER_VALUE,PARAMETER:" + parameterId
                + ",property=value,before+after,context=OBJECT,origin=TURBOISM",
            "stableId=" + entry.entryId().isPresent()
                + ",level=" + entry.detailLevel().name()
                + ",action=" + action.map(value -> value.kind().name()).orElse("missing")
                + ",targets=" + entry.detail().targets().size()
                + ",changes=" + entry.detail().changes().size()
                + ",origin=" + entry.detail().origin().kind().name()
        );
    }

    private static boolean semanticParameterChange(final HistoryChange change) {
        return change.operation() == HistoryChange.Operation.SET
            && change.property().filter("value"::equals).isPresent()
            && change.before().isPresent()
            && change.after().isPresent()
            && change.context().kind()
                == dev.turboism.sdk.cubism.history.HistoryEditContext.Kind.OBJECT
            && change.context().formId().isEmpty()
            && change.context().coordinates().isEmpty();
    }

    private void validateArtMeshCapturedSemantic(final Evidence evidence) throws Exception {
        evidence.event("phase", "artmesh-sdk-captured-semantic");
        final Drawable drawable = awaitDrawable();
        final String drawableId = onEdt(() -> drawable.id().value());
        final Color first = artMeshProbeColorA();
        final Color second = artMeshProbeColorB();
        final HistorySnapshot baseline = context.cubism().history().snapshot();
        capturePaired(evidence, "artmesh-baseline");

        HistorySnapshot projected = baseline;
        try {
            onEdt(() -> { drawable.setMultiplyColor(first); return null; });
            final Optional<HistorySnapshot> firstAdvance = awaitHistoryAdvance(baseline, 50);
            final HistorySnapshot knownFirst = firstAdvance.orElseGet(() -> context.cubism().history().snapshot());
            capturePaired(evidence, "artmesh-write-1");

            onEdt(() -> { drawable.setMultiplyColor(second); return null; });
            projected = awaitHistoryAdvance(knownFirst, 100)
                .orElseThrow(() -> new IllegalStateException(
                    "Timed out waiting for known Artmesh color transition"
                ));
            capturePaired(evidence, "artmesh-write-2");
            final HistoryEntry entry = projected.entries().get(projected.position() - 1);
            evidence.entry("artmesh-captured-entry", entry);
            evidence.nestedDetails("artmesh-captured-child", entry.detail());
            final Optional<HistoryEntryDetail> semanticDetail = semanticDetail(
                entry.detail(),
                "multiplyColor"
            );
            semanticDetail.ifPresent(detail -> evidence.detail("artmesh-captured-semantic", detail));
            final Optional<HistoryChange> semanticChange = semanticDetail.flatMap(detail ->
                detail.changes().stream()
                    .filter(change -> change.property().filter("multiplyColor"::equals).isPresent())
                    .findFirst()
            );
            final boolean contextComplete = semanticChange.map(change -> switch (change.context().kind()) {
                case DEFAULT_FORM -> change.context().formId().isPresent()
                    && change.context().coordinates().isEmpty();
                case KEYFORM -> change.context().formId().isPresent()
                    && !change.context().coordinates().isEmpty();
                default -> false;
            }).orElse(false);
            final boolean valid = entry.entryId().isPresent()
                && entry.action().isEmpty()
                && semanticDetail.filter(detail ->
                    detail.detailLevel() == HistoryAction.DetailLevel.FULL
                        && detail.origin().kind()
                            == dev.turboism.sdk.cubism.history.HistoryOrigin.Kind.TURBOISM
                        && detail.targets().size() == 1
                        && detail.targets().get(0).type().equals("ART_MESH")
                        && detail.targets().get(0).id().filter(drawableId::equals).isPresent()
                        && detail.changes().size() == 1
                ).isPresent()
                && semanticChange.filter(change ->
                    change.operation() == HistoryChange.Operation.SET
                        && change.before().filter(rgb(first)::equals).isPresent()
                        && change.after().filter(rgb(second)::equals).isPresent()
                ).isPresent()
                && contextComplete;
            evidence.check(
                "artmesh-sdk-captured-semantic-detail",
                valid,
                "stableId,direct-or-nested FULL,ART_MESH:" + drawableId
                    + ",property=multiplyColor,before=" + rgb(first)
                    + ",after=" + rgb(second)
                    + ",context=DEFAULT_FORM|KEYFORM,origin=TURBOISM,legacyAction=empty",
                "stableId=" + entry.entryId().isPresent()
                    + ",topLevel=" + entry.detailLevel().name()
                    + ",semanticLevel=" + semanticDetail.map(value -> value.detailLevel().name()).orElse("missing")
                    + ",action=" + entry.action().isPresent()
                    + ",targets=" + semanticDetail.map(value -> value.targets().size()).orElse(0)
                    + ",changes=" + semanticDetail.map(value -> value.changes().size()).orElse(0)
                    + ",origin=" + semanticDetail.map(value -> value.origin().kind().name()).orElse("missing")
                    + ",before=" + semanticChange.flatMap(HistoryChange::before).orElse("missing")
                    + ",after=" + semanticChange.flatMap(HistoryChange::after).orElse("missing")
                    + ",context=" + semanticChange.map(change -> change.context().kind().name()).orElse("missing")
            );
            final HistoryEntry firstEntry = knownFirst.entries().get(knownFirst.position() - 1);
            onEdt(() -> { drawable.setMultiplyColor(new Color(0.2F, 0.4F, 0.6F, 1.0F)); return null; });
            final HistorySnapshot third = awaitHistoryAdvance(projected, 100).orElseThrow();
            capturePaired(evidence, "artmesh-third-write");
            evidence.check("artmesh-capture-stable-after-third-write",
                sameSemanticEntry(firstEntry, third) && sameSemanticEntry(entry, third),
                "both earlier entries retain exact frozen detail", "first=" + sameSemanticEntry(firstEntry, third)
                    + ",second=" + sameSemanticEntry(entry, third));
            final HistoryMoveResult back = onEdt(() -> {
                final HistorySnapshot fresh = context.cubism().history().snapshot();
                return context.cubism().history().moveTo(fresh.generation(), fresh.revision(), knownFirst.position());
            });
            evidence.check("artmesh-capture-stable-after-undo",
                back.outcome() == HistoryMoveResult.Outcome.MOVED && sameSemanticEntry(entry, back.snapshot())
                    && sameSemanticEntry(firstEntry, back.snapshot()), "MOVED with frozen details", back.outcome().name());
            capturePaired(evidence, "artmesh-undo");
            final HistoryMoveResult redo = onEdt(() -> {
                final HistorySnapshot fresh = context.cubism().history().snapshot();
                return context.cubism().history().moveTo(fresh.generation(), fresh.revision(), third.position());
            });
            evidence.check("artmesh-capture-stable-after-redo",
                redo.outcome() == HistoryMoveResult.Outcome.MOVED && sameSemanticEntry(entry, redo.snapshot())
                    && sameSemanticEntry(firstEntry, redo.snapshot()), "MOVED with frozen details", redo.outcome().name());
            capturePaired(evidence, "artmesh-redo");
        } finally {
            final HistorySnapshot current = context.cubism().history().snapshot();
            final HistoryMoveResult restored = context.cubism().history().moveTo(
                current.generation(),
                current.revision(),
                baseline.position()
            );
            evidence.check(
                "artmesh-multiply-color-restored",
                restored.outcome() == HistoryMoveResult.Outcome.MOVED
                    && restored.snapshot().position() == baseline.position(),
                "outcome=MOVED,position=" + baseline.position(),
                "outcome=" + restored.outcome().name()
                    + ",position=" + restored.snapshot().position()
            );
            capturePaired(evidence, "restored");
            Thread.sleep(2_000L);
        }
    }

    /** One direct native parent observation: at most one of Part or Deformer is present. */
    record NativeParent(Optional<String> partId, Optional<String> deformerId) {
        String text() {
            return "part=" + partId.orElse("ROOT") + ",deformer=" + deformerId.orElse("ROOT");
        }
    }

    /**
     * Captures and verifies direct relation semantics for one Artmesh: a Part membership and a
     * second direct relation (deformer parent when the fixture exposes a Deformer, otherwise a
     * second Part membership). Parent identity is discovered at runtime and every captured
     * endpoint is compared against the native direct parent observed immediately before the write,
     * so no fixture name is hard-coded and a missing operand fails loudly.
     */
    private void validateRelationCapturedSemantic(final Evidence evidence) throws Exception {
        evidence.event("phase", "relation-sdk-captured-semantic");
        // One access owns the child and every parent operand: a Part obtained from another
        // access belongs to a different model generation and cannot be applied to this child.
        final CubismModel model = onEdt(() -> context.cubism().model().active());
        final Drawable drawable = awaitDrawable(model);
        final String childId = onEdt(() -> drawable.id().value());
        final List<Part> parts = onEdt(() -> model.parts().all());
        final List<Deformer> deformers = onEdt(() -> model.deformers().all());
        final List<String> partIds = onEdt(() -> parts.stream().map(part -> part.id().value()).toList());
        final List<String> deformerIds = onEdt(() -> deformers.stream()
            .map(deformer -> deformer.id().value()).toList());
        final NativeParent original = observeNativeParent(drawable);
        evidence.event(
            "relation-inventory",
            "child=" + childId + ";parts=" + partIds + ";deformers=" + deformerIds
                + ";original=" + original.text()
        );

        final List<String> candidates = partIds.stream()
            .filter(id -> !id.equals(original.partId().orElse(null)))
            .filter(id -> !INTERNAL_ROOT_PART.equals(id))
            .toList();
        final Optional<String> membershipTarget = candidates.stream().findFirst();
        evidence.check(
            "relation-part-membership-target",
            membershipTarget.isPresent(),
            "a real Part exists that is not the current direct parent",
            "parts=" + partIds + ",currentPart=" + original.partId().orElse("ROOT")
        );
        if (membershipTarget.isEmpty()) {
            return;
        }
        final String membershipId = membershipTarget.orElseThrow();
        final Part membershipParent = onEdt(() -> model.parts().find(new PartId(membershipId)));

        final HistorySnapshot baseline = context.cubism().history().snapshot();
        capturePaired(evidence, "relation-baseline");
        final NativeParent beforeMembership = observeNativeParent(drawable);
        onEdt(() -> { drawable.setParent(membershipParent, -1); return null; });
        final HistorySnapshot membershipHistory = awaitHistoryAdvance(baseline, 100)
            .orElseThrow(() -> new IllegalStateException(
                "Timed out waiting for Part membership relation capture"
            ));
        capturePaired(evidence, "relation-write-1");
        final HistoryEntry membershipEntry = membershipHistory.entries()
            .get(membershipHistory.position() - 1);
        evidence.entry("relation-membership-entry", membershipEntry);
        evidence.nestedDetails("relation-membership-child", membershipEntry.detail());
        validateCapturedRelation(
            evidence,
            "relation-part-membership-captured",
            membershipEntry,
            childId,
            "PART_MEMBERSHIP",
            beforeMembership,
            "PART",
            membershipId
        );
        final NativeParent afterMembership = observeNativeParent(drawable);
        evidence.check(
            "relation-part-membership-native-applied",
            afterMembership.partId().filter(membershipId::equals).isPresent(),
            "native parentPartId=" + membershipId,
            afterMembership.text()
        );

        final Optional<String> secondTarget = candidates.stream()
            .filter(id -> !id.equals(membershipId))
            .findFirst();
        evidence.check(
            "relation-second-direct-target",
            secondTarget.isPresent(),
            "a second Part exists that is not the current or first target parent",
            "parts=" + partIds + ";first=" + membershipId
        );
        if (secondTarget.isEmpty()) {
            return;
        }
        final String secondId = secondTarget.orElseThrow();
        final Part secondParent = onEdt(() -> model.parts().find(new PartId(secondId)));
        final NativeParent beforeSecond = observeNativeParent(drawable);
        onEdt(() -> { drawable.setParent(secondParent, -1); return null; });
        final HistorySnapshot secondHistory = awaitHistoryAdvance(membershipHistory, 100)
            .orElseThrow(() -> new IllegalStateException(
                "Timed out waiting for the second direct relation capture"
            ));
        capturePaired(evidence, "relation-write-2");
        final HistoryEntry secondEntry = secondHistory.entries().get(secondHistory.position() - 1);
        evidence.entry("relation-second-entry", secondEntry);
        evidence.nestedDetails("relation-second-child", secondEntry.detail());
        validateCapturedRelation(
            evidence,
            "relation-second-captured",
            secondEntry,
            childId,
            "PART_MEMBERSHIP",
            beforeSecond,
            "PART",
            secondId
        );
        evidence.check(
            "relation-first-entry-frozen",
            sameSemanticEntry(membershipEntry, secondHistory),
            "the first relation entry keeps its exact captured detail",
            "frozen=" + sameSemanticEntry(membershipEntry, secondHistory)
        );
        final NativeParent afterSecond = observeNativeParent(drawable);

        final HistoryMoveResult undone = onEdt(() -> context.cubism().history().undo(1));
        evidence.check(
            "relation-undo-moved",
            undone.outcome() == HistoryMoveResult.Outcome.MOVED,
            HistoryMoveResult.Outcome.MOVED.name(),
            undone.outcome().name()
        );
        // Native Undo is applied outside the calling thread, so the parent is awaited rather than
        // sampled once.
        final NativeParent afterUndo = awaitNativeParent(drawable, beforeSecond);
        // Diagnostic: distinguish a stale handle from a native Undo that did not revert the
        // relation. A fresh model access must agree with the cached handle.
        evidence.observation(
            "relation-undo-parent-reread",
            "the cached handle agrees with a fresh model access",
            "cached=" + afterUndo.text() + ";fresh=" + rereadParentViaFreshAccess(childId)
        );
        capturePaired(evidence, "relation-undo");
        evidence.check(
            "relation-undo-restores-first-parent",
            afterUndo.equals(beforeSecond),
            beforeSecond.text(),
            afterUndo.text()
        );

        final HistoryMoveResult redone = onEdt(() -> context.cubism().history().redo(1));
        evidence.check(
            "relation-redo-moved",
            redone.outcome() == HistoryMoveResult.Outcome.MOVED,
            HistoryMoveResult.Outcome.MOVED.name(),
            redone.outcome().name()
        );
        final NativeParent afterRedo = awaitNativeParent(drawable, afterSecond);
        evidence.observation(
            "relation-redo-parent-reread",
            "the cached handle agrees with a fresh model access",
            "cached=" + afterRedo.text() + ";fresh=" + rereadParentViaFreshAccess(childId)
        );
        capturePaired(evidence, "relation-redo");
        evidence.check(
            "relation-redo-reapplies-second-parent",
            afterRedo.equals(afterSecond),
            afterSecond.text(),
            afterRedo.text()
        );
        validateDeformerRootCapture(evidence, model, drawable, deformers);
        validateModelObjectServiceIngress(evidence, model, drawable, original);
        restoreOriginalParent(evidence, model, drawable, original);
    }

    /**
     * Validates the ROOT -> TARGET Deformer relation: attaching a Deformer to a child that has no
     * direct Deformer parent must be captured exactly, applied natively, and restored by Undo.
     */
    private void validateDeformerRootCapture(
        final Evidence evidence,
        final CubismModel model,
        final Drawable drawable,
        final List<Deformer> deformers
    ) throws Exception {
        final List<String> deformerIds = onEdt(() -> deformers.stream()
            .map(deformer -> deformer.id().value()).toList());
        evidence.check(
            "relation-deformer-root-target",
            !deformers.isEmpty(),
            "a Deformer exists that can become the direct parent",
            "deformers=" + deformerIds
        );
        if (deformers.isEmpty()) {
            return;
        }
        final NativeParent beforeWrite = observeNativeParent(drawable);
        evidence.check(
            "relation-deformer-root-start",
            beforeWrite.deformerId().isEmpty(),
            "the Artmesh starts without a direct Deformer parent",
            beforeWrite.text()
        );
        if (beforeWrite.deformerId().isPresent()) {
            return;
        }
        final String deformerId = deformerIds.get(0);
        // Classify through the exact SDK collections: an instance check on the evaluated object
        // does not identify the host relation type.
        final List<String> warpIds = onEdt(() -> model.warpDeformers().all().stream()
            .map(candidate -> candidate.id().value()).toList());
        final List<String> rotationIds = onEdt(() -> model.rotationDeformers().all().stream()
            .map(candidate -> candidate.id().value()).toList());
        final String expectedType = warpIds.contains(deformerId)
            ? "WARP_DEFORMER"
            : rotationIds.contains(deformerId) ? "ROTATION_DEFORMER" : "DEFORMER";
        final HistorySnapshot before = context.cubism().history().snapshot();
        onEdt(() -> {
            drawable.setTargetDeformer(Optional.of(new DeformerId(deformerId)));
            return null;
        });
        final HistorySnapshot after = awaitHistoryAdvance(before, 100).orElse(context.cubism().history().snapshot());
        final HistoryEntryDetail detail = currentEntry(after);
        final Optional<HistoryRelationChange> relation = detail == null
            ? Optional.empty()
            : detail.changes().stream()
                .map(HistoryChange::relation)
                .flatMap(Optional::stream)
                .findFirst();
        evidence.check(
            "relation-deformer-root-captured",
            relation.isPresent()
                && relation.orElseThrow().kind() == HistoryRelationChange.Kind.DEFORMER_PARENT,
            "one captured DEFORMER_PARENT relation",
            "deformer=" + deformerId + "," + describeCurrentEntry(after)
        );
        evidence.check(
            "relation-deformer-root-before",
            relation.isPresent()
                && relation.orElseThrow().before().state() == HistoryRelationChange.State.ROOT
                && relation.orElseThrow().before().target().isEmpty(),
            "before=ROOT with no target",
            relation.map(value -> describeEndpoint(value.before())).orElse("no relation")
        );
        evidence.check(
            "relation-deformer-root-after",
            relation.isPresent()
                && relation.orElseThrow().after().state() == HistoryRelationChange.State.TARGET
                && relation.orElseThrow().after().target().map(target ->
                    expectedType.equals(target.type())
                        && target.id().filter(deformerId::equals).isPresent()
                ).orElse(false),
            "after=" + expectedType + ":" + deformerId,
            relation.map(value -> describeEndpoint(value.after())).orElse("no relation")
        );
        evidence.check(
            "relation-deformer-root-detail",
            detail != null
                && detail.detailLevel() == HistoryAction.DetailLevel.FULL
                && detail.origin().kind() == HistoryOrigin.Kind.TURBOISM,
            "level=FULL,origin=TURBOISM",
            "level=" + (detail == null ? "none" : detail.detailLevel().name())
                + ",origin=" + (detail == null ? "none" : detail.origin().kind().name())
        );
        final NativeParent applied = observeNativeParent(drawable);
        evidence.check(
            "relation-deformer-root-native-applied",
            applied.deformerId().filter(deformerId::equals).isPresent(),
            beforeWrite.text() + " -> deformer=" + deformerId,
            applied.text()
        );

        final HistoryMoveResult undone = onEdt(() -> context.cubism().history().undo(1));
        final NativeParent restored = awaitNativeParent(drawable, beforeWrite);
        evidence.check(
            "relation-deformer-root-undo-restores",
            undone.outcome() == HistoryMoveResult.Outcome.MOVED && restored.equals(beforeWrite),
            beforeWrite.text(),
            undone.outcome().name() + "," + restored.text()
        );
        final HistoryMoveResult redone = onEdt(() -> context.cubism().history().redo(1));
        final NativeParent reapplied = awaitNativeParent(drawable, applied);
        evidence.check(
            "relation-deformer-root-redo-reapplies",
            redone.outcome() == HistoryMoveResult.Outcome.MOVED && reapplied.equals(applied),
            applied.text(),
            redone.outcome().name() + "," + reapplied.text()
        );
        if (deformerIds.size() < 2) {
            return;
        }
        // Second write with a direct Deformer parent already present: a target-to-target relation
        // whose state lives on the child, recorded as supporting evidence next to the Part rows.
        final String secondDeformer = deformerIds.get(1);
        final HistorySnapshot beforeSecond = context.cubism().history().snapshot();
        onEdt(() -> {
            drawable.setTargetDeformer(Optional.of(new DeformerId(secondDeformer)));
            return null;
        });
        final HistorySnapshot afterSecond =
            awaitHistoryAdvance(beforeSecond, 100).orElse(context.cubism().history().snapshot());
        evidence.observation(
            "relation-deformer-second-write",
            "target-to-target Deformer reparent is captured",
            "to=" + secondDeformer + "," + describeCurrentEntry(afterSecond)
                + ",native=" + observeNativeParent(drawable).text()
        );
        final HistoryMoveResult secondUndone = onEdt(() -> context.cubism().history().undo(1));
        Thread.sleep(1_500L);
        evidence.observation(
            "relation-deformer-undo-restores",
            "native Undo restores the previous Deformer parent",
            "outcome=" + secondUndone.outcome().name()
                + ",native=" + observeNativeParent(drawable).text()
        );
        final HistoryMoveResult secondRedone = onEdt(() -> context.cubism().history().redo(1));
        Thread.sleep(1_500L);
        evidence.observation(
            "relation-deformer-redo-reapplies",
            "native Redo reapplies the second Deformer parent",
            "outcome=" + secondRedone.outcome().name()
                + ",native=" + observeNativeParent(drawable).text()
        );
    }

    private HistoryEntryDetail currentEntry(final HistorySnapshot snapshot) {
        if (snapshot.position() <= 0 || snapshot.position() > snapshot.entries().size()) {
            return null;
        }
        return snapshot.entries().get(snapshot.position() - 1).detail();
    }

    private String describeCurrentEntry(final HistorySnapshot snapshot) {
        final HistoryEntryDetail detail = currentEntry(snapshot);
        if (detail == null) {
            return "no observable entry";
        }
        return "level=" + detail.detailLevel().name()
            + ",origin=" + detail.origin().kind().name()
            + ",changes=" + detail.changes().size()
            + ",relation=" + detail.changes().stream()
                .anyMatch(change -> change.relation().isPresent());
    }

    /**
     * Validates the ingress used by the MCP model-object tools.
     *
     * <p>{@code turboism_model_object_reparent} calls {@link dev.turboism.sdk.cubism.model.ModelObjectService#reparent},
     * so the shared service is the exact MCP ingress. A reparent through it must produce the same
     * captured relation row as the direct SDK call and must apply and restore natively.</p>
     */
    private void validateModelObjectServiceIngress(
        final Evidence evidence,
        final CubismModel model,
        final Drawable drawable,
        final NativeParent original
    ) throws Exception {
        evidence.event("phase", "relation-mcp-ingress");
        final String childId = onEdt(() -> drawable.id().value());
        final List<String> partIds = onEdt(() -> model.parts().all().stream()
            .map(part -> part.id().value()).toList());
        final NativeParent before = observeNativeParent(drawable);
        final Optional<String> target = partIds.stream()
            .filter(id -> !id.equals(before.partId().orElse(null)))
            .filter(id -> !INTERNAL_ROOT_PART.equals(id))
            .findFirst();
        evidence.check(
            "relation-mcp-ingress-target",
            target.isPresent(),
            "a Part exists that is not the current direct parent",
            "parts=" + partIds + ",currentPart=" + before.partId().orElse("ROOT")
        );
        if (target.isEmpty()) {
            return;
        }
        final String partId = target.orElseThrow();
        final HistorySnapshot baseline = context.cubism().history().snapshot();
        capturePaired(evidence, "relation-mcp-baseline");
        final AtomicReference<ModelObjectDescriptor> returned = new AtomicReference<>();
        final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        onEdt(() -> {
            try {
                returned.set(context.modelObjects().reparent(
                    new ModelObjectReference(ModelObjectKind.ART_MESH, childId),
                    new ModelObjectReference(ModelObjectKind.PART, partId),
                    -1
                ));
            } catch (RuntimeException exception) {
                failure.set(exception);
            }
            return null;
        });
        evidence.check(
            "relation-mcp-ingress-accepted",
            failure.get() == null && returned.get() != null,
            "the model-object service accepts the reparent",
            failure.get() == null
                ? "descriptor=" + describeDescriptor(returned.get())
                : "failed: " + failure.get().getClass().getSimpleName()
                    + ":" + failure.get().getMessage()
        );
        if (failure.get() != null || returned.get() == null) {
            return;
        }
        // The descriptor exposes one parent slot, and the service projects the Deformer parent
        // first when an ArtMesh has both. Verify the descriptor against the live readback instead
        // of encoding that projection as a requirement, and record the Part effect separately.
        final String liveParent = onEdt(() -> {
            final Optional<Drawable> current = model.drawables().all().stream()
                .filter(candidate -> candidate.id().value().equals(childId))
                .findFirst();
            return current
                .flatMap(candidate -> candidate.parentDeformerId()
                    .map(id -> "DEFORMER:" + id.value())
                    .or(() -> candidate.parentPartId().map(id -> "PART:" + id.value())))
                .orElse("none");
        });
        final String descriptorParent = returned.get().parent()
            .map(parent -> (parent.kind() == ModelObjectKind.ART_MESH ? "ART_MESH:" : parent.kind() + ":")
                + parent.id())
            .orElse("none");
        evidence.check(
            "relation-mcp-ingress-descriptor",
            returned.get().reference().kind() == ModelObjectKind.ART_MESH
                && returned.get().reference().id().equals(childId)
                && descriptorParentMatches(descriptorParent, liveParent),
            "descriptor matches the live parent readback for " + childId,
            describeDescriptor(returned.get()) + ",live=" + liveParent
        );
        evidence.observation(
            "relation-mcp-ingress-descriptor-part-slot",
            "documented projection: one parent slot, Deformer first",
            "requested part=" + partId + ",descriptor=" + describeDescriptor(returned.get())
                + "; the Part membership change is carried by the captured relation row"
        );

        final HistorySnapshot after = awaitHistoryAdvance(baseline, 100)
            .orElse(context.cubism().history().snapshot());
        final HistoryEntryDetail detail = currentEntry(after);
        final Optional<HistoryRelationChange> relation = detail == null
            ? Optional.empty()
            : detail.changes().stream()
                .map(HistoryChange::relation)
                .flatMap(Optional::stream)
                .findFirst();
        evidence.check(
            "relation-mcp-ingress-captured",
            relation.isPresent()
                && relation.orElseThrow().kind() == HistoryRelationChange.Kind.PART_MEMBERSHIP
                && detail.detailLevel() == HistoryAction.DetailLevel.FULL
                && detail.origin().kind() == HistoryOrigin.Kind.TURBOISM,
            "one captured PART_MEMBERSHIP relation at level=FULL,origin=TURBOISM",
            "child=" + childId + "," + describeCurrentEntry(after)
        );
        evidence.check(
            "relation-mcp-ingress-endpoints",
            relation.isPresent()
                && relation.orElseThrow().after().target().map(candidate ->
                    "PART".equals(candidate.type())
                        && candidate.id().filter(partId::equals).isPresent()
                ).orElse(false)
                && relation.orElseThrow().before().state() == HistoryRelationChange.State.TARGET,
            "before=" + (before.partId().map(id -> "PART:" + id).orElse("ROOT"))
                + ",after=PART:" + partId,
            relation.map(value -> describeEndpoint(value.before())
                + "->" + describeEndpoint(value.after())).orElse("no relation")
        );
        final NativeParent applied = awaitNativeParent(
            drawable,
            new NativeParent(Optional.of(partId), before.deformerId())
        );
        evidence.check(
            "relation-mcp-ingress-native-applied",
            applied.partId().filter(partId::equals).isPresent(),
            "part=" + partId,
            applied.text()
        );

        final HistoryMoveResult undone = onEdt(() -> context.cubism().history().undo(1));
        final NativeParent restored = awaitNativeParent(drawable, before);
        capturePaired(evidence, "relation-mcp-undo");
        evidence.check(
            "relation-mcp-ingress-undo-restores",
            undone.outcome() == HistoryMoveResult.Outcome.MOVED && restored.equals(before),
            before.text(),
            undone.outcome().name() + "," + restored.text()
        );
    }

    /** Compares the descriptor parent text with the live readback, ignoring the deformer prefix. */
    private static boolean descriptorParentMatches(final String descriptorParent, final String liveParent) {
        if (descriptorParent.equals(liveParent)) {
            return true;
        }
        return liveParent.startsWith("DEFORMER:")
            && descriptorParent.endsWith(":" + liveParent.substring("DEFORMER:".length()));
    }

    private static String describeDescriptor(final ModelObjectDescriptor descriptor) {
        return descriptor.reference().kind() + ":" + descriptor.reference().id()
            + ",parent=" + descriptor.parent().map(parent -> parent.kind() + ":" + parent.id())
                .orElse("none");
    }

    private void restoreOriginalParent(
        final Evidence evidence,
        final CubismModel model,
        final Drawable drawable,
        final NativeParent original
    ) throws Exception {
        if (original.partId().isPresent()) {
            final String partId = original.partId().orElseThrow();
            final Part part = onEdt(() -> model.parts().find(new PartId(partId)));
            onEdt(() -> { drawable.setParent(part, -1); return null; });
        }
        if (original.deformerId().isPresent()) {
            final String deformerId = original.deformerId().orElseThrow();
            onEdt(() -> {
                drawable.setTargetDeformer(Optional.of(new DeformerId(deformerId)));
                return null;
            });
        } else {
            onEdt(() -> { drawable.setTargetDeformer(Optional.empty()); return null; });
        }
        final NativeParent restored = observeNativeParent(drawable);
        evidence.check(
            "relation-fixture-parent-restored",
            restored.partId().equals(original.partId())
                && restored.deformerId().equals(original.deformerId()),
            original.text(),
            restored.text()
        );
        Thread.sleep(2_000L);
    }

    /** Records one relation check against the observed native direct parent before the write. */
    private void validateCapturedRelation(
        final Evidence evidence,
        final String checkName,
        final HistoryEntry entry,
        final String childId,
        final String expectedKind,
        final NativeParent before,
        final String expectedAfterType,
        final String expectedAfterId
    ) throws Exception {
        final HistoryEntryDetail detail = entry.detail();
        evidence.detail(checkName + "-semantic", detail);
        final Optional<HistoryChange> relationChange = detail.changes().stream()
            .filter(change -> change.relation().isPresent())
            .findFirst();
        final HistoryRelationChange relation = relationChange
            .flatMap(HistoryChange::relation)
            .orElse(null);
        final HistoryTarget afterTarget = relation == null
            ? null
            : relation.after().target().orElse(null);
        final boolean targetsMatch = detail.targets().size() == 1
            && detail.targets().get(0).type().equals("ART_MESH")
            && detail.targets().get(0).id().filter(childId::equals).isPresent();
        final boolean beforeMatches = relation != null && endpointMatches(relation.before(), before);
        final boolean afterMatches = relation != null
            && relation.after().state() == HistoryRelationChange.State.TARGET
            && afterTarget != null
            && afterTarget.type().equals(expectedAfterType)
            && afterTarget.id().filter(expectedAfterId::equals).isPresent()
            && afterTarget.displayName().isPresent();
        final boolean valid = entry.entryId().isPresent()
            && entry.action().isEmpty()
            && detail.detailLevel() == HistoryAction.DetailLevel.FULL
            && detail.origin().kind() == dev.turboism.sdk.cubism.history.HistoryOrigin.Kind.TURBOISM
            && targetsMatch
            && detail.changes().size() == 1
            && relation != null
            && relation.kind().name().equals(expectedKind)
            && relationChange.map(change -> change.operation() == HistoryChange.Operation.SET).orElse(false)
            && beforeMatches
            && afterMatches;
        evidence.check(
            checkName,
            valid,
            "entryId,noLegacyAction,FULL,TURBOISM,ART_MESH:" + childId + ",1 change,SET,kind="
                + expectedKind + ",before=" + before.text() + ",after=" + expectedAfterType
                + ":" + expectedAfterId + " with captured name",
            "entryId=" + entry.entryId().isPresent()
                + ",action=" + entry.action().isPresent()
                + ",level=" + detail.detailLevel().name()
                + ",origin=" + detail.origin().kind().name()
                + ",targets=" + detail.targets().size()
                + ",changes=" + detail.changes().size()
                + ",kind=" + (relation == null ? "" : relation.kind().name())
                + ",before=" + describeEndpoint(relation == null ? null : relation.before())
                + ",after=" + describeEndpoint(relation == null ? null : relation.after())
        );
    }

    /** Matches one captured endpoint against the native direct parent observed before the write. */
    static boolean endpointMatches(
        final HistoryRelationChange.Endpoint endpoint,
        final NativeParent observed
    ) {
        if (endpoint.state() == HistoryRelationChange.State.ROOT) {
            return observed.partId().isEmpty() && observed.deformerId().isEmpty();
        }
        if (endpoint.state() != HistoryRelationChange.State.TARGET) {
            return false;
        }
        final HistoryTarget target = endpoint.target().orElse(null);
        if (target == null || target.displayName().isEmpty()) {
            return false;
        }
        if (target.type().equals("PART")) {
            return observed.partId().filter(id -> target.id().filter(id::equals).isPresent()).isPresent();
        }
        return target.type().endsWith("DEFORMER")
            && observed.deformerId().filter(id -> target.id().filter(id::equals).isPresent()).isPresent();
    }

    static String describeEndpoint(final HistoryRelationChange.Endpoint endpoint) {
        if (endpoint == null) {
            return "";
        }
        if (endpoint.state() != HistoryRelationChange.State.TARGET) {
            return endpoint.state().name();
        }
        final HistoryTarget target = endpoint.target().orElse(null);
        return target == null ? "TARGET" : target.type() + ":" + target.id().orElse("");
    }

    /** Reads the ArtMesh Part parent through a freshly obtained model access. */
    private String rereadParentViaFreshAccess(final String childId) throws Exception {
        return onEdt(() -> {
            final CubismModel fresh = context.cubism().model().active();
            return fresh.drawables().all().stream()
                .filter(candidate -> candidate.id().value().equals(childId))
                .findFirst()
                .map(candidate -> candidate.parentPartId().map(PartId::value).orElse("ROOT"))
                .orElse("ABSENT");
        });
    }

    /** Waits for the native direct parent to reach the expected state after an Undo/Redo move. */
    private NativeParent awaitNativeParent(
        final Drawable drawable,
        final NativeParent expected
    ) throws Exception {
        NativeParent observed = observeNativeParent(drawable);
        for (int attempt = 0; attempt < 50 && !observed.equals(expected)
            && !Thread.currentThread().isInterrupted(); attempt++) {
            Thread.sleep(100L);
            observed = observeNativeParent(drawable);
        }
        return observed;
    }

    private NativeParent observeNativeParent(final Drawable drawable) throws Exception {
        return onEdt(() -> new NativeParent(
            drawable.parentPartId().map(PartId::value),
            drawable.parentDeformerId().map(DeformerId::value)
        ));
    }

    static String deformerType(final Deformer deformer) {
        if (deformer instanceof dev.turboism.sdk.cubism.model.WarpDeformer) {
            return "WARP_DEFORMER";
        }
        if (deformer instanceof dev.turboism.sdk.cubism.model.RotationDeformer) {
            return "ROTATION_DEFORMER";
        }
        return "DEFORMER";
    }

    static boolean sameSemanticEntry(final HistoryEntry expected, final HistorySnapshot actual) {
        return expected.entryId().isPresent() && actual.entries().stream()
            .filter(entry -> entry.entryId().equals(expected.entryId()))
            .anyMatch(entry -> entry.detail().equals(expected.detail()));
    }

    private static Optional<HistoryEntryDetail> semanticDetail(
        final HistoryEntryDetail detail,
        final String property
    ) {
        if (detail.changes().stream()
            .anyMatch(change -> change.property().filter(property::equals).isPresent())) {
            return Optional.of(detail);
        }
        return detail.group().stream()
            .flatMap(group -> group.children().stream())
            .map(child -> semanticDetail(child, property))
            .flatMap(Optional::stream)
            .findFirst();
    }

    private Drawable awaitDrawable() throws Exception {
        return awaitDrawable(onEdt(() -> context.cubism().model().active()));
    }

    private Drawable awaitDrawable(final CubismModel model) throws Exception {
        Exception unavailable = null;
        for (int attempt = 0; attempt < 120 && !Thread.currentThread().isInterrupted(); attempt++) {
            try {
                return onEdt(() -> model.drawables().all().stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No writable Artmesh is available")));
            } catch (Exception exception) {
                unavailable = exception;
                Thread.sleep(500L);
            }
        }
        throw unavailable == null ? new IllegalStateException("Artmesh validation was interrupted") : unavailable;
    }


    private Optional<HistorySnapshot> awaitHistoryAdvance(
        final HistorySnapshot baseline,
        final int attempts
    ) throws Exception {
        HistorySnapshot actual = baseline;
        for (int attempt = 0; attempt < attempts; attempt++) {
            actual = context.cubism().history().snapshot();
            if (actual.availability() == HistorySnapshot.Availability.AVAILABLE
                && actual.position() > baseline.position()
                && actual.position() <= actual.entries().size()
                && actual.revision() != baseline.revision()) {
                return Optional.of(actual);
            }
            Thread.sleep(100L);
        }
        return Optional.empty();
    }

    static Color artMeshProbeColorA() {
        return new Color(34.0F / 255.0F, 68.0F / 255.0F, 102.0F / 255.0F, 1.0F);
    }

    static Color artMeshProbeColorB() {
        return new Color(102.0F / 255.0F, 136.0F / 255.0F, 170.0F / 255.0F, 1.0F);
    }

    static String rgb(final Color color) {
        return String.format(
            java.util.Locale.ROOT,
            "#%02x%02x%02x",
            colorChannel(color.red()),
            colorChannel(color.green()),
            colorChannel(color.blue())
        );
    }

    private static int colorChannel(final float value) {
        if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
            throw new IllegalStateException("Artmesh color channel is outside [0,1]");
        }
        return Math.round(value * 255.0F);
    }


    private Parameter awaitParameter() throws Exception {
        Exception unavailable = null;
        for (int attempt = 0; attempt < 120 && !Thread.currentThread().isInterrupted(); attempt++) {
            try {
                return onEdt(() -> context.cubism().model().active().parameters().all().stream()
                    .filter(parameter -> parameter.getMaximumValue() > parameter.getMinimumValue())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No writable Parameter is available")));
            } catch (Exception exception) {
                unavailable = exception;
                Thread.sleep(500L);
            }
        }
        throw unavailable == null ? new IllegalStateException("History seed was interrupted") : unavailable;
    }

    private void writeValue(
        final Parameter parameter,
        final Evidence evidence,
        final String phase,
        final String pairedPhase,
        final String id,
        final float value
    ) throws Exception {
        onEdt(() -> { parameter.setValue(value); return null; });
        final float actual = awaitValue(parameter, value);
        evidence.check(phase, same(value, actual), "parameter=" + id + ",value=" + value, "parameter=" + id + ",value=" + actual);
        Thread.sleep(750L);
        capturePaired(evidence, pairedPhase);
    }

    static float valueAt(final float minimum, final float maximum, final float fraction) {
        final float value = minimum + (maximum - minimum) * fraction;
        if (!Float.isFinite(value)) throw new IllegalStateException("Parameter range is not finite");
        return value;
    }

    static float alternate(final float value, final float minimum, final float maximum) {
        final float candidate = minimum + (maximum - minimum) * 0.37F;
        if (!Float.isFinite(candidate)) throw new IllegalStateException("Parameter range is not finite");
        if (!same(candidate, value)) return candidate;
        final float fallback = minimum + (maximum - minimum) * 0.63F;
        if (!Float.isFinite(fallback) || same(fallback, value)) {
            throw new IllegalStateException("No distinct Parameter value is available");
        }
        return fallback;
    }

    private static float awaitValue(final Parameter parameter, final float expected) throws Exception {
        float actual = Float.NaN;
        for (int attempt = 0; attempt < 50; attempt++) {
            actual = onEdt(parameter::getValue);
            if (same(actual, expected)) return actual;
            Thread.sleep(100L);
        }
        throw new IllegalStateException("Timed out waiting for Parameter value " + expected + "; actual=" + actual);
    }

    private static <T> T onEdt(final Callable<T> call) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(call.call());
            } catch (Exception exception) {
                failure.set(exception);
            }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private static boolean same(final float left, final float right) {
        return Float.compare(left, right) == 0;
    }

    static String json(final String value) {
        if (value == null) return "";
        final StringBuilder escaped = new StringBuilder();
        value.codePoints().limit(512).forEach(codePoint -> {
            switch (codePoint) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (codePoint < 0x20) escaped.append(String.format("\\u%04x", codePoint));
                    else escaped.appendCodePoint(codePoint);
                }
            }
        });
        return escaped.toString();
    }

    private static void append(final Path artifact, final String value) throws Exception {
        append(artifact, value, false);
    }

    private static void append(final Path artifact, final String value, final boolean terminal) throws Exception {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final long existing = Files.exists(artifact) ? Files.size(artifact) : 0L;
        final long reserve = terminal ? 0L : TERMINAL_RESERVE_BYTES;
        if (bytes.length > MAX_EVIDENCE_BYTES
            || existing > MAX_EVIDENCE_BYTES - reserve
            || existing + bytes.length > MAX_EVIDENCE_BYTES - reserve) {
            throw new IllegalStateException("History seed evidence budget exhausted");
        }
        Files.writeString(
            artifact,
            value,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }

    static final class Evidence {
        private final Path artifact;
        private boolean passed = true;
        private final Map<String, WindowsHistoryManagerValidationProbe.Snapshot> pairedSamples = new LinkedHashMap<>();

        Evidence(final Path artifact) {
            this.artifact = artifact;
        }

        void event(final String name, final String value) throws Exception {
            append(artifact,
                "{\"type\":\"event\",\"name\":\"" + json(name)
                    + "\",\"value\":\"" + json(value) + "\"}\n");
        }

        void check(
            final String name,
            final boolean status,
            final String expected,
            final String actual
        ) {
            passed &= status;
            try {
                append(artifact,
                    "{\"type\":\"check\",\"name\":\"" + json(name)
                        + "\",\"status\":\"" + (status ? "PASS" : "FAIL")
                        + "\",\"expected\":\"" + json(expected)
                        + "\",\"actual\":\"" + json(actual) + "\"}\n");
            } catch (Exception exception) {
                throw new IllegalStateException("Could not write evidence check", exception);
            }
        }

        /**
         * Records a non-gating observation. Observations document a known and reviewed host
         * behaviour without turning it into a required assertion.
         */
        void observation(final String name, final String expected, final String actual) {
            try {
                append(artifact,
                    "{\"type\":\"observation\",\"name\":\"" + json(name)
                        + "\",\"expected\":\"" + json(expected)
                        + "\",\"actual\":\"" + json(actual) + "\"}\n");
            } catch (Exception exception) {
                throw new IllegalStateException("Could not write evidence observation", exception);
            }
        }

        void pairedSample(
            final String phase,
            final WindowsHistoryManagerValidationProbe.Snapshot snapshot
        ) {
            final List<String> errors = new ArrayList<>();
            final boolean acceptedPhase = phase != null
                && !phase.isBlank()
                && !pairedSamples.containsKey(phase)
                && pairedSamples.size() < MAX_PAIRED_SAMPLES;
            if (phase == null || phase.isBlank()) errors.add("paired-phase-missing");
            if (pairedSamples.containsKey(phase)) errors.add("paired-phase-duplicate");
            if (pairedSamples.size() >= MAX_PAIRED_SAMPLES && !pairedSamples.containsKey(phase)) {
                errors.add("paired-sample-limit");
            }
            if (snapshot == null) {
                errors.add("paired-snapshot-missing");
            } else {
                errors.addAll(validateSnapshot(snapshot));
                final WindowsHistoryManagerValidationProbe.Snapshot previous = lastSample();
                if (previous != null) errors.addAll(validateContinuity(previous, snapshot));
            }
            if (acceptedPhase && snapshot != null) pairedSamples.put(phase, snapshot);
            final boolean status = errors.isEmpty();
            passed &= status;
            try {
                if (snapshot != null) append(artifact, snapshot.pairedJson(phase) + "\n");
                append(artifact, pairedValidationJson(phase, status, errors));
            } catch (Exception exception) {
                throw new IllegalStateException("Could not write paired history evidence", exception);
            }
        }

        void pairedFailure(final String phase, final Exception exception) {
            passed = false;
            try {
                append(artifact,
                    "{\"type\":\"paired-snapshot-failure\",\"phase\":\"" + json(phase)
                        + "\",\"errorType\":\"" + json(exception.getClass().getName())
                        + "\",\"message\":\"" + json(exception.getMessage()) + "\"}\n");
            } catch (Exception writeFailure) {
                throw new IllegalStateException("Could not write paired history failure", writeFailure);
            }
        }

        private WindowsHistoryManagerValidationProbe.Snapshot lastSample() {
            WindowsHistoryManagerValidationProbe.Snapshot last = null;
            for (WindowsHistoryManagerValidationProbe.Snapshot sample : pairedSamples.values()) {
                last = sample;
            }
            return last;
        }

        private static List<String> validateSnapshot(
            final WindowsHistoryManagerValidationProbe.Snapshot snapshot
        ) {
            final List<String> errors = new ArrayList<>();
            if (!snapshot.edt()) errors.add("paired-snapshot-not-edt");
            if (absent(snapshot.documentIdentity())) errors.add("native-document-identity-missing");
            if (absent(snapshot.currentModeIdentity())) errors.add("native-mode-identity-missing");
            if (absent(snapshot.hostLoader())) errors.add("native-classloader-identity-missing");

            final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot sdk = snapshot.sdkHistory();
            if (sdk == null) {
                errors.add("sdk-history-missing");
            } else {
                if (!"AVAILABLE".equals(sdk.availability())) errors.add("sdk-history-unavailable");
                if (sdk.totalEntries() < 0) errors.add("sdk-sequence-total-invalid");
                if (absent(sdk.documentBindingId()) || absent(sdk.managerBindingId())) {
                    errors.add("sdk-binding-identity-missing");
                }
                if (sdk.entries() == null) {
                    errors.add("sdk-sequence-missing");
                } else {
                    if (sdk.entries().size() > WindowsHistoryManagerValidationProbe.MAX_ENTRIES) {
                        errors.add("sdk-sequence-over-bound");
                    }
                    if (sdk.totalEntries() > sdk.entries().size()) errors.add("sdk-sequence-truncated");
                    if (sdk.position() < 0 || sdk.position() > sdk.entries().size()) {
                        errors.add("sdk-position-invalid");
                    }
                    for (int index = 0; index < sdk.entries().size(); index++) {
                        final WindowsHistoryManagerValidationProbe.SdkEntry entry = sdk.entries().get(index);
                        if (entry == null || entry.index() != index) errors.add("sdk-sequence-index-mismatch");
                        if (entry == null || entry.entryId() == null || entry.entryId().isBlank()) {
                            errors.add("sdk-entry-identity-missing");
                        }
                        if (entry != null && (entry.detailJson() == null || entry.detailJson().isBlank())) {
                            errors.add("sdk-detail-missing");
                        }
                    }
                }
            }

            final WindowsHistoryManagerValidationProbe.ManagerSnapshot document = snapshot.document();
            validateNativeManager(document, errors, "DOCUMENT", true);
            validateNativeManager(snapshot.current(), errors, "CURRENT", false);
            validateNativeManager(snapshot.main(), errors, "MAIN", false);
            validateNativeManager(snapshot.linked(), errors, "LINKED", false);
            if (sdk != null && document != null
                && (document.position() != sdk.position()
                    || document.totalEntries() != sdk.totalEntries())) {
                errors.add("native-sdk-sequence-mismatch");
            }
            if (sdk != null && document != null && sdk.entries() != null && document.entries() != null) {
                final int overlap = Math.min(sdk.entries().size(), document.entries().size());
                for (int index = 0; index < overlap; index++) {
                    final WindowsHistoryManagerValidationProbe.SdkEntry sdkEntry = sdk.entries().get(index);
                    final WindowsHistoryManagerValidationProbe.Entry nativeEntry = document.entries().get(index);
                    if (sdkEntry == null || nativeEntry == null || !same(sdkEntry.label(), nativeEntry.label())) {
                        errors.add("native-sdk-entry-sequence-mismatch");
                    }
                }
            }
            return errors;
        }

        private static void validateNativeManager(
            final WindowsHistoryManagerValidationProbe.ManagerSnapshot manager,
            final List<String> errors,
            final String expectedName,
            final boolean required
        ) {
            if (manager == null) {
                errors.add(expectedName + (required
                    ? "-native-manager-missing"
                    : "-native-manager-snapshot-missing"));
                return;
            }
            if (!expectedName.equals(manager.name())) {
                errors.add(expectedName + "-native-manager-name-mismatch");
            }
            if (!required && isAbsentOptionalManager(manager, expectedName)) return;
            if (absent(manager.identity())) errors.add(expectedName + "-native-manager-identity-missing");
            if (manager.entries() == null) {
                errors.add(expectedName + "-native-sequence-missing");
                return;
            }
            if (manager.totalEntries() < 0) errors.add(expectedName + "-native-total-invalid");
            if (manager.position() < 0 || manager.position() > manager.entries().size()) {
                errors.add(expectedName + "-native-position-invalid");
            }
            if (manager.totalEntries() > manager.entries().size()) {
                errors.add(expectedName + "-native-sequence-truncated");
            }
            for (int index = 0; index < manager.entries().size(); index++) {
                final WindowsHistoryManagerValidationProbe.Entry entry = manager.entries().get(index);
                if (entry == null || entry.index() != index) {
                    errors.add(expectedName + "-native-sequence-index-mismatch");
                }
                if (entry == null || entry.detail() == null) {
                    errors.add(expectedName + "-native-detail-missing");
                } else {
                    validateNativeDetail(entry.detail(), expectedName, errors);
                }
            }
        }

        private static boolean isAbsentOptionalManager(
            final WindowsHistoryManagerValidationProbe.ManagerSnapshot manager,
            final String expectedName
        ) {
            return ("CURRENT".equals(expectedName) || "MAIN".equals(expectedName) || "LINKED".equals(expectedName))
                && expectedName.equals(manager.name())
                && "null".equals(manager.identity())
                && manager.position() == -1
                && !manager.canUndo()
                && !manager.canRedo()
                && manager.totalEntries() == 0
                && manager.entries() != null
                && manager.entries().isEmpty();
        }

        private static void validateNativeDetail(
            final WindowsHistoryManagerValidationProbe.NativeDetail detail,
            final String managerName,
            final List<String> errors
        ) {
            if (detail == null) {
                errors.add(managerName + "-native-detail-missing");
                return;
            }
            if (detail.truncated()) errors.add(managerName + "-native-detail-truncated");
            if ("history.detail.decoder-failed".equals(detail.degradationCode())) {
                errors.add(managerName + "-native-detail-failed");
            }
            if (detail.childDetails() == null) {
                errors.add(managerName + "-native-child-details-missing");
                return;
            }
            for (WindowsHistoryManagerValidationProbe.NativeDetail child : detail.childDetails()) {
                if (child != null) validateNativeDetail(child, managerName, errors);
                else errors.add(managerName + "-native-child-detail-missing");
            }
        }

        private static List<String> validateContinuity(
            final WindowsHistoryManagerValidationProbe.Snapshot previous,
            final WindowsHistoryManagerValidationProbe.Snapshot current
        ) {
            final List<String> errors = new ArrayList<>();
            if (!same(previous.hostLoader(), current.hostLoader())) errors.add("host-classloader-identity-mismatch");
            if (!same(previous.documentIdentity(), current.documentIdentity())) {
                errors.add("native-document-identity-mismatch");
            }
            if (!same(previous.currentModeClass(), current.currentModeClass())
                || !same(previous.currentModeIdentity(), current.currentModeIdentity())) {
                errors.add("native-mode-identity-mismatch");
            }
            if (previous.document() != null && current.document() != null
                && !same(previous.document().identity(), current.document().identity())) {
                errors.add("native-manager-identity-mismatch");
            }
            final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot left = previous.sdkHistory();
            final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot right = current.sdkHistory();
            if (left == null || right == null) {
                errors.add("sdk-history-identity-missing");
                return errors;
            }
            if (left.generation() != right.generation()) errors.add("sdk-generation-mismatch");
            if (!same(left.documentBindingId(), right.documentBindingId())
                || !same(left.managerBindingId(), right.managerBindingId())) {
                errors.add("sdk-binding-identity-mismatch");
            }
            if (left.entries() == null || right.entries() == null) {
                errors.add("sdk-sequence-missing");
                return errors;
            }
            if (right.totalEntries() < left.totalEntries()) errors.add("sdk-sequence-shortened");
            final int overlap = Math.min(left.entries().size(), right.entries().size());
            for (int index = 0; index < overlap; index++) {
                final WindowsHistoryManagerValidationProbe.SdkEntry previousEntry = left.entries().get(index);
                final WindowsHistoryManagerValidationProbe.SdkEntry currentEntry = right.entries().get(index);
                final String previousId = previousEntry == null ? null : previousEntry.entryId();
                final String currentId = currentEntry == null ? null : currentEntry.entryId();
                if (previousId == null || previousId.isBlank() || currentId == null || currentId.isBlank()
                    || !previousId.equals(currentId)) {
                    errors.add("sdk-sequence-identity-mismatch");
                }
            }
            return errors;
        }

        private static boolean absent(final String value) {
            return value == null || value.isBlank() || "null".equals(value);
        }

        private static boolean same(final String left, final String right) {
            return left == null ? right == null : left.equals(right);
        }

        private static String pairedValidationJson(
            final String phase,
            final boolean status,
            final List<String> errors
        ) {
            final StringBuilder result = new StringBuilder("{\"type\":\"paired-validation\",\"phase\":\"")
                .append(json(phase))
                .append("\",\"status\":\"")
                .append(status ? "PASS" : "FAIL")
                .append("\",\"nativePairing\":\"ordinal-label-supporting-only\"")
                .append(",\"nativeStableIdMatch\":false,\"errors\":[");
            for (int index = 0; index < errors.size(); index++) {
                if (index > 0) result.append(',');
                result.append('\"').append(json(errors.get(index))).append('\"');
            }
            return result.append("]}\n").toString();
        }

        void entry(final String name, final HistoryEntry entry) {
            detail(name, entry.detail());
        }

        void detail(final String name, final dev.turboism.sdk.cubism.history.HistoryEntryDetail detail) {
            final StringBuilder targets = new StringBuilder("[");
            for (int index = 0; index < detail.targets().size(); index++) {
                if (index > 0) targets.append(',');
                final var target = detail.targets().get(index);
                targets.append("{\"type\":\"").append(json(target.type()))
                    .append("\",\"id\":\"").append(json(target.id().orElse("")))
                    .append("\",\"displayName\":\"").append(json(target.displayName().orElse("")))
                    .append("\"}");
            }
            targets.append(']');
            final StringBuilder changes = new StringBuilder("[");
            for (int index = 0; index < detail.changes().size(); index++) {
                if (index > 0) changes.append(',');
                final HistoryChange change = detail.changes().get(index);
                changes.append("{\"operation\":\"").append(change.operation().name())
                    .append("\",\"property\":\"").append(json(change.property().orElse("")))
                    .append("\",\"before\":\"").append(json(change.before().orElse("")))
                    .append("\",\"after\":\"").append(json(change.after().orElse("")))
                    .append("\",\"context\":{\"kind\":\"").append(change.context().kind().name())
                    .append("\",\"formId\":\"").append(json(change.context().formId().orElse("")))
                    .append("\",\"coordinates\":[");
                for (int coordinateIndex = 0;
                     coordinateIndex < change.context().coordinates().size();
                     coordinateIndex++) {
                    if (coordinateIndex > 0) changes.append(',');
                    final var coordinate = change.context().coordinates().get(coordinateIndex);
                    changes.append("{\"parameterType\":\"")
                        .append(json(coordinate.parameter().type()))
                        .append("\",\"parameterId\":\"")
                        .append(json(coordinate.parameter().id().orElse("")))
                        .append("\",\"parameterName\":\"")
                        .append(json(coordinate.parameter().displayName().orElse("")))
                        .append("\",\"value\":\"").append(json(coordinate.value())).append("\"}");
                }
                changes.append("]}}");
            }
            changes.append(']');
            try {
                append(artifact,
                    "{\"type\":\"semantic-detail\",\"name\":\"" + json(name)
                        + "\",\"level\":\"" + detail.detailLevel().name()
                        + "\",\"origin\":\"" + detail.origin().kind().name()
                        + "\",\"degradationCode\":\"" + json(detail.degradationCode().orElse(""))
                        + "\",\"targets\":" + targets + ",\"changes\":" + changes + "}\n");
            } catch (Exception exception) {
                throw new IllegalStateException("Could not write semantic detail evidence", exception);
            }
        }

        void nestedDetails(final String prefix, final HistoryEntryDetail detail) {
            detail.group().ifPresent(group -> {
                for (int index = 0; index < group.children().size(); index++) {
                    final HistoryEntryDetail child = group.children().get(index);
                    final String childName = prefix + "-" + index;
                    detail(childName, child);
                    nestedDetails(childName, child);
                }
            });
        }

        void summary() throws Exception {
            final List<String> missing = REQUIRED_PAIRED_PHASES.stream()
                .filter(phase -> !pairedSamples.containsKey(phase))
                .toList();
            if (!missing.isEmpty()) {
                passed = false;
                append(artifact, pairedValidationJson("summary", false, missing));
            }
            append(
                artifact,
                passed
                    ? "{\"type\":\"summary\",\"status\":\"PASS\"}\n"
                    : "{\"type\":\"summary\",\"status\":\"FAIL\"}\n",
                true
            );
        }
    }
}
