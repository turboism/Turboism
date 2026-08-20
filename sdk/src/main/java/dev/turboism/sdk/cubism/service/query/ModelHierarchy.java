package dev.turboism.sdk.cubism.service.query;

import dev.turboism.sdk.cubism.id.ModelObjectId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable snapshot of a model's object tree, indexed for lookup by id.
 *
 * <p>All three components are defensively copied at construction, so the snapshot is unaffected by
 * later Editor edits and by mutation of the collections passed in. Nothing guarantees that every id
 * referenced by a node is present in {@code nodesById}; the lookup methods are total and simply
 * return nothing for ids the snapshot does not contain.
 *
 * @param rootNode the tree's root, usually the model itself
 * @param nodes every node in the snapshot, in capture order; unmodifiable
 * @param nodesById lookup index over {@code nodes}; unmodifiable. When built by the two-argument
 *                  constructor, later nodes sharing an id win.
 */
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

    /**
     * Looks a node up by id within this snapshot.
     *
     * @param id the model object to find
     * @return the node, or empty if this snapshot contains no node with that id
     * @throws NullPointerException if {@code id} is {@code null}
     */
    public Optional<HierarchyNode> findNode(ModelObjectId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(nodesById.get(id));
    }

    /**
     * Resolves a node's direct children to the node objects held by this snapshot.
     *
     * <p>Child ids that this snapshot does not contain are silently skipped, so the result may be
     * shorter than the node's {@code childIds}. An unknown {@code id} yields an empty list rather
     * than an error.
     *
     * @param id the parent to expand
     * @return the resolvable children in Editor order; empty if the id is unknown or has no children
     * @throws NullPointerException if {@code id} is {@code null}
     */
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
