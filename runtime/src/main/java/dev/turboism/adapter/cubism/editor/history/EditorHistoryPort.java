package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.adapter.cubism.editor.transaction.AuthoringHistoryPort;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Adapts the Editor history snapshot provider and metadata registry to the transaction port.
 *
 * <p>This is the only place that knows both sides: authoring transactions depend on
 * {@link AuthoringHistoryPort}, and the recorder is constructed here, in the layer above them.</p>
 */
public final class EditorHistoryPort implements AuthoringHistoryPort {

    private final EditorHistorySnapshotProvider history;

    /**
     * Creates the port over the history provider of one resolved Editor connection.
     *
     * @param resolver supplier of the verified member resolver for the active connection
     * @param generation supplier of the current model binding generation
     */
    public EditorHistoryPort(final Supplier<Optional<VerifiedMemberResolver>> resolver, final LongSupplier generation) {
        this.history = new EditorHistorySnapshotProvider(resolver, Objects.requireNonNull(generation, "generation"));
    }

    @Override
    public HistorySnapshot snapshot() {
        return history.snapshot();
    }

    @Override
    public void registerTransaction(final Object nativeEntry, final String transactionId) {
        EditorHistoryMetadataRegistry.registerTransaction(nativeEntry, transactionId);
    }

    @Override
    public void registerTransaction(
            final Object nativeEntry,
            final String transactionId,
            final HistoryEntryDetail detail,
            final Optional<HistoryAction> action) {
        EditorHistoryMetadataRegistry.registerTransaction(nativeEntry, transactionId, detail, action);
    }

    @Override
    public String prepareTransaction(
            final Object nativeEntry,
            final String transactionId,
            final HistoryEntryDetail detail,
            final Optional<HistoryAction> action) {
        return EditorHistoryMetadataRegistry.prepareTransaction(nativeEntry, transactionId, detail, action)
                .value();
    }
}
