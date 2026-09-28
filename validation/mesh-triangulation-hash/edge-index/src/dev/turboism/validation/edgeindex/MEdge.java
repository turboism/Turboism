package dev.turboism.validation.edgeindex;

/**
 * Minimal stand-in for the official {@code MEdge}. The field order and the
 * (index1 &lt;= index2) normalization mirror what the official {@code addEdge}
 * stores; this type deliberately carries no geometry or owner state.
 */
final class MEdge {
    final int index1;
    final int index2;
    final EdgeType type;

    MEdge(final int index1, final int index2, final EdgeType type) {
        this.index1 = index1;
        this.index2 = index2;
        this.type = type;
    }

    @Override
    public String toString() {
        return "(" + index1 + "," + index2 + "," + type.name() + ")";
    }
}
