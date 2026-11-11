package dev.turboism.automesh.host;

import dev.turboism.sdk.cubism.model.Drawable;
import java.util.Objects;

/**
 * One confirmed ArtMesh together with the host handles its funnel delivered.
 *
 * <p>The editable mesh is the object the Editor's own generator replaces, so the write must reach
 * the very instance the funnel was given instead of resolving it again from the current selection.
 * The source, the editable mesh and the pack stay opaque here: the adapter that implements the
 * write owns their meaning, and a null editable mesh states that the host exposed none.</p>
 *
 * @param funnel the funnel that delivered this target
 * @param drawable the SDK view of the target ArtMesh
 * @param source the host ArtMesh source of the target
 * @param editableMesh the host editable mesh of the target, or {@code null} when the host exposed none
 * @param completePack the host pack of the funnel call, or {@code null} when the call carried none
 */
public record AutoMeshResolvedTarget(
        AutoMeshFunnel funnel, Drawable drawable, Object source, Object editableMesh, Object completePack) {

    public AutoMeshResolvedTarget {
        funnel = Objects.requireNonNull(funnel, "funnel");
        drawable = Objects.requireNonNull(drawable, "drawable");
        source = Objects.requireNonNull(source, "source");
    }
}
