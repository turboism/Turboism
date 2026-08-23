package dev.turboism.ui.context;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import dev.turboism.sdk.ui.context.ContextMenuSelection;

import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared fail-closed host implementation; exact-version reflection is supplied by narrow adapters. */
public final class VerifiedObjectContextMenuHostOperations
    implements ContextMenuHostOperations, NativeObjectContextMenuBridge.Handler {

    private final SelectionResolver selectionResolver;
    private final NativeAppender appender;
    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();
    private final PersistentAppender persistentAppender;

    public VerifiedObjectContextMenuHostOperations(
        final SelectionResolver selectionResolver,
        final NativeAppender appender
    ) {
        this.selectionResolver = Objects.requireNonNull(selectionResolver, "selectionResolver");
        this.appender = Objects.requireNonNull(appender, "appender");
        this.persistentAppender = (menu, contribution, action) -> {
            appender.append(menu, contribution, action);
            return () -> {};
        };
    }

    public VerifiedObjectContextMenuHostOperations(
        final SelectionResolver selectionResolver,
        final NativeAppender appender,
        final PersistentAppender persistentAppender
    ) {
        this.selectionResolver = Objects.requireNonNull(selectionResolver, "selectionResolver");
        this.appender = Objects.requireNonNull(appender, "appender");
        this.persistentAppender = Objects.requireNonNull(persistentAppender, "persistentAppender");
    }

    @Override
    public Registration addItem(
        final ContextMenuContributionDescriptor contribution,
        final MenuAction action
    ) {
        final Entry entry = new Entry(
            Objects.requireNonNull(contribution, "contribution"),
            Objects.requireNonNull(action, "action")
        );
        entries.add(entry);
        entries.sort(Comparator.comparingInt(value -> value.contribution().priority()));
        final AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true) && entries.remove(entry)) entry.close();
        };
    }

    @Override
    public Object augment(final Object menu, final Location location, final Object source) {
        if (menu == null || location == null || source == null || entries.isEmpty()) return menu;
        try {
            final ContextMenuSelection selection = selectionResolver.resolve(location, source);
            if (selection == null || selection.location() != location || selection.items().isEmpty()) return menu;
            for (Entry entry : entries) {
                final boolean matches = entry.contribution().matches(selection);
                if (matches) {
                    appender.append(
                        menu,
                        entry.contribution(),
                        actionId -> entry.action().run(selection, actionId)
                    );
                }
            }
        } catch (Throwable failure) {
            // Host UI callbacks must fail closed and preserve Cubism's original menu.
        }
        return menu;
    }

    /** Installs entries once into a persistent native menu while resolving selection at click time. */
    public void installPersistent(
        final Object menu,
        final Location location,
        final java.util.function.Supplier<ContextMenuSelection> selection
    ) {
        if (menu == null || location == null || selection == null || entries.isEmpty()) return;
        for (Entry entry : entries) {
            if (entry.contribution().location() != location || entry.isInstalled(menu)) continue;
            try {
                final ContextMenuSelection current = selection.get();
                if (current == null || current.location() != location || !entry.contribution().matches(current)) continue;
                entry.install(menu, persistentAppender.append(menu, entry.contribution(), actionId -> {
                    final ContextMenuSelection latest = selection.get();
                    if (latest != null && latest.location() == location
                        && entry.contribution().matches(latest)) entry.action().run(latest, actionId);
                }));
            } catch (Throwable failure) {
                // Persistent native menus must preserve Cubism behavior when unavailable.
            }
        }
    }

    /**
     * Adapts these host operations into a parameter-point menu handler, when the configured
     * selection resolver is one that can read native parameter points.
     *
     * @return a handler for {@link NativeParameterPointContextMenuBridge#install}, or null when
     *         the selection resolver has no native access and parameter-point menus therefore
     *         cannot be contributed to
     */
    public NativeParameterPointContextMenuBridge.Handler parameterPointHandler() {
        if (!(selectionResolver instanceof VerifiedObjectContextMenuNativeAccess nativeAccess)) return null;
        return NativeParameterPointContextMenuBridge.handler(this, nativeAccess);
    }


    @FunctionalInterface
    public interface SelectionResolver {
        ContextMenuSelection resolve(Location location, Object source);
    }

    @FunctionalInterface
    public interface NativeAppender {
        void append(
            Object menu,
            ContextMenuContributionDescriptor contribution,
            java.util.function.Consumer<String> action
        );
    }

    @FunctionalInterface
    public interface PersistentAppender {
        Registration append(
            Object menu,
            ContextMenuContributionDescriptor contribution,
            java.util.function.Consumer<String> action
        );
    }

    private static final class Entry {
        private final ContextMenuContributionDescriptor contribution;
        private final MenuAction action;
        private final java.util.Map<Object, Registration> installations =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

        private Entry(final ContextMenuContributionDescriptor contribution, final MenuAction action) {
            this.contribution = contribution;
            this.action = action;
        }

        private ContextMenuContributionDescriptor contribution() { return contribution; }
        private MenuAction action() { return action; }
        private boolean isInstalled(final Object menu) { return installations.containsKey(menu); }
        private void install(final Object menu, final Registration registration) {
            final Registration previous = installations.putIfAbsent(menu, registration);
            if (previous != null) registration.close();
        }
        private void close() {
            synchronized (installations) {
                installations.values().forEach(Registration::close);
                installations.clear();
            }
        }
    }
}
