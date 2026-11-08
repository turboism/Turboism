package dev.turboism.automesh;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.plugin.Registration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Session-owned catalog of auto-mesh algorithms: the Editor's own generator plus every live
 * plugin registration, and the algorithm the native entry currently runs.
 *
 * <p>One catalog serves the whole Editor session, while each plugin generation gets its own
 * {@link RuntimeAutoMeshAlgorithmRegistry} view. That split is what makes the session selection
 * survive a plugin reload: the selection is stored here as text and resolves to the Editor's
 * generator while no live registration matches it.</p>
 *
 * <p>Every registration carries its owner's liveness probe. A probe that reports inactive — or
 * throws — makes the entry invisible to {@link #algorithms()} and {@link #find(String)} even
 * before the owning registry closes, so a fenced plugin generation is never listed or dispatched.
 * Probes run under the catalog monitor and must therefore be a plain flag read with no callback
 * into this catalog.</p>
 */
public final class AutoMeshAlgorithmCatalog implements AutoMeshSessionAlgorithms {

    private static final String NATIVE_DISPLAY_NAME = "Native (Cubism)";

    /**
     * Placeholder for the host's own generator. The native selector resolves the localized text by
     * id when it is built, so this label never reaches a user interface on its own.
     */
    private static final String NATIVE_DESCRIPTION = "The Editor's built-in mesh generator.";

    private final AutoMeshAlgorithm nativeAlgorithm;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private String selectedAlgorithmId;

    public AutoMeshAlgorithmCatalog() {
        this.nativeAlgorithm = new AutoMeshAlgorithm(
                AutoMeshAlgorithm.NATIVE_ALGORITHM_ID,
                NATIVE_DISPLAY_NAME,
                NATIVE_DESCRIPTION,
                List.of(),
                List.of(),
                false,
                null);
    }

    /**
     * Registers one algorithm under its owner.
     *
     * @param algorithm the algorithm to publish
     * @param ownerId the plugin that owns the registration
     * @param ownerGeneration the plugin generation that owns the registration
     * @param ownerActive liveness probe of that generation
     * @return an idempotent registration that removes the entry
     * @throws IllegalStateException when the id is already registered by a live entry
     */
    public synchronized Registration register(
            final AutoMeshAlgorithm algorithm,
            final String ownerId,
            final long ownerGeneration,
            final BooleanSupplier ownerActive) {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(ownerActive, "ownerActive");
        final Entry entry = new Entry(algorithm, ownerId, ownerGeneration, ownerActive);
        final Entry existing = liveEntry(algorithm.id());
        if (existing != null) {
            throw new IllegalStateException("auto-mesh algorithm id is already registered: " + algorithm.id());
        }
        entries.put(algorithm.id(), entry);
        return new CatalogRegistration(entry);
    }

    /** Lists the Editor's own generator first, then the live registrations in registration order. */
    public synchronized List<AutoMeshAlgorithm> algorithms() {
        final List<AutoMeshAlgorithm> available = new ArrayList<>();
        available.add(nativeAlgorithm);
        for (final Entry entry : new ArrayList<>(entries.values())) {
            if (evictIfDead(entry)) {
                continue;
            }
            available.add(entry.algorithm);
        }
        return List.copyOf(available);
    }

    /** Resolves one algorithm by id, including the Editor's own generator. */
    @Override
    public synchronized Optional<AutoMeshAlgorithm> find(final String algorithmId) {
        if (algorithmId == null) {
            return Optional.empty();
        }
        if (AutoMeshAlgorithm.NATIVE_ALGORITHM_ID.equals(algorithmId)) {
            return Optional.of(nativeAlgorithm);
        }
        final Entry entry = liveEntry(algorithmId);
        return entry == null ? Optional.empty() : Optional.of(entry.algorithm);
    }

    /** Stores the requested selection; a native value clears it. */
    @Override
    public synchronized void select(final String algorithmId) {
        this.selectedAlgorithmId = normalize(algorithmId);
    }

    /**
     * Reports the algorithm that would run now: the stored selection while a live registration
     * matches it, and empty — the Editor's own generator — otherwise.
     */
    @Override
    public synchronized Optional<String> selectedAlgorithmId() {
        final String stored = selectedAlgorithmId;
        if (stored == null) {
            return Optional.empty();
        }
        return find(stored).map(AutoMeshAlgorithm::id);
    }

    /** Drops every registration a generation left behind, including ones its registry lost track of. */
    public synchronized void revokeAll(final String ownerId, final long ownerGeneration) {
        Objects.requireNonNull(ownerId, "ownerId");
        for (final Entry entry : new ArrayList<>(entries.values())) {
            if (entry.ownerId.equals(ownerId) && entry.ownerGeneration == ownerGeneration) {
                remove(entry);
            }
        }
    }

    private static String normalize(final String algorithmId) {
        if (algorithmId == null || algorithmId.isBlank()) {
            return null;
        }
        final String candidate = algorithmId.strip();
        return AutoMeshAlgorithm.NATIVE_ALGORITHM_ID.equals(candidate) ? null : candidate;
    }

    private Entry liveEntry(final String algorithmId) {
        final Entry entry = entries.get(algorithmId);
        if (entry == null) {
            return null;
        }
        if (evictIfDead(entry)) {
            return null;
        }
        return entry;
    }

    private boolean evictIfDead(final Entry entry) {
        if (entry.live()) {
            return false;
        }
        remove(entry);
        return true;
    }

    private void remove(final Entry entry) {
        entry.revoked.set(true);
        entries.remove(entry.algorithm.id(), entry);
    }

    /** One registered generation: the published algorithm, its owner, and its liveness probe. */
    private final class CatalogRegistration implements Registration {

        private final Entry entry;
        private final AtomicBoolean closed = new AtomicBoolean();

        private CatalogRegistration(final Entry entry) {
            this.entry = entry;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (AutoMeshAlgorithmCatalog.this) {
                remove(entry);
            }
        }
    }

    private static final class Entry {

        private final AutoMeshAlgorithm algorithm;
        private final String ownerId;
        private final long ownerGeneration;
        private final BooleanSupplier ownerActive;
        private final AtomicBoolean revoked = new AtomicBoolean();

        private Entry(
                final AutoMeshAlgorithm algorithm,
                final String ownerId,
                final long ownerGeneration,
                final BooleanSupplier ownerActive) {
            this.algorithm = algorithm;
            this.ownerId = ownerId;
            this.ownerGeneration = ownerGeneration;
            this.ownerActive = ownerActive;
        }

        private boolean live() {
            if (revoked.get()) {
                return false;
            }
            try {
                return ownerActive.getAsBoolean();
            } catch (RuntimeException | Error failure) {
                FatalErrors.rethrowIfFatal(failure);
                return false;
            }
        }
    }
}
