package dev.turboism.validation.productui;

import java.awt.*;
import java.awt.event.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Task-only observer. All enable/disable/shutdown operations use production management. */
public final class ProductUiHostAgent {
    private static final Properties RESULT = new Properties();
    private static Path output;
    private static Object runtime, coordinator, management;
    private static final String[][] PRODUCTS = {
        {"acp", "turboism-acp.settings.open", "window", "settingsFrame"},
        {"command-palette", "command-palette.open", "palette", "dialog"},
        {"perf-stats", "perf-stats.window.show", "window", "frame"},
        {"mcp", "turboism.mcp.connection.open", "window", "frame"}
    };

    public static void premain(String ignored, Instrumentation instrumentation) {
        Path home = Path.of(System.getProperty("turboism.home", ""));
        String id = System.getProperty("turboism.validation.runId", "");
        if (!id.matches("queue-[0-9a-f]{32}") || home.getParent() == null
                || !id.equals(home.getParent().getFileName().toString())
                || !home.getFileName().toString().equals("turboism-home")) {
            throw new IllegalArgumentException("requires bound queue task home");
        }
        output = home.resolve("state/product-ui-lifecycle");
        Thread worker = new Thread(ProductUiHostAgent::run, "product-ui-host-validation");
        worker.setDaemon(true);
        worker.start();
    }

    private static Object field(Object value, String name) throws Exception {
        Field f = value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value);
    }
    private static Object call(Object value, String name, Object... args) throws Exception {
        for (Class<?> c = value.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                m.setAccessible(true);
                try { return m.invoke(value, args); }
                catch (InvocationTargetException e) { throw new Exception(name + " failed", e.getCause()); }
            }
        }
        throw new NoSuchMethodException(value.getClass() + "." + name);
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeLater(task);
        return task.get(25, TimeUnit.SECONDS);
    }
    private static void check(boolean value, String key) {
        RESULT.setProperty(key, Boolean.toString(value));
        if (!value) throw new AssertionError(key);
    }
    private static String id(String[] product) { return "dev.turboism.plugin." + product[0]; }
    private static Object loaded(String[] product) throws Exception {
        return ((Optional<?>) call(coordinator, "findLoaded", id(product))).orElseThrow();
    }
    private static Object instance(Object loaded) throws Exception {
        return ((List<?>) call(loaded, "entrypoints")).get(0);
    }
    private static Window window(Object instance, String[] product) throws Exception {
        Object holder = field(instance, product[2]);
        if (holder instanceof AtomicReference<?> ref) holder = ref.get();
        return holder == null ? null : (Window) field(holder, product[3]);
    }
    private static void open(Object loaded, String[] product) throws Exception {
        Object registry = call(call(loaded, "context"), "actions");
        Class<?> context = Class.forName("dev.turboism.sdk.action.ActionRegistry$ActionContext");
        Object event = Proxy.newProxyInstance(context.getClassLoader(), new Class<?>[]{context},
            (proxy, method, args) -> method.getName().equals("toString") ? "validation" : Optional.empty());
        call(registry, "execute", product[1], event);
    }
    private static Window awaitWindow(Object loaded, String[] product) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < end) {
            Window w = edt(() -> window(instance(loaded), product));
            if (w != null && edt(w::isShowing)) return w;
            Thread.sleep(100);
        }
        throw new AssertionError(product[0] + " window not visible");
    }
    private static void enabled(String[] product, boolean value) throws Exception {
        Object result = call(management, "setEnabled", id(product), value);
        RESULT.setProperty(product[0] + ".management." + value, String.valueOf(call(result, "code")));
        check("PLUGIN_HOT_APPLIED".equals(call(result, "code")), product[0] + ".managementApplied." + value);
        check((Boolean)call(coordinator, "isLoaded", id(product)) == value, product[0] + ".loaded." + value);
    }
    private static void screenshot(String name) throws Exception {
        Thread.sleep(300);
        Rectangle bounds = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        ImageIO.write(new Robot().createScreenCapture(bounds), "png", output.resolve(name + ".png").toFile());
    }
    // Task-only scheduler decorator: retain the actual product cleanup closure, then
    // replay it after a new generation is showing. Ordinary scenarios above/below
    // still use the unmodified production UiScheduler and its real EDT timeout.
    private static List<Runnable> retainCleanup(Object plugin) throws Exception {
        List<Runnable> retained = new ArrayList<>();
        Class<?> schedulerType = Class.forName("dev.turboism.sdk.ui.UiScheduler");
        java.util.function.Function<Object,Object> decorate = original -> Proxy.newProxyInstance(
            schedulerType.getClassLoader(), new Class<?>[]{schedulerType}, (proxy, method, args) -> {
                if (method.getName().equals("runUiTeardown")) {
                    retained.add((Runnable)args[0]); return false;
                }
                try { return method.invoke(original, args); }
                catch (InvocationTargetException e) { throw e.getCause(); }
            });
        try {
            Field scheduler = plugin.getClass().getDeclaredField("uiScheduler");
            scheduler.setAccessible(true); scheduler.set(plugin, decorate.apply(scheduler.get(plugin)));
        } catch (NoSuchFieldException absent) {
            Field context = plugin.getClass().getDeclaredField("context"); context.setAccessible(true);
            Object original = context.get(plugin);
            Class<?> type = Class.forName("dev.turboism.sdk.plugin.PluginContext");
            Object scheduler = decorate.apply(call(original, "uiScheduler"));
            context.set(plugin, Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getName().equals("uiScheduler")) return scheduler;
                    try { return method.invoke(original, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                }));
        }
        return retained;
    }

    private static void test(String[] product) throws Exception {
        String key = product[0];
        Object old = loaded(product);
        open(old, product);
        Window first = awaitWindow(old, product);
        screenshot(key + "-opened");
        enabled(product, false);
        check(edt(() -> !first.isDisplayable()), key + ".disabledDisposed");
        enabled(product, true);
        Object second = loaded(product);
        open(second, product);
        Window replacement = awaitWindow(second, product);
        check(first != replacement, key + ".newWindowIdentity");
        screenshot(key + "-reenabled");

        // An actual EDT stall, always bounded even if the probe/lifecycle fails.
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean watchdogReleased = new AtomicBoolean();
        Thread watchdog = new Thread(() -> {
            try { if (!release.await(12, TimeUnit.SECONDS)) { watchdogReleased.set(true); release.countDown(); } }
            catch (InterruptedException e) { release.countDown(); Thread.currentThread().interrupt(); }
        }, "product-ui-bounded-edt-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        SwingUtilities.invokeLater(() -> {
            entered.countDown();
            try { release.await(13, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        check(entered.await(5, TimeUnit.SECONDS), key + ".edtBlocked");
        long start = System.nanoTime();
        try {
            // Production action queues an open behind the blocker; unload fences this generation.
            open(second, product);
            Thread.sleep(150);
            enabled(product, false);
            RESULT.setProperty(key + ".blockedDisableMillis", Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)));
        } finally { release.countDown(); }
        edt(() -> null);
        RESULT.setProperty(key + ".watchdogReleased", Boolean.toString(watchdogReleased.get()));
        check(edt(() -> !replacement.isDisplayable()), key + ".deferredOldDisposed");
        check(edt(() -> window(instance(second), product) == null), key + ".queuedOpenFenced");
        enabled(product, true);
        Object third = loaded(product);
        open(third, product);
        Window newest = awaitWindow(third, product);
        // Drain every earlier EDT callback, then prove the replacement survived.
        edt(() -> null); Thread.sleep(500); edt(() -> null);
        check(edt(newest::isDisplayable), key + ".newWindowSurvivesOldCallbacks");
        screenshot(key + "-after-deferred-cleanup");
        enabled(product, false); // Also runs production shutdown and scope closure.
        check(edt(() -> !newest.isDisplayable()), key + ".shutdownDisposed");
        check(edt(() -> !first.isDisplayable() && !replacement.isDisplayable()), key + ".noOldWindows");
        enabled(product, true);
        Object retainedGeneration = loaded(product);
        open(retainedGeneration, product);
        Window retainedWindow = awaitWindow(retainedGeneration, product);
        List<Runnable> cleanups = retainCleanup(instance(retainedGeneration));
        enabled(product, false);
        check(!cleanups.isEmpty(), key + ".taskHookCapturedProductCleanup");
        check(edt(retainedWindow::isDisplayable), key + ".taskHookOldWindowRetained");
        enabled(product, true);
        Object afterRetained = loaded(product);
        open(afterRetained, product);
        Window afterWindow = awaitWindow(afterRetained, product);
        edt(() -> { for (Runnable cleanup : cleanups) cleanup.run(); return null; });
        check(edt(() -> !retainedWindow.isDisplayable()), key + ".delayedClosureDisposedOld");
        check(edt(afterWindow::isDisplayable), key + ".delayedClosurePreservedNew");
        screenshot(key + "-after-retained-closure");
        enabled(product, false);
        check(edt(() -> !afterWindow.isDisplayable()), key + ".finalShutdownDisposed");
        RESULT.setProperty(key + ".status", "PASS");
        save();
    }
    private static void save() throws Exception {
        Files.createDirectories(output);
        try (var stream = Files.newOutputStream(output.resolve("result.txt"))) { RESULT.store(stream, "Product UI lifecycle host assertions"); }
    }
    private static void run() {
        try {
            Files.createDirectories(output);
            Field f = Class.forName("dev.turboism.bootstrap.TurboismAgent").getDeclaredField("RUNTIME");
            f.setAccessible(true); AtomicReference<?> ref = (AtomicReference<?>) f.get(null);
            long end = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
            while (System.nanoTime() < end) {
                edt(() -> dev.turboism.validation.NativeFreeStartup.selectFreeOnly());
                if (ref.get() != null) {
                    runtime = field(ref.get(), "pluginRuntime");
                    coordinator = call(runtime, "hotReloadCoordinator");
                    boolean all = true;
                    for (String[] p : PRODUCTS) all &= (Boolean)call(coordinator,"isLoaded",id(p));
                    if (all) break;
                }
                Thread.sleep(300);
            }
            check(runtime != null, "runtimeStarted");
            management = field(runtime, "pluginManagement");
            Thread.sleep(3000);
            for (String[] product : PRODUCTS) test(product);
            RESULT.setProperty("status", "PASS");
        } catch (Throwable failure) {
            failure.printStackTrace(); RESULT.setProperty("status", "FAIL"); RESULT.setProperty("failure", failure.toString());
        } finally {
            try { save(); } catch (Exception e) { e.printStackTrace(); }
            System.out.println("PRODUCT_UI_LIFECYCLE_RESULT status=" + RESULT.getProperty("status"));
            Runtime.getRuntime().exit("PASS".equals(RESULT.getProperty("status")) ? 0 : 2);
        }
    }
}
