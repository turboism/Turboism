package dev.turboism.adapter.cubism.editor.transaction;

import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import java.util.Optional;

/**
 * What an authoring transaction needs from the Editor history recorder.
 *
 * <p>Authoring transactions run below the history capture package, so they receive this port
 * instead of importing it: the recorder supplies the snapshot an edit is committed against and
 * accepts the metadata that ties a native undo entry to a transaction. Implementations live with
 * the recorder; nothing in this package may import them.</p>
 */
public interface AuthoringHistoryPort {

    /**
     * Captures the document's current undo stack.
     *
     * @return the current history snapshot
     */
    HistorySnapshot snapshot();

    /**
     * Records a committed native undo entry as belonging to one transaction.
     *
     * @param nativeEntry native undo entry the Editor appended
     * @param transactionId owning transaction identity
     */
    void registerTransaction(Object nativeEntry, String transactionId);

    /**
     * Records a committed native undo entry with its decoded detail and action.
     *
     * @param nativeEntry native undo entry the Editor appended
     * @param transactionId owning transaction identity
     * @param detail decoded history detail
     * @param action optional authored action
     */
    void registerTransaction(
            Object nativeEntry, String transactionId, HistoryEntryDetail detail, Optional<HistoryAction> action);

    /**
     * Prepares the metadata entry for an edit that is about to be committed.
     *
     * @param nativeEntry native edit object
     * @param transactionId owning transaction identity
     * @param detail decoded history detail
     * @param action optional authored action
     * @return the prepared entry identity
     */
    String prepareTransaction(
            Object nativeEntry, String transactionId, HistoryEntryDetail detail, Optional<HistoryAction> action);
}
