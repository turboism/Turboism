package dev.turboism.sdk.cubism.service.query;

import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.event.SelectionChangedEvent;
import dev.turboism.sdk.plugin.Registration;
import java.util.List;

/**
 * Read-only access to what the user currently has selected in the Editor, plus change notification.
 *
 * <p>Queries return snapshots taken at call time. Implementations bridge to the Cubism host, so
 * calls may need to be made from the host thread and fail with {@link CubismServiceException} when
 * the host is unavailable.
 */
public interface SelectionQueryService {

    /**
     * @return the current selection; {@link SelectionSummary#empty()} rather than {@code null} when
     *         nothing is selected
     * @throws CubismServiceException if the host could not be queried
     */
    SelectionSummary currentSelection() throws CubismServiceException;

    /**
     * @param kind restricts the result to selected objects of this kind
     * @return ids of the selected objects of that kind, empty when none are selected
     * @throws CubismServiceException if the host could not be queried
     */
    List<ModelObjectId> selectedIds(HierarchyNode.Kind kind) throws CubismServiceException;

    /**
     * Subscribes to selection changes for as long as the returned registration is held.
     *
     * <p>The listener is invoked by the host, so it should return promptly and must not block; close
     * the registration to unsubscribe. Failing to close it leaks the listener for the life of the
     * host.
     *
     * @param listener called on each selection change
     * @return a handle that removes the listener when closed
     * @throws CubismServiceException if the subscription could not be established
     */
    Registration onSelectionChanged(SelectionChangedListener listener) throws CubismServiceException;

    @FunctionalInterface
    interface SelectionChangedListener {
        void selectionChanged(SelectionChangedEvent event);
    }
}
