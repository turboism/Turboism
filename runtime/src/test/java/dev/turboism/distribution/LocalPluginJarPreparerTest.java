package dev.turboism.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPluginJarPreparerTest {
    @TempDir Path root;

    @Test
    void stagesAValidDirectPluginJar() throws Exception {
        final Path source = root.resolve("sample.jar");
        Files.write(source, pluginJar("example.plugin", "1.0.0"));

        final LocalPluginJarPreparer.Prepared result = assertInstanceOf(
            LocalPluginJarPreparer.Prepared.class,
            new LocalPluginJarPreparer().prepare(source, root.resolve("staging"))
        );

        assertEquals("example.plugin", result.value().descriptor().id());
        assertEquals("1.0.0", result.value().descriptor().version());
        assertTrue(Files.isRegularFile(result.value().stagedJar()));
        assertEquals(Files.size(source), result.value().jarSize());
        assertEquals(64, result.value().jarSha256().length());
    }

    @Test
    void rejectsSourceMutationAfterSnapshot() throws Exception {
        final Path source = root.resolve("sample.jar");
        Files.write(source, pluginJar("example.plugin", "1.0.0"));
        final PackageAccess mutating = new PackageAccess() {
            @Override public void afterInitialHash(final Path path) throws java.io.IOException {
                Files.writeString(path, "changed");
            }
        };

        final LocalPluginJarPreparer.Preparation result =
            new LocalPluginJarPreparer(mutating).prepare(source, root.resolve("staging"));

        final LocalPluginJarPreparer.PreparationRejected rejected = assertInstanceOf(
            LocalPluginJarPreparer.PreparationRejected.class, result);
        assertEquals(DistributionErrors.PACKAGE_CHANGED, rejected.code());
        assertFalse(hasJar(root.resolve("staging")));
    }

    @Test
    void rejectsSymbolicLinkSource() throws Exception {
        final Path target = root.resolve("target.jar");
        final Path source = root.resolve("linked.jar");
        Files.write(target, pluginJar("example.plugin", "1.0.0"));
        try {
            Files.createSymbolicLink(source, target);
        } catch (UnsupportedOperationException failure) {
            return;
        }

        final LocalPluginJarPreparer.Preparation result =
            new LocalPluginJarPreparer().prepare(source, root.resolve("staging"));

        assertInstanceOf(LocalPluginJarPreparer.PreparationRejected.class, result);
        assertFalse(hasJar(root.resolve("staging")));
    }

    /**
     * The F2 regression: a schema-v5 plugin embedding a declared, correctly pinned,
     * class-only contract artifact must be accepted by managed direct installation,
     * exactly as drop-in discovery already accepts it.
     */
    @Test
    void stagesSchemaV5PluginWithDeclaredContractArtifact() throws Exception {
        final byte[] artifact = contractArtifact(root, Map.of(
            EVENT_TYPE, """
                package com.acme.events;
                public record Greeting(com.acme.events.GreetingPayload payload)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """,
            "com.acme.events.GreetingPayload", """
                package com.acme.events;
                public record GreetingPayload(String text) {}
                """
        ));
        final Path source = root.resolve("contracted.jar");
        Files.write(source, v5ContractPluginJar(
            "dev.example.provider", artifact, sha256Hex(artifact), Map.of()
        ));

        final LocalPluginJarPreparer.Prepared result = assertInstanceOf(
            LocalPluginJarPreparer.Prepared.class,
            new LocalPluginJarPreparer().prepare(source, root.resolve("staging"))
        );

        assertEquals("dev.example.provider", result.value().descriptor().id());
        assertTrue(Files.isRegularFile(result.value().stagedJar()));
    }

    @Test
    void rejectsUndeclaredContractArtifact() throws Exception {
        final byte[] artifact = contractArtifact(root, Map.of(
            EVENT_TYPE, """
                package com.acme.events;
                public record Greeting(String payload)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """
        ));
        final Path source = root.resolve("undeclared-contract.jar");
        Files.write(source, v5ContractPluginJar(
            "dev.example.provider", artifact, null, Map.of()
        ));

        final LocalPluginJarPreparer.PreparationRejected rejected = assertInstanceOf(
            LocalPluginJarPreparer.PreparationRejected.class,
            new LocalPluginJarPreparer().prepare(source, root.resolve("staging"))
        );
        assertEquals("PLUGIN_CONTRACT_ARTIFACT_UNDECLARED", rejected.code());
        assertFalse(hasJar(root.resolve("staging")));
    }

    @Test
    void rejectsNestedJarOutsideContractsDirectory() throws Exception {
        final byte[] artifact = contractArtifact(root, Map.of(
            EVENT_TYPE, """
                package com.acme.events;
                public record Greeting(String payload)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """
        ));
        final Path source = root.resolve("nested-outside.jar");
        Files.write(source, v5ContractPluginJar(
            "dev.example.provider", artifact, sha256Hex(artifact),
            Map.of("lib/helper.jar", new byte[]{1, 2, 3})
        ));

        final LocalPluginJarPreparer.PreparationRejected rejected = assertInstanceOf(
            LocalPluginJarPreparer.PreparationRejected.class,
            new LocalPluginJarPreparer().prepare(source, root.resolve("staging"))
        );
        assertEquals("PLUGIN_CONTENT_CONTAMINATION", rejected.code());
        assertFalse(hasJar(root.resolve("staging")));
    }

    @Test
    void rejectsContractArtifactWithMismatchedSha256() throws Exception {
        final byte[] artifact = contractArtifact(root, Map.of(
            EVENT_TYPE, """
                package com.acme.events;
                public record Greeting(String payload)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """
        ));
        final Path source = root.resolve("wrong-hash.jar");
        Files.write(source, v5ContractPluginJar(
            "dev.example.provider", artifact, "0".repeat(64), Map.of()
        ));

        final LocalPluginJarPreparer.PreparationRejected rejected = assertInstanceOf(
            LocalPluginJarPreparer.PreparationRejected.class,
            new LocalPluginJarPreparer().prepare(source, root.resolve("staging"))
        );
        assertEquals("PLUGIN_CONTRACT_ARTIFACT_HASH_MISMATCH", rejected.code());
        assertFalse(hasJar(root.resolve("staging")));
    }

    /**
     * A contract event type whose payload references a class that is neither part of
     * the artifact nor reachable through the SDK/JDK closure must fail admission.
     */
    @Test
    void rejectsContractArtifactViolatingPayloadClosure() throws Exception {
        final Path foreign = compileSources(root, Map.of(
            "com.evil.External", """
                package com.evil;
                public record External(String marker) {}
                """
        ));
        final byte[] artifact = contractArtifact(root, Map.of(
            EVENT_TYPE, """
                package com.acme.events;
                public record Greeting(com.evil.External smuggled)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """
        ), foreign);
        final Path source = root.resolve("bad-payload.jar");
        Files.write(source, v5ContractPluginJar(
            "dev.example.provider", artifact, sha256Hex(artifact), Map.of()
        ));

        final LocalPluginJarPreparer.PreparationRejected rejected = assertInstanceOf(
            LocalPluginJarPreparer.PreparationRejected.class,
            new LocalPluginJarPreparer().prepare(source, root.resolve("staging"))
        );
        assertEquals("PLUGIN_CONTRACT_ARTIFACT_INVALID", rejected.code());
        assertFalse(hasJar(root.resolve("staging")));
    }

    @Test
    void rejectsContractArtifactWithNonClassEntry() throws Exception {
        final byte[] artifact = contractArtifact(root, Map.of(
            EVENT_TYPE, """
                package com.acme.events;
                public record Greeting(String payload)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """
        ), Map.of("com/acme/events/config.json", "{}".getBytes(StandardCharsets.UTF_8)));
        final Path source = root.resolve("non-class.jar");
        Files.write(source, v5ContractPluginJar(
            "dev.example.provider", artifact, sha256Hex(artifact), Map.of()
        ));

        final LocalPluginJarPreparer.PreparationRejected rejected = assertInstanceOf(
            LocalPluginJarPreparer.PreparationRejected.class,
            new LocalPluginJarPreparer().prepare(source, root.resolve("staging"))
        );
        assertEquals("PLUGIN_CONTRACT_ARTIFACT_INVALID", rejected.code());
        assertFalse(hasJar(root.resolve("staging")));
    }

    @Test
    void rejectsContractArtifactWithMalformedClass() throws Exception {
        final byte[] artifact = contractArtifact(root, Map.of(
            EVENT_TYPE, """
                package com.acme.events;
                public record Greeting(String payload)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """
        ), Map.of(
            "com/acme/events/Broken.class",
            "not a class file".getBytes(StandardCharsets.UTF_8)
        ));
        final Path source = root.resolve("malformed.jar");
        Files.write(source, v5ContractPluginJar(
            "dev.example.provider", artifact, sha256Hex(artifact), Map.of()
        ));

        final LocalPluginJarPreparer.PreparationRejected rejected = assertInstanceOf(
            LocalPluginJarPreparer.PreparationRejected.class,
            new LocalPluginJarPreparer().prepare(source, root.resolve("staging"))
        );
        assertEquals("PLUGIN_CONTRACT_ARTIFACT_INVALID", rejected.code());
        assertFalse(hasJar(root.resolve("staging")));
    }

    @Test
    void rejectsOversizedContractArtifact() throws Exception {
        // The 8 MiB bound applies to the embedded artifact JAR bytes, so the padding
        // must be incompressible to inflate the archive past the limit.
        final byte[] padding = new byte[9 * 1024 * 1024];
        new java.util.Random(7).nextBytes(padding);
        final byte[] artifact = contractArtifact(root, Map.of(
            EVENT_TYPE, """
                package com.acme.events;
                public record Greeting(String payload)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """
        ), Map.of("com/acme/events/Padding.class", padding));
        final Path source = root.resolve("oversized.jar");
        Files.write(source, v5ContractPluginJar(
            "dev.example.provider", artifact, sha256Hex(artifact), Map.of()
        ));

        final LocalPluginJarPreparer.PreparationRejected rejected = assertInstanceOf(
            LocalPluginJarPreparer.PreparationRejected.class,
            new LocalPluginJarPreparer().prepare(source, root.resolve("staging"))
        );
        assertEquals("PLUGIN_CONTRACT_ARTIFACT_TOO_LARGE", rejected.code());
        assertFalse(hasJar(root.resolve("staging")));
    }

    /**
     * A loose plugin class shadowing a contract member that is not the declared
     * event type must still fail admission — the payload companion is not an
     * eventType, so the public-event-API embedding rule does not fire first.
     */
    @Test
    void rejectsLoosePluginClassShadowingContractMember() throws Exception {
        final byte[] artifact = contractArtifact(root, Map.of(
            EVENT_TYPE, """
                package com.acme.events;
                public record Greeting(com.acme.events.GreetingPayload payload)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """,
            "com.acme.events.GreetingPayload", """
                package com.acme.events;
                public record GreetingPayload(String text) {}
                """
        ));
        final Path source = root.resolve("shadowed.jar");
        Files.write(source, v5ContractPluginJar(
            "dev.example.provider", artifact, sha256Hex(artifact),
            Map.of("com/acme/events/GreetingPayload.class", new byte[]{0})
        ));

        final LocalPluginJarPreparer.PreparationRejected rejected = assertInstanceOf(
            LocalPluginJarPreparer.PreparationRejected.class,
            new LocalPluginJarPreparer().prepare(source, root.resolve("staging"))
        );
        assertEquals("PLUGIN_CONTRACT_ARTIFACT_CLASS_COLLISION", rejected.code());
        assertFalse(hasJar(root.resolve("staging")));
    }

    /**
     * Admission must never define, initialize, or execute contract classes. The
     * fixture arms a static initializer that flips a system property; a passing
     * prepare with the flag untouched is the targeted no-execution evidence.
     */
    @Test
    void contractPreflightDoesNotInitializeContractClasses() throws Exception {
        final String marker = "turboism.contract.preflight.clinit";
        System.clearProperty(marker);
        try {
            final byte[] artifact = contractArtifact(root, Map.of(
                EVENT_TYPE, """
                    package com.acme.events;
                    public record Greeting(String payload)
                        implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                    """,
                "com.acme.events.Armed", """
                    package com.acme.events;
                    public final class Armed {
                        static {
                            System.setProperty(
                                "turboism.contract.preflight.clinit", "fired");
                        }
                    }
                    """
            ));
            final Path source = root.resolve("armed.jar");
            Files.write(source, v5ContractPluginJar(
                "dev.example.provider", artifact, sha256Hex(artifact), Map.of()
            ));

            assertInstanceOf(
                LocalPluginJarPreparer.Prepared.class,
                new LocalPluginJarPreparer().prepare(source, root.resolve("staging"))
            );
            assertNull(
                System.getProperty(marker),
                "admission must inspect contract bytes without initializing classes"
            );
        } finally {
            System.clearProperty(marker);
        }
    }

    private static boolean hasJar(final Path directory) throws Exception {
        if (!Files.isDirectory(directory)) return false;
        try (var paths = Files.list(directory)) {
            return paths.anyMatch(path -> path.getFileName().toString().endsWith(".jar"));
        }
    }

    private static byte[] pluginJar(final String id, final String version) throws Exception {
        final String descriptor = "{\"format\":\"turboism.plugin.meta\",\"schemaVersion\":2,"
            + "\"id\":\"" + id + "\",\"name\":\"Example\",\"version\":\"" + version + "\","
            + "\"description\":\"Example\",\"entrypoints\":[\"example.Plugin\"],"
            + "\"turboismApi\":\"[0.1.0,0.2.0)\",\"authors\":[{\"name\":\"Test\"}],"
            + "\"license\":\"Test\",\"website\":\"https://example.test\",\"resources\":[],"
            + "\"i18n\":{\"baseName\":\"META-INF/turboism/i18n/messages\",\"locales\":[]},"
            + "\"dependencies\":[],\"permissions\":[],\"capabilities\":[],"
            + "\"environment\":{\"requiresCubism\":false,\"ui\":\"none\"}}";
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            add(jar, "META-INF/turboism/plugin.json", descriptor.getBytes(StandardCharsets.UTF_8));
            add(jar, "META-INF/turboism/i18n/messages.properties",
                "plugin.name=Example\nplugin.description=Example\n".getBytes(StandardCharsets.UTF_8));
            add(jar, "example/Plugin.class", new byte[]{0});
        }
        return output.toByteArray();
    }

    private static void add(final JarOutputStream jar, final String name, final byte[] bytes) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }

    // -- schema-v5 contract fixtures, shared with PluginPackageRaceRegressionTest --

    static final String EVENT_TYPE = "com.acme.events.Greeting";
    static final String CONTRACT_ARTIFACT_PATH =
        "META-INF/turboism/contracts/acme-events-1.0.0.jar";

    /**
     * Builds a schema-v5 plugin JAR embedding {@code contractBytes} at
     * {@link #CONTRACT_ARTIFACT_PATH}. {@code declaredSha256} pins the artifact in the
     * descriptor; {@code null} embeds the bytes undeclared.
     */
    static byte[] v5ContractPluginJar(
        final String pluginId,
        final byte[] contractBytes,
        final String declaredSha256,
        final Map<String, byte[]> extraEntries
    ) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            add(jar, "META-INF/turboism/plugin.json",
                v5Descriptor(pluginId, declaredSha256).getBytes(StandardCharsets.UTF_8));
            add(jar, "META-INF/turboism/i18n/messages.properties",
                "plugin.name=Contracted\n".getBytes(StandardCharsets.UTF_8));
            add(jar, "dev/example/provider/ProviderPlugin.class", new byte[]{0});
            if (contractBytes != null) {
                add(jar, CONTRACT_ARTIFACT_PATH, contractBytes);
            }
            for (final Map.Entry<String, byte[]> entry : extraEntries.entrySet()) {
                add(jar, entry.getKey(), entry.getValue());
            }
        }
        return output.toByteArray();
    }

    static String v5Descriptor(
        final String pluginId,
        final String declaredSha256
    ) {
        final String contracts = declaredSha256 == null ? "" : """
            ,"eventExports":[{"id":"greeting","contractVersion":"1.0.0",
              "eventType":"com.acme.events.Greeting","abiSha256":"%s"}],
            "eventContracts":[{"id":"acme.events","version":"1.0.0",
              "artifact":"%s","sha256":"%s"}]
            """.formatted("a".repeat(64), CONTRACT_ARTIFACT_PATH, declaredSha256);
        return """
            {"format":"turboism.plugin.meta","schemaVersion":5,
            "id":"%s","name":"%s","version":"0.1.0",
            "description":"test","entrypoints":["dev.example.provider.ProviderPlugin"],
            "turboismApi":"[0.1.0,0.2.0)","authors":[{"name":"Tests"}],
            "license":"Test","website":"https://turboism.dev","resources":[],
            "i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]},
            "dependencies":[],"permissions":[],"capabilities":[],
            "environment":{"requiresCubism":false,"ui":"none"},
            "category":"workflow"%s}
            """.formatted(pluginId, pluginId, contracts);
    }

    /** Compiles and packages a class-only contract artifact. */
    static byte[] contractArtifact(
        final Path workDir,
        final Map<String, String> sources,
        final Path... extraClasspath
    ) throws Exception {
        return contractArtifact(workDir, sources, Map.of(), extraClasspath);
    }

    static byte[] contractArtifact(
        final Path workDir,
        final Map<String, String> sources,
        final Map<String, byte[]> extraEntries,
        final Path... extraClasspath
    ) throws Exception {
        final Path classes = compileSources(workDir, sources, extraClasspath);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            try (var paths = Files.walk(classes)) {
                for (final Path file : paths.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder()).toList()) {
                    add(jar, classes.relativize(file).toString().replace('\\', '/'),
                        Files.readAllBytes(file));
                }
            }
            for (final Map.Entry<String, byte[]> entry : extraEntries.entrySet()) {
                add(jar, entry.getKey(), entry.getValue());
            }
        }
        return output.toByteArray();
    }

    static Path compileSources(
        final Path workDir,
        final Map<String, String> sources,
        final Path... extraClasspath
    ) throws Exception {
        final Path sourceRoot = Files.createDirectories(
            workDir.resolve("src-" + System.nanoTime()));
        final Path classes = Files.createDirectories(
            workDir.resolve("classes-" + System.nanoTime()));
        final List<String> files = new ArrayList<>();
        for (final Map.Entry<String, String> source : sources.entrySet()) {
            final Path file = sourceRoot.resolve(
                source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
            files.add(file.toString());
        }
        final StringBuilder classpath = new StringBuilder(
            System.getProperty("java.class.path"));
        for (final Path extra : extraClasspath) {
            classpath.append(java.io.File.pathSeparator).append(extra);
        }
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final List<String> arguments = new ArrayList<>(List.of(
            "-classpath", classpath.toString(), "-d", classes.toString()));
        arguments.addAll(files);
        if (compiler.run(null, null, null, arguments.toArray(new String[0])) != 0) {
            throw new IllegalStateException("fixture compilation failed");
        }
        return classes;
    }

    static String sha256Hex(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
