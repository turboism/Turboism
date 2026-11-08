package dev.turboism.automesh.host;

import java.util.List;

/**
 * Executable stand-in for a host funnel class.
 *
 * <p>The reviewed Editor cannot start headless, so the guard control flow is proven on this class:
 * it carries the same two funnel shapes with neutral parameter types, it is loaded from its own
 * bytes through a child-first loader, and its counters show whether the native body ran.</p>
 */
public final class FunnelFixture {

    /** Number of times the modeling-mode body executed. */
    public int modelingCalls;

    /** Number of times the mesh-editor body executed. */
    public int meshEditorCalls;

    /** Last pack argument the native body observed. */
    public Object lastPack;

    /** Modeling-mode funnel body. */
    public void updateMeshFromInspector_inModelingMode(
            final Object pack,
            final Object event,
            final List<?> extensions,
            final Object undoGroup,
            final Object completion) {
        modelingCalls++;
        lastPack = pack;
    }

    /** Mesh-editor funnel body. */
    public void updateMeshFromInspector_inMeshEditorMode(final Object pack, final Object event, final List<?> targets) {
        meshEditorCalls++;
        lastPack = pack;
    }
}
