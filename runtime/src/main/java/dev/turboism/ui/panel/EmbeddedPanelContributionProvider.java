package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reversible owner-scoped embedded-panel provider. */
public final class EmbeddedPanelContributionProvider implements EditorUiContributionProvider {

    private final EditorUiProviderAdmission admission;
    private final EmbeddedPanelHostOperations host;
    private final RuntimeEmbeddedPanelActivationCoordinator activationCoordinator;

    public EmbeddedPanelContributionProvider(
        final EditorUiProviderAdmission admission,
        final EmbeddedPanelHostOperations host,
        final RuntimeEmbeddedPanelActivationCoordinator activationCoordinator
    ) {
        this.admission = Objects.requireNonNull(admission, "admission");
        if (admission.family() != EditorUiFamily.PANEL) {
            throw new IllegalArgumentException("embedded-panel provider requires PANEL admission");
        }
        this.host = Objects.requireNonNull(host, "host");
        this.activationCoordinator = Objects.requireNonNull(
            activationCoordinator,
            "activationCoordinator"
        );
    }

    @Override
    public EditorUiFamily family() {
        return EditorUiFamily.PANEL;
    }

    @Override
    public EditorUiProviderAdmission admission() {
        return admission;
    }

    @Override
    public Registration apply(
        final long hostGeneration,
        final List<EditorUiContribution<?>> contributions
    ) {
        if (!admission.isAdmittedTo(hostGeneration)) {
            throw new IllegalStateException("embedded-panel provider admission is stale");
        }
        final List<EmbeddedPanelContributionDescriptor> descriptors = contributions.stream()
            .map(EmbeddedPanelContributionDescriptor::from)
            .toList();
        final Reconciler reconciler = new Reconciler(descriptors);
        final List<Registration> registrations = new ArrayList<>();
        try {
            reconciler.reconcile();
            registrations.add(reconciler);
            registrations.add(activationCoordinator.bind(hostGeneration, reconciler::activate));
            registrations.add(host.onRebuild(reconciler::reconcile));
            return () -> closeAll(registrations);
        } catch (RuntimeException | Error failure) {
            closeAllSuppressing(registrations, failure);
            if (registrations.isEmpty()) {
                reconciler.closeSuppressing(failure);
            }
            throw failure;
        }
    }

    private final class Reconciler implements Registration {
        private final List<EmbeddedPanelContributionDescriptor> descriptors;
        private Map<EditorUiContributionIdentity, EmbeddedPanelHostOperations.PanelHandle> panels = Map.of();
        private boolean closed;

        private Reconciler(final List<EmbeddedPanelContributionDescriptor> descriptors) {
            this.descriptors = List.copyOf(descriptors);
        }

        private synchronized void reconcile() {
            if (closed) {
                return;
            }
            closePanels(panels.values().stream().toList());
            panels = Map.of();
            final LinkedHashMap<EditorUiContributionIdentity, EmbeddedPanelHostOperations.PanelHandle> installed =
                new LinkedHashMap<>();
            try {
                for (EmbeddedPanelContributionDescriptor descriptor : descriptors) {
                    final EditorUiContributionIdentity identity = new EditorUiContributionIdentity(
                        descriptor.pluginId(),
                        EditorUiFamily.PANEL,
                        descriptor.contributionId()
                    );
                    installed.put(
                        identity,
                        Objects.requireNonNull(host.addPanel(descriptor), "host.addPanel()")
                    );
                }
            } catch (RuntimeException | Error failure) {
                closePanelsSuppressing(installed.values().stream().toList(), failure);
                throw failure;
            }
            panels = Collections.unmodifiableMap(new LinkedHashMap<>(installed));
        }

        private synchronized void activate(
            final String pluginId,
            final EmbeddedPanelId panelId
        ) {
            if (closed) {
                throw new IllegalStateException("embedded-panel provider is closed");
            }
            final EditorUiContributionIdentity identity = new EditorUiContributionIdentity(
                pluginId,
                EditorUiFamily.PANEL,
                panelId.value()
            );
            final EmbeddedPanelHostOperations.PanelHandle panel = panels.get(identity);
            if (panel == null) {
                throw new IllegalStateException("embedded panel is unavailable for the calling plugin");
            }
            panel.activate();
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            final List<EmbeddedPanelHostOperations.PanelHandle> installed =
                panels.values().stream().toList();
            panels = Map.of();
            closePanels(installed);
        }

        private synchronized void closeSuppressing(final Throwable failure) {
            try {
                close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void closeAll(final List<? extends Registration> registrations) {
        RuntimeException first = null;
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (RuntimeException failure) {
                if (first == null) {
                    first = failure;
                } else {
                    first.addSuppressed(failure);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static void closeAllSuppressing(
        final List<? extends Registration> registrations,
        final Throwable failure
    ) {
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void closePanels(
        final List<? extends EmbeddedPanelHostOperations.PanelHandle> panels
    ) {
        closeAll(panels);
    }

    private static void closePanelsSuppressing(
        final List<? extends EmbeddedPanelHostOperations.PanelHandle> panels,
        final Throwable failure
    ) {
        closeAllSuppressing(panels, failure);
    }
}
