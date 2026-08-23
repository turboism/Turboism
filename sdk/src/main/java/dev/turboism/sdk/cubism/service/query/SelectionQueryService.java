package dev.turboism.sdk.cubism.service.query;

import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import java.util.List;

/**
 * Read-only access to what the user currently has selected in the Editor.
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
     * Selection observations are published as
     * {@link dev.turboism.sdk.cubism.event.SelectionChangedEvent} through the plugin event bus.
     * Because the current host integration is pull-based, an event is emitted only when a fresh
     * query detects a transition; this API does not claim a native push subscription.
     */
}
