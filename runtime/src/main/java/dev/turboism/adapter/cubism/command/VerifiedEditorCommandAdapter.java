package dev.turboism.adapter.cubism.command;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorParameterizedRequest;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Invokes only enabled exact-version native menu items from the verified top-menu root. */
public final class VerifiedEditorCommandAdapter implements EditorCommandAdapter {
    private static final String APP_INSTANCE = "cubism.ui-top-menu.app-controller.instance";
    private static final String APP_MAIN_FRAME = "cubism.ui-top-menu.app-controller.main-frame";
    private static final String MAIN_FRAME_WINDOW = "cubism.ui-top-menu.main-frame.window";
    private static final String WINDOW_MENU_BAR = "cubism.ui-top-menu.window.menu-bar";
    private static final String MENU_BAR_SWING = "cubism.ui-top-menu.menu-bar.swing";

    private final VerifiedMemberResolver resolver;
    private final Set<EditorCommand> supported;
    private final VerifiedTypedEditorCommandOperations typed;

    public VerifiedEditorCommandAdapter(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.typed = new VerifiedTypedEditorCommandOperations(resolver);
        final EnumSet<EditorCommand> commands = EnumSet.noneOf(EditorCommand.class);
        for (EditorCommand command : EditorCommand.values()) {
            if (command.supports(resolver.cubismVersion())) commands.add(command);
        }
        this.supported = Set.copyOf(commands);
    }

    @Override
    public Set<EditorCommand> available() {
        try {
            return onEdt(this::availableOnEdt);
        } catch (RuntimeException exception) {
            return Set.of();
        }
    }

    @Override
    public EditorCommandResult execute(final EditorCommand command) {
        Objects.requireNonNull(command, "command");
        if (!supported.contains(command)) return result(command, EditorCommandResult.Status.UNSUPPORTED_VERSION);
        try {
            return onEdt(() -> invoke(command));
        } catch (RuntimeException exception) {
            return result(command, EditorCommandResult.Status.FAILED);
        }
    }

    @Override
    public EditorCommandResult execute(final ResolvedEditorFileCommand command) {
        Objects.requireNonNull(command, "command");
        if (!command.command().supports(resolver.cubismVersion())) {
            return new EditorCommandResult(EditorCommandResult.Status.UNSUPPORTED_VERSION, command.commandId());
        }
        try {
            return onEdt(() -> typed.execute(command));
        } catch (RuntimeException exception) {
            return new EditorCommandResult(EditorCommandResult.Status.FAILED, command.commandId());
        }
    }

    @Override
    public EditorCommandResult execute(final EditorParameterizedRequest command) {
        Objects.requireNonNull(command, "command");
        if (!command.command().supports(resolver.cubismVersion())) {
            return new EditorCommandResult(EditorCommandResult.Status.UNSUPPORTED_VERSION, command.commandId());
        }
        try {
            return onEdt(() -> typed.execute(command));
        } catch (VerifiedTypedEditorCommandOperations.InvalidState invalidState) {
            return new EditorCommandResult(EditorCommandResult.Status.INVALID_STATE, command.commandId());
        } catch (RuntimeException exception) {
            return new EditorCommandResult(EditorCommandResult.Status.FAILED, command.commandId());
        }
    }

    private Set<EditorCommand> availableOnEdt() {
        final JMenuBar bar = menuBar();
        if (bar == null) return Set.of();
        final EnumSet<EditorCommand> result = EnumSet.noneOf(EditorCommand.class);
        for (EditorCommand command : supported) {
            final JMenuItem item = find(bar, NativeEditorCommandIds.id(command));
            if (item != null && item.isEnabled()) result.add(command);
        }
        return Set.copyOf(result);
    }

    private EditorCommandResult invoke(final EditorCommand command) {
        final JMenuBar bar = menuBar();
        if (bar == null) return result(command, EditorCommandResult.Status.UNAVAILABLE);
        final JMenuItem item = find(bar, NativeEditorCommandIds.id(command));
        if (item == null) return result(command, EditorCommandResult.Status.UNAVAILABLE);
        if (!item.isEnabled()) return result(command, EditorCommandResult.Status.INVALID_STATE);
        item.doClick(0);
        return result(command, EditorCommandResult.Status.EXECUTED);
    }

    private JMenuBar menuBar() {
        final Object app = resolver.invokeStatic(APP_INSTANCE);
        final Object mainFrame = resolver.invoke(APP_MAIN_FRAME, app);
        if (mainFrame == null) return null;
        final Object window = resolver.invoke(MAIN_FRAME_WINDOW, mainFrame);
        if (window == null) return null;
        final Object value = resolver.invoke(WINDOW_MENU_BAR, window);
        final Object swing = value == null ? null : resolver.invoke(MENU_BAR_SWING, value);
        return swing instanceof JMenuBar bar ? bar : null;
    }

    private static JMenuItem find(final JMenuBar bar, final String nativeId) {
        for (int index = 0; index < bar.getMenuCount(); index++) {
            final JMenuItem found = find(bar.getMenu(index), nativeId);
            if (found != null) return found;
        }
        return null;
    }

    private static JMenuItem find(final JMenuItem item, final String nativeId) {
        if (item == null) return null;
        if (nativeId.equals(item.getActionCommand())) return item;
        if (item instanceof JMenu menu) {
            for (Component child : menu.getMenuComponents()) {
                if (child instanceof JMenuItem menuItem) {
                    final JMenuItem found = find(menuItem, nativeId);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static EditorCommandResult result(
        final EditorCommand command,
        final EditorCommandResult.Status status
    ) {
        return new EditorCommandResult(status, command.id());
    }

    private static <T> T onEdt(final Operation<T> operation) {
        if (SwingUtilities.isEventDispatchThread()) return operation.run();
        final FutureTask<T> task = new FutureTask<>(operation::run);
        SwingUtilities.invokeLater(task);
        try {
            return task.get(30L, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Editor command dispatch was interrupted", exception);
        } catch (TimeoutException exception) {
            task.cancel(false);
            throw new IllegalStateException("Editor command dispatch timed out", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            final Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Editor command dispatch failed", cause);
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }
}
