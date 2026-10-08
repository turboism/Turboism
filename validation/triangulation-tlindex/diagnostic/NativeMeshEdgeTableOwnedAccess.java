package dev.turboism.adapter.cubism.mesh;

/** Owned-fixture access to the actual package-private production storage class. */
public final class NativeMeshEdgeTableOwnedAccess {
    private NativeMeshEdgeTableOwnedAccess() { }
    public static Object reserve(int entries) { return NativeMeshEdgeTable.reserve(entries); }
    public static boolean putFirst(Object table, int first, int second, int slot) {
        return ((NativeMeshEdgeTable) table).putFirst(first, second, slot);
    }
    public static int find(Object table, int first, int second) {
        return ((NativeMeshEdgeTable) table).find(first, second);
    }
    public static void close(Object table) { ((NativeMeshEdgeTable) table).close(); }
    public static int reservedBytes() { return NativeMeshEdgeTable.reservedBytes(); }
    public static byte[] runtimePatch(byte[] raw) { return NativeMeshEdgePatcher.patch(raw); }
    public static AutoCloseable begin(Object mesh) { return NativeMeshEdgeLookup.begin(mesh, () -> { }); }
    public static AutoCloseable begin(Object mesh, AutoCloseable definition) { return NativeMeshEdgeLookup.begin(mesh, definition); }
}
