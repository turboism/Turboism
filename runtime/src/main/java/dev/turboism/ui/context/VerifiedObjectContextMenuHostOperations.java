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

    public VerifiedObjectContextMenuHostOperations(
        final SelectionResolver selectionResolver,
        final NativeAppender appender
    ) {
        this.selectionResolver = Objects.requireNonNull(selectionResolver, "selectionResolver");
        this.appender = Objects.requireNonNull(appender, "appender");
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
            if (closed.compareAndSet(false, true)) entries.remove(entry);
        };
    }

    @Override
    public Object augment(final Object menu, final Location location, final Object source) {
        if (menu == null || location == null || source == null || entries.isEmpty()) return menu;
        try {
            final ContextMenuSelection selection = selectionResolver.resolve(location, source);
            if (selection == null || selection.location() != location || selection.items().isEmpty()) return menu;
            for (Entry entry : entries) {
                if (entry.contribution().matches(selection)) {
                    appender.append(menu, entry.contribution(), () -> entry.action().run(selection));
                }
            }
        } catch (Throwable ignored) {
            // Host UI callbacks must fail closed and preserve Cubism's original menu.
        }
        return menu;
    }

    @FunctionalInterface
    public interface SelectionResolver {
        ContextMenuSelection resolve(Location location, Object source);
    }

    @FunctionalInterface
    public interface NativeAppender {
        void append(Object menu, ContextMenuContributionDescriptor contribution, Runnable action);
    }

    private record Entry(ContextMenuContributionDescriptor contribution, MenuAction action) {
    }
}
