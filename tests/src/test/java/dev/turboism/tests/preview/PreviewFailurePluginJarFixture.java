package dev.turboism.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Builds a plugin JAR that triggers reportable failures through PluginContext services. */
final class PreviewFailurePluginJarFixture {

    static final String PLUGIN_ID = "dev.example.preview-failures";
    static final String SECRET = "preview-secret-must-not-leak";
    private static final String ENTRYPOINT = "dev.example.previewfailures.PreviewFailurePlugin";

    private PreviewFailurePluginJarFixture() {
    }

    static Path write(final Path plugins, final Path temporary) throws Exception {
        Files.createDirectories(plugins);
        final Path sourceRoot = temporary.resolve("fixture-source");
        final Path classRoot = temporary.resolve("fixture-classes");
        final Path source = sourceRoot.resolve("dev/example/previewfailures/PreviewFailurePlugin.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, source());
        compile(source, classRoot);
        if (!Files.exists(classRoot.resolve("dev/example/previewfailures/PreviewFailurePlugin.class"))) {
            throw new IOException("Fixture plugin class was not compiled");
        }

        final Path jar = plugins.resolve("preview-failure-plugin.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            addClasses(output, classRoot);
            add(output, "META-INF/turboism/plugin.json", descriptor());
        }
        if (!Files.isRegularFile(jar)) {
            throw new IOException("Fixture plugin JAR was not created");
        }
        return jar;
    }

    private static void compile(final Path source, final Path classRoot) throws IOException {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("JDK compiler is unavailable");
        }
        Files.createDirectories(classRoot);
        final String classpath = System.getProperty("java.class.path");
        final int result = compiler.run(
            null,
            null,
            null,
            "-classpath",
            classpath,
            "-d",
            classRoot.toString(),
            source.toString()
        );
        if (result != 0) {
            throw new IOException("Fixture plugin compilation failed with exit code " + result);
        }
    }

    private static void addClasses(final JarOutputStream output, final Path classRoot) throws IOException {
        try (var paths = Files.walk(classRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList()) {
                add(output, classRoot.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
            }
        }
    }

    private static void add(final JarOutputStream output, final String name, final String value)
        throws IOException {
        add(output, name, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void add(final JarOutputStream output, final String name, final byte[] value)
        throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(value);
        output.closeEntry();
    }

    private static byte[] descriptor() throws IOException {
        final ObjectMapper mapper = new ObjectMapper();
        final ObjectNode descriptor = mapper.createObjectNode();
        descriptor.put("format", "turboism.plugin.meta");
        descriptor.put("schemaVersion", 1);
        descriptor.put("id", PLUGIN_ID);
        descriptor.put("name", "Preview Failure Fixture");
        descriptor.put("version", "0.1.0");
        descriptor.put("description", "Exercises report-safe preview failure collection.");
        final ObjectNode entrypoints = descriptor.putObject("entrypoints");
        entrypoints.put("plugin", ENTRYPOINT);
        descriptor.put("turboismApi", "[0.1.0,0.2.0)");
        final ArrayNode authors = descriptor.putArray("authors");
        authors.addObject().put("name", "Turboism Tests");
        descriptor.put("license", "Test License");
        descriptor.put("homepage", "https://turboism.dev/tests");
        descriptor.putArray("dependencies");
        final ArrayNode permissions = descriptor.putArray("permissions");
        permissions.addObject()
            .put("id", "turboism.config.plugin.read")
            .put("scope", "application")
            .put("reason", "Exercises typed config failure reporting.");
        descriptor.putArray("capabilities");
        descriptor.putObject("environment")
            .put("requiresCubism", false)
            .put("ui", "none");
        return mapper.writeValueAsBytes(descriptor);
    }

    private static String source() {
        return """
            package dev.example.previewfailures;

            import dev.turboism.sdk.config.ConfigCodecs;
            import dev.turboism.sdk.config.ConfigKey;
            import dev.turboism.sdk.plugin.PluginContext;
            import dev.turboism.sdk.plugin.TurboismPlugin;
            import dev.turboism.sdk.storage.StoragePath;
            import dev.turboism.sdk.storage.StorageRoot;
            import dev.turboism.sdk.task.PluginTaskKind;
            import dev.turboism.sdk.task.PluginTaskPriority;
            import dev.turboism.sdk.task.PluginTaskRequest;
            import dev.turboism.sdk.task.TaskId;
            import dev.turboism.sdk.ui.UserFileLifetime;
            import dev.turboism.sdk.ui.UserFileMode;
            import dev.turboism.sdk.ui.UserFileRequest;

            public final class PreviewFailurePlugin implements TurboismPlugin {
                @Override
                public void init(PluginContext context) {
                    context.tasks().submit(new PluginTaskRequest(
                        new TaskId("preview-secret-must-not-leak-task"),
                        PluginTaskKind.COMPUTE,
                        PluginTaskPriority.NORMAL,
                        token -> { }
                    ));
                    context.storage().readUtf8(
                        new StoragePath(StorageRoot.DATA, "preview-secret-must-not-leak/storage.txt"),
                        16
                    );
                    context.userFiles().request(new UserFileRequest(
                        "fixture-file",
                        "preview-secret-must-not-leak",
                        java.util.List.of("txt"),
                        UserFileMode.READ,
                        UserFileLifetime.UNTIL_DISABLE
                    ));
                    context.config().read(new ConfigKey<>(
                        "preview-secret-must-not-leak",
                        "enabled",
                        true,
                        ConfigCodecs.booleanValue()
                    ));
                    dev.turboism.sdk.plugin.Registration readScope = context.config().readScope(
                        "preview-secret-must-not-leak/legacy.properties"
                    );
                    try {
                        context.config().readString(
                            "preview-secret-must-not-leak/legacy.properties",
                            "preview-secret-must-not-leak"
                        );
                    } finally {
                        readScope.close();
                    }
                }
            }
            """;
    }
}
