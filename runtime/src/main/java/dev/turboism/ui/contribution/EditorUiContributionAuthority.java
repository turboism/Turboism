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

    public Registration contribute(final EditorUiContribution<?> contribution) {
        final EditorUiContribution<?> requested = Objects.requireNonNull(contribution, "contribution");
        final StoredContribution stored = new StoredContribution(requested);
        synchronized (monitor) {
            requireOpen();
            final StoredContribution previous = contributions.putIfAbsent(requested.identity(), stored);
            if (previous != null) {
                throw new IllegalStateException(
                    "Editor UI contribution is already registered: " + requested.identity()
                );
            }
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
