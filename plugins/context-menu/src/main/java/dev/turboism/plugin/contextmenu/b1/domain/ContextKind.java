package dev.turboism.plugin.contextmenu.b1.domain;

/**
 * The Editor selection a context-menu entry attaches to.
 *
 * <p>Each constant names one host menu surface; a contribution is offered only on the surface
 * matching its kind.
 */
public enum ContextKind {
    PARTS,
    DEFORMER,
    PARAMETER,
    WORKSPACE_OBJECT
}
