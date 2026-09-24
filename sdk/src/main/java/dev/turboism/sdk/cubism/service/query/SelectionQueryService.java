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
     * Fresh queries and the session-bounded observer share one snapshot source and one
     * versioned baseline: whichever path reads a newer snapshot commits the baseline and
     * publishes the transition, so a query result and a later observed transition cannot
     * double-publish or regress each other. There is still no native object-selection
     * identity and no native push subscription behind this API.
     */

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static SelectionQueryService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: calls refuse work without reaching the host. */
    enum Unavailable implements SelectionQueryService {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public SelectionSummary currentSelection() throws CubismServiceException {
            throw unavailable();
        }

        @Override public List<ModelObjectId> selectedIds(final HierarchyNode.Kind kind)
            throws CubismServiceException {
            throw unavailable();
        }

        private static CubismServiceException unavailable() {
            return new CubismServiceException(
                "cubism.query.unavailable",
                "selectionQuery service is not available"
            );
        }
    }
}
