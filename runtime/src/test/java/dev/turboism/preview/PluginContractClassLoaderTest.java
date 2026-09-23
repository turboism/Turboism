package dev.turboism.preview;

import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.event.PublicEventContractCatalog;
import dev.turboism.sdk.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin artifact loader delegates declared contract member names to the
 * session-bound contract loader <em>before</em> the parent chain, and owns its
 * contract lease with the sticky-failure rule: a failed {@code super.close()}
 * is rethrown on every retry and never releases the lease.
 */
class PluginContractClassLoaderTest {

    private static final String EVENT_TYPE = "com.acme.events.Greeting";
    private static final String ARTIFACT_PATH =
        "META-INF/turboism/contracts/acme-events-1.0.0.jar";

    @TempDir
    Path temporary;

    @Test
    void contractMemberResolvesFromBoundLoaderBeforeSameNamedParentClass() throws Exception {
        final Path artifact = contractArtifact();
        final Path parentClasses = compile(Map.of(
            EVENT_TYPE, """
                package com.acme.events;
                public record Greeting(String hostShadow)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """
        ));
        try (PublicEventContractCatalog catalog =
                new PublicEventContractCatalog(temporary.resolve("contracts"));
             URLClassLoader hostParent = new URLClassLoader(
                 new URL[]{parentClasses.toUri().toURL()},
                 PluginContractClassLoaderTest.class.getClassLoader()
             )) {
            final PluginDescriptor descriptor = descriptor(pluginJson(artifact));
            final var lease = catalog.acquire(
                descriptor,
                pluginJar(descriptor, artifact)
            );
            final ClassLoader contractLoader = lease.delegates().get(EVENT_TYPE);
            final Class<?> hostShadow = hostParent.loadClass(EVENT_TYPE);
            try (PluginContractClassLoader pluginLoader = new PluginContractClassLoader(
                new URL[]{temporary.resolve("empty.jar").toUri().toURL()},
                hostParent,
                lease
            )) {
                final Class<?> resolved = pluginLoader.loadClass(EVENT_TYPE);
                assertSame(
                    contractLoader.loadClass(EVENT_TYPE),
                    resolved,
                    "the bound contract class must preempt a same-named parent class"
                );
                assertNotSame(hostShadow, resolved);
            }
        }
    }

    @Test
    void nonContractNamesFollowOrdinaryParentFirstRules() throws Exception {
        final Path artifact = contractArtifact();
        final Path pluginClasses = compile(Map.of(
            "dev.example.plugin.OwnType", """
                package dev.example.plugin;
                public final class OwnType {}
                """
        ));
        try (PublicEventContractCatalog catalog =
                new PublicEventContractCatalog(temporary.resolve("contracts"))) {
            final PluginDescriptor descriptor = descriptor(pluginJson(artifact));
            final var lease = catalog.acquire(
                descriptor,
                pluginJar(descriptor, artifact)
            );
            final Path pluginJar = pluginJar(descriptor, artifact, pluginClasses);
            try (PluginContractClassLoader pluginLoader = new PluginContractClassLoader(
                new URL[]{pluginJar.toUri().toURL()},
                PluginContractClassLoaderTest.class.getClassLoader(),
                lease
            )) {
                // Plugin's own classes resolve from its JAR.
                assertSame(
                    pluginLoader,
                    pluginLoader.loadClass("dev.example.plugin.OwnType")
                        .getClassLoader()
                );
                // SDK names delegate to the shared SDK loader.
                assertSame(
                    dev.turboism.sdk.event.EventBus.class,
                    pluginLoader.loadClass("dev.turboism.sdk.event.EventBus")
                );
            }
        }
    }

    @Test
    void failedCloseIsStickyAndNeverReleasesTheLease() throws Exception {
        final Path artifact = contractArtifact();
        try (PublicEventContractCatalog catalog =
                new PublicEventContractCatalog(temporary.resolve("contracts"))) {
            final PluginDescriptor descriptor = descriptor(pluginJson(artifact));
            final var lease = catalog.acquire(
                descriptor,
                pluginJar(descriptor, artifact)
            );
            final PluginContractClassLoader loader = new FailingCloseLoader(
                new URL[]{temporary.resolve("empty.jar").toUri().toURL()},
                getClass().getClassLoader(),
                lease
            );
            final IOException first = assertThrows(IOException.class, loader::close);
            // A no-op retry must rethrow the original failure, not report success.
            final IOException second = assertThrows(IOException.class, loader::close);
            assertSame(first, second);
            // The binding is still held: the contract type remains bound and the
            // same id cannot be rebound while the unproven loader retains it.
            assertTrue(catalog.isContractBound(EVENT_TYPE));
        }
    }

    @Test
    void successfulCloseReleasesTheLeaseExactlyOnce() throws Exception {
        final Path artifact = contractArtifact();
        try (PublicEventContractCatalog catalog =
                new PublicEventContractCatalog(temporary.resolve("contracts"))) {
            final PluginDescriptor descriptor = descriptor(pluginJson(artifact));
            final var lease = catalog.acquire(
                descriptor,
                pluginJar(descriptor, artifact)
            );
            final PluginContractClassLoader loader = new PluginContractClassLoader(
                new URL[]{temporary.resolve("empty.jar").toUri().toURL()},
                getClass().getClassLoader(),
                lease
            );
            loader.close();
            assertTrue(
                !catalog.isContractBound(EVENT_TYPE),
                "the last lease release must retire the contract binding"
            );
            // Idempotent: a repeated close reports success without touching the
            // lease again.
            loader.close();
        }
    }

    // -- fixtures -------------------------------------------------------------

    private Path contractArtifact() throws IOException {
        final Path classes = compile(Map.of(
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
        final Path jar = temporary.resolve("contract-" + System.nanoTime() + ".jar");
        writeJar(jar, classes);
        return jar;
    }

    private Path compile(final Map<String, String> sources) throws IOException {
        final Path sourceRoot = Files.createDirectories(
            temporary.resolve("src-" + System.nanoTime())
        );
        final Path classes = Files.createDirectories(
            temporary.resolve("classes-" + System.nanoTime())
        );
        final var files = new java.util.ArrayList<String>();
        for (final Map.Entry<String, String> source : sources.entrySet()) {
            final Path file = sourceRoot.resolve(
                source.getKey().replace('.', '/') + ".java"
            );
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
            files.add(file.toString());
        }
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final var arguments = new java.util.ArrayList<>(java.util.List.of(
            "-classpath", System.getProperty("java.class.path"),
            "-d", classes.toString()
        ));
        arguments.addAll(files);
        if (compiler.run(null, null, null, arguments.toArray(new String[0])) != 0) {
            throw new IllegalStateException("fixture compilation failed");
        }
        return classes;
    }

    private Path pluginJar(
        final PluginDescriptor descriptor,
        final Path contractArtifact
    ) throws IOException {
        return pluginJar(descriptor, contractArtifact, null);
    }

    private Path pluginJar(
        final PluginDescriptor descriptor,
        final Path contractArtifact,
        final Path pluginClasses
    ) throws IOException {
        final Path jar = temporary.resolve(
            descriptor.id() + "-" + System.nanoTime() + ".jar"
        );
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            if (contractArtifact != null) {
                put(output, ARTIFACT_PATH, Files.readAllBytes(contractArtifact));
            }
            if (pluginClasses != null) {
                try (var paths = Files.walk(pluginClasses)) {
                    for (final Path file : paths.filter(Files::isRegularFile)
                        .sorted(Comparator.naturalOrder()).toList()) {
                        put(
                            output,
                            pluginClasses.relativize(file).toString().replace('\\', '/'),
                            Files.readAllBytes(file)
                        );
                    }
                }
            }
            put(
                output,
                "META-INF/turboism/plugin.json",
                pluginJson(contractArtifact).getBytes(StandardCharsets.UTF_8)
            );
        }
        return jar;
    }

    private static void writeJar(final Path jar, final Path classes) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
             var paths = Files.walk(classes)) {
            for (final Path file : paths.filter(Files::isRegularFile)
                .sorted(Comparator.naturalOrder()).toList()) {
                put(
                    output,
                    classes.relativize(file).toString().replace('\\', '/'),
                    Files.readAllBytes(file)
                );
            }
        }
    }

    private static void put(
        final JarOutputStream output,
        final String name,
        final byte[] bytes
    ) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static PluginDescriptor descriptor(final String json) {
        try {
            return new PluginDescriptorParser().parse(json);
        } catch (dev.turboism.core.descriptor.DescriptorParseException failure) {
            throw new IllegalStateException("fixture descriptor is invalid", failure);
        }
    }

    private static String pluginJson(final Path contractArtifact) throws IOException {
        return """
            {"format":"turboism.plugin.meta","schemaVersion":5,
            "id":"dev.example.contract-plugin","name":"Contract","version":"0.1.0",
            "description":"test","entrypoints":["dev.example.contract.Plugin"],
            "turboismApi":"[0.1.0,0.2.0)","authors":[{"name":"Tests"}],
            "license":"Test","website":"https://turboism.dev","resources":[],
            "i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]},
            "dependencies":[],"permissions":[],"capabilities":[],
            "environment":{"requiresCubism":false,"ui":"none"},
            "category":"workflow",
            "eventContracts":[{"id":"acme.events","version":"1.0.0",
              "artifact":"%s","sha256":"%s"}]}
            """.formatted(ARTIFACT_PATH, sha256Hex(Files.readAllBytes(contractArtifact)));
    }

    private static String sha256Hex(final byte[] bytes) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    /** Loader whose real disposal always fails, to exercise the sticky rule. */
    private static final class FailingCloseLoader extends PluginContractClassLoader {
        private FailingCloseLoader(
            final URL[] urls,
            final ClassLoader parent,
            final PublicEventContractCatalog.ContractLease lease
        ) {
            super(urls, parent, lease);
        }

        @Override
        void closeDelegate() throws IOException {
            throw new IOException("simulated disposal failure");
        }
    }
}
