package dev.turboism.ui.menu;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.action.EditorUiActionRouter;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Reversible provider for the bounded runtime-owned Turboism top menu. */
public final class TopMenuContributionProvider implements EditorUiContributionProvider {

    private static final Comparator<TopMenuItemDescriptor> ITEM_ORDER = Comparator
        .comparingInt(TopMenuItemDescriptor::order)
        .thenComparing(TopMenuItemDescriptor::pluginId)
        .thenComparing(TopMenuItemDescriptor::contributionId);

    private final EditorUiProviderAdmission admission;
    private final TopMenuHostOperations host;
    private final EditorUiActionRouter actionRouter;

    public TopMenuContributionProvider(
        final EditorUiProviderAdmission admission,
        final TopMenuHostOperations host,
        final EditorUiActionRouter actionRouter
    ) {
        this.admission = Objects.requireNonNull(admission, "admission");
        if (admission.family() != EditorUiFamily.MENU) {
            throw new IllegalArgumentException("top-menu provider requires MENU admission");
        }
        this.host = Objects.requireNonNull(host, "host");
        this.actionRouter = Objects.requireNonNull(actionRouter, "actionRouter");
    }

    @Override
    public EditorUiFamily family() {
        return EditorUiFamily.MENU;
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
            throw new IllegalStateException("top-menu provider admission is stale");
        }
        final List<TopMenuItemDescriptor> items = contributions.stream()
            .map(TopMenuItemDescriptor::from)
            .flatMap(java.util.Optional::stream)
            .sorted(ITEM_ORDER)
            .toList();
        final Reconciler reconciler = new Reconciler(items);
        final List<Registration> registrations = new ArrayList<>();
        try {
            reconciler.reconcile();
            registrations.add(reconciler);
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
        private final List<TopMenuItemDescriptor> items;
        private Registration installed = () -> { };
        private boolean hasInstalled;
        private boolean closed;

        private Reconciler(final List<TopMenuItemDescriptor> items) {
            this.items = List.copyOf(items);
        }

        private synchronized void reconcile() {
            if (closed) {
                return;
            }
            if (hasInstalled) {
                installed.close();
                installed = () -> { };
                hasInstalled = false;
            }
            if (items.isEmpty()) {
                return;
            }
            installed = Objects.requireNonNull(
                host.addMenu(
                    TopMenuDescriptor.turboism(items),
                    item -> actionRouter.invoke(item.pluginId(), item.actionId())
                ),
                "host.addMenu()"
            );
            hasInstalled = true;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (hasInstalled) {
                hasInstalled = false;
                final Registration current = installed;
                installed = () -> { };
                current.close();
            }
        }

        private void closeSuppressing(final Throwable failure) {
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
}
