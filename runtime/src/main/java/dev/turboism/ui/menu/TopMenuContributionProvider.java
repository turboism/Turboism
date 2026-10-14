package dev.turboism.ui.menu;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.action.EditorUiActionRouter;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Reversible provider for plugin-owned top-level and nested menus. */
public final class TopMenuContributionProvider implements EditorUiContributionProvider {

    private static final Comparator<TopMenuItemDescriptor> ITEM_ORDER = Comparator.comparingInt(
                    TopMenuItemDescriptor::order)
            .thenComparing(TopMenuItemDescriptor::pluginId)
            .thenComparing(TopMenuItemDescriptor::contributionId);

    private static final String FIRST_PARTY_PLUGIN_PREFIX = "dev.turboism.plugin.";

    private final EditorUiProviderAdmission admission;
    private final TopMenuHostOperations host;
    private final EditorUiActionRouter actionRouter;
    private final Supplier<String> sharedRootLabel;
    private final Supplier<String> pluginsGroupLabel;

    public TopMenuContributionProvider(
            final EditorUiProviderAdmission admission,
            final TopMenuHostOperations host,
            final EditorUiActionRouter actionRouter,
            final String sharedRootLabel) {
        this(admission, host, actionRouter, fixedLabel(sharedRootLabel), () -> "Plugins");
    }

    /**
     * @param sharedRootLabel resolves the localized shared-root label at apply
     *     time, so a locale settled after this provider's construction is honored
     */
    public TopMenuContributionProvider(
            final EditorUiProviderAdmission admission,
            final TopMenuHostOperations host,
            final EditorUiActionRouter actionRouter,
            final Supplier<String> sharedRootLabel) {
        this(admission, host, actionRouter, sharedRootLabel, () -> "Plugins");
    }

    public TopMenuContributionProvider(
            final EditorUiProviderAdmission admission,
            final TopMenuHostOperations host,
            final EditorUiActionRouter actionRouter,
            final Supplier<String> sharedRootLabel,
            final Supplier<String> pluginsGroupLabel) {
        this.admission = Objects.requireNonNull(admission, "admission");
        if (admission.family() != EditorUiFamily.MENU) {
            throw new IllegalArgumentException("top-menu provider requires MENU admission");
        }
        this.host = Objects.requireNonNull(host, "host");
        this.actionRouter = Objects.requireNonNull(actionRouter, "actionRouter");
        this.sharedRootLabel = Objects.requireNonNull(sharedRootLabel, "sharedRootLabel");
        this.pluginsGroupLabel = Objects.requireNonNull(pluginsGroupLabel, "pluginsGroupLabel");
    }

    private static Supplier<String> fixedLabel(final String label) {
        final String required = Objects.requireNonNull(label, "sharedRootLabel");
        return () -> required;
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
    public Registration apply(final long hostGeneration, final List<EditorUiContribution<?>> contributions) {
        if (!admission.isAdmittedTo(hostGeneration)) {
            throw new IllegalStateException("top-menu provider admission is stale");
        }
        final List<TopMenuItemDescriptor> rawItems = contributions.stream()
                .map(TopMenuItemDescriptor::from)
                .flatMap(java.util.Optional::stream)
                .sorted(ITEM_ORDER)
                .toList();
        final String groupLabel = pluginsGroupLabel.get();
        final List<TopMenuItemDescriptor> items = new ArrayList<>(rawItems.size());
        for (TopMenuItemDescriptor item : rawItems) {
            if (RESERVED_SHARED_ROOT.equals(item.rootLabel())
                    && item.pluginId().startsWith(FIRST_PARTY_PLUGIN_PREFIX)) {
                final List<String> currentSubmenu = item.submenuPath();
                if (currentSubmenu.isEmpty() || !currentSubmenu.get(0).equals(groupLabel)) {
                    final List<String> newSubmenu = new ArrayList<>(currentSubmenu.size() + 1);
                    newSubmenu.add(groupLabel);
                    newSubmenu.addAll(currentSubmenu);
                    items.add(new TopMenuItemDescriptor(
                            item.pluginId(),
                            item.contributionId(),
                            item.nativeItemId(),
                            item.rootLabel(),
                            newSubmenu,
                            item.label(),
                            item.actionId(),
                            item.order()));
                    continue;
                }
            }
            items.add(item);
        }
        final LinkedHashMap<MenuKey, List<TopMenuItemDescriptor>> grouped = new LinkedHashMap<>();
        for (TopMenuItemDescriptor item : items) {
            final boolean shared = RESERVED_SHARED_ROOT.equals(item.rootLabel());
            final String owner = shared ? SHARED_ROOT_OWNER : item.pluginId();
            grouped.computeIfAbsent(new MenuKey(owner, item.rootLabel(), shared), ignored -> new ArrayList<>())
                    .add(item);
        }
        final List<TopMenuDescriptor> menus = grouped.entrySet().stream()
                .map(entry -> entry.getKey().shared()
                        ? TopMenuDescriptor.shared(entry.getKey().rootLabel(), sharedRootLabel.get(), entry.getValue())
                        : TopMenuDescriptor.owned(entry.getKey().rootLabel(), entry.getValue()))
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
                            host.addMenu(menu, item -> actionRouter.invoke(item.pluginId(), item.actionId())),
                            "host.addMenu()"));
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

    /** Reserved root label shared by multiple plugin-owned top menus; merged into one visible root. */
    private static final String RESERVED_SHARED_ROOT = "Turboism";

    /**
     * Internal placeholder owner for the merged reserved-root group. It is only
     * a grouping key inside this class; it is never emitted as a plugin id and
     * the {@code shared} flag, not this string, decides shared aggregation.
     */
    private static final String SHARED_ROOT_OWNER = "shared";

    private record MenuKey(String owner, String rootLabel, boolean shared) {}

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

    private static void closeAllSuppressing(final List<? extends Registration> registrations, final Throwable failure) {
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }
}
