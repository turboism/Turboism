package dev.turboism.core.event;

import dev.turboism.adapter.host.HostSession;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.preview.LocalPluginRuntime;
import dev.turboism.preview.PreviewLog;
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
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The headline acceptance scenario: two plugin JARs built by independent
 * compilations against the published contract artifact embed the same bytes,
 * and a typed publish/subscribe round-trip flows through
 * {@link LocalPluginRuntime} with a single shared {@link Class} identity owned
 * by the session contract loader — never by the provider's implementation
 * loader. Provider and consumer sources import the contract types directly;
 * only {@code javac} sees the artifact on its classpath, mirroring how two
 * independently built plugin projects consume a released contract.
 */
class PublicEventContractIntegrationTest {

    private static final String PROVIDER_ID = "dev.example.provider";
    private static final String CONSUMER_ID = "dev.example.consumer";
    private static final String CONTRACT_ID = "acme.events";
    private static final String EVENT_TYPE = "com.acme.events.Greeting";
    private static final String PAYLOAD_TYPE = "com.acme.events.GreetingPayload";
    private static final String ARTIFACT_PATH =
        "META-INF/turboism/contracts/acme-events-1.0.0.jar";

    private static final String PROP_PROVIDER_CLASS = "dev.example.provider.event-class";
    private static final String PROP_PROVIDER_LOADER = "dev.example.provider.loader";
    private static final String PROP_PROVIDER_PUBLISH = "dev.example.provider.publish";
    private static final String PROP_CONSUMER_CLASS = "dev.example.consumer.event-class";
    private static final String PROP_CONSUMER_LOADER = "dev.example.consumer.loader";
    private static final String PROP_CONSUMER_PARENT = "dev.example.consumer.parent";
    private static final String PROP_RECEIVED = "dev.example.consumer.received";

    @TempDir
    Path temporary;

    @Test
    void providerAndConsumerShareContractIdentityThroughRuntime() throws Exception {
        final Path home = temporary.resolve("home");
        final Path contractJar = contractArtifact();
        writeFixturePlugins(home.resolve("plugins"), contractJar, ConsumerKind.PROGRAMMATIC);
        assertContractRoundTrip(home);
    }

    @Test
    void contractBackedReflectiveSubscriberReceivesDelivery() throws Exception {
        // The reflective @SubscribeEvent route resolves its parameter type
        // through the consumer loader's contract delegation: the subscribed
        // event type is the session-bound contract Class, not a plugin copy.
        final Path home = temporary.resolve("home");
        final Path contractJar = contractArtifact();
        writeFixturePlugins(home.resolve("plugins"), contractJar, ConsumerKind.REFLECTIVE);
        assertContractRoundTrip(home);
    }

    @Test
    void contractBackedGeneratedCatalogReceivesDelivery() throws Exception {
        // The generated-catalog route exercises the same binding through a
        // hand-written GeneratedSubscriberCatalog + META-INF/services entry,
        // standing in for the compile-time annotation processor's output.
        final Path home = temporary.resolve("home");
        final Path contractJar = contractArtifact();
        writeFixturePlugins(home.resolve("plugins"), contractJar, ConsumerKind.GENERATED);
        assertContractRoundTrip(home);
    }

    private void assertContractRoundTrip(final Path home) throws Exception {
        final Properties properties = System.getProperties();
        clearProbeProperties(properties);

        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                home, scheduler, host.adapterAccess(), log
            );
            try {
                final LocalPluginRuntime.LoadReport report = runtime.loadAll();
                assertTrue(
                    report.failures().isEmpty(),
                    "provider and consumer must both load: " + report.failures()
                );

                // The consumer subscribed during init/registration; the provider
                // publishes the contract event through its own plugin event bus
                // after activation.
                final Runnable publish = (Runnable) properties.get(PROP_PROVIDER_PUBLISH);
                publish.run();
                final Object received = await(properties, PROP_RECEIVED);
                final Class<?> eventClass = received.getClass();

                // One identity across both independently built plugins.
                final Class<?> providerSide = (Class<?>) properties.get(PROP_PROVIDER_CLASS);
                final Class<?> consumerSide = (Class<?>) properties.get(PROP_CONSUMER_CLASS);
                assertSame(providerSide, consumerSide);
                assertSame(providerSide, eventClass);
                assertEquals(EVENT_TYPE, eventClass.getName());

                // The contract class is owned by the session contract loader, not
                // by either plugin's implementation loader.
                final ClassLoader providerLoader =
                    (ClassLoader) properties.get(PROP_PROVIDER_LOADER);
                final ClassLoader consumerLoader =
                    (ClassLoader) properties.get(PROP_CONSUMER_LOADER);
                assertNotSame(providerLoader, eventClass.getClassLoader());
                assertNotSame(consumerLoader, eventClass.getClassLoader());
                // The provider implementation loader must never appear on the
                // consumer's parent chain.
                for (ClassLoader parent = consumerLoader;
                     parent != null; parent = parent.getParent()) {
                    assertNotSame(
                        providerLoader, parent,
                        "consumer parent chain must not contain the provider loader"
                    );
                }
            } finally {
                runtime.close();
            }
        } finally {
            clearProbeProperties(properties);
            host.close();
            scheduler.shutdown();
        }
    }

    @Test
    void requiredContractImportWithoutAfterOrderingIsRejectedAtLoad() throws Exception {
        final Path home = temporary.resolve("home");
        final Path contractJar = contractArtifact();
        final Path plugins = home.resolve("plugins");
        writeProviderPlugin(plugins, contractJar);
        // The consumer declares the required import but orders itself BEFORE the
        // provider: admission must fail with a diagnostic naming the ordering rule.
        writeConsumerPlugin(
            plugins.resolve("consumer.jar"),
            consumerDescriptor(
                contractJar,
                """
                [{"id":"%s","version":"[0.1.0,0.2.0)","type":"required","ordering":"before"}]
                """.formatted(PROVIDER_ID)
            ),
            contractJar,
            ConsumerKind.PROGRAMMATIC
        );
        final Properties properties = System.getProperties();
        clearProbeProperties(properties);

        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                home, scheduler, host.adapterAccess(), log
            );
            try {
                final LocalPluginRuntime.LoadReport report = runtime.loadAll();
                final var consumerFailure = report.failures().stream()
                    .filter(failure -> failure.pluginId().equals(CONSUMER_ID))
                    .findFirst();
                assertTrue(
                    consumerFailure.isPresent(),
                    "the mis-ordered consumer must fail admission: " + report.failures()
                );
                assertTrue(
                    consumerFailure.get().toString().contains("ordering 'after'")
                        || consumerFailure.get().toString().contains("ordering=after"),
                    consumerFailure.get().toString()
                );
            } finally {
                runtime.close();
            }
        } finally {
            clearProbeProperties(properties);
            host.close();
            scheduler.shutdown();
        }
    }

    // -- fixtures -------------------------------------------------------------

    private void clearProbeProperties(final Properties properties) {
        for (final String key : List.of(
            PROP_PROVIDER_CLASS, PROP_PROVIDER_LOADER, PROP_PROVIDER_PUBLISH,
            PROP_CONSUMER_CLASS, PROP_CONSUMER_LOADER, PROP_CONSUMER_PARENT,
            PROP_RECEIVED
        )) {
            properties.remove(key);
        }
    }

    private static Object await(final Properties properties, final String key)
        throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Object value;
        while ((value = properties.get(key)) == null) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + key);
            }
            Thread.sleep(10L);
        }
        return value;
    }

    /** How the consumer plugin binds its subscription to the contract type. */
    private enum ConsumerKind {
        /** Typed {@code eventBus().subscribe(Greeting.class, ...)} during init. */
        PROGRAMMATIC,
        /** Reflective {@code @SubscribeEvent void onGreeting(Greeting)}. */
        REFLECTIVE,
        /** Hand-written {@code GeneratedSubscriberCatalog} service binding. */
        GENERATED
    }

    private void writeFixturePlugins(
        final Path plugins,
        final Path contractJar,
        final ConsumerKind consumerKind
    ) throws Exception {
        writeProviderPlugin(plugins, contractJar);
        writeConsumerPlugin(
            plugins.resolve("consumer.jar"),
            consumerDescriptor(
                contractJar,
                """
                [{"id":"%s","version":"[0.1.0,0.2.0)","type":"required","ordering":"after"}]
                """.formatted(PROVIDER_ID)
            ),
            contractJar,
            consumerKind
        );
    }

    private void writeProviderPlugin(final Path plugins, final Path contractJar)
        throws Exception {
        // Typed references: the contract artifact is on the compile classpath but
        // is never copied into the plugin classes — the bytecode keeps symbolic
        // references that the plugin loader resolves through contract delegation.
        final Path classes = compile(Map.of(
            "dev.example.provider.ProviderPlugin", """
                package dev.example.provider;

                import com.acme.events.Greeting;
                import com.acme.events.GreetingPayload;
                import dev.turboism.sdk.plugin.PluginContext;
                import dev.turboism.sdk.plugin.TurboismPlugin;

                public final class ProviderPlugin implements TurboismPlugin {
                    private PluginContext context;

                    @Override public void init(PluginContext context) throws Exception {
                        this.context = context;
                        System.getProperties().put(
                            "dev.example.provider.event-class", Greeting.class);
                        System.getProperties().put(
                            "dev.example.provider.loader", getClass().getClassLoader());
                        System.getProperties().put(
                            "dev.example.provider.publish", (Runnable) this::publishGreeting);
                    }

                    private void publishGreeting() {
                        context.eventBus().publish(
                            new Greeting(new GreetingPayload("from-provider")));
                    }
                }
                """
        ), contractJar);
        writePlugin(
            plugins.resolve("provider.jar"),
            classes,
            providerDescriptor(contractJar),
            contractJar,
            Map.of()
        );
    }

    private void writeConsumerPlugin(
        final Path jar,
        final String descriptor,
        final Path contractJar,
        final ConsumerKind kind
    ) throws Exception {
        final Path classes = compile(consumerSources(kind), contractJar);
        writePlugin(jar, classes, descriptor, contractJar, consumerExtraEntries(kind));
    }

    private static Map<String, String> consumerSources(final ConsumerKind kind) {
        final String plugin = switch (kind) {
            case PROGRAMMATIC -> """
                package dev.example.consumer;

                import com.acme.events.Greeting;
                import dev.turboism.sdk.plugin.PluginContext;
                import dev.turboism.sdk.plugin.TurboismPlugin;

                public final class ConsumerPlugin implements TurboismPlugin {
                    @Override public void init(PluginContext context) throws Exception {
                        ClassLoader loader = getClass().getClassLoader();
                        System.getProperties().put(
                            "dev.example.consumer.event-class", Greeting.class);
                        System.getProperties().put(
                            "dev.example.consumer.loader", loader);
                        System.getProperties().put(
                            "dev.example.consumer.parent", loader.getParent());
                        context.eventBus().subscribe(
                            Greeting.class,
                            event -> System.getProperties().put(
                                "dev.example.consumer.received", event)
                        );
                    }
                }
                """;
            case REFLECTIVE -> """
                package dev.example.consumer;

                import com.acme.events.Greeting;
                import dev.turboism.sdk.event.SubscribeEvent;
                import dev.turboism.sdk.plugin.PluginContext;
                import dev.turboism.sdk.plugin.TurboismPlugin;

                public final class ConsumerPlugin implements TurboismPlugin {
                    @Override public void init(PluginContext context) throws Exception {
                        ClassLoader loader = getClass().getClassLoader();
                        System.getProperties().put(
                            "dev.example.consumer.event-class", Greeting.class);
                        System.getProperties().put(
                            "dev.example.consumer.loader", loader);
                    }

                    @SubscribeEvent
                    public void onGreeting(Greeting event) {
                        System.getProperties().put(
                            "dev.example.consumer.received", event);
                    }
                }
                """;
            case GENERATED -> """
                package dev.example.consumer;

                import com.acme.events.Greeting;
                import dev.turboism.sdk.plugin.PluginContext;
                import dev.turboism.sdk.plugin.TurboismPlugin;

                public final class ConsumerPlugin implements TurboismPlugin {
                    @Override public void init(PluginContext context) throws Exception {
                        ClassLoader loader = getClass().getClassLoader();
                        System.getProperties().put(
                            "dev.example.consumer.event-class", Greeting.class);
                        System.getProperties().put(
                            "dev.example.consumer.loader", loader);
                    }

                    public void onGreeting(Greeting event) {
                        System.getProperties().put(
                            "dev.example.consumer.received", event);
                    }
                }
                """;
        };
        if (kind != ConsumerKind.GENERATED) {
            return Map.of("dev.example.consumer.ConsumerPlugin", plugin);
        }
        return Map.of(
            "dev.example.consumer.ConsumerPlugin", plugin,
            "dev.example.consumer.ConsumerSubscriberCatalog", """
                package dev.example.consumer;

                import com.acme.events.Greeting;
                import dev.turboism.sdk.event.EventPriority;
                import dev.turboism.sdk.event.EventSubscriberRegistrar;
                import dev.turboism.sdk.event.GeneratedSubscriberCatalog;

                public final class ConsumerSubscriberCatalog
                    implements GeneratedSubscriberCatalog<ConsumerPlugin> {

                    @Override public Class<ConsumerPlugin> entrypointType() {
                        return ConsumerPlugin.class;
                    }

                    @Override public void register(
                        ConsumerPlugin entrypoint,
                        EventSubscriberRegistrar registrar
                    ) {
                        registrar.register(
                            Greeting.class,
                            EventPriority.NORMAL,
                            0,
                            "dev.example.consumer.ConsumerPlugin"
                                + "#onGreeting(com.acme.events.Greeting):void",
                            entrypoint::onGreeting
                        );
                    }
                }
                """
        );
    }

    private static Map<String, String> consumerExtraEntries(final ConsumerKind kind) {
        if (kind != ConsumerKind.GENERATED) {
            return Map.of();
        }
        return Map.of(
            "META-INF/services/dev.turboism.sdk.event.GeneratedSubscriberCatalog",
            "dev.example.consumer.ConsumerSubscriberCatalog\n"
        );
    }

    private Path contractArtifact() throws IOException {
        final Path classes = compile(Map.of(
            EVENT_TYPE, """
                package com.acme.events;
                public record Greeting(com.acme.events.GreetingPayload payload)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """,
            PAYLOAD_TYPE, """
                package com.acme.events;
                public record GreetingPayload(String text) {}
                """
        ), null);
        final Path jar = temporary.resolve("acme-events-1.0.0.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
             var paths = Files.walk(classes)) {
            for (final Path file : paths.filter(Files::isRegularFile)
                .sorted(Comparator.naturalOrder()).toList()) {
                output.putNextEntry(new JarEntry(
                    classes.relativize(file).toString().replace('\\', '/')
                ));
                output.write(Files.readAllBytes(file));
                output.closeEntry();
            }
        }
        return jar;
    }

    /**
     * Compiles fixture sources with the contract artifact on the compile
     * classpath only — plugin JARs embed the artifact separately, mirroring the
     * published-contract workflow. A {@code null} {@code contractJar} compiles
     * against the test classpath alone (the contract artifact itself).
     */
    private Path compile(final Map<String, String> sources, final Path contractJar)
        throws IOException {
        final Path sourceRoot = Files.createDirectories(
            temporary.resolve("src-" + System.nanoTime())
        );
        final Path classes = Files.createDirectories(
            temporary.resolve("classes-" + System.nanoTime())
        );
        final List<String> files = new ArrayList<>();
        for (final Map.Entry<String, String> source : sources.entrySet()) {
            final Path file = sourceRoot.resolve(
                source.getKey().replace('.', '/') + ".java"
            );
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
            files.add(file.toString());
        }
        final StringBuilder classpath = new StringBuilder(
            System.getProperty("java.class.path")
        );
        if (contractJar != null) {
            classpath.append(java.io.File.pathSeparator).append(contractJar);
        }
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final List<String> arguments = new ArrayList<>(List.of(
            "-classpath", classpath.toString(),
            "-d", classes.toString()
        ));
        arguments.addAll(files);
        if (compiler.run(null, null, null, arguments.toArray(new String[0])) != 0) {
            throw new IllegalStateException("fixture compilation failed");
        }
        return classes;
    }

    private void writePlugin(
        final Path jar,
        final Path classes,
        final String descriptor,
        final Path contractJar,
        final Map<String, String> extraEntries
    ) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
             var paths = Files.walk(classes)) {
            for (final Path file : paths.filter(Files::isRegularFile)
                .sorted(Comparator.naturalOrder()).toList()) {
                output.putNextEntry(new JarEntry(
                    classes.relativize(file).toString().replace('\\', '/')
                ));
                output.write(Files.readAllBytes(file));
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry(ARTIFACT_PATH));
            output.write(Files.readAllBytes(contractJar));
            output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/turboism/plugin.json"));
            output.write(descriptor.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry(
                "META-INF/turboism/i18n/messages.properties"
            ));
            output.closeEntry();
            for (final Map.Entry<String, String> entry : extraEntries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    private String providerDescriptor(final Path contractJar) throws IOException {
        return """
            {"format":"turboism.plugin.meta","schemaVersion":5,
            "id":"%s","name":"Provider","version":"0.1.0",
            "description":"test","entrypoints":["dev.example.provider.ProviderPlugin"],
            "turboismApi":"[0.1.0,0.2.0)","authors":[{"name":"Tests"}],
            "license":"Test","website":"https://turboism.dev","resources":[],
            "i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]},
            "dependencies":[],
            "permissions":[{"id":"turboism.event.publish","scope":"application",
              "reason":"publishes contract events"}],
            "capabilities":[],
            "environment":{"requiresCubism":false,"ui":"none"},
            "category":"workflow",
            "eventExports":[{"id":"greeting","contractVersion":"1.0.0",
              "eventType":"%s","abiSha256":"%s"}],
            "eventContracts":[{"id":"%s","version":"1.0.0",
              "artifact":"%s","sha256":"%s"}]}
            """.formatted(
                PROVIDER_ID, EVENT_TYPE, eventAbi(contractJar, EVENT_TYPE),
                CONTRACT_ID, ARTIFACT_PATH, sha256Hex(Files.readAllBytes(contractJar))
            );
    }

    private String consumerDescriptor(
        final Path contractJar,
        final String dependencies
    ) throws IOException {
        return """
            {"format":"turboism.plugin.meta","schemaVersion":5,
            "id":"%s","name":"Consumer","version":"0.1.0",
            "description":"test","entrypoints":["dev.example.consumer.ConsumerPlugin"],
            "turboismApi":"[0.1.0,0.2.0)","authors":[{"name":"Tests"}],
            "license":"Test","website":"https://turboism.dev","resources":[],
            "i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]},
            "dependencies":%s,
            "permissions":[{"id":"turboism.event.subscribe","scope":"application",
              "reason":"subscribes to contract events"}],
            "capabilities":[],
            "environment":{"requiresCubism":false,"ui":"none"},
            "category":"workflow",
            "eventImports":[{"provider":"%s","eventId":"greeting",
              "contractVersion":"[1.0.0,2.0.0)","eventType":"%s",
              "abiSha256":"%s","required":true}],
            "eventContracts":[{"id":"%s","version":"1.0.0",
              "artifact":"%s","sha256":"%s"}]}
            """.formatted(
                CONSUMER_ID, dependencies, PROVIDER_ID, EVENT_TYPE,
                eventAbi(contractJar, EVENT_TYPE),
                CONTRACT_ID, ARTIFACT_PATH, sha256Hex(Files.readAllBytes(contractJar))
            );
    }

    private String eventAbi(final Path contractJar, final String eventType)
        throws IOException {
        try (URLClassLoader loader = new URLClassLoader(
            new URL[]{contractJar.toUri().toURL()},
            getClass().getClassLoader()
        )) {
            try {
                return PublicEventAbi.sha256(
                    Class.forName(eventType, false, loader)
                );
            } catch (ClassNotFoundException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 16, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
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
}
