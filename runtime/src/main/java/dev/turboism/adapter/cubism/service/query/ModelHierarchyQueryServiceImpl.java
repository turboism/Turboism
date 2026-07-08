package dev.turboism.adapter.cubism.service.query;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.SnapshotWithVersion;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.service.query.HierarchyNode;
import dev.turboism.sdk.cubism.service.query.ModelHierarchy;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ModelHierarchyQueryServiceImpl implements ModelHierarchyQueryService {

    private final CubismFacadeImpl facade;
    private final CubismPermissionGate permissionGate;
    private volatile HierarchyCache cachedHierarchy = new HierarchyCache(Long.MIN_VALUE, Optional.empty());

    public ModelHierarchyQueryServiceImpl(final CubismFacadeImpl facade, final CubismPermissionGate permissionGate) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
    }

    @Override
    public Optional<ModelHierarchy> currentHierarchy() throws CubismServiceException {
        permissionGate.require(CubismFacadeImpl.MODEL_READ_PERMISSION, "modelHierarchyQuery.currentHierarchy");
        return hierarchy();
    }

    @Override
    public List<HierarchyNode> childrenOf(final ModelObjectId id) throws CubismServiceException {
        Objects.requireNonNull(id, "id");
        permissionGate.require(CubismFacadeImpl.MODEL_READ_PERMISSION, "modelHierarchyQuery.childrenOf");
        return hierarchy().map(modelHierarchy -> modelHierarchy.childrenOf(id)).orElseGet(List::of);
    }

    @Override
    public Optional<HierarchyNode> findNode(final ModelObjectId id) throws CubismServiceException {
        Objects.requireNonNull(id, "id");
        permissionGate.require(CubismFacadeImpl.MODEL_READ_PERMISSION, "modelHierarchyQuery.findNode");
        return hierarchy().flatMap(modelHierarchy -> modelHierarchy.findNode(id));
    }

    private Optional<ModelHierarchy> hierarchy() throws CubismServiceException {
        final SnapshotWithVersion versioned = runtimeWithServiceError();
        final HierarchyCache currentCache = cachedHierarchy;
        if (currentCache.version() == versioned.version()) {
            return currentCache.hierarchy();
        }
        final HierarchyCache nextCache = new HierarchyCache(versioned.version(), buildHierarchy(versioned.snapshot()));
        cachedHierarchy = nextCache;
        return nextCache.hierarchy();
    }

    private SnapshotWithVersion runtimeWithServiceError() throws CubismServiceException {
        try {
            return facade.runtimeWithVersion();
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new CubismServiceException(ServiceError.INVALID_SNAPSHOT, "Cubism runtime snapshot is invalid.", error);
        }
    }

    private Optional<ModelHierarchy> buildHierarchy(final CubismRuntimeSnapshot snapshot) throws CubismServiceException {
        if (snapshot.model().isEmpty()) {
            return Optional.empty();
        }
        final ModelSnapshot model = snapshot.model().orElseThrow();
        final boolean canReadMesh = canReadMesh();
        final ModelObjectId rootId = new ModelObjectId(model.modelId());
        final Map<String, List<ModelObjectId>> childrenByParentId = childrenByParentId(model, rootId.value(), canReadMesh);
        final List<HierarchyNode> nodes = new ArrayList<>();
        nodes.add(new HierarchyNode(rootId, model.name(), HierarchyNode.Kind.MODEL, Optional.empty(), childIds(childrenByParentId, rootId.value())));
        for (ParameterSnapshot parameter : model.parameters()) {
            final ModelObjectId id = new ModelObjectId(parameter.id());
            nodes.add(new HierarchyNode(id, parameter.name(), HierarchyNode.Kind.PARAMETER, Optional.of(rootId), List.of()));
        }
        for (DeformerSnapshot deformer : model.deformers()) {
            final ModelObjectId id = new ModelObjectId(deformer.id());
            final Optional<ModelObjectId> parentId = Optional.of(new ModelObjectId(deformer.parentId().orElse(rootId.value())));
            nodes.add(new HierarchyNode(id, deformer.name(), HierarchyNode.Kind.DEFORMER, parentId, childIds(childrenByParentId, deformer.id())));
        }
        if (canReadMesh) {
            for (ArtMeshSnapshot artMesh : model.artMeshes()) {
                final ModelObjectId id = new ModelObjectId(artMesh.id());
                final Optional<ModelObjectId> parentId = Optional.of(new ModelObjectId(parentIdForArtMesh(childrenByParentId, rootId.value(), artMesh.id())));
                nodes.add(new HierarchyNode(id, artMesh.name(), HierarchyNode.Kind.ART_MESH, parentId, List.of()));
            }
        }
        assertUniqueIds(nodes);
        return Optional.of(new ModelHierarchy(nodes.get(0), nodes));
    }

    private boolean canReadMesh() {
        return permissionGate.hasPermission(CubismFacadeImpl.MESH_READ_PERMISSION);
    }

    private Map<String, List<ModelObjectId>> childrenByParentId(final ModelSnapshot model, final String rootId, final boolean canReadMesh) {
        final Map<String, List<ModelObjectId>> childrenByParentId = new LinkedHashMap<>();
        for (ParameterSnapshot parameter : model.parameters()) {
            childrenByParentId.computeIfAbsent(rootId, ignored -> new ArrayList<>()).add(new ModelObjectId(parameter.id()));
        }
        for (DeformerSnapshot deformer : model.deformers()) {
            childrenByParentId.computeIfAbsent(deformer.parentId().orElse(rootId), ignored -> new ArrayList<>()).add(new ModelObjectId(deformer.id()));
            for (String childId : deformer.childIds()) {
                childrenByParentId.computeIfAbsent(deformer.id(), ignored -> new ArrayList<>()).add(new ModelObjectId(childId));
            }
        }
        if (canReadMesh) {
            for (ArtMeshSnapshot artMesh : model.artMeshes()) {
                if (!hasParent(childrenByParentId, artMesh.id())) {
                    childrenByParentId.computeIfAbsent(rootId, ignored -> new ArrayList<>()).add(new ModelObjectId(artMesh.id()));
                }
            }
        }
        return childrenByParentId;
    }

    private List<ModelObjectId> childIds(final Map<String, List<ModelObjectId>> childrenByParentId, final String parentId) {
        return List.copyOf(childrenByParentId.getOrDefault(parentId, List.of()));
    }

    private String parentIdForArtMesh(final Map<String, List<ModelObjectId>> childrenByParentId, final String rootId, final String artMeshId) {
        for (Map.Entry<String, List<ModelObjectId>> entry : childrenByParentId.entrySet()) {
            if (!entry.getKey().equals(rootId) && entry.getValue().contains(new ModelObjectId(artMeshId))) {
                return entry.getKey();
            }
        }
        return rootId;
    }

    private boolean hasParent(final Map<String, List<ModelObjectId>> childrenByParentId, final String childId) {
        return childrenByParentId.values().stream().anyMatch(childIds -> childIds.contains(new ModelObjectId(childId)));
    }

    private void assertUniqueIds(final List<HierarchyNode> nodes) throws CubismServiceException {
        final Map<ModelObjectId, HierarchyNode> nodesById = new LinkedHashMap<>();
        for (HierarchyNode node : nodes) {
            if (nodesById.put(node.id(), node) != null) {
                throw new CubismServiceException(ServiceError.INVALID_SNAPSHOT, "Duplicate hierarchy node id " + node.id().value());
            }
        }
    }

    private record HierarchyCache(long version, Optional<ModelHierarchy> hierarchy) {
        private HierarchyCache {
            hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
        }
    }
}
