package dev.turboism.adapter.cubism.command;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedEditorCommandAdapterTest {
    @AfterEach
    void clearHost() {
        Host.frame = null;
    }

    @Test
    void reportsAndExecutesOnlyEnabledCommandsPresentInTheCurrentMenu() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean invokedOnEdt = new AtomicBoolean();
        JMenuItem next = item("CMD_NEXT_FRAME", true, calls, invokedOnEdt);
        JMenuItem disabled = item("CMD_DELETE", false, calls, invokedOnEdt);
        Host.install(menu(next, disabled));
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(resolver("5.3.02"));

        assertEquals(Set.of(EditorCommand.NEXT_FRAME), adapter.available());
        assertEquals(EditorCommandResult.Status.EXECUTED, adapter.execute(EditorCommand.NEXT_FRAME).status());
        assertEquals(1, calls.get());
        assertTrue(invokedOnEdt.get());
        assertEquals(EditorCommandResult.Status.INVALID_STATE, adapter.execute(EditorCommand.DELETE).status());
    }

    @Test
    void failsClosedForMissingHostStateAndUnsupportedVersions() {
        VerifiedEditorCommandAdapter noHost = new VerifiedEditorCommandAdapter(resolver("5.3.02"));
        assertEquals(Set.of(), noHost.available());
        assertEquals(EditorCommandResult.Status.UNAVAILABLE, noHost.execute(EditorCommand.NEXT_FRAME).status());

        Host.install(menu(item("CMD_EXPAND_WARPDEFORMER", true, new AtomicInteger(), new AtomicBoolean())));
        VerifiedEditorCommandAdapter oldVersion = new VerifiedEditorCommandAdapter(resolver("5.2.03"));
        assertFalse(oldVersion.available().contains(EditorCommand.EXPAND_WARPDEFORMER));
        assertEquals(
            EditorCommandResult.Status.UNSUPPORTED_VERSION,
            oldVersion.execute(EditorCommand.EXPAND_WARPDEFORMER).status()
        );
    }

    @Test
    void sanitizesHostCallbackFailures() {
        JMenuItem failing = new JMenuItem("failing");
        failing.setActionCommand("CMD_NEXT_FRAME");
        failing.addActionListener(ignored -> { throw new IllegalStateException("private host detail"); });
        Host.install(menu(failing));

        EditorCommandResult result = new VerifiedEditorCommandAdapter(resolver("5.3.02"))
            .execute(EditorCommand.NEXT_FRAME);

        assertEquals(EditorCommandResult.Status.FAILED, result.status());
        assertEquals(EditorCommand.NEXT_FRAME.id(), result.commandId());
    }

    private static JMenuItem item(
        final String command,
        final boolean enabled,
        final AtomicInteger calls,
        final AtomicBoolean invokedOnEdt
    ) {
        JMenuItem item = new JMenuItem(command);
        item.setActionCommand(command);
        item.setEnabled(enabled);
        item.addActionListener(ignored -> {
            calls.incrementAndGet();
            invokedOnEdt.set(SwingUtilities.isEventDispatchThread());
        });
        return item;
    }

    private static JMenuBar menu(final JMenuItem... items) {
        JMenuBar bar = new JMenuBar();
        JMenu menu = new JMenu("test");
        for (JMenuItem item : items) menu.add(item);
        bar.add(menu);
        return bar;
    }

    private static VerifiedMemberResolver resolver(final String version) {
        String host = internal(Host.class);
        String frame = internal(Frame.class);
        String window = internal(Window.class);
        String wrapper = internal(MenuBarWrapper.class);
        return TestVerifiedResolvers.create(
            version,
            "adapter.ui.top-menu",
            Set.of("cubism.ui.top-menu"),
            List.of(
                StaticSelector.staticMethod(
                    "cubism.ui-top-menu.app-controller.instance", host, "instance", "()L" + host + ";",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "cubism.ui-top-menu.app-controller.main-frame", host, "mainFrame", "()L" + frame + ";",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "cubism.ui-top-menu.main-frame.window", frame, "window", "()L" + window + ";",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "cubism.ui-top-menu.window.menu-bar", window, "menuBar", "()L" + wrapper + ";",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "cubism.ui-top-menu.menu-bar.swing", wrapper, "swing", "()Ljavax/swing/JMenuBar;",
                    StaticSelector.ACCESS_PUBLIC
                )
            ),
            Host.class.getClassLoader()
        );
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static Frame frame;
        public static Host instance() { return INSTANCE; }
        public Frame mainFrame() { return frame; }
        static void install(final JMenuBar bar) { frame = new Frame(new Window(new MenuBarWrapper(bar))); }
    }

    public record Frame(Window window) { }
    public record Window(MenuBarWrapper menuBar) { }
    public record MenuBarWrapper(JMenuBar swing) { }
}
