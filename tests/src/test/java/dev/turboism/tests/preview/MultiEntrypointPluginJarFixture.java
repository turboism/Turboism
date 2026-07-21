package dev.turboism.tests.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Builds a real plugin JAR with two ordered lifecycle entrypoints. */
final class MultiEntrypointPluginJarFixture {

    static final String PLUGIN_ID = "dev.example.multi-entrypoint";
    static final String MARKER_PROPERTY =
        "dev.turboism.tests.preview.multi-entrypoint.marker";
    static final String FAIL_PROPERTY =
        "dev.turboism.tests.preview.multi-entrypoint.fail-second-enable";
    private static final String FIRST =
        "dev.example.multientrypoint.FirstEntrypoint";
    private static final String SECOND =
        "dev.example.multientrypoint.SecondEntrypoint";

    private MultiEntrypointPluginJarFixture() {
    }

    static Path write(final Path plugins, final Path temporary) throws Exception {
        final Path sourceRoot = temporary.resolve("multi-entrypoint-source");
        final Path classes = temporary.resolve("multi-entrypoint-classes");
        writeSource(sourceRoot, "FirstEntrypoint", false);
        writeSource(sourceRoot, "SecondEntrypoint", true);
        compile(sourceRoot, classes);
        Files.createDirectories(plugins);
        final Path jar = plugins.resolve("multi-entrypoint-plugin.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var paths = Files.walk(classes)) {
                for (Path path : paths.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder()).toList()) {
                    add(
                        output,
                        classes.relativize(path).toString().replace('\\', '/'),
                        Files.readAllBytes(path)
                    );
                }
            }
            add(output, "META-INF/turboism/plugin.json", descriptor());
        }
        return jar;
    }

    private static void writeSource(
        final Path root,
        final String simpleName,
        final boolean second
    ) throws IOException {
        final Path source = root.resolve(
            "dev/example/multientrypoint/" + simpleName + ".java"
        );
        Files.createDirectories(source.getParent());
        final String id = second ? "B" : "A";
        final String fail = second
            ? "if (Boolean.getBoolean(\"" + FAIL_PROPERTY + "\")) throw new IllegalStateException(\"second enable failed\");"
            : "";
        Files.writeString(source, """
            package dev.example.multientrypoint;

            import dev.turboism.sdk.plugin.PluginContext;
            import dev.turboism.sdk.plugin.TurboismPlugin;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.StandardOpenOption;

            public final class %s implements TurboismPlugin {
                private static void record(String phase) throws Exception {
                    Path marker = Path.of(System.getProperty("%s"));
                    Files.createDirectories(marker.getParent());
                    Files.writeString(
                        marker,
                        phase + ":%s\\n",
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                    );
                }

                @Override public void init(PluginContext context) throws Exception {
                    record("init");
                }

                @Override public void enable() throws Exception {
                    record("enable");
                    %s
                }

                @Override public void disable() throws Exception {
                    record("disable");
                }

                @Override public void shutdown() throws Exception {
                    record("shutdown");
                }
            }
            """.formatted(simpleName, MARKER_PROPERTY, id, fail), StandardCharsets.UTF_8);
    }

    private static void compile(final Path sourceRoot, final Path classes)
        throws IOException {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("JDK compiler is unavailable");
        }
        Files.createDirectories(classes);
        final String[] sources;
        try (var paths = Files.walk(sourceRoot)) {
            sources = paths.filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .map(Path::toString)
                .toArray(String[]::new);
        }
        final String[] arguments = new String[5 + sources.length];
        arguments[0] = "-classpath";
        arguments[1] = System.getProperty("java.class.path");
        arguments[2] = "-d";
        arguments[3] = classes.toString();
        arguments[4] = "-parameters";
        System.arraycopy(sources, 0, arguments, 5, sources.length);
        final int result = compiler.run(null, null, null, arguments);
        if (result != 0) {
            throw new IOException("Multi-entrypoint fixture compilation failed: " + result);
        }
    }

    private static byte[] descriptor() throws IOException {
        final ObjectNode descriptor = new ObjectMapper().createObjectNode();
        descriptor.put("format", "turboism.plugin.meta");
        descriptor.put("schemaVersion", 2);
        descriptor.put("id", PLUGIN_ID);
        descriptor.put("name", "Multi Entrypoint Fixture");
        descriptor.put("version", "0.1.0");
        descriptor.put("description", "Verifies ordered atomic plugin entrypoints.");
        descriptor.putArray("entrypoints").add(FIRST).add(SECOND);
        descriptor.put("turboismApi", "[0.1.0,0.2.0)");
        descriptor.putArray("authors").addObject().put("name", "Turboism Tests");
        descriptor.put("license", "Test License");
        descriptor.put("website", "https://turboism.dev/tests");
        descriptor.putArray("resources");
        descriptor.putObject("i18n")
            .put("baseName", "META-INF/turboism/i18n/messages")
            .putArray("locales");
        descriptor.putArray("dependencies");
        descriptor.putArray("permissions");
        descriptor.putArray("capabilities");
        descriptor.putObject("environment")
            .put("requiresCubism", false)
            .put("ui", "none");
        return new ObjectMapper().writeValueAsBytes(descriptor);
    }

    private static void add(
        final JarOutputStream output,
        final String name,
        final byte[] content
    ) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(content);
        output.closeEntry();
    }
}
