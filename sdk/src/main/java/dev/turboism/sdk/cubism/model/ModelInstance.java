package dev.turboism.sdk.cubism.model;


/**
 * Read-only projection of one Editor model instance
 * ({@code com.live2d.cubism.doc.model.CModel}).
 *
 * <p>Instances are created by the Editor itself (modeling view, animation
 * workspace, export); this projection never mutates the host.</p>
 */
public interface ModelInstance {

    /** Render kind of this instance. */
    InstanceRenderType renderType();
}
