package dev.turboism.tests.preview;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Compiles the SDK-only fixture source and packs it as a plugin JAR. */
final class PreviewContextServicesFixtureArchive {

    private static final String SOURCE_PATH =
        "dev/example/previewcontextservices/PreviewContextServicesPlugin.java";
    private static final String CLASS_PATH =
        "dev/example/previewcontextservices/PreviewContextServicesPlugin.class";

    private PreviewContextServicesFixtureArchive() {
    }

    static Path write(
        final Path plugins,
        final Path temporary,
        final String markerDirectoryProperty
    ) throws Exception {
        Files.createDirectories(plugins);
        final Path source = writeSource(temporary, markerDirectoryProperty);
        final Path classes = temporary.resolve("context-services-fixture-classes");
        compile(source, classes);
        requireClass(classes);
        return writeArchive(plugins, classes);
    }

    private static Path writeSource(
        final Path temporary,
        final String markerDirectoryProperty
    ) throws IOException {
        final Path source = temporary.resolve("context-services-fixture-source").resolve(SOURCE_PATH);
        Files.createDirectories(source.getParent());
        Files.writeString(source, PreviewContextServicesFixtureResources.source(markerDirectoryProperty),
            StandardCharsets.UTF_8);
        return source;
    }

    private static void compile(final Path source, final Path classes) throws IOException {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("JDK compiler is unavailable");
        }
        Files.createDirectories(classes);
        final int result = compiler.run(null, null, null, "-classpath",
            System.getProperty("java.class.path"), "-d", classes.toString(), source.toString());
        if (result != 0) {
            throw new IOException("Context services fixture plugin compilation failed with exit code " + result);
        }
    }

    private static void requireClass(final Path classes) throws IOException {
        if (!Files.exists(classes.resolve(CLASS_PATH))) {
            throw new IOException("Context services fixture plugin class was not compiled");
        }
    }

    private static Path writeArchive(final Path plugins, final Path classes) throws IOException {
        final Path jar = plugins.resolve("preview-context-services-plugin.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            addClasses(output, classes);
            add(output, "META-INF/turboism/plugin.json", PreviewContextServicesFixtureResources.descriptor());
        }
        if (!Files.isRegularFile(jar)) {
            throw new IOException("Context services fixture plugin JAR was not created");
        }
        return jar;
    }

    private static void addClasses(final JarOutputStream output, final Path classes) throws IOException {
        try (var paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList()) {
                add(output, classes.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
            }
        }
    }

    private static void add(final JarOutputStream output, final String name, final byte[] value)
        throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(value);
        output.closeEntry();
    }
}
