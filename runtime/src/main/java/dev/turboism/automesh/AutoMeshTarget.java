package dev.turboism.automesh;

import dev.turboism.sdk.cubism.model.Drawable;

/**
 * One ArtMesh the dispatch places a plugin layout on.
 *
 * <p>The dispatch reads only the SDK drawable. The host side supplies the implementation and keeps
 * the Editor objects its writer needs, so this package never depends on the host package.</p>
 */
public interface AutoMeshTarget {

    /**
     * Returns the SDK view of the target ArtMesh.
     *
     * @return the drawable the algorithm request is built from; never {@code null}
     */
    Drawable drawable();
}
