package dev.turboism.ui.toolbar;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.ui.action.EditorUiActionRouter;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Reversible main-toolbar provider shared by fake and verified host operations. */
public final class MainToolbarContributionProvider implements EditorUiContributionProvider {

    private final EditorUiProviderAdmission admission;
    private final MainToolbarHostOperations host;
    private final EditorUiActionRouter actionRouter;

    public MainToolbarContributionProvider(
        final EditorUiProviderAdmission admission,
        final MainToolbarHostOperations host,
        final EditorUiActionRouter actionRouter
    ) {
        this.admission = Objects.requireNonNull(admission, "admission");
        if (admission.family() != EditorUiFamily.MAIN_TOOLBAR) {
            throw new IllegalArgumentException("main-toolbar provider requires MAIN_TOOLBAR admission");
        }
        this.host = Objects.requireNonNull(host, "host");
        this.actionRouter = Objects.requireNonNull(actionRouter, "actionRouter");
    }

    @Override
    public EditorUiFamily family() {
        return EditorUiFamily.MAIN_TOOLBAR;
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
            throw new IllegalStateException("main-toolbar provider admission is stale");
        }
        final List<MainToolbarContributionDescriptor> descriptors = contributions.stream()
            .map(MainToolbarContributionDescriptor::from)
            .toList();
        final Reconciler reconciler = new Reconciler(descriptors);
        reconciler.reconcile();
        final Registration rebuild = host.onRebuild(reconciler::reconcile);
        final Registration appearance = host.onAppearanceChanged(reconciler::refreshAppearance);
        return () -> closeAll(List.of(appearance, rebuild, reconciler));
    }

    private final class Reconciler implements Registration {
        private final List<MainToolbarContributionDescriptor> descriptors;
        private List<Registration> nativeButtons = List.of();
        private boolean closed;

        private Reconciler(final List<MainToolbarContributionDescriptor> descriptors) {
            this.descriptors = List.copyOf(descriptors);
        }

        private synchronized void reconcile() {
            if (closed) {
                return;
            }
            closeAll(nativeButtons);
            final List<Registration> installed = new ArrayList<>();
            try {
                for (MainToolbarContributionDescriptor descriptor : descriptors) {
                    final Optional<MainToolbarHostOperations.AnchorHandle> anchor = resolveAnchor(
                        descriptor.placement()
                    );
                    installed.add(Objects.requireNonNull(host.addButton(
                        descriptor,
                        anchor,
                        () -> actionRouter.invoke(descriptor.pluginId(), descriptor.actionId())
                    ), "host.addButton()"));
                }
            } catch (RuntimeException | Error failure) {
                closeAllSuppressing(installed, failure);
                throw failure;
            }
            nativeButtons = List.copyOf(installed);
        }

        private void refreshAppearance() {
            reconcile();
        }

        private Optional<MainToolbarHostOperations.AnchorHandle> resolveAnchor(
            final MainToolbarRegistry.Placement placement
        ) {
            if (placement.position() == MainToolbarRegistry.Position.FIRST
                || placement.position() == MainToolbarRegistry.Position.LAST) {
                return Optional.empty();
            }
            return Optional.of(host.anchor(placement.anchor().orElseThrow())
                .orElseThrow(() -> new IllegalStateException("main-toolbar semantic anchor is missing")));
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            final List<Registration> buttons = nativeButtons;
            nativeButtons = List.of();
            closeAll(buttons);
        }
    }

    private static void closeAll(final List<? extends Registration> registrations) {
        RuntimeException first = null;
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
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
}
