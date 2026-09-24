package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.EditorObjectReadCore.*;
import static dev.turboism.adapter.cubism.editor.EditorObjectReadCore.*;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorObjectWriteSelectorContract;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.id.ArtMeshId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Exact-version clip-mask batch replacement for ArtMesh editor objects. */
final class EditorObjectClipMaskAccess {

    private final VerifiedMemberResolver resolver;
    private final EditorObjectReadAccess.CurrentGuard currentGuard;
    private final EditorObjectReadCore core;
    private final EditorObjectWriteAccess writes;

    EditorObjectClipMaskAccess(
        final VerifiedMemberResolver resolver,
        final EditorObjectReadAccess.CurrentGuard currentGuard,
        final EditorObjectReadCore core,
        final EditorObjectWriteAccess writes
    ) {
        this.resolver = resolver;
        this.currentGuard = currentGuard;
        this.core = core;
        this.writes = writes;
    }

    private void requireClipMaskWriteAuthorized() {
        if (!resolver.authorizesFeature(
            EditorObjectWriteSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectWriteSelectorContract.CLIP_MASK_CAPABILITY_ID,
            EditorObjectWriteSelectorContract.CLIP_MASK_REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Editor clip-mask writes require exact verified host evidence."
            );
        }
    }

    void replaceArtMeshClipMasks(
        final String identity,
        final Object modelSource,
        final Object model,
        final List<ClipMaskReplacement> replacements
    ) {
        final List<ClipMaskReplacement> batch = List.copyOf(
            Objects.requireNonNull(replacements, "replacements")
        );
        if (batch.isEmpty()) {
            throw new IllegalArgumentException("replacements must not be empty");
        }
        currentGuard.requireCurrent(identity, model);
        requireClipMaskWriteAuthorized();

        final List<ObjectRef> meshes = core.artMeshes(identity, modelSource, model);
        final java.util.Map<String, ObjectRef> byId = new java.util.HashMap<>();
        for (ObjectRef mesh : meshes) {
            byId.put(mesh.id(), mesh);
        }

        final java.util.HashSet<String> targetIds = new java.util.HashSet<>();
        final ArrayList<ClipMaskPlan> plans = new ArrayList<>(batch.size());
        boolean changed = false;
        for (ClipMaskReplacement replacement : batch) {
            final String targetId = replacement.targetArtMeshId().value();
            if (!targetIds.add(targetId)) {
                throw new IllegalArgumentException("replacement targets must be unique");
            }
            final ObjectRef target = byId.get(targetId);
            if (target == null) {
                throw unavailable("Clip-mask target ArtMesh is outside the active model.");
            }
            final List<ArtMeshId> actualMasks = core.maskIds(identity, modelSource, model, target.source());
            final boolean actualInverted = core.sourceFlag(
                "cubism.editor-model.art-mesh-source.inverted-mask",
                target.source(),
                "ArtMesh inverted-mask state"
            );
            if (!actualMasks.equals(replacement.expectedMaskArtMeshIds())
                || actualInverted != replacement.expectedInverted()) {
                throw new IllegalStateException(
                    "Clip-mask expected state does not match ArtMesh " + targetId
                );
            }

            final ArrayList<Object> replacementGuids = new ArrayList<>(
                replacement.replacementMaskArtMeshIds().size()
            );
            for (ArtMeshId maskId : replacement.replacementMaskArtMeshIds()) {
                final ObjectRef mask = byId.get(maskId.value());
                if (mask == null) {
                    throw unavailable("Clip-mask source ArtMesh is outside the active model.");
                }
                replacementGuids.add(resolver.invoke(
                    "cubism.editor-model.art-mesh-source.guid",
                    mask.source()
                ));
            }
            final Object replacementClipGuidList = newClipGuidList(replacementGuids);
            final List<?> originalGuids = iterable(
                resolver.invoke(
                    "cubism.editor-model.art-mesh-source.clip-guid-list",
                    target.source()
                ),
                "Editor ArtMesh clipping masks"
            );
            final Object originalClipGuidList = newClipGuidList(originalGuids);
            changed |= !actualMasks.equals(replacement.replacementMaskArtMeshIds())
                || actualInverted != replacement.replacementInverted();
            plans.add(new ClipMaskPlan(
                target,
                actualMasks,
                actualInverted,
                replacement.replacementInverted(),
                originalClipGuidList,
                replacementClipGuidList
            ));
        }

        if (!changed) return;

        final ArrayList<Object> undoSources = new ArrayList<>(plans.size());
        for (ClipMaskPlan plan : plans) {
            undoSources.add(plan.target().source());
        }
        // Exact 5.2 and 5.3.02 evidence: handler Undo snapshots are target-scoped, so the
        // batch admits one snapshot per planned target in plan order inside the single edit
        // session; the host groups those snapshots into one Undo step.
        writes.writeClipMaskBatch(modelSource, undoSources, "Replace ArtMesh clip masks", () -> {
            final ArrayList<ClipMaskPlan> applied = new ArrayList<>(plans.size());
            try {
                for (ClipMaskPlan plan : plans) {
                    applied.add(plan);
                    resolver.invoke(
                        "cubism.editor-model.art-mesh-source.set-clip-guid-list",
                        plan.target().source(),
                        plan.replacementClipGuidList()
                    );
                    resolver.invoke(
                        "cubism.editor-model.art-mesh-source.set-inverted-mask",
                        plan.target().source(),
                        Boolean.valueOf(plan.replacementInverted())
                    );
                }
            } catch (RuntimeException failure) {
                try {
                    restoreClipMaskBatch(identity, modelSource, model, applied);
                } catch (RuntimeException rollbackFailure) {
                    final IllegalStateException combined = new IllegalStateException(
                        "Clip-mask batch mutation failed and rollback did not complete.",
                        failure
                    );
                    combined.addSuppressed(rollbackFailure);
                    throw combined;
                }
                throw failure;
            }
        });
    }

    private Object newClipGuidList(final List<?> values) {
        final Object result = resolver.construct(
            "cubism.editor-model.c-array-list.create",
            values
        );
        if (!resolver.isInstance("cubism.editor-model.c-array-list.class", result)) {
            throw unavailable("Editor clip-mask list type is invalid.");
        }
        return result;
    }

    private void restoreClipMaskBatch(
        final String identity,
        final Object modelSource,
        final Object model,
        final List<ClipMaskPlan> applied
    ) {
        RuntimeException failure = null;
        for (int index = applied.size() - 1; index >= 0; index--) {
            final ClipMaskPlan plan = applied.get(index);
            try {
                resolver.invoke(
                    "cubism.editor-model.art-mesh-source.set-clip-guid-list",
                    plan.target().source(),
                    plan.originalClipGuidList()
                );
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
            try {
                resolver.invoke(
                    "cubism.editor-model.art-mesh-source.set-inverted-mask",
                    plan.target().source(),
                    Boolean.valueOf(plan.originalInverted())
                );
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
        }
        for (ClipMaskPlan plan : applied) {
            try {
                if (!plan.originalMaskIds().equals(
                        core.maskIds(identity, modelSource, model, plan.target().source()))
                    || core.sourceFlag(
                        "cubism.editor-model.art-mesh-source.inverted-mask",
                        plan.target().source(),
                        "ArtMesh inverted-mask state"
                    ) != plan.originalInverted()) {
                    failure = appendFailure(
                        failure,
                        new IllegalStateException("Clip-mask rollback verification failed.")
                    );
                }
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
        }
        if (failure != null) throw failure;
    }

    private static RuntimeException appendFailure(
        final RuntimeException current,
        final RuntimeException next
    ) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    private record ClipMaskPlan(
        ObjectRef target,
        List<ArtMeshId> originalMaskIds,
        boolean originalInverted,
        boolean replacementInverted,
        Object originalClipGuidList,
        Object replacementClipGuidList
    ) {
    }
}
