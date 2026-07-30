package dev.turboism.ui.menu;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.action.EditorUiActionRouter;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Reversible provider for plugin-owned top-level and nested menus. */
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
        final LinkedHashMap<MenuKey, List<TopMenuItemDescriptor>> grouped = new LinkedHashMap<>();
        for (TopMenuItemDescriptor item : items) {
            grouped.computeIfAbsent(
                new MenuKey(item.pluginId(), item.rootLabel()),
                ignored -> new ArrayList<>()
            ).add(item);
        }
        final List<TopMenuDescriptor> menus = grouped.entrySet().stream()
            .map(entry -> TopMenuDescriptor.owned(
                entry.getKey().pluginId(),
                entry.getKey().rootLabel(),
                entry.getValue()
            ))
            .toList();
        final Reconciler reconciler = new Reconciler(menus);
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
        private final List<TopMenuDescriptor> menus;
        private List<Registration> installed = List.of();
        private boolean closed;

        private Reconciler(final List<TopMenuDescriptor> menus) {
            this.menus = List.copyOf(menus);
        }

        private synchronized void reconcile() {
            if (closed) {
                return;
            }
            closeAll(installed);
            installed = List.of();
            if (menus.isEmpty()) {
                return;
            }
            final List<Registration> next = new ArrayList<>();
            try {
                for (TopMenuDescriptor menu : menus) {
                    next.add(Objects.requireNonNull(
                        host.addMenu(
                            menu,
                            item -> actionRouter.invoke(item.pluginId(), item.actionId())
                        ),
                        "host.addMenu()"
                    ));
                }
            } catch (RuntimeException | Error failure) {
                closeAllSuppressing(next, failure);
                throw failure;
            }
            installed = List.copyOf(next);
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            final List<Registration> current = installed;
            installed = List.of();
            closeAll(current);
        }

        private void closeSuppressing(final Throwable failure) {
            try {
                close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private record MenuKey(String pluginId, String rootLabel) { }

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
