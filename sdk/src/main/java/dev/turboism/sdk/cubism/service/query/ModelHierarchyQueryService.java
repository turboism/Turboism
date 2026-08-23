package dev.turboism.sdk.cubism.service.query;

import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the Editor's model object tree.
 *
 * <p>Every method returns a snapshot taken at the moment of the call; nothing returned stays in sync
 * with subsequent Editor edits. Implementations bridge to the Cubism host, so calls may need to be
 * made from the host thread and may fail with {@link CubismServiceException} when the host is
 * unavailable or the bound Editor build does not expose the required internals.
 */
public interface ModelHierarchyQueryService {

    /**
     * @return a snapshot of the currently open model's tree, or empty when no model is open
     * @throws CubismServiceException if the host could not be queried
     */
    Optional<ModelHierarchy> currentHierarchy() throws CubismServiceException;

    /**
     * @param id the model object to expand
     * @return that object's direct children, empty if it has none or is not present in the current model
     * @throws CubismServiceException if the host could not be queried
     */
    List<HierarchyNode> childrenOf(ModelObjectId id) throws CubismServiceException;

    /**
     * @param id the model object to look up
     * @return that object as a node, or empty when the current model contains no such object
     * @throws CubismServiceException if the host could not be queried
     */
    Optional<HierarchyNode> findNode(ModelObjectId id) throws CubismServiceException;
}
