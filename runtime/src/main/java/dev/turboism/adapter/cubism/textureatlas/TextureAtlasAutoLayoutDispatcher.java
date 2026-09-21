package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Runtime-owned dispatch of the native texture-atlas automatic-layout entry.
 *
 * <p>{@link #dispatch()} is invoked by the verified host hook inside the
 * {@link TextureAtlasNativeInvocationCoordinator} invocation scope — the same thread,
 * with the native session open. It resolves the runtime-owned selection against the
 * runtime registry, runs the selected planner on the issued current-page snapshot, and
 * applies the plan through the layout service. Every unsafe outcome — no selection, an
 * unregistered or unloaded algorithm, a {@code null} planner, a planner that throws or
 * returns {@code null}, a registration revoked or replaced mid-flight, or a rejected
 * apply — returns {@code false} so the coordinator restores the native session and the
 * host proceeds with its own packing. Nothing escapes into the host.</p>
 */
public final class TextureAtlasAutoLayoutDispatcher {

    private final RuntimeTextureAtlasLayoutAlgorithmRegistry algorithms;
    private final TextureAtlasAutoLayoutSelection selection;
    private final TextureAtlasLayoutService layouts;

    public TextureAtlasAutoLayoutDispatcher(
        final RuntimeTextureAtlasLayoutAlgorithmRegistry algorithms,
        final TextureAtlasAutoLayoutSelection selection,
        final TextureAtlasLayoutService layouts
    ) {
        this.algorithms = Objects.requireNonNull(algorithms, "algorithms");
        this.selection = Objects.requireNonNull(selection, "selection");
        this.layouts = Objects.requireNonNull(layouts, "layouts");
    }

    /** @return the dispatch callback the verified hook wraps in the native invocation scope. */
    public BooleanSupplier callback() {
        return this::dispatch;
    }

    /**
     * Routes one native automatic-layout invocation to the selected algorithm.
     *
     * @return true only when the plan was applied (the coordinator additionally requires
     *     the invocation to be marked handled and still current); false defers to the
     *     host's native packing after the session is restored
     */
    public boolean dispatch() {
        final TextureAtlasLayoutSelection selected = selection.selection();
        final String algorithmId = selected.algorithmId();
        // Unset and the reserved explicit-native id both defer to the host.
        if (algorithmId == null
            || TextureAtlasLayoutSelection.NATIVE_ALGORITHM_ID.equals(algorithmId)) {
            return false;
        }
        final RuntimeTextureAtlasLayoutAlgorithmRegistry.RegisteredAlgorithm lease =
            algorithms.acquire(algorithmId);
        if (lease == null) {
            return false;
        }
        try {
            return invoke(lease, selected.parallel());
        } catch (RuntimeException | Error failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                "texture-atlas",
                "Texture-atlas automatic-layout dispatch failed safely",
                failure
            );
            return false;
        } finally {
            algorithms.release(lease);
        }
    }

    private boolean invoke(
        final RuntimeTextureAtlasLayoutAlgorithmRegistry.RegisteredAlgorithm lease,
        final boolean parallel
    ) {
        final TextureAtlasLayoutPlanner planner = lease.algorithm().planner();
        if (planner == null) {
            return false;
        }
        final Optional<TextureAtlasLayoutSnapshot> current = layouts.current();
        if (current.isEmpty()) {
            return false;
        }
        final TextureAtlasLayoutSnapshot snapshot = current.orElseThrow();
        final TextureAtlasLayoutPlan plan;
        final ClassLoader ownerLoader = lease.ownerLoader();
        final Thread thread = Thread.currentThread();
        final ClassLoader previousLoader = thread.getContextClassLoader();
        try {
            if (ownerLoader != null && ownerLoader != previousLoader) {
                thread.setContextClassLoader(ownerLoader);
            }
            try {
                plan = planner.plan(snapshot.items(), snapshot.constraints(), parallel);
            } finally {
                thread.setContextClassLoader(previousLoader);
            }
        } catch (RuntimeException | Error failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                "texture-atlas",
                "Texture-atlas layout planner failed safely",
                failure
            );
            return false;
        }
        if (plan == null) {
            dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                "texture-atlas",
                "Texture-atlas layout planner returned no plan; using native packing"
            );
            return false;
        }
        // The commit gate serializes the current-check with revocation: a registration
        // removed, replaced, or owner-dead by the time its plan completes is never
        // allowed to write back a stale result. The gate is never held across plan().
        synchronized (lease.commitGate()) {
            if (!algorithms.isCurrent(lease)) {
                return false;
            }
            final TextureAtlasLayoutApplyResult result;
            try {
                result = layouts.apply(snapshot.target(), plan);
            } catch (RuntimeException | Error failure) {
                dev.turboism.runtime.log.RuntimeDiagnostics.error(
                    "texture-atlas",
                    "Texture-atlas layout apply failed safely",
                    failure
                );
                return false;
            }
            return result != null && result.status().isPresent();
        }
    }
}
