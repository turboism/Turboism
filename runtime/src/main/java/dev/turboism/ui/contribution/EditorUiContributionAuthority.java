package dev.turboism.ui.contribution;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.host.EditorUiHostLifecycle;
import dev.turboism.ui.host.EditorUiHostSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Single runtime authority for persistent plugin-owned Editor UI contributions.
 *
 * <p>The authority owns logical contribution state and family reconciliation. It never receives
 * or exposes native Editor objects.</p>
 */
public final class EditorUiContributionAuthority implements AutoCloseable {

    private static final Comparator<EditorUiContribution<?>> CONTRIBUTION_ORDER = Comparator
        .comparingInt((EditorUiContribution<?> contribution) -> contribution.order())
        .thenComparing(EditorUiContribution::identity);

    private final Object monitor = new Object();
    private final EditorUiHostLifecycle hostLifecycle;
    private final Map<EditorUiContributionIdentity, StoredContribution> contributions = new HashMap<>();
    private final Map<EditorUiFamily, EditorUiContributionProvider> providers = new EnumMap<>(EditorUiFamily.class);
    private final Map<EditorUiFamily, Registration> nativeRegistrations = new EnumMap<>(EditorUiFamily.class);
    private final Map<EditorUiFamily, EditorUiContributionFailure> failures = new EnumMap<>(EditorUiFamily.class);
    private final Registration lifecycleRegistration;
    private boolean closed;

    public EditorUiContributionAuthority(final EditorUiHostLifecycle hostLifecycle) {
        this.hostLifecycle = Objects.requireNonNull(hostLifecycle, "hostLifecycle");
        this.lifecycleRegistration = hostLifecycle.subscribe(this::onHostChanged);
    }

    /**
     * Registers a plugin-owned contribution and reconciles its family against the host.
     *
     * <p>A contribution with an identity that is already present replaces the previous one, which
     * the provider's incremental reconcile path applies in place — the host palette and any
     * floating window are not rebuilt. If reconciliation fails, the contribution is rolled back out
     * of the authority before the failure propagates, so a rejected contribution leaves no state
     * behind.
     *
     * @param contribution the logical contribution; never a native Editor object
     * @return a registration whose {@code close()} removes the contribution and reconciles again;
     *     closing twice is a no-op
     * @throws NullPointerException if {@code contribution} is {@code null}
     * @throws IllegalStateException if the authority is closed
     */
    public Registration contribute(final EditorUiContribution<?> contribution) {
        final EditorUiContribution<?> requested = Objects.requireNonNull(contribution, "contribution");
        final StoredContribution stored = new StoredContribution(requested);
        synchronized (monitor) {
            requireOpen();
            // Same identity replaces the previous contribution (content refresh).
            // The provider's incremental reconcile path then updates the attached
            // native content in place, so the host palette and its floating
            // window are never rebuilt and never drop back to the dock.
            contributions.put(requested.identity(), stored);
        }
        try {
            reconcile(requested.identity().family());
        } catch (RuntimeException | Error failure) {
            synchronized (monitor) {
                contributions.remove(requested.identity(), stored);
            }
            throw failure;
        }
        return new ContributionRegistration(requested.identity(), stored);
    }

    /**
     * Installs the single provider responsible for realizing one UI family natively, then
     * reconciles that family.
     *
     * <p>At most one provider may be installed per family; re-installing the identical instance is
     * accepted, a different one is rejected. The provider's self-reported availability must be
     * derived from its admission, and the admission's family must match the provider's — the
     * authority refuses to trust a provider that disagrees with its own admission.
     *
     * @param provider the family's provider
     * @throws NullPointerException if {@code provider} or its admission is {@code null}
     * @throws IllegalArgumentException if the admission family or availability is inconsistent
     * @throws IllegalStateException if the authority is closed, or a different provider is already
     *     installed for that family
     */
    public void installProvider(final EditorUiContributionProvider provider) {
        final EditorUiContributionProvider requested = Objects.requireNonNull(provider, "provider");
        final EditorUiProviderAdmission admission = Objects.requireNonNull(
            requested.admission(),
            "provider.admission()"
        );
        if (admission.family() != requested.family()) {
            throw new IllegalArgumentException("Editor UI provider admission family does not match");
        }
        if (requested.isAvailable() != admission.isAdmitted()) {
            throw new IllegalArgumentException(
                "Editor UI provider availability must be derived from admission"
            );
        }
        synchronized (monitor) {
            requireOpen();
            final EditorUiContributionProvider previous = providers.putIfAbsent(
                requested.family(),
                requested
            );
            if (previous != null && previous != requested) {
                throw new IllegalStateException(
                    "Editor UI provider is already installed for " + requested.family()
                );
            }
        }
        reconcile(requested.family());
    }

    /**
     * Uninstalls a provider, tears down its native registration, and records the family as
     * unavailable.
     *
     * <p>Removal is identity-based: passing a provider that is not the installed one for its family
     * does nothing. Logical contributions are kept — they simply stop being realized until a
     * provider is installed again.
     *
     * @param provider the provider to remove
     * @throws NullPointerException if {@code provider} is {@code null}
     */
    public void removeProvider(final EditorUiContributionProvider provider) {
        final EditorUiContributionProvider requested = Objects.requireNonNull(provider, "provider");
        final boolean removed;
        synchronized (monitor) {
            removed = providers.remove(requested.family(), requested);
        }
        if (removed) {
            closeNative(requested.family());
            recordFailure(new EditorUiContributionFailure(
                EditorUiContributionFailure.Code.MAPPING_NOT_VERIFIED,
                requested.family(),
                "Editor UI contribution provider is unavailable."
            ));
        }
    }

    /**
     * @param family the UI family to list
     * @return an immutable snapshot of that family's contributions in reconcile order — ascending
     *     {@code order()}, ties broken by identity — taken under the authority's lock, so it does
     *     not track later changes
     * @throws NullPointerException if {@code family} is {@code null}
     */
    public List<EditorUiContribution<?>> contributions(final EditorUiFamily family) {
        Objects.requireNonNull(family, "family");
        synchronized (monitor) {
            return contributions.values().stream()
                .map(StoredContribution::contribution)
                .filter(value -> value.identity().family() == family)
                .sorted(CONTRIBUTION_ORDER)
                .toList();
        }
    }

    /**
     * @param family the UI family to inspect
     * @return the most recent reconciliation failure for that family, or empty when the last
     *     reconcile succeeded — a successful reconcile clears the record, and closing the authority
     *     discards all of them
     * @throws NullPointerException if {@code family} is {@code null}
     */
    public Optional<EditorUiContributionFailure> lastFailure(final EditorUiFamily family) {
        synchronized (monitor) {
            return Optional.ofNullable(failures.get(Objects.requireNonNull(family, "family")));
        }
    }

    @Override
    public void close() {
        final List<Registration> registrations;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            registrations = new ArrayList<>(nativeRegistrations.values());
            nativeRegistrations.clear();
            providers.clear();
            contributions.clear();
            failures.clear();
        }
        RuntimeException first = closeAll(registrations);
        try {
            lifecycleRegistration.close();
        } catch (RuntimeException exception) {
            first = append(first, exception);
        }
        if (first != null) {
            throw first;
        }
    }

    private void onHostChanged(final EditorUiHostSnapshot snapshot) {
        final List<EditorUiFamily> families;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            families = new ArrayList<>(providers.keySet());
        }
        RuntimeException first = null;
        for (EditorUiFamily family : families) {
            try {
                if (snapshot.isReady(family)) {
                    reconcile(family);
                } else {
                    closeNative(family);
                }
            } catch (RuntimeException exception) {
                first = append(first, exception);
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private void reconcile(final EditorUiFamily family) {
        final EditorUiContributionProvider provider;
        final EditorUiHostSnapshot snapshot;
        final List<EditorUiContribution<?>> familyContributions;
        final Registration existing;
        synchronized (monitor) {
            requireOpen();
            provider = providers.get(family);
            snapshot = hostLifecycle.snapshot();
            familyContributions = contributions.values().stream()
                .map(StoredContribution::contribution)
                .filter(value -> value.identity().family() == family)
                .sorted(CONTRIBUTION_ORDER)
                .toList();
            existing = nativeRegistrations.remove(family);
        }
        if (provider == null) {
            closeNativeRegistration(family, existing);
            return;
        }
        final EditorUiProviderAdmission admission = Objects.requireNonNull(
            provider.admission(),
            "provider.admission()"
        );
        if (admission.family() != family) {
            closeNativeRegistration(family, existing);
            recordFailure(new EditorUiContributionFailure(
                EditorUiContributionFailure.Code.PROVIDER_FAILED,
                family,
                "Editor UI provider admission family does not match."
            ));
            return;
        }
        if (!admission.isAdmitted()) {
            closeNativeRegistration(family, existing);
            recordFailure(new EditorUiContributionFailure(
                admission.failureCode().orElse(EditorUiContributionFailure.Code.MAPPING_NOT_VERIFIED),
                family,
                "Editor UI contribution provider is unavailable."
            ));
            return;
        }
        if (!snapshot.isReady(family)) {
            closeNativeRegistration(family, existing);
            return;
        }
        if (!admission.isAdmittedTo(snapshot.generation())) {
            closeNativeRegistration(family, existing);
            recordFailure(new EditorUiContributionFailure(
                EditorUiContributionFailure.Code.MAPPING_NOT_VERIFIED,
                family,
                "Editor UI provider admission is stale for the current host generation."
            ));
            return;
        }
        final Registration nativeRegistration;
        try {
            if (provider.supportsIncrementalReconcile()) {
                nativeRegistration = provider.reconcile(
                    snapshot.generation(),
                    familyContributions,
                    existing
                );
            } else {
                closeNativeRegistration(family, existing);
                nativeRegistration = familyContributions.isEmpty()
                    ? null
                    : Objects.requireNonNull(
                        provider.apply(snapshot.generation(), familyContributions),
                        "provider.apply()"
                    );
            }
        } catch (RuntimeException | Error failure) {
            recordFailure(new EditorUiContributionFailure(
                EditorUiContributionFailure.Code.PROVIDER_FAILED,
                family,
                "Editor UI contribution provider failed safely."
            ));
            throw failure;
        }
        synchronized (monitor) {
            if (closed
                || providers.get(family) != provider
                || !hostLifecycle.snapshot().equals(snapshot)) {
                if (nativeRegistration != null) {
                    nativeRegistration.close();
                }
                return;
            }
            if (nativeRegistration != null) {
                nativeRegistrations.put(family, nativeRegistration);
            }
            failures.remove(family);
        }
    }

    private void closeNative(final EditorUiFamily family) {
        final Registration registration;
        synchronized (monitor) {
            registration = nativeRegistrations.remove(family);
        }
        if (registration == null) {
            return;
        }
        try {
            registration.close();
        } catch (RuntimeException | Error failure) {
            recordFailure(new EditorUiContributionFailure(
                EditorUiContributionFailure.Code.PROVIDER_CLEANUP_FAILED,
                family,
                "Editor UI contribution cleanup failed safely."
            ));
            throw failure;
        }
    }

    private void closeNativeRegistration(
        final EditorUiFamily family,
        final Registration registration
    ) {
        if (registration == null) {
            return;
        }
        try {
            registration.close();
        } catch (RuntimeException | Error failure) {
            recordFailure(new EditorUiContributionFailure(
                EditorUiContributionFailure.Code.PROVIDER_CLEANUP_FAILED,
                family,
                "Editor UI contribution cleanup failed safely."
            ));
            throw failure;
        }
    }

    private void recordFailure(final EditorUiContributionFailure failure) {
        synchronized (monitor) {
            if (!closed) {
                failures.put(failure.family(), failure);
            }
        }
    }

    private void clearFailure(final EditorUiFamily family) {
        synchronized (monitor) {
            failures.remove(family);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Editor UI contribution authority is closed");
        }
    }

    private static RuntimeException closeAll(final List<Registration> registrations) {
        RuntimeException first = null;
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (RuntimeException exception) {
                first = append(first, exception);
            }
        }
        return first;
    }

    private static RuntimeException append(
        final RuntimeException first,
        final RuntimeException next
    ) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private final class ContributionRegistration implements Registration {
        private final EditorUiContributionIdentity identity;
        private final StoredContribution stored;
        private boolean closed;

        private ContributionRegistration(
            final EditorUiContributionIdentity identity,
            final StoredContribution stored
        ) {
            this.identity = identity;
            this.stored = stored;
        }

        @Override
        public void close() {
            synchronized (monitor) {
                if (closed) {
                    return;
                }
                closed = true;
                contributions.remove(identity, stored);
            }
            reconcile(identity.family());
        }
    }

    private record StoredContribution(EditorUiContribution<?> contribution) {
        private StoredContribution {
            contribution = Objects.requireNonNull(contribution, "contribution");
        }
    }
}
