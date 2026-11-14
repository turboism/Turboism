package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.plugin.Registration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runtime-owned canvas point-and-edge authoring session bound to one mesh-tool activation.
 *
 * <p>The session owns everything that needs the host canvas: the overlay that draws the anchor
 * and the preview, the mouse and key routing that keeps the surrounding mesh editor usable, the
 * mesh-space projection the preview needs, and the single transactional commit. The plugin owns
 * the geometry: it computes the vertices and edges and hands them to the session as mesh-space
 * data, so shape generators, parameter state and commit policy stay plugin business logic.</p>
 *
 * <p>Obtained from {@link MeshToolContext#canvasSession()}, which requires capability
 * {@code cubism.mesh.point-and-edge-layout} and permissions
 * {@code turboism.cubism.model.read} plus {@code turboism.cubism.model.write}. Every member fails
 * closed once the activation, its mesh session, its host generation or its plugin generation is
 * invalidated.</p>
 *
 * <p><strong>Preview API:</strong> this contract may evolve before stabilization.</p>
 */
@CubismEditor(from = "5.0.00")
public interface MeshCanvasSession extends AutoCloseable {

    /**
     * Returns the current center anchor in mesh space.
     *
     * <p>The anchor starts at the active mesh center and follows the operator's primary-button
     * presses and drags on the canvas.</p>
     *
     * @return the anchor in mesh space, never {@code null}
     */
    Point2 anchor();

    /**
     * Observes anchor moves caused by the operator on the canvas.
     *
     * <p>The listener runs on the UI thread, once per anchor update, and never after the session
     * is closed. Closing the returned registration stops the notifications; the session also
     * releases every listener when it closes.</p>
     *
     * @param listener receives the new mesh-space anchor
     * @return idempotent registration that stops the notifications
     */
    Registration observeAnchorMoved(Consumer<Point2> listener);

    /**
     * Paints a preview of the given mesh-space layout around the current anchor.
     *
     * <p>The runtime projects the anchor and the vertices into canvas space and repaints on every
     * canvas resize, scroll or anchor move. Passing an empty vertex list paints the anchor alone.</p>
     *
     * @param vertices mesh-space vertices, in the order the edge pairs index them
     * @param edgePairs undirected edge index pairs, flat {@code [u0, v0, u1, v1, ...]}
     * @throws IllegalArgumentException when an edge pair is not even-length or names a vertex outside
     *     {@code vertices}
     */
    void showPreview(List<Point2> vertices, int[] edgePairs);

    /** Clears the preview; the anchor marker stays visible while the session is open. */
    void clearPreview();

    /**
     * Adds the given mesh-space layout to the active mesh as one transactional edit.
     *
     * <p>The whole layout lands in the host's own Undo group, so exactly one Undo entry reverts it,
     * and the added vertices become the native selection afterwards. This is the only authoring
     * verb of the session: it never opens, saves or discards a document.</p>
     *
     * @param vertices mesh-space vertices, in the order the edge pairs index them
     * @param edgePairs undirected edge index pairs, flat {@code [u0, v0, u1, v1, ...]}
     * @return the added vertex indices in {@code vertices} order
     * @throws IllegalArgumentException when an edge pair is not even-length or names a vertex outside
     *     {@code vertices}
     */
    List<Integer> commit(List<Point2> vertices, int[] edgePairs);

    /**
     * Ends this activation and returns the host to the previously active mesh tool.
     *
     * <p>Matches the operator pressing Escape: the preview is cleared and the runtime deactivates
     * this activation through its normal transition, which closes the session as part of it.</p>
     */
    void deactivate();

    /**
     * Cancels the preview and releases the overlay, listeners and key bindings.
     *
     * <p>Closing is idempotent and never commits geometry; the mesh and its Undo history are
     * untouched. Use {@link #deactivate()} when the host tool should also be left.</p>
     */
    @Override
    void close();
}
