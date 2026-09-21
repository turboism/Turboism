package dev.turboism.preview;

import dev.turboism.adapter.host.HostSession;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;

import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Production-classpath headless gate: constructs {@link LocalPluginRuntime} with no core
 * entrypoint, loads a real external plugin JAR, and closes — all while the JVM classpath
 * provably contains no {@code dev.turboism.plugin.core.*} classes. The external fixture also
 * verifies that an ordinary plugin cannot resolve internal management contracts
 * ({@code dev.turboism.internal.*}) through its classloader's parent boundary.
 *
 * <p>Run by {@code :runtime:checkHeadlessRuntimeClasspath} with
 * {@code sourceSets.main.runtimeClasspath}, which no longer carries the core UI module.</p>
 */
public final class HeadlessRuntimeProbeMain {
    private static final String PLUGIN_ID = "dev.example.headless";
    private static final String LOADED_MARKER = "dev.turboism.probe.headless-loaded";
    private static final String DENIED_MARKER = "dev.turboism.probe.internal-denied";
    private static final String CONTRACT_PROBE =
        "dev.turboism.internal.core.CorePluginManagement";

    private HeadlessRuntimeProbeMain() { }

    public static void main(final String[] args) throws Exception {
        final Path home = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(home.resolve("plugins"));

        assertCoreAbsent();
        writePluginJar(home);

        final RuntimeScheduler scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 16, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
        final HostSession host = new HostSession(Optional::empty);
        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime =
                new LocalPluginRuntime(home, scheduler, host.adapterAccess(), log);
            try {
                final LocalPluginRuntime.LoadReport report = runtime.loadAll();
                require(
                    report.loaded().stream().noneMatch(plugin -> plugin.id().equals("turboism.core")),
                    "headless runtime must not report a built-in core"
                );
                require(
                    report.loaded().stream().anyMatch(plugin ->
                        plugin.id().equals(PLUGIN_ID) && plugin.state().name().equals("ENABLED")),
                    "external plugin must load ENABLED without core classes"
                );
                require(report.failures().isEmpty(), "external load must not record failures");
                require(
                    Boolean.getBoolean(LOADED_MARKER),
                    "external plugin entrypoint did not initialize"
                );
                require(
                    "denied".equals(System.getProperty(DENIED_MARKER)),
                    "plugin classloader resolved an internal management contract"
                );
            } finally {
                runtime.close();
            }
        } finally {
            host.close();
            scheduler.shutdown();
        }
        System.out.println("HEADLESS-PROBE-PASS");
    }

    /** The probe classpath must contain no core UI implementation classes at all. */
    private static void assertCoreAbsent() {
        try {
            Class.forName("dev.turboism.plugin.core.MainToolbarPlugin", false,
                HeadlessRuntimeProbeMain.class.getClassLoader());
            throw new AssertionError(
                "core UI class resolvable on a supposedly headless classpath"
            );
        } catch (ClassNotFoundException expected) {
            // expected: the production runtime classpath no longer carries :plugins:core
        }
    }

    private static void writePluginJar(final Path home) throws Exception {
        final Path work = home.resolve("fixture-build");
        final Path source = work.resolve("source/dev/example/HeadlessFixture.java");
        final Path classes = work.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package dev.example;
            import dev.turboism.sdk.plugin.PluginContext;
            import dev.turboism.sdk.plugin.TurboismPlugin;
            public final class HeadlessFixture implements TurboismPlugin {
                @Override public void init(final PluginContext context) {
                    System.setProperty("%s", "true");
                    try {
                        Class.forName("%s");
                        System.setProperty("%s", "leaked");
                    } catch (ClassNotFoundException denied) {
                        System.setProperty("%s", "denied");
                    }
                }
            }
            """.formatted(LOADED_MARKER, CONTRACT_PROBE, DENIED_MARKER, DENIED_MARKER),
            StandardCharsets.UTF_8);
        Files.createDirectories(classes);
        final int compiled = ToolProvider.getSystemJavaCompiler().run(
            null, null, null,
            "-classpath", System.getProperty("java.class.path"),
            "-d", classes.toString(),
            source.toString()
        );
        if (compiled != 0) throw new IllegalStateException("fixture compilation failed");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(
            home.resolve("plugins/headless-fixture.jar")))) {
            for (Path file : Files.walk(classes).filter(Files::isRegularFile)
                .sorted(Comparator.naturalOrder()).toList()) {
                add(output,
                    classes.relativize(file).toString().replace('\\', '/'),
                    Files.readAllBytes(file));
            }
            add(output, "META-INF/turboism/plugin.json", descriptor().getBytes(StandardCharsets.UTF_8));
            add(output, "META-INF/turboism/i18n/messages.properties", new byte[0]);
        }
    }

    private static String descriptor() {
        return """
            {"format":"turboism.plugin.meta","schemaVersion":2,"id":"dev.example.headless",
            "name":"HeadlessFixture","version":"0.1.0","description":"probe",
            "entrypoints":["dev.example.HeadlessFixture"],"turboismApi":"[0.1.0,0.2.0)",
            "authors":[{"name":"Probe"}],"license":"Test","website":"https://turboism.dev",
            "resources":[],"i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]},
            "dependencies":[],"permissions":[],"capabilities":[],
            "environment":{"requiresCubism":false,"ui":"none"}}
            """;
    }

    private static void add(
        final JarOutputStream output,
        final String name,
        final byte[] bytes
    ) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }
}
