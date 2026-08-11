package dev.turboism.sdk.cubism.core;

import dev.turboism.sdk.PreviewApi;

import java.util.List;

/**
 * Evaluated read-only view of one plugin-owned Core {@code CubismModel}.
 *
 * <p>Every read returns immutable adapter-owned copies of the evaluated surface
 * (canvas, parameters, parts, drawables, glues, deformers). {@link #update()} runs the
 * Core evaluation step; the projection never exposes Core writes
 * ({@code setValue}/{@code setOpacity} stay unavailable). Closing the model releases
 * the Core instance.</p>
 */
@PreviewApi
public interface OwnedModel extends AutoCloseable {

    /** Core native handle of the owned {@code CubismModel} instance. */
    long nativeHandle();

    /** Evaluated canvas information. */
    OwnedCanvasInfo canvasInfo();

    /** Evaluated parameter definitions and current values. */
    List<OwnedParameter> parameters();

    /** Evaluated part definitions and current opacities. */
    List<OwnedPart> parts();

    /** Evaluated drawable definitions (blend, colors, vertices, flags). */
    List<OwnedDrawable> drawables();

    /** Evaluated glue definitions. */
    List<OwnedGlue> glues();

    /** Evaluated deformer definitions. */
    List<OwnedDeformer> deformers();

    /**
     * Runs the Core evaluation step ({@code CubismModel.update()}).
     *
     * <p>Read-only projection: this controls evaluation only; no Core value is
     * written through this projection.</p>
     */
    void update();

    /**
     * Releases the owned Core model instance.
     *
     * <p>After close every read and {@link #update()} fail closed.</p>
     */
    @Override
    void close();
}
