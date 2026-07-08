package dev.turboism.sdk.cubism.service.query;

import dev.turboism.sdk.cubism.id.ModelObjectId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ModelHierarchy(
    HierarchyNode rootNode,
    List<HierarchyNode> nodes,
    Map<ModelObjectId, HierarchyNode> nodesById
) {
    public ModelHierarchy(HierarchyNode rootNode, List<HierarchyNode> nodes) {
        this(rootNode, nodes, indexById(nodes));
    }

    public ModelHierarchy {
        rootNode = Objects.requireNonNull(rootNode, "rootNode");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        nodesById = Map.copyOf(Objects.requireNonNull(nodesById, "nodesById"));
    }

    public Optional<HierarchyNode> findNode(ModelObjectId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(nodesById.get(id));
    }

    public List<HierarchyNode> childrenOf(ModelObjectId id) {
        Objects.requireNonNull(id, "id");
        return findNode(id)
            .map(this::childrenOf)
            .orElseGet(List::of);
    }

    private List<HierarchyNode> childrenOf(HierarchyNode node) {
        return node.childIds().stream()
            .map(nodesById::get)
            .filter(Objects::nonNull)
            .toList();
    }

    private static Map<ModelObjectId, HierarchyNode> indexById(List<HierarchyNode> nodes) {
        Objects.requireNonNull(nodes, "nodes");
        Map<ModelObjectId, HierarchyNode> indexed = new LinkedHashMap<>();
        for (HierarchyNode node : nodes) {
            indexed.put(node.id(), node);
        }
        return indexed;
    }
}
