package dev.turboism.plugin.commandpalette;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.test.plugin.FakePluginContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Entry-path test: the plugin registers exactly one action (with the declared Ctrl+K
 * default shortcut) and one Turboism-menu contribution, and both land in the disposable
 * scope so disable withdraws them.
 */
class CommandPalettePluginTest {

    @Test
    void enableRegistersOpenActionWithCtrlKAndMenuEntry() throws Exception {
        final StubPluginContext context = new StubPluginContext();
        final CommandPalettePlugin plugin = new CommandPalettePlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(1, context.actionIds.size());
        assertEquals(CommandPalettePlugin.ACTION_ID, context.actionIds.get(0));
        final ActionRegistry.Action action = context.actions.get(0);
        assertTrue(action.defaultShortcut().isPresent());
        assertEquals("Ctrl+K", action.defaultShortcut().get());
        assertEquals(1, context.menuPaths.size());
        assertTrue(context.menuPaths.get(0).startsWith("Turboism/"));

        plugin.disable();
        plugin.shutdown();
    }

    @Test
    void queuedOpenCannotCreatePaletteAfterDisable() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        final StubPluginContext context = new StubPluginContext();
        final CommandPalettePlugin plugin = new CommandPalettePlugin();
        plugin.init(context);
        plugin.enable();
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        javax.swing.SwingUtilities.invokeLater(() -> {
            entered.countDown();
            try {
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("EDT release");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
        try {
            context.actions.get(0).handler().accept(new ActionRegistry.ActionContext() {});
            plugin.disable();
        } finally {
            release.countDown();
        }
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        final java.lang.reflect.Field field = CommandPalettePlugin.class.getDeclaredField("palette");
        field.setAccessible(true);
        final Object window = ((java.util.concurrent.atomic.AtomicReference<?>) field.get(plugin)).get();
        try {
            org.junit.jupiter.api.Assertions.assertNull(window, "queued open must not resurrect a disabled palette");
        } finally {
            plugin.shutdown();
            javax.swing.SwingUtilities.invokeAndWait(() -> {});
        }
    }

    private static final class StubPluginContext extends FakePluginContext {
        final TestUiScheduler scheduler = new TestUiScheduler();
        final List<String> actionIds = new ArrayList<>();
        final List<ActionRegistry.Action> actions = new ArrayList<>();
        final List<String> menuPaths = new ArrayList<>();
        private final DisposableScope scope = new DisposableScope();

        StubPluginContext() {
            super(FakePluginContext.builder());
        }

        @Override
        public dev.turboism.sdk.ui.UiScheduler uiScheduler() {
            if (scheduler.fenced) throw new IllegalStateException("generation fenced: uiScheduler accessor");
            return scheduler;
        }

        @Override
        public ActionRegistry actions() {
            return (id, action) -> {
                actionIds.add(id);
                actions.add(action);
                return () -> {};
            };
        }

        @Override
        public MenuRegistry menus() {
            return contribution -> {
                menuPaths.add(contribution.menuPath());
                return () -> {};
            };
        }

        @Override
        public DisposableScope disposableScope() {
            return scope;
        }
    }

    private static final class TestUiScheduler implements dev.turboism.sdk.ui.UiScheduler {
        boolean fenced;
        boolean deferTeardown;
        boolean queueOpens;
        int teardowns;
        final List<Runnable> opens = new ArrayList<>();
        final List<Runnable> cleanups = new ArrayList<>();

        @Override
        public dev.turboism.sdk.plugin.Registration runOnUiThread(final Runnable work) {
            if (!fenced) {
                if (queueOpens) opens.add(work);
                else javax.swing.SwingUtilities.invokeLater(work);
            }
            return () -> {};
        }

        @Override
        public dev.turboism.sdk.plugin.Registration runOnUiThreadLater(
                final Runnable work, final java.time.Duration delay) {
            return runOnUiThread(work);
        }

        @Override
        public boolean runUiTeardown(final Runnable cleanup, final java.time.Duration timeout) {
            teardowns++;
            if (deferTeardown) {
                cleanups.add(cleanup);
                return false;
            }
            onEdt(cleanup);
            return true;
        }
    }

    private static void onEdt(final Runnable work) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) work.run();
        else {
            try {
                javax.swing.SwingUtilities.invokeAndWait(work);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static Object palette(final CommandPalettePlugin plugin) throws Exception {
        final var field = CommandPalettePlugin.class.getDeclaredField("palette");
        field.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicReference<?>) field.get(plugin)).get();
    }

    private static java.awt.Window frame(final Object palette) throws Exception {
        final var field = CommandPaletteDialog.class.getDeclaredField("dialog");
        field.setAccessible(true);
        return (java.awt.Window) field.get(palette);
    }

    @Test
    void oldOpenCannotCrossDisableAndReenable() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        final var context = new StubPluginContext();
        context.scheduler.queueOpens = true;
        final var plugin = new CommandPalettePlugin();
        plugin.init(context);
        plugin.enable();
        context.actions.get(0).handler().accept(new ActionRegistry.ActionContext() {});
        assertEquals(1, context.scheduler.opens.size());
        plugin.disable();
        plugin.enable();
        onEdt(context.scheduler.opens.remove(0));
        org.junit.jupiter.api.Assertions.assertNull(palette(plugin));
        plugin.shutdown();
    }

    @Test
    void deferredFencedCleanupDisposesOnlyCapturedWindow() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        final var context = new StubPluginContext();
        final var plugin = new CommandPalettePlugin();
        plugin.init(context);
        plugin.enable();
        context.actions.get(0).handler().accept(new ActionRegistry.ActionContext() {});
        onEdt(() -> {});
        final var old = frame(palette(plugin));
        context.scheduler.fenced = true;
        context.scheduler.deferTeardown = true;
        plugin.disable();
        assertTrue(old.isDisplayable(), "false means cleanup remains pending");
        assertEquals(1, context.scheduler.teardowns);
        context.scheduler.fenced = false;
        plugin.enable();
        context.actions.get(1).handler().accept(new ActionRegistry.ActionContext() {});
        onEdt(() -> {});
        final var fresh = frame(palette(plugin));
        try {
            onEdt(context.scheduler.cleanups.get(0));
            onEdt(context.scheduler.cleanups.get(0));
            org.junit.jupiter.api.Assertions.assertFalse(old.isDisplayable());
            assertTrue(fresh.isDisplayable(), "late cleanup must not dispose the new generation");
        } finally {
            context.scheduler.deferTeardown = false;
            onEdt(plugin::shutdown);
            plugin.disable();
            context.scope.close();
        }
        org.junit.jupiter.api.Assertions.assertFalse(fresh.isDisplayable());
        assertEquals(2, context.scheduler.teardowns, "repeated lifecycle cleanup is idempotent");
    }
}
