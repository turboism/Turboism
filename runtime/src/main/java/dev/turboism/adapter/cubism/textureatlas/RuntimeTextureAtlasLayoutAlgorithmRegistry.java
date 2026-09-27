package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection;
import dev.turboism.sdk.plugin.Registration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Connection-owned registry of texture-atlas layout algorithms.
 *
 * <p>Registrations may carry an owner lease ({@link #register(TextureAtlasLayoutAlgorithm,
 * BooleanSupplier)}): once the owner reports inactive — for example when the registering
 * plugin's scope is closed — the entry stops resolving through {@link #find}, {@link
 * #algorithms}, and {@link #acquire}, so a disposed plugin generation is never invoked.</p>
 *
 * <p>Concurrency contract: dispatchers take an in-flight lease via {@link #acquire} and
 * release it via {@link #release}; a {@link Registration#close()} first revokes the
 * registration under its commit gate (blocking new dispatches and serializing against a
 * commit already in progress) and then waits uninterruptibly until the in-flight count
 * drains — an interrupt is recorded and restored only after cleanup completes, so an
 * interrupted lifecycle close can never release a plugin's class loader while its planner
 * still runs. The commit gate is always the outermost lock; nothing holding the registry
 * monitor ever acquires it, and it is never held across {@code plan()} — only across the
 * final current-check plus writeback. A planner that closes its own registration from the
 * dispatching thread is rejected explicitly: the registration is revoked and removed, a
 * diagnostic is logged, and {@code close()} throws {@link IllegalStateException} rather
 * than pretending quiescence — its own in-flight slot could never drain while it waited
 * on itself. Scope cleanup is still safe because the registration is already dead.</p>
 */
public final class RuntimeTextureAtlasLayoutAlgorithmRegistry implements TextureAtlasLayoutAlgorithmRegistry {

    private final Map<String, RegisteredAlgorithm> algorithms = new LinkedHashMap<>();
    private volatile TextureAtlasAutoLayoutSelection selection = new TextureAtlasAutoLayoutSelection();

    /** Binds the runtime-owned selection authority this registry exposes to plugins. */
    public synchronized void bindSelection(final TextureAtlasAutoLayoutSelection selection) {
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    /**
     * @return the bound runtime-owned selection authority; runtime-internal, used by
     *     the dialog contribution and dispatcher
     */
    TextureAtlasAutoLayoutSelection selectionState() {
        return selection;
    }

    @Override
    public Registration register(final TextureAtlasLayoutAlgorithm algorithm) {
        return register(algorithm, null);
    }

    /**
     * Registers an algorithm bound to an owner liveness lease.
     *
     * <p>{@code ownerActive} is consulted on every read and dispatch; once it reports
     * inactive (or throws), the entry is treated as absent and evicted. Replacing an
     * existing id revokes the previous registration so it cannot acquire new
     * dispatches or commit a writeback, without waiting for its in-flight calls.
     * The returned registration removes only its own generation.</p>
     *
     * @param ownerActive owner liveness probe, or {@code null} for a registration
     *                    without an owner (never expires by owner)
     */
    public Registration register(
        final TextureAtlasLayoutAlgorithm algorithm,
        final BooleanSupplier ownerActive
    ) {
        Objects.requireNonNull(algorithm, "algorithm");
        // The native choice is reserved: it must always resolve to the host's
        // own packing and can never be claimed by a plugin registration.
        if (TextureAtlasLayoutSelection.NATIVE_ALGORITHM_ID.equals(algorithm.id())) {
            throw new IllegalArgumentException(
                "Texture-atlas algorithm id is reserved for the native layout: "
                    + algorithm.id()
            );
        }
        final RegisteredAlgorithm registration = new RegisteredAlgorithm(
            algorithm,
            ownerActive
        );
        final RegisteredAlgorithm previous;
        synchronized (this) {
            previous = algorithms.put(algorithm.id(), registration);
        }
        if (previous != null) {
            previous.revoke();
        }
        return () -> closeRegistration(registration);
    }

    @Override
    public synchronized Optional<TextureAtlasLayoutAlgorithm> find(final String id) {
        final RegisteredAlgorithm registration = algorithms.get(id);
        if (registration == null) {
            return Optional.empty();
        }
        if (!registration.live()) {
            evict(id, registration);
            return Optional.empty();
        }
        return Optional.of(registration.algorithm());
    }

    @Override
    public synchronized List<TextureAtlasLayoutAlgorithm> algorithms() {
        final List<TextureAtlasLayoutAlgorithm> live = new ArrayList<>();
        final List<Map.Entry<String, RegisteredAlgorithm>> dead = new ArrayList<>();
        for (Map.Entry<String, RegisteredAlgorithm> entry : algorithms.entrySet()) {
            if (entry.getValue().live()) {
                live.add(entry.getValue().algorithm());
            } else {
                dead.add(entry);
            }
        }
        for (Map.Entry<String, RegisteredAlgorithm> entry : dead) {
            evict(entry.getKey(), entry.getValue());
        }
        return List.copyOf(live);
    }

    @Override
    public TextureAtlasLayoutSelection selection() {
        return selection.selection();
    }

    @Override
    public void select(final TextureAtlasLayoutSelection selection) {
        this.selection.select(selection);
    }

    @Override
    public boolean selectIfUnset(final TextureAtlasLayoutSelection selection) {
        return this.selection.selectIfUnset(selection);
    }

    /**
     * Acquires a dispatch lease on the algorithm registered under {@code id}.
     *
     * <p>Returns {@code null} when no live registration exists. A non-null handle must
     * be released with {@link #release(RegisteredAlgorithm)} exactly once after the
     * planner invocation completes; while held, a concurrent {@link Registration#close()}
     * waits for release before completing cleanup. Dispatchers must run their writeback
     * inside {@link RegisteredAlgorithm#commitGate()} guarded by {@link
     * #isCurrent(RegisteredAlgorithm)} so a removed or replaced registration never
     * applies a stale plan.</p>
     */
    synchronized RegisteredAlgorithm acquire(final String id) {
        final RegisteredAlgorithm registration = algorithms.get(id);
        if (registration == null || !registration.live()) {
            if (registration != null) {
                evict(id, registration);
            }
            return null;
        }
        registration.inFlight++;
        registration.inFlightThreads.add(Thread.currentThread());
        return registration;
    }

    /** Releases a dispatch lease previously taken by {@link #acquire(String)}. */
    synchronized void release(final RegisteredAlgorithm registration) {
        registration.inFlight = Math.max(0, registration.inFlight - 1);
        registration.inFlightThreads.remove(Thread.currentThread());
        if (registration.inFlight == 0) {
            notifyAll();
        }
    }

    /**
     * @return whether {@code registration} is still the live entry for its id: not
     *     revoked, owner still active, and not replaced by a newer generation.
     *     Callers serialize the check plus writeback under {@link
     *     RegisteredAlgorithm#commitGate()}.
     */
    synchronized boolean isCurrent(final RegisteredAlgorithm registration) {
        return registration.live()
            && algorithms.get(registration.algorithm().id()) == registration;
    }

    /** Removes a dead entry and revokes it so no new dispatch can acquire it. Caller holds the lock. */
    private void evict(final String id, final RegisteredAlgorithm registration) {
        if (algorithms.remove(id, registration)) {
            registration.revoked = true;
        }
    }

    private void closeRegistration(final RegisteredAlgorithm registration) {
        // Revoke first: serialized with any in-progress commit so no writeback can be
        // admitted once the registration is gone. Runs outside the registry monitor so
        // the commit gate is never taken while holding it.
        registration.revoke();
        boolean interrupted = false;
        synchronized (this) {
            algorithms.remove(registration.algorithm().id(), registration);
            if (registration.inFlightThreads.contains(Thread.currentThread())) {
                // Self-close: the caller holds an in-flight lease on this very
                // registration, so waiting would deadlock and returning normally would
                // falsely report quiescence. The registration is already revoked and
                // removed, so scope cleanup is still safe; reject explicitly instead.
                final IllegalStateException rejection = new IllegalStateException(
                    "A texture-atlas registration cannot be closed from its own dispatch."
                );
                dev.turboism.runtime.log.RuntimeDiagnostics.error(
                    "texture-atlas",
                    "Texture-atlas planner closed its own registration during dispatch; "
                        + "registration revoked without waiting",
                    rejection
                );
                throw rejection;
            }
            while (registration.inFlight > 0) {
                try {
                    wait();
                } catch (InterruptedException interruptedWait) {
                    // Teardown must not report success while a planner still runs; record
                    // the interrupt, keep waiting, and restore the flag only after drain.
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** One registered generation: algorithm, owner lease, commit gate, and in-flight tracking. */
    static final class RegisteredAlgorithm {
        private final TextureAtlasLayoutAlgorithm algorithm;
        private final BooleanSupplier ownerActive;
        private final ClassLoader ownerLoader;
        private final Object commitGate = new Object();
        private volatile boolean revoked;
        private int inFlight;
        private final Set<Thread> inFlightThreads = new HashSet<>();

        private RegisteredAlgorithm(
            final TextureAtlasLayoutAlgorithm algorithm,
            final BooleanSupplier ownerActive
        ) {
            this.algorithm = algorithm;
            this.ownerActive = ownerActive;
            this.ownerLoader = algorithm.planner() == null
                ? null
                : algorithm.planner().getClass().getClassLoader();
        }

        TextureAtlasLayoutAlgorithm algorithm() {
            return algorithm;
        }

        /**
         * @return the class loader that defines the planner implementation; dispatchers
         *     bind it as the thread context class loader around {@code plan} so plugin
         *     resource and {@code ServiceLoader} lookups resolve plugin classes
         */
        ClassLoader ownerLoader() {
            return ownerLoader;
        }

        /**
         * @return the gate serializing the final current-check and plan writeback with
         *     {@link #revoke()}; never held across {@code plan()} itself
         */
        Object commitGate() {
            return commitGate;
        }

        /** Revokes this registration for new dispatches and commits, serialized with commits. */
        void revoke() {
            synchronized (commitGate) {
                revoked = true;
            }
        }

        private boolean live() {
            if (revoked) {
                return false;
            }
            if (ownerActive == null) {
                return true;
            }
            try {
                return ownerActive.getAsBoolean();
            } catch (RuntimeException | Error failure) {
                return false;
            }
        }
    }
}
