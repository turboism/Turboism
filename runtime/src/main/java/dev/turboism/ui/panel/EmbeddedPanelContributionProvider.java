package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.ui.action.EditorUiActionRouter;
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
    private final EditorUiActionRouter actionRouter;
    private final PanelTabMenuCoordinator panelTabMenus;
    private final RuntimeDockMaintenanceCoordinator dockMaintenance;

    public EmbeddedPanelContributionProvider(
        final EditorUiProviderAdmission admission,
        final EmbeddedPanelHostOperations host,
        final RuntimeEmbeddedPanelActivationCoordinator activationCoordinator,
        final EditorUiActionRouter actionRouter
    ) {
        this(
            admission, host, activationCoordinator, actionRouter,
            new PanelTabMenuCoordinator(), new RuntimeDockMaintenanceCoordinator()
        );
    }

    public EmbeddedPanelContributionProvider(
        final EditorUiProviderAdmission admission,
        final EmbeddedPanelHostOperations host,
        final RuntimeEmbeddedPanelActivationCoordinator activationCoordinator,
        final EditorUiActionRouter actionRouter,
        final PanelTabMenuCoordinator panelTabMenus
    ) {
        this(
            admission, host, activationCoordinator, actionRouter,
            panelTabMenus, new RuntimeDockMaintenanceCoordinator()
        );
    }

    public EmbeddedPanelContributionProvider(
        final EditorUiProviderAdmission admission,
        final EmbeddedPanelHostOperations host,
        final RuntimeEmbeddedPanelActivationCoordinator activationCoordinator,
        final EditorUiActionRouter actionRouter,
        final PanelTabMenuCoordinator panelTabMenus,
        final RuntimeDockMaintenanceCoordinator dockMaintenance
    ) {
        this.admission = Objects.requireNonNull(admission, "admission");
        if (admission.family() != EditorUiFamily.PANEL) {
            throw new IllegalArgumentException("embedded-panel provider requires PANEL admission");
        }
        this.host = Objects.requireNonNull(host, "host");
        this.activationCoordinator = Objects.requireNonNull(activationCoordinator, "activationCoordinator");
        this.actionRouter = Objects.requireNonNull(actionRouter, "actionRouter");
        this.panelTabMenus = Objects.requireNonNull(panelTabMenus, "panelTabMenus");
        this.dockMaintenance = Objects.requireNonNull(dockMaintenance, "dockMaintenance");
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
        final Session session = new Session(hostGeneration);
        try {
            session.reconcile(descriptors(contributions));
            session.bind();
            return session;
        } catch (RuntimeException | Error failure) {
            session.closeSuppressing(failure);
            throw failure;
        }
    }

    @Override
    public boolean supportsIncrementalReconcile() {
        return true;
    }

    @Override
    public Registration reconcile(
        final long hostGeneration,
        final List<EditorUiContribution<?>> contributions,
        final Registration existing
    ) {
        if (!admission.isAdmittedTo(hostGeneration)) {
            throw new IllegalStateException("embedded-panel provider admission is stale");
        }
        if (existing instanceof Session session && session.hostGeneration == hostGeneration) {
            session.reconcile(descriptors(contributions));
            return session;
        }
        if (existing != null) {
            existing.close();
        }
        return apply(hostGeneration, contributions);
    }

    private static List<EmbeddedPanelContributionDescriptor> descriptors(
        final List<EditorUiContribution<?>> contributions
    ) {
        return contributions.stream()
            .map(EmbeddedPanelContributionDescriptor::from)
            .toList();
    }

    private final class Session implements Registration,
        dev.turboism.ui.panel.RuntimeEmbeddedPanelActivationCoordinator.ActivationTarget {
        private final long hostGeneration;
        private Map<EditorUiContributionIdentity, InstalledPanel> panels = Map.of();
        private List<Registration> bindings = List.of();
        private Thread startupDockCleanup;
        private boolean closed;

        private Session(final long hostGeneration) {
            this.hostGeneration = hostGeneration;
        }

        private synchronized void bind() {
            if (closed) {
                throw new IllegalStateException("embedded-panel provider is closed");
            }
            if (!bindings.isEmpty()) {
                return;
            }
            final List<Registration> installedBindings = new ArrayList<>();
            RuntimeDockMaintenanceCoordinator.EmptyDockCleaner startupDockCleaner = null;
            try {
                installedBindings.add(activationCoordinator.bind(hostGeneration, this));
                installedBindings.add(host.onRebuild(this::rebuild));
                if (host instanceof VerifiedEmbeddedPanelHostOperations verified) {
                    verified.bindHostGeneration(hostGeneration);
                }
                installedBindings.add(host.bindPanelTabMenus(panelTabMenus));
                if (host instanceof VerifiedEmbeddedPanelHostOperations verified) {
                    final RuntimeDockMaintenanceCoordinator.EmptyDockCleaner cleaner =
                        () -> verified.cleanEmptyDocks(hostGeneration);
                    installedBindings.add(dockMaintenance.bind(hostGeneration, cleaner));
                    startupDockCleaner = cleaner;
                }
                bindings = List.copyOf(installedBindings);
                // The dock tree may not be materialized during bind. Retry in a bounded
                // daemon task; each host operation dispatches synchronously to the EDT.
                if (startupDockCleaner != null) {
                    scheduleStartupDockCleanup(startupDockCleaner);
                }
            } catch (RuntimeException | Error failure) {
                closeAllSuppressing(installedBindings, failure);
                throw failure;
            }
        }

        private void scheduleStartupDockCleanup(
            final RuntimeDockMaintenanceCoordinator.EmptyDockCleaner cleaner
        ) {
            final Thread cleanup = new Thread(() -> {
                for (int attempt = 1; attempt <= 5; attempt++) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    try {
                        cleaner.clean();
                        System.err.println("Turboism startup empty-dock cleanup completed");
                        return;
                    } catch (RuntimeException | Error failure) {
                        System.err.println(
                            "Turboism startup empty-dock cleanup retry " + attempt
                                + " failed safely: " + failure.getClass().getName()
                                + ": " + failure.getMessage()
                        );
                        if (attempt == 5) {
                            break;
                        }
                        try {
                            Thread.sleep(2_000L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                System.err.println("Turboism startup empty-dock cleanup gave up after retries");
            }, "turboism-startup-dock-cleanup");
            cleanup.setDaemon(true);
            startupDockCleanup = cleanup;
            cleanup.start();
        }

        private synchronized void reconcile(
            final List<EmbeddedPanelContributionDescriptor> descriptors
        ) {
            if (closed) {
                throw new IllegalStateException("embedded-panel provider is closed");
            }
            final LinkedHashMap<EditorUiContributionIdentity, EmbeddedPanelContributionDescriptor> desired =
                new LinkedHashMap<>();
            for (EmbeddedPanelContributionDescriptor descriptor : descriptors) {
                desired.put(identity(descriptor), descriptor);
            }

            final LinkedHashMap<EditorUiContributionIdentity, InstalledPanel> next =
                new LinkedHashMap<>();
            final List<EmbeddedPanelHostOperations.PanelHandle> added = new ArrayList<>();
            try {
                for (Map.Entry<EditorUiContributionIdentity, EmbeddedPanelContributionDescriptor> entry
                    : desired.entrySet()) {
                    final InstalledPanel current = panels.get(entry.getKey());
                    if (current != null && current.descriptor().equals(entry.getValue())) {
                        next.put(entry.getKey(), current);
                        continue;
                    }
                    final EmbeddedPanelHostOperations.PanelHandle handle = install(entry.getValue());
                    added.add(handle);
                    next.put(entry.getKey(), new InstalledPanel(entry.getValue(), handle));
                }
            } catch (RuntimeException | Error failure) {
                closePanelsSuppressing(added, failure);
                throw failure;
            }

            final List<EmbeddedPanelHostOperations.PanelHandle> removed = new ArrayList<>();
            for (Map.Entry<EditorUiContributionIdentity, InstalledPanel> entry : panels.entrySet()) {
                if (next.get(entry.getKey()) != entry.getValue()) {
                    removed.add(entry.getValue().handle());
                }
            }
            panels = Collections.unmodifiableMap(next);
            closePanels(removed);
        }

        private EmbeddedPanelHostOperations.PanelHandle install(
            final EmbeddedPanelContributionDescriptor descriptor
        ) {
            return Objects.requireNonNull(
                host.addPanel(
                    descriptor,
                    (actionId, event) -> actionRouter.invoke(
                        descriptor.pluginId(),
                        actionId,
                        event
                    )
                ),
                "host.addPanel()"
            );
        }

        private synchronized void rebuild() {
            if (closed) {
                return;
            }
            final List<EmbeddedPanelContributionDescriptor> descriptors = panels.values().stream()
                .map(InstalledPanel::descriptor)
                .toList();
            final List<EmbeddedPanelHostOperations.PanelHandle> installed = panels.values().stream()
                .map(InstalledPanel::handle)
                .toList();
            panels = Map.of();
            closePanels(installed);
            reconcile(descriptors);
        }

        @Override
        public synchronized void activate(
            final String pluginId,
            final EmbeddedPanelId panelId
        ) {
            if (closed) {
                throw new IllegalStateException("embedded-panel provider is closed");
            }
            final InstalledPanel panel = panels.get(new EditorUiContributionIdentity(
                pluginId,
                EditorUiFamily.PANEL,
                panelId.value()
            ));
            if (panel == null) {
                throw new IllegalStateException("embedded panel is unavailable for the calling plugin");
            }
            panel.handle().activate();
        }

        @Override
        public synchronized void activateFloating(
            final String pluginId,
            final EmbeddedPanelId panelId
        ) {
            if (closed) {
                throw new IllegalStateException("embedded-panel provider is closed");
            }
            final InstalledPanel panel = panels.get(new EditorUiContributionIdentity(
                pluginId,
                EditorUiFamily.PANEL,
                panelId.value()
            ));
            if (panel == null) {
                throw new IllegalStateException("embedded panel is unavailable for the calling plugin");
            }
            panel.handle().activate();
            panel.handle().floatPanel();
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            // Unbind first, then invalidate the generation-bound host before interrupting
            // the retry task. Any already queued EDT cleanup now fails closed.
            final List<Registration> installedBindings = bindings;
            bindings = List.of();
            RuntimeException first = closeAllReturning(installedBindings);
            host.invalidateHost();
            final Thread cleanup = startupDockCleanup;
            startupDockCleanup = null;
            if (cleanup != null) {
                cleanup.interrupt();
            }
            final List<EmbeddedPanelHostOperations.PanelHandle> installedPanels = panels.values().stream()
                .map(InstalledPanel::handle)
                .toList();
            panels = Map.of();
            try {
                closePanels(installedPanels);
            } catch (RuntimeException failure) {
                if (first == null) {
                    first = failure;
                } else {
                    first.addSuppressed(failure);
                }
            }
            if (first != null) {
                throw first;
            }
        }

        private synchronized void closeSuppressing(final Throwable failure) {
            try {
                close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static EditorUiContributionIdentity identity(
        final EmbeddedPanelContributionDescriptor descriptor
    ) {
        return new EditorUiContributionIdentity(
            descriptor.pluginId(),
            EditorUiFamily.PANEL,
            descriptor.contributionId()
        );
    }

    private record InstalledPanel(
        EmbeddedPanelContributionDescriptor descriptor,
        EmbeddedPanelHostOperations.PanelHandle handle
    ) {
        private InstalledPanel {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            handle = Objects.requireNonNull(handle, "handle");
        }
    }

    private static void closeAll(final List<? extends Registration> registrations) {
        final RuntimeException failure = closeAllReturning(registrations);
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException closeAllReturning(
        final List<? extends Registration> registrations
    ) {
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
        return first;
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
