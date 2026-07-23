package dev.turboism.ui.provider;

import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Installs one connection's independently admitted Editor UI providers. */
public final class EditorUiProviderInstaller {

    private EditorUiProviderInstaller() {
    }

    public static Installation install(
        final long hostGeneration,
        final EditorUiContributionAuthority authority,
        final List<? extends EditorUiContributionProvider> providers
    ) {
        if (hostGeneration <= 0) {
            throw new IllegalArgumentException("hostGeneration must be positive");
        }
        final EditorUiContributionAuthority target = Objects.requireNonNull(authority, "authority");
        final List<? extends EditorUiContributionProvider> requested = List.copyOf(
            Objects.requireNonNull(providers, "providers")
        );
        final EnumSet<EditorUiFamily> seenFamilies = EnumSet.noneOf(EditorUiFamily.class);
        final List<EditorUiContributionProvider> installed = new ArrayList<>();
        final EnumSet<EditorUiFamily> readyFamilies = EnumSet.noneOf(EditorUiFamily.class);
        try {
            for (EditorUiContributionProvider provider : requested) {
                final EditorUiContributionProvider candidate = Objects.requireNonNull(
                    provider,
                    "provider"
                );
                final EditorUiProviderAdmission admission = Objects.requireNonNull(
                    candidate.admission(),
                    "provider.admission()"
                );
                if (admission.family() != candidate.family()) {
                    throw new IllegalArgumentException(
                        "Editor UI provider admission family does not match"
                    );
                }
                if (!seenFamilies.add(candidate.family())) {
                    throw new IllegalArgumentException(
                        "Duplicate Editor UI provider family: " + candidate.family()
                    );
                }
                if (!admission.isAdmittedTo(hostGeneration)) {
                    continue;
                }
                target.installProvider(candidate);
                installed.add(candidate);
                readyFamilies.add(candidate.family());
            }
        } catch (RuntimeException | Error failure) {
            closeInstalled(target, installed, failure);
            throw failure;
        }
        return new Installation(target, installed, readyFamilies);
    }

    private static void closeInstalled(
        final EditorUiContributionAuthority authority,
        final List<EditorUiContributionProvider> installed,
        final Throwable failure
    ) {
        for (int index = installed.size() - 1; index >= 0; index--) {
            try {
                authority.removeProvider(installed.get(index));
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
    }

    /** Connection-owned provider registration, closed before the connection itself. */
    public static final class Installation implements AutoCloseable {
        private final EditorUiContributionAuthority authority;
        private final List<EditorUiContributionProvider> installed;
        private final Set<EditorUiFamily> readyFamilies;
        private boolean closed;

        private Installation(
            final EditorUiContributionAuthority authority,
            final List<EditorUiContributionProvider> installed,
            final Set<EditorUiFamily> readyFamilies
        ) {
            this.authority = authority;
            this.installed = List.copyOf(installed);
            this.readyFamilies = readyFamilies.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(readyFamilies));
        }

        public Set<EditorUiFamily> readyFamilies() {
            return readyFamilies;
        }

        @Override
        public void close() {
            final List<EditorUiContributionProvider> toRemove;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                toRemove = installed;
            }
            RuntimeException first = null;
            for (int index = toRemove.size() - 1; index >= 0; index--) {
                try {
                    authority.removeProvider(toRemove.get(index));
                } catch (RuntimeException exception) {
                    if (first == null) {
                        first = exception;
                    } else {
                        first.addSuppressed(exception);
                    }
                }
            }
            if (first != null) {
                throw first;
            }
        }
    }
}
