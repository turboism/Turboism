package dev.turboism.ui.toolbar;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.action.EditorUiActionRouter;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reversible vertical tool-strip provider shared by fake and verified host operations. */
public final class VerticalToolbarContributionProvider implements EditorUiContributionProvider {

    private final EditorUiProviderAdmission admission;
    private final VerticalToolbarHostOperations host;
    private final EditorUiActionRouter actionRouter;

    public VerticalToolbarContributionProvider(
        final EditorUiProviderAdmission admission,
        final VerticalToolbarHostOperations host,
        final EditorUiActionRouter actionRouter
    ) {
        this.admission = Objects.requireNonNull(admission, "admission");
        if (admission.family() != EditorUiFamily.VERTICAL_TOOLBAR) {
            throw new IllegalArgumentException(
                "vertical-toolbar provider requires VERTICAL_TOOLBAR admission"
            );
        }
        this.host = Objects.requireNonNull(host, "host");
        this.actionRouter = Objects.requireNonNull(actionRouter, "actionRouter");
    }

    @Override
    public EditorUiFamily family() {
        return EditorUiFamily.VERTICAL_TOOLBAR;
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
            throw new IllegalStateException("vertical-toolbar provider admission is stale");
        }
        final List<VerticalToolbarContributionDescriptor> descriptors = contributions.stream()
            .map(VerticalToolbarContributionDescriptor::from)
            .toList();
        final Reconciler reconciler = new Reconciler(descriptors);
        reconciler.reconcile();
        final Registration rebuild = host.onRebuild(reconciler::reconcile);
        return () -> closeAll(List.of(rebuild, reconciler));
    }

    private final class Reconciler implements Registration {
        private final List<VerticalToolbarContributionDescriptor> descriptors;
        private final List<Registration> installed = new ArrayList<>();
        private boolean closed;

        private Reconciler(final List<VerticalToolbarContributionDescriptor> descriptors) {
            this.descriptors = List.copyOf(descriptors);
        }

        private synchronized void reconcile() {
            if (closed) {
                return;
            }
            closeAll(installed);
            installed.clear();
            final List<Registration> next = new ArrayList<>();
            try {
                for (final VerticalToolbarContributionDescriptor descriptor : descriptors) {
                    next.add(host.attach(descriptor, actionId -> actionRouter.invoke(
                        descriptor.pluginId(),
                        actionId,
                        java.util.Optional.empty()
                    )));
                }
                installed.addAll(next);
            } catch (RuntimeException | Error failure) {
                closeAll(next);
                installed.clear();
                throw failure;
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            closeAll(installed);
            installed.clear();
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
}
