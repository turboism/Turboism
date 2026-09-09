package dev.turboism.adapter.cubism.editor.transaction;

import dev.turboism.adapter.cubism.editor.history.EditorHistoryMetadataRegistry;
import dev.turboism.adapter.cubism.editor.history.EditorHistorySnapshotProvider;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistorySnapshot;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Verified Editor-model implementation of the authoring coordinator host boundary.
 *
 * <p>The active native document/source/model are resolved through a generation-bound supplier on
 * every admission or refresh. The native edit object is retained only inside a private token so an
 * abort can still close the exact edit mode even if the active document changes.</p>
 */
public final class VerifiedEditorAuthoringTransactionHost
    implements EditorAuthoringTransactionCoordinator.Host {

    private final VerifiedMemberResolver resolver;
    private final Supplier<NativeBinding> current;
    private final EditorHistorySnapshotProvider history;
    private final Object editLock = new Object();
    private final Map<Object, Object> editModes = new IdentityHashMap<>();

    /**
     * Creates a host over one reviewed Editor-model resolver.
     *
     * @param resolver verified member resolver for the active connection
     * @param current generation-bound native binding supplier
     * @param generation current model binding generation supplier
     */
    public VerifiedEditorAuthoringTransactionHost(
        final VerifiedMemberResolver resolver,
        final Supplier<NativeBinding> current,
        final LongSupplier generation
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.current = Objects.requireNonNull(current, "current");
        this.history = new EditorHistorySnapshotProvider(
            () -> Optional.of(this.resolver),
            Objects.requireNonNull(generation, "generation")
        );
    }

    /**
     * Resolves the current native binding for one owning plugin.
     *
     * @param pluginId owning plugin identity
     * @return current authoring binding, or empty when no stable native model is available
     */
    public Optional<EditorAuthoringTransactionCoordinator.Binding> binding(
        final String pluginId
    ) {
        final String owner = Objects.requireNonNull(pluginId, "pluginId").strip();
        if (owner.isEmpty()) throw new IllegalArgumentException("pluginId must not be blank");
        try {
            final NativeBinding binding = Objects.requireNonNull(current.get(), "current binding");
            return Optional.of(new EditorAuthoringTransactionCoordinator.Binding(
                owner,
                binding.identity(),
                binding.generation(),
                binding.identity(),
                binding.generation(),
                Thread.currentThread()
            ));
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isCurrent(final EditorAuthoringTransactionCoordinator.Binding expected) {
        Objects.requireNonNull(expected, "expected");
        try {
            final NativeBinding active = Objects.requireNonNull(current.get(), "current binding");
            return expected.documentGeneration() == active.generation()
                && expected.modelGeneration() == active.generation()
                && expected.documentIdentity().equals(active.identity())
                && expected.modelIdentity().equals(active.identity());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public HistorySnapshot history(final EditorAuthoringTransactionCoordinator.Binding binding) {
        if (!isCurrent(binding)) return HistorySnapshot.unavailable();
        final HistorySnapshot snapshot = history.snapshot();
        return snapshot.availability() == HistorySnapshot.Availability.AVAILABLE
            && snapshot.generation() == binding.documentGeneration()
            ? snapshot
            : HistorySnapshot.unavailable();
    }

    @Override
    public Object beginEdit(
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final String label
    ) {
        final NativeBinding active = currentFor(binding);
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode",
            active.document()
        );
        final Object edit = resolver.invoke(
            "cubism.editor-model.edit-mode.begin",
            editMode,
            label
        );
        if (edit == null) {
            throw new IllegalStateException("Editor authoring edit did not begin");
        }
        synchronized (editLock) {
            if (editModes.put(edit, editMode) != null) {
                throw new IllegalStateException("Editor authoring edit identity was reused");
            }
        }
        return edit;
    }

    @Override
    public void endEdit(
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final Object edit,
        final boolean abort
    ) {
        final Object editMode;
        synchronized (editLock) {
            editMode = editModes.remove(Objects.requireNonNull(edit, "edit"));
        }
        if (editMode == null) {
            throw new IllegalArgumentException("Editor authoring edit token is invalid or closed");
        }
        resolver.invoke(
            "cubism.editor-model.edit-mode.end",
            editMode,
            abort,
            null
        );
    }

    @Override
    public void refresh(
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final Set<EditorRefreshRequirement> requirements
    ) {
        final NativeBinding active = currentFor(binding);
        final Set<EditorRefreshRequirement> requested = Set.copyOf(
            Objects.requireNonNull(requirements, "requirements")
        );
        final Object app = resolver.invokeStatic(
            "cubism.editor-model.app-controller.instance"
        );
        final Object completePack = resolver.invoke(
            "cubism.editor-model.app-controller.complete-pack",
            app
        );
        if (requested.contains(EditorRefreshRequirement.MODEL_INSTANCES)) {
            resolver.invoke(
                "cubism.editor-model.model-source.update-instances",
                active.source()
            );
        }
        if (requested.contains(EditorRefreshRequirement.PARAMETER_PALETTE)) {
            resolver.invoke(
                "cubism.editor-model.complete-pack.update-parameter",
                completePack,
                Boolean.TRUE
            );
        }
        if (requested.contains(EditorRefreshRequirement.PART_PALETTE)) {
            resolver.invoke(
                "cubism.editor-model.complete-pack.update-part-palette",
                completePack,
                Boolean.TRUE
            );
        }
        if (requested.contains(EditorRefreshRequirement.DEFORMER_PALETTE)) {
            resolver.invoke(
                "cubism.editor-model.complete-pack.update-deformer-palette",
                completePack,
                Boolean.TRUE
            );
        }
        if (requested.contains(EditorRefreshRequirement.MARK_DIRTY)) {
            resolver.invoke(
                "cubism.editor-model.modeling-document.mark-dirty",
                active.document()
            );
        }
        if (requested.contains(EditorRefreshRequirement.CANVAS)) {
            resolver.invoke(
                "cubism.editor-model.complete-pack.repaint-canvas",
                completePack,
                Boolean.TRUE
            );
        }
    }

    @Override
    public Optional<String> committedHistoryEntryId(
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final HistorySnapshot before,
        final HistorySnapshot after,
        final String transactionId,
        final String label
    ) {
        return committedHistoryEntryIdInternal(
            binding,
            before,
            after,
            transactionId,
            label,
            Optional.empty(),
            Optional.empty()
        );
    }

    @Override
    public Optional<String> committedHistoryEntryId(
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final HistorySnapshot before,
        final HistorySnapshot after,
        final String transactionId,
        final String label,
        final HistoryEntryDetail detail,
        final Optional<HistoryAction> action
    ) {
        return committedHistoryEntryIdInternal(
            binding,
            before,
            after,
            transactionId,
            label,
            Optional.of(Objects.requireNonNull(detail, "detail")),
            Objects.requireNonNull(action, "action")
        );
    }

    private Optional<String> committedHistoryEntryIdInternal(
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final HistorySnapshot before,
        final HistorySnapshot after,
        final String transactionId,
        final String label,
        final Optional<HistoryEntryDetail> detail,
        final Optional<HistoryAction> action
    ) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(action, "action");
        if (!isCurrent(binding)
            || before.availability() != HistorySnapshot.Availability.AVAILABLE
            || after.availability() != HistorySnapshot.Availability.AVAILABLE
            || before.generation() != after.generation()
            || before.generation() != binding.documentGeneration()
            || !before.documentBindingId().equals(after.documentBindingId())
            || !before.managerBindingId().equals(after.managerBindingId())) {
            return Optional.empty();
        }
        final List<HistoryEntry> prior = before.entries();
        final List<HistoryEntry> committed = after.entries();
        if (before.position() < 0 || before.position() > prior.size()) {
            return Optional.empty();
        }
        if (committed.isEmpty()) return Optional.empty();
        final int retainedPosition = retainedPosition(prior, before.position(),
            committed.get(committed.size() - 1).significant());
        final int expectedPosition = retainedPosition + 1;
        if (committed.size() != expectedPosition || after.position() != expectedPosition) {
            return Optional.empty();
        }
        for (int index = 0; index < retainedPosition; index++) {
            if (!prior.get(index).equals(committed.get(index))) return Optional.empty();
        }
        final HistoryEntry appended = committed.get(expectedPosition - 1);
        if (!label.equals(appended.label()) || appended.entryId().isEmpty()) {
            return Optional.empty();
        }
        final NativeBinding active = currentFor(binding);
        final Object manager = resolver.invoke(
            "cubism.editor-history.document.undo-manager",
            active.document()
        );
        final Object rawEntries = resolver.invoke(
            "cubism.editor-history.manager.entries",
            manager
        );
        if (!(rawEntries instanceof List<?> nativeEntries)
            || nativeEntries.size() != committed.size()) {
            return Optional.empty();
        }
        final Object nativeEntry = nativeEntries.get(expectedPosition - 1);
        final Object nativeLabel = resolver.invoke(
            "cubism.editor-history.entry.presentation-name",
            nativeEntry
        );
        if (!label.equals(nativeLabel)) return Optional.empty();
        if (detail.isPresent()) {
            EditorHistoryMetadataRegistry.registerTransaction(
                nativeEntry,
                transactionId,
                detail.orElseThrow(),
                action
            );
        } else {
            EditorHistoryMetadataRegistry.registerTransaction(nativeEntry, transactionId);
        }
        return Optional.of(appended.entryId().orElseThrow().value());
    }

    static int retainedPosition(final List<HistoryEntry> prior, final int position, final boolean significant) {
        if (position < 0 || position > prior.size()) throw new IllegalArgumentException("invalid position");
        int retained = position;
        // Exact CUndoManager.addEdit only prunes this tail when currentPos > 1.
        if (significant && position > 1) {
            while (retained > 0 && !prior.get(retained - 1).significant()) retained--;
        }
        return retained;
    }

    @Override
    public Optional<String> prepareHistoryMetadata(
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final Object edit,
        final String transactionId,
        final HistoryEntryDetail detail,
        final Optional<HistoryAction> action
    ) {
        currentFor(binding);
        synchronized (editLock) {
            if (!editModes.containsKey(edit)) throw new IllegalStateException("root edit is not owned");
        }
        return Optional.of(EditorHistoryMetadataRegistry.prepareTransaction(edit, transactionId, detail, action).value());
    }

    @Override
    public String diagnosticId(final String code, final Throwable failure) {
        return Objects.requireNonNull(code, "code");
    }

    private NativeBinding currentFor(
        final EditorAuthoringTransactionCoordinator.Binding expected
    ) {
        final NativeBinding active = Objects.requireNonNull(current.get(), "current binding");
        if (expected.documentGeneration() != active.generation()
            || expected.modelGeneration() != active.generation()
            || !expected.documentIdentity().equals(active.identity())
            || !expected.modelIdentity().equals(active.identity())) {
            throw new IllegalStateException("Editor authoring binding is stale");
        }
        return active;
    }

    /**
     * Exact native document/source/model tuple resolved by the Editor-backed model access.
     *
     * @param identity opaque generation-bound model identity
     * @param generation positive model generation
     * @param document active native Modeling document
     * @param source active native model source
     * @param model active native model instance
     */
    public record NativeBinding(
        String identity,
        long generation,
        Object document,
        Object source,
        Object model
    ) {

        /** Validates one native binding. */
        public NativeBinding {
            identity = Objects.requireNonNull(identity, "identity").strip();
            if (identity.isEmpty()) {
                throw new IllegalArgumentException("identity must not be blank");
            }
            if (generation <= 0) {
                throw new IllegalArgumentException("generation must be positive");
            }
            document = Objects.requireNonNull(document, "document");
            source = Objects.requireNonNull(source, "source");
            model = Objects.requireNonNull(model, "model");
        }
    }

}
