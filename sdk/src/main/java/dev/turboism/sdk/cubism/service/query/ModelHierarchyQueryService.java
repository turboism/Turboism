package dev.turboism.sdk.cubism.service.query;

import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import java.util.List;
import java.util.Optional;

public interface ModelHierarchyQueryService {

    Optional<ModelHierarchy> currentHierarchy() throws CubismServiceException;

    List<HierarchyNode> childrenOf(ModelObjectId id) throws CubismServiceException;

    Optional<HierarchyNode> findNode(ModelObjectId id) throws CubismServiceException;
}
