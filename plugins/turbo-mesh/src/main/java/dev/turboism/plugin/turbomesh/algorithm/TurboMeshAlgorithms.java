package dev.turboism.plugin.turbomesh.algorithm;

import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithmRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Binds the plugin's auto-mesh algorithms to the framework registry of one plugin generation.
 *
 * <p>The auto-mesh surface is optional: a host or runtime without the registry still exposes the
 * ArtMesh context-menu actions, so a missing registry must never fail plugin enablement. The
 * helper therefore reports the absence once and returns an inert instance, and the plugin keeps its
 * existing behaviour exactly.</p>
 *
 * <p>Closing the helper releases only its own registrations and closes each one at most once,
 * whether the plugin lifecycle or the disposable scope reaches it first.</p>
 */
public final class TurboMeshAlgorithms implements AutoCloseable {

    /** Log line emitted when the runtime exposes no auto-mesh registry. */
    public static final String UNAVAILABLE_MESSAGE =
            "Auto-mesh algorithm registration is unavailable; context-menu placement stays available";

    /** Log line emitted when the runtime rejects a registration. */
    static final String REJECTED_MESSAGE = "Auto-mesh algorithm registration was refused";

    /** Number of algorithms this plugin contributes. */
    private static final int ALGORITHM_COUNT = 2;

    private final List<Registration> registrations;

    private final AtomicBoolean closed = new AtomicBoolean();

    private TurboMeshAlgorithms(final List<Registration> registrations) {
        this.registrations = registrations;
    }

    /**
     * Registers every algorithm of this plugin when the runtime offers the registry.
     *
     * @param context the plugin context of the current generation
     * @return the handle the caller registers in its disposable scope and closes on disable
     */
    public static TurboMeshAlgorithms register(final PluginContext context) {
        final PluginLogger logger = context.logger();
        final AutoMeshAlgorithmRegistry registry =
                context.services().find(AutoMeshAlgorithmRegistry.class).orElse(null);
        if (registry == null || !registry.isAvailable()) {
            logger.warn(UNAVAILABLE_MESSAGE);
            return new TurboMeshAlgorithms(List.of());
        }
        final List<Registration> registrations = new ArrayList<>(ALGORITHM_COUNT);
        try {
            registrations.add(registry.register(RegionAlgorithm.create(context.localization())));
            registrations.add(registry.register(AdaptiveAlgorithm.create(context.localization(), logger::info)));
        } catch (RuntimeException failure) {
            logger.warn(REJECTED_MESSAGE + ": " + failure.getMessage());
            closeAll(registrations, logger);
            return new TurboMeshAlgorithms(List.of());
        }
        return new TurboMeshAlgorithms(Collections.unmodifiableList(registrations));
    }

    /** Releases every registration of this handle; calling it more than once has no effect. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException first = null;
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (RuntimeException failure) {
                first = append(first, failure);
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static void closeAll(final List<Registration> registrations, final PluginLogger logger) {
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (RuntimeException failure) {
                logger.warn(REJECTED_MESSAGE + " cleanup failed: " + failure.getMessage());
            }
        }
    }

    private static RuntimeException append(final RuntimeException first, final RuntimeException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }
}
