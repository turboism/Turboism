package dev.turboism.adapter.host;

import dev.turboism.sdk.cubism.core.CoreCapabilities;
import dev.turboism.sdk.cubism.core.CoreRuntimeInfo;
import dev.turboism.sdk.cubism.core.CoreVersion;
import dev.turboism.sdk.cubism.core.MocData;
import dev.turboism.sdk.cubism.core.MocInfo;
import dev.turboism.sdk.cubism.core.MocInspector;
import dev.turboism.sdk.cubism.core.MocVersion;

import java.util.Objects;

/** Stable generation-bound view over connection-owned Core runtime metadata. */
final class DynamicCoreRuntimeInfo implements CoreRuntimeInfo {

    private static final CoreRuntimeInfo UNAVAILABLE = new CoreRuntimeInfo() {
        @Override public CoreVersion version() { throw unavailable(); }
        @Override public CoreCapabilities capabilities() { throw unavailable(); }
        @Override public MocInspector mocInspector() { throw unavailable(); }
    };

    private final Object lifecycle = new Object();
    private long generation;
    private CoreRuntimeInfo delegate = UNAVAILABLE;

    void connect(final CoreRuntimeInfo runtimeInfo) {
        synchronized (lifecycle) {
            generation++;
            delegate = Objects.requireNonNull(runtimeInfo, "runtimeInfo");
        }
    }

    void deactivate() {
        synchronized (lifecycle) {
            generation++;
            delegate = UNAVAILABLE;
        }
    }

    @Override
    public CoreVersion version() {
        return current().delegate().version();
    }

    @Override
    public CoreCapabilities capabilities() {
        return current().delegate().capabilities();
    }

    @Override
    public MocInspector mocInspector() {
        final Current current = current();
        final MocInspector inspector = current.delegate().mocInspector();
        return new MocInspector() {
            @Override public MocVersion latestVersion() {
                requireCurrent(current.generation());
                return inspector.latestVersion();
            }

            @Override public MocInfo inspect(final MocData data) {
                requireCurrent(current.generation());
                return inspector.inspect(data);
            }
        };
    }

    static CoreRuntimeInfo unavailableRuntime() {
        return UNAVAILABLE;
    }

    private Current current() {
        synchronized (lifecycle) {
            if (delegate == UNAVAILABLE) throw unavailable();
            return new Current(generation, delegate);
        }
    }

    private void requireCurrent(final long expectedGeneration) {
        synchronized (lifecycle) {
            if (delegate == UNAVAILABLE || generation != expectedGeneration) {
                throw new IllegalStateException("Core runtime reference is stale.");
            }
        }
    }

    private static UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException("Core runtime metadata is unavailable.");
    }

    private record Current(long generation, CoreRuntimeInfo delegate) { }
}
