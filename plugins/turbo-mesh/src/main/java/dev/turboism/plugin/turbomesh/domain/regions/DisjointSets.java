package dev.turboism.plugin.turbomesh.domain.regions;

/**
 * Disjoint-set operations over a caller-owned {@code int[]} parent array.
 *
 * <p>Every region pass builds its own parent array and labels it in place, so the operations stay
 * static and array-based instead of owning a set object. {@link #find} compresses the walked path,
 * and {@link #union} always makes the smaller index the root so the labels a pass emits are stable
 * across runs.</p>
 */
final class DisjointSets {

    private DisjointSets() {}

    /**
     * Returns the root of {@code start} and compresses every node on the path to that root.
     *
     * @param parent parent array; {@code parent[i] == i} marks a root
     * @param start node to resolve
     * @return the root index of {@code start}
     */
    static int find(final int[] parent, final int start) {
        int root = start;
        while (parent[root] != root) {
            root = parent[root];
        }
        int cursor = start;
        while (parent[cursor] != root) {
            final int next = parent[cursor];
            parent[cursor] = root;
            cursor = next;
        }
        return root;
    }

    /**
     * Merges the sets holding {@code a} and {@code b}, keeping the smaller root index.
     *
     * @param parent parent array; {@code parent[i] == i} marks a root
     * @param a one member of the sets to merge
     * @param b the other member of the sets to merge
     */
    static void union(final int[] parent, final int a, final int b) {
        final int rootA = find(parent, a);
        final int rootB = find(parent, b);
        if (rootA != rootB) {
            parent[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
        }
    }
}
