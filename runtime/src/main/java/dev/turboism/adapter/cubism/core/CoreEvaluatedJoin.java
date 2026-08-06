package dev.turboism.adapter.cubism.core;

import dev.turboism.sdk.cubism.core.MocConsistency;
import dev.turboism.sdk.cubism.core.MocInfo;
import dev.turboism.sdk.cubism.core.MocVersion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Generation-bound Core evaluated snapshot join for Editor-backed object views.
 *
 * <p>One bulk structural snapshot (native arrays copied whole through the verified
 * Core call-site table) is taken per Editor binding identity, then pinned. Every
 * later access re-validates the Core generation in O(1) and fails closed on any
 * mismatch; stale data is never returned and snapshots are never silently
 * re-fetched for a pinned identity. Lookups join by stable drawable ID through a
 * prebuilt map, never by per-object reflection.</p>
 *
 * <p>The join never writes to Core and never closes or mutates the borrowed
 * model.</p>
 */
public final class CoreEvaluatedJoin {

    private final ActiveCoreModelSource source;
    private final CorePublicApiProvider provider;
    private final CoreStructuralTracer tracer;

    private final Object cacheLock = new Object();
    private String cachedIdentity;
    private CoreStructuralSnapshot cachedSnapshot;
    private Map<String, CoreDrawableDefinition> cachedDrawablesById;

    CoreEvaluatedJoin(
        final ActiveCoreModelSource source,
        final CorePublicApiProvider provider,
        final CoreStructuralTracer tracer
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    /**
     * Returns the evaluated snapshot pinned to one Editor binding identity.
     *
     * <p>First access for an identity takes the one bulk snapshot; later accesses
     * are O(1) generation validations. A Core generation change for the same
     * identity fails closed instead of silently re-snapshotting.</p>
     */
    public CoreEvaluatedSnapshot evaluated(final String identity) {
        Objects.requireNonNull(identity, "identity");
        synchronized (cacheLock) {
            if (cachedIdentity != null && cachedIdentity.equals(identity)) {
                requireCurrentCoreGeneration(cachedSnapshot.generation());
                return new CoreEvaluatedSnapshot(
                    cachedSnapshot.generation(),
                    cachedDrawablesById
                );
            }
            final CoreStructuralSnapshot snapshot = traceSnapshot();
            cachedIdentity = identity;
            cachedSnapshot = snapshot;
            cachedDrawablesById = drawablesById(snapshot.drawables());
            return new CoreEvaluatedSnapshot(snapshot.generation(), cachedDrawablesById);
        }
    }

    /**
     * Returns MOC metadata of the active borrowed Core model.
     *
     * <p>Only profiles whose exact selectors are admitted can expose the MOC
     * version (5.3.02); other profiles fail closed. Byte-level consistency is
     * not reachable from a borrowed model and stays {@link MocConsistency#UNKNOWN}.</p>
     */
    public MocInfo mocInfo() {
        final CoreModelAcquisition acquisition = source.acquire(provider);
        if (!acquisition.isAcquired()) {
            throw failClosed(acquisition.failure().orElseThrow());
        }
        try (CoreModelLease lease = acquisition.lease().orElseThrow()) {
            final CoreProviderResult<Integer> result;
            try {
                result = lease.readForProvider(provider::mocVersionOfModel);
            } catch (CoreModelLeaseException exception) {
                throw failClosed(exception.failure());
            }
            if (!result.isSuccess()) {
                throw failClosed(result.failure().orElseThrow());
            }
            return new MocInfo(
                mocVersion(result.value().orElseThrow()),
                MocConsistency.UNKNOWN
            );
        }
    }

    private CoreStructuralSnapshot traceSnapshot() {
        final CoreModelAcquisition acquisition = source.acquire(provider);
        if (!acquisition.isAcquired()) {
            throw failClosed(acquisition.failure().orElseThrow());
        }
        try (CoreModelLease lease = acquisition.lease().orElseThrow()) {
            final CoreProviderResult<CoreStructuralSnapshot> result = tracer.trace(lease);
            if (!result.isSuccess()) {
                throw failClosed(result.failure().orElseThrow());
            }
            return result.value().orElseThrow();
        }
    }

    private void requireCurrentCoreGeneration(final long expectedGeneration) {
        if (source.currentGeneration() != expectedGeneration) {
            throw new IllegalStateException(
                "Core evaluated snapshot is stale for the active model generation: expected "
                    + expectedGeneration + " but the Core source is at "
                    + source.currentGeneration() + "."
            );
        }
    }

    private static Map<String, CoreDrawableDefinition> drawablesById(
        final List<CoreDrawableDefinition> drawables
    ) {
        final Map<String, CoreDrawableDefinition> byId = new LinkedHashMap<>();
        for (CoreDrawableDefinition drawable : drawables) {
            final CoreDrawableDefinition previous = byId.put(drawable.id(), drawable);
            if (previous != null) {
                throw new IllegalStateException(
                    "Core evaluated snapshot contains a duplicate drawable id: "
                        + drawable.id()
                );
            }
        }
        return Map.copyOf(byId);
    }

    private static MocVersion mocVersion(final int coreConstant) {
        return switch (coreConstant) {
            case 0 -> MocVersion.UNKNOWN;
            case 1 -> MocVersion.V3_0;
            case 2 -> MocVersion.V3_3;
            case 3 -> MocVersion.V4_0;
            case 4 -> MocVersion.V4_2;
            case 5 -> MocVersion.V5_0;
            case 6 -> MocVersion.V5_3;
            default -> throw new IllegalStateException(
                "Core reported an unsupported MOC version constant: " + coreConstant
            );
        };
    }

    private static IllegalStateException failClosed(final CoreModelFailure failure) {
        return failClosed(failure.code().name(), failure.message());
    }

    private static IllegalStateException failClosed(final CoreProviderFailure failure) {
        return failClosed(failure.code().name(), failure.message());
    }

    private static IllegalStateException failClosed(final String code, final String message) {
        final String prefix = switch (code) {
            case "STALE_GENERATION", "LEASE_CLOSED", "TRANSITION_IN_PROGRESS", "SOURCE_CLOSED" ->
                "Core evaluated data is unavailable for the active generation: ";
            default -> "Core evaluated data is unavailable: ";
        };
        return new IllegalStateException(prefix + message);
    }

    /** Adapter-owned, generation-pinned evaluated snapshot projection. */
    public record CoreEvaluatedSnapshot(
        long generation,
        Map<String, CoreDrawableDefinition> drawablesById
    ) {

        public CoreEvaluatedSnapshot {
            if (generation < 0) {
                throw new IllegalArgumentException("generation must not be negative");
            }
            drawablesById = Map.copyOf(Objects.requireNonNull(drawablesById, "drawablesById"));
        }

        /** Joins one drawable by stable ID; fails closed when absent. */
        public CoreDrawableDefinition drawable(final String id) {
            final CoreDrawableDefinition drawable = drawablesById.get(id);
            if (drawable == null) {
                throw new NoSuchElementException("Cubism Drawable is absent: " + id);
            }
            return drawable;
        }
    }
}
