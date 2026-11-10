package dev.turboism.sdk.cubism.mesh.automesh;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;
import dev.turboism.sdk.plugin.Registration;
import java.util.List;
import java.util.Optional;

/**
 * Plugin-generation-scoped registry of auto-mesh placement algorithms.
 *
 * <p>The native Auto Mesh Generate entry lists every registered algorithm next to the Editor's own
 * generator and runs the selected one. The Editor's generator is always the first entry — it is
 * the default and cannot be removed — and the registered algorithms follow in registration
 * order.</p>
 *
 * <p>Registering and selecting require the {@code cubism.mesh.automesh-algorithms} capability and the
 * {@code turboism.cubism.mesh.automesh.contribute} permission. A registration lives until its {@link
 * Registration} is closed or the owning plugin generation ends, whichever comes first, and closing
 * it is idempotent.</p>
 *
 * <p>Selection is session state owned by the runtime, not by the registering plugin: it survives
 * plugin reloads and falls back to the Editor's generator while the selected algorithm is not
 * registered. Selecting never registers an algorithm, and registering never selects one.</p>
 *
 * <p><strong>Preview API:</strong> this contract may evolve before stabilization.</p>
 */
@Incubating
@CubismEditor
public interface AutoMeshAlgorithmRegistry extends AutoCloseable {

    /**
     * Registers one algorithm for this plugin generation.
     *
     * <p>A duplicate id fails, including an id another plugin already registered, because two
     * algorithms with the same id would make the session selection ambiguous.</p>
     *
     * @param algorithm the algorithm declaration and its compute function
     * @return the registration; closing it removes only that exact registration
     * @throws IllegalArgumentException when the id is blank, the native id, or already registered
     * @throws SecurityException when the plugin lacks the required permission or capability
     */
    Registration register(AutoMeshAlgorithm algorithm);

    /**
     * Lists the algorithms the native entry may offer.
     *
     * @return the Editor's own generator first, then the live registrations in registration order
     */
    List<AutoMeshAlgorithm> algorithms();

    /**
     * Finds one registered algorithm by id.
     *
     * @param algorithmId the algorithm id to resolve
     * @return the algorithm, or empty when it is not registered or its plugin generation ended
     */
    Optional<AutoMeshAlgorithm> find(String algorithmId);

    /**
     * Requests the algorithm the native Auto Mesh Generate entry runs.
     *
     * <p>Passing {@code null}, a blank id or {@link AutoMeshAlgorithm#NATIVE_ALGORITHM_ID} restores
     * the Editor's own generator. Any other id is stored as given: an id that is not registered at
     * the moment of the run resolves to the Editor's generator instead of failing, so a selection
     * survives a plugin reload.</p>
     *
     * @param algorithmId the algorithm to select, or a native value
     * @throws SecurityException when the plugin lacks the required permission or capability
     */
    void select(String algorithmId);

    /**
     * Returns the algorithm the native entry currently runs.
     *
     * @return the selected algorithm id, or empty when the Editor's own generator runs — either
     *     because nothing was selected or because the selected algorithm is not registered
     */
    Optional<String> selectedAlgorithmId();

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} when the backend backing this instance is unavailable, including the
     *     {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static AutoMeshAlgorithmRegistry unavailable() {
        return Unavailable.INSTANCE;
    }

    /**
     * Revokes every algorithm this plugin generation registered.
     *
     * <p>Calling it more than once has no further effect.</p>
     */
    @Override
    void close();

    /**
     * Sentinel returned by {@link #unavailable()}: writes throw a stable {@link
     * UnsupportedOperationException} and reads report empty.
     */
    enum Unavailable implements AutoMeshAlgorithmRegistry {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Registration register(final AutoMeshAlgorithm algorithm) {
            throw unavailable();
        }

        @Override
        public List<AutoMeshAlgorithm> algorithms() {
            return List.of();
        }

        @Override
        public Optional<AutoMeshAlgorithm> find(final String algorithmId) {
            return Optional.empty();
        }

        @Override
        public void select(final String algorithmId) {
            throw unavailable();
        }

        @Override
        public Optional<String> selectedAlgorithmId() {
            return Optional.empty();
        }

        @Override
        public void close() {}

        private static UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException("autoMeshAlgorithms service is not available");
        }
    }
}
