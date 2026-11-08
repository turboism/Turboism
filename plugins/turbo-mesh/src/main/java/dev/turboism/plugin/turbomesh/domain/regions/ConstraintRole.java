package dev.turboism.plugin.turbomesh.domain.regions;

/**
 * Role of a constraint edge in the planned mesh.
 *
 * <p>The role tells consumers why an edge must survive deformation: it either borders empty space,
 * separates two texture regions, or carries a shape direction guide.</p>
 */
public enum ConstraintRole {
    /** Silhouette or alpha-hole border; the mesh may never move this edge off the material. */
    OUTLINE,
    /** Border between two texture regions of the same solid area. */
    TEXTURE_LINE,
    /** Shape direction guide: concentric ring, spindle arc, or hard-surface parallel line. */
    DIRECTION_LINE
}
