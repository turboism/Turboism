package dev.turboism.test;

import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.test.plugin.NoopPluginLogger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Shared, dependency-light stubs for SDK types used across module tests.
 *
 * <p>Only JDK and {@code :sdk} types belong here. Runtime-owned contracts live in
 * {@code dev.turboism.testkit.RuntimeTestKit} inside {@code :runtime}.
 */
public final class Stubs {

    private Stubs() {}

    /** Returns the stateless logger that discards every message. */
    public static PluginLogger noopLogger() {
        return NoopPluginLogger.INSTANCE;
    }

    /** Returns a permission with the common {@code read}/{@code test} scope and reason. */
    public static PluginPermission permission(final String id) {
        return permission(id, "read", "test");
    }

    /** Returns a permission with explicit scope and reason values. */
    public static PluginPermission permission(final String id, final String scope, final String reason) {
        return new SimplePermission(id, scope, reason);
    }

    /** Returns an empty sequence whose indexed access reports the requested index. */
    public static IntSequence emptyInts() {
        return EmptyIntSequence.INSTANCE;
    }

    /** Returns an empty sequence whose indexed access throws without a message. */
    public static IntSequence emptyIntsWithoutIndex() {
        return EmptyIntSequenceWithoutIndex.INSTANCE;
    }

    /** Returns an empty sequence whose indexed access reports the requested index. */
    public static FloatSequence emptyFloats() {
        return EmptyFloatSequence.INSTANCE;
    }

    /** Returns an empty sequence whose indexed access throws without a message. */
    public static FloatSequence emptyFloatsWithoutIndex() {
        return EmptyFloatSequenceWithoutIndex.INSTANCE;
    }

    /** Returns the JVM default value for a primitive type, or {@code null} otherwise. */
    public static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        return null;
    }

    /** Returns the lowercase SHA-256 hex digest of the supplied bytes. */
    public static String sha256Hex(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Walks up from the current working directory to the project root. */
    public static Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("project root is unavailable");
        }
        return current;
    }

    /** Builds a classloader over every JAR next to {@code artifact}, using the platform loader. */
    public static URLClassLoader jarClassLoader(final Path artifact) {
        return jarClassLoader(artifact, ClassLoader.getPlatformClassLoader());
    }

    /** Builds a classloader over every JAR next to {@code artifact}, using {@code parent}. */
    public static URLClassLoader jarClassLoader(final Path artifact, final ClassLoader parent) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(parent, "parent");
        try (Stream<Path> files = Files.list(artifact.getParent())) {
            final URL[] classpath = files.filter(
                            path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .map(path -> {
                        try {
                            return path.toUri().toURL();
                        } catch (java.net.MalformedURLException exception) {
                            throw new IllegalArgumentException(exception);
                        }
                    })
                    .toArray(URL[]::new);
            return new URLClassLoader(classpath, parent);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Returns an empty diagnostic report with the supplied creation instant. */
    public static DiagnosticReport diagnostics(final Instant createdAt) {
        return diagnostics(createdAt, List.of());
    }

    /** Returns a diagnostic report with the supplied creation instant and problems. */
    public static DiagnosticReport diagnostics(final Instant createdAt, final List<DiagnosticReport.Problem> problems) {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(problems, "problems");
        return new SimpleDiagnosticReport(createdAt, List.copyOf(problems));
    }

    /** Returns the common test descriptor used by compiled Cubism facade fixtures. */
    public static PluginDescriptor pluginDescriptor(final String id) {
        return pluginDescriptor(id, List.of());
    }

    /** Returns the common test descriptor with explicit permissions. */
    public static PluginDescriptor pluginDescriptor(
            final String id, final List<PluginDescriptor.PermissionRef> permissions) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(permissions, "permissions");
        return new SimplePluginDescriptor(id, List.copyOf(permissions));
    }

    private record SimplePermission(String id, String scope, String reason) implements PluginPermission {
        private SimplePermission {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private static final class EmptyIntSequence implements IntSequence {
        private static final EmptyIntSequence INSTANCE = new EmptyIntSequence();

        @Override
        public int size() {
            return 0;
        }

        @Override
        public int get(final int index) {
            throw new IndexOutOfBoundsException(index);
        }
    }

    private static final class EmptyIntSequenceWithoutIndex implements IntSequence {
        private static final EmptyIntSequenceWithoutIndex INSTANCE = new EmptyIntSequenceWithoutIndex();

        @Override
        public int size() {
            return 0;
        }

        @Override
        public int get(final int index) {
            throw new IndexOutOfBoundsException();
        }
    }

    private static final class EmptyFloatSequence implements FloatSequence {
        private static final EmptyFloatSequence INSTANCE = new EmptyFloatSequence();

        @Override
        public int size() {
            return 0;
        }

        @Override
        public float get(final int index) {
            throw new IndexOutOfBoundsException(index);
        }
    }

    private static final class EmptyFloatSequenceWithoutIndex implements FloatSequence {
        private static final EmptyFloatSequenceWithoutIndex INSTANCE = new EmptyFloatSequenceWithoutIndex();

        @Override
        public int size() {
            return 0;
        }

        @Override
        public float get(final int index) {
            throw new IndexOutOfBoundsException();
        }
    }

    private record SimpleDiagnosticReport(Instant createdAt, List<DiagnosticReport.Problem> problems)
            implements DiagnosticReport {}

    private record SimpleI18n() implements PluginDescriptor.I18n {
        @Override
        public String baseName() {
            return "messages";
        }

        @Override
        public List<String> locales() {
            return List.of();
        }
    }

    private record SimpleEnvironment() implements PluginDescriptor.Environment {
        @Override
        public boolean requiresCubism() {
            return false;
        }

        @Override
        public String ui() {
            return "none";
        }
    }

    private record SimplePluginDescriptor(String id, List<PluginDescriptor.PermissionRef> permissions)
            implements PluginDescriptor {

        @Override
        public String name() {
            return id;
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public String description() {
            return "test";
        }

        @Override
        public List<String> entrypoints() {
            return List.of();
        }

        @Override
        public String turboismApi() {
            return "[0.67.0,1.0.0)";
        }

        @Override
        public List<Author> authors() {
            return List.of();
        }

        @Override
        public String license() {
            return "UNLICENSED";
        }

        @Override
        public Optional<String> website() {
            return Optional.empty();
        }

        @Override
        public List<String> resources() {
            return List.of();
        }

        @Override
        public I18n i18n() {
            return new SimpleI18n();
        }

        @Override
        public List<DependencyRef> dependencies() {
            return List.of();
        }

        @Override
        public List<String> capabilities() {
            return List.of();
        }

        @Override
        public Environment environment() {
            return new SimpleEnvironment();
        }
    }
}
