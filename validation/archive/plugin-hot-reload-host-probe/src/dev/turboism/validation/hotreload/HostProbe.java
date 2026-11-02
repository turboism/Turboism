package dev.turboism.validation.hotreload;

import dev.turboism.preview.LocalPluginRuntime;
import dev.turboism.preview.PluginHotReloadCoordinator;
import dev.turboism.pluginmanagement.*;
import dev.turboism.pluginmanagement.PluginCatalog.*;
import dev.turboism.sdk.plugin.PluginContext;
import java.awt.*;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Validation-only secondary agent: exercises the real session, transport bytes are local fixtures. */
public final class HostProbe {
    private static final String ID = "dev.turboism.validation.hot-reload-fixture";
    private static Path home;

    public static void premain(String args, Instrumentation instrumentation) {
        home = Path.of(args);
        Thread worker = new Thread(HostProbe::run, "hot-reload-host-probe");
        worker.setDaemon(true);
        worker.start();
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static Object accessor(Object owner, String name) throws Exception {
        var method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(owner);
    }

    private static void record(String line) throws IOException {
        Files.writeString(home.resolve("result.txt"), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("HOT_RELOAD_PROBE " + line);
    }

    private static void check(boolean passed, String detail) throws Exception {
        record((passed ? "PASS " : "FAIL ") + detail);
        if (!passed) throw new AssertionError(detail);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void run() {
        try {
            Field runtimeField = Class.forName("dev.turboism.bootstrap.TurboismAgent").getDeclaredField("RUNTIME");
            runtimeField.setAccessible(true);
            AtomicReference<?> ref = (AtomicReference<?>) runtimeField.get(null);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
            while (ref.get() == null && System.nanoTime() < deadline) Thread.sleep(200);
            check(ref.get() != null, "real Cubism runtime started pid=" + ProcessHandle.current().pid());
            LocalPluginRuntime runtime = (LocalPluginRuntime) field(ref.get(), "pluginRuntime");
            PluginHotReloadCoordinator coordinator = runtime.hotReloadCoordinator();
            while (!coordinator.isLoaded(ID) && System.nanoTime() < deadline) Thread.sleep(200);
            check(coordinator.isLoaded(ID), "fixture loaded by real host");
            Thread.sleep(3000);
            Object old = coordinator.findLoaded(ID).orElseThrow();
            ClassLoader oldLoader = (ClassLoader) accessor(old, "classLoader");
            PluginContext stale = (PluginContext) oldLoader.loadClass("dev.turboism.validation.hotreload.FixturePlugin")
                    .getField("LAST_CONTEXT").get(null);
            screenshot("before");
            RuntimePluginManagementService management = (RuntimePluginManagementService) field(runtime, "pluginManagement");
            var result = update(coordinator, management, "0.2.0");
            check(result.code().equals("PLUGIN_HOT_APPLIED"), "market update immediately applied: " + result);
            check("0.2.0".equals(System.getProperty("turboism.validation.hotReload.version")), "new generation executed");
            check(accessor(coordinator.findLoaded(ID).orElseThrow(), "classLoader") != oldLoader, "classloader replaced");
            boolean fenced = false;
            try { stale.paths().dataDir(); } catch (IllegalStateException expected) { fenced = true; }
            check(fenced, "old context fenced");
            screenshot("after");
            check(management.setEnabled(ID, false).code().equals("PLUGIN_HOT_APPLIED"), "disable immediately applied");
            check(!coordinator.isLoaded(ID), "disabled generation unloaded");
            check(management.setEnabled(ID, true).code().equals("PLUGIN_HOT_APPLIED"), "enable immediately applied");
            check(coordinator.isLoaded(ID), "enabled generation active");
            check(update(coordinator, management, "0.4.0", true).code().equals("PLUGIN_HOT_APPLIED"), "automatic updater hot applied");
            check("0.4.0".equals(System.getProperty("turboism.validation.hotReload.version")), "automatic update generation executed");
            var pending = new PendingPluginOperations(home);
            var operations = PendingPluginOperations.class.getDeclaredMethod("operations");
            operations.setAccessible(true);
            check(((List<?>) operations.invoke(pending)).isEmpty(), "automatic update cleared pending journal");
            screenshot("automatic");
            result = update(coordinator, management, "0.3.0");
            record("broken update result=" + result);
            check(!result.accepted() && result.code().equals("PLUGIN_HOT_UPDATE_ROLLED_BACK"), "broken update rejected after rollback");
            check(((dev.turboism.core.plugin.PluginRuntime) accessor(coordinator.findLoaded(ID).orElseThrow(), "runtime")).descriptor().version().equals("0.4.0"), "broken update restored active generation");
            check(Arrays.equals(Files.readAllBytes(home.resolve("fixtures/0.4.0.jar")), Files.readAllBytes(home.resolve("plugins/" + ID + ".jar"))), "broken update restored installed artifact");
            check(((List<?>) operations.invoke(pending)).isEmpty(), "broken update not scheduled again at restart");
            screenshot("rollback");
            record("status=PASS");
        } catch (Throwable failure) {
            failure.printStackTrace();
            try { record("status=FAIL " + failure); } catch (IOException ignored) {}
        }
    }

    private static dev.turboism.internal.core.CorePluginManagement.OperationResult update(
            PluginHotReloadCoordinator coordinator, RuntimePluginManagementService management, String version) throws Exception {
        return update(coordinator, management, version, false);
    }

    private static dev.turboism.internal.core.CorePluginManagement.OperationResult update(
            PluginHotReloadCoordinator coordinator, RuntimePluginManagementService management, String version, boolean automatic) throws Exception {
        Path source = home.resolve("fixtures/" + version + ".jar");
        byte[] bytes = Files.readAllBytes(source);
        byte[] descriptor;
        try (JarFile jar = new JarFile(source.toFile())) {
            try (InputStream in = jar.getInputStream(jar.getJarEntry("META-INF/turboism/plugin.json"))) { descriptor = in.readAllBytes(); }
        }
        PluginCatalogClient client = new PluginCatalogClient(PluginCatalogClient.CATALOG_URI, PluginCatalogClient.SIGNATURE_URI,
                Duration.ofSeconds(10), uri -> new HttpURLConnection(uri.toURL()) {
                    public void connect() {}
                    public void disconnect() {}
                    public boolean usingProxy() { return false; }
                    public int getResponseCode() { return 200; }
                    public long getContentLengthLong() { return bytes.length; }
                    public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
                });
        CatalogRelease release = new CatalogRelease(version, "stable", "active", Instant.now(), "workflow", List.of("test"),
                "[0.67.0,1.0.0)", false, List.of(), List.of("windows-x64"), List.of(), List.of(),
                "https://github.com/turboism/test/releases/tag/v" + version, Optional.empty(),
                new CatalogArtifact("application/java-archive", "fixture.jar", "https://github.com/turboism/test/releases/download/v" + version + "/fixture.jar",
                        hash(bytes), hash(descriptor), bytes.length), true);
        CatalogPlugin plugin = new CatalogPlugin(ID, "fixture", "Fixture", "Hot reload probe", "official", "Test", "MIT",
                "https://github.com/turboism/test", "https://github.com/turboism/test/issues", Map.of(), List.of(release));
        if (automatic) {
            Object existing = field(management, "autoUpdater");
            try (var updater = new RuntimePluginAutoUpdater(home,
                    (dev.turboism.core.runtime.RuntimeScheduler) field(existing, "scheduler"),
                    (dev.turboism.sdk.runtime.RuntimeSettingsService) field(existing, "runtimeSettings"),
                    client, new PluginCatalogVerifier(), Clock.systemUTC(),
                    dev.turboism.core.FrameworkBuildInfo.current().channel(), Optional::empty,
                    dev.turboism.core.FrameworkBuildInfo.current().apiVersion(), Duration.ZERO, Duration.ofHours(1),
                    message -> System.out.println("HOT_RELOAD_AUTO " + message))) {
                updater.bindHotReloadCoordinator(coordinator);
                var method = RuntimePluginAutoUpdater.class.getDeclaredMethod("downloadVerifyAndStage",
                        PluginAutoUpdatePlanner.PlannedUpdate.class, Optional.class);
                method.setAccessible(true);
                Object result = method.invoke(updater, new PluginAutoUpdatePlanner.PlannedUpdate(plugin, release,
                        dev.turboism.core.version.PluginVersion.parse("0.2.0"), dev.turboism.core.version.PluginVersion.parse(version)), Optional.empty());
                record("automatic download/verify/stage result=" + result);
                return dev.turboism.internal.core.CorePluginManagement.OperationResult.accepted(
                        result.toString().equals("HOT_APPLIED") ? "PLUGIN_HOT_APPLIED" : result.toString(), "Automatic updater probe");
            }
        }
        try (PluginMarketService market = new PluginMarketService(home, client, new PluginCatalogVerifier(),
                new PluginCatalogStateStore(home), new PendingPluginOperations(home), management, Optional::empty,
                dev.turboism.core.FrameworkBuildInfo.current().apiVersion(), message -> {
                    try { record(message); } catch (IOException e) { throw new UncheckedIOException(e); }
                })) {
            market.bindHotReloadCoordinator(coordinator);
            return market.installOrUpdate(plugin, release).toCompletableFuture().get(60, TimeUnit.SECONDS);
        }
    }

    private static void screenshot(String name) throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
        Thread.sleep(500);
        ImageIO.write(new Robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())),
                "png", home.resolve(name + ".png").toFile());
    }
}
