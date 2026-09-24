package dev.turboism.core.event;

import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.event.EventBus;
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
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused coverage for {@link PublicEventContractCatalog}: artifact binding,
 * reference-counted leases, conflict rejection, extraction-cache integrity, and
 * the restricted class-loading closure contract artifacts live inside.
 */
class PublicEventContractCatalogTest {

    private static final String CONTRACT_ID = "acme.events";
    private static final String EVENT_TYPE = "com.acme.events.Greeting";
    private static final String ARTIFACT_PATH =
        "META-INF/turboism/contracts/acme-events-1.0.0.jar";

    @TempDir
    Path temporary;

    @Test
    void twoPluginsBoundToSameArtifactShareOneContractClass() throws Exception {
        final Path artifact = contractArtifact("v1");
        final Path providerJar = pluginJar("dev.example.provider", artifact);
        final Path consumerJar = pluginJar("dev.example.consumer", artifact);
        try (PublicEventContractCatalog catalog = catalog()) {
            final var providerLease = catalog.acquire(
                descriptor(pluginJson("dev.example.provider", artifact)),
                providerJar
            );
            final var consumerLease = catalog.acquire(
                descriptor(pluginJson("dev.example.consumer", artifact)),
                consumerJar
            );
            try {
                final ClassLoader providerContract =
                    providerLease.delegates().get(EVENT_TYPE);
                final ClassLoader consumerContract =
                    consumerLease.delegates().get(EVENT_TYPE);
                assertSame(providerContract, consumerContract);
                assertSame(
                    providerContract.loadClass(EVENT_TYPE),
                    consumerContract.loadClass(EVENT_TYPE)
                );
            } finally {
                providerLease.close();
                consumerLease.close();
            }
        }
    }

    @Test
    void sameContractIdWithDifferentBytesIsRejectedWhileBound() throws Exception {
        final Path artifactV1 = contractArtifact("v1");
        final Path artifactV2 = contractArtifact("v2");
        try (PublicEventContractCatalog catalog = catalog()) {
            final var lease = catalog.acquire(
                descriptor(pluginJson("dev.example.provider", artifactV1)),
                pluginJar("dev.example.provider", artifactV1)
            );
            try {
                final IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> catalog.acquire(
                        descriptor(pluginJson("dev.example.other", artifactV2)),
                        pluginJar("dev.example.other", artifactV2)
                    )
                );
                assertTrue(failure.getMessage().contains(CONTRACT_ID));
                assertTrue(failure.getMessage().contains("different artifact"));
            } finally {
                lease.close();
            }
        }
    }

    @Test
    void rebindAfterLastReleaseYieldsANewClassIdentity() throws Exception {
        final Path artifactV1 = contractArtifact("v1");
        final Path artifactV2 = contractArtifact("v2");
        final Class<?> firstGeneration;
        final Class<?> secondGeneration;
        try (PublicEventContractCatalog catalog = catalog()) {
            final var lease1 = catalog.acquire(
                descriptor(pluginJson("dev.example.provider", artifactV1)),
                pluginJar("dev.example.provider", artifactV1)
            );
            firstGeneration = lease1.delegates().get(EVENT_TYPE).loadClass(EVENT_TYPE);
            lease1.close();

            final var lease2 = catalog.acquire(
                descriptor(pluginJson("dev.example.provider", artifactV2)),
                pluginJar("dev.example.provider", artifactV2)
            );
            try {
                secondGeneration =
                    lease2.delegates().get(EVENT_TYPE).loadClass(EVENT_TYPE);
            } finally {
                lease2.close();
            }
        }
        assertEquals(EVENT_TYPE, firstGeneration.getName());
        assertEquals(EVENT_TYPE, secondGeneration.getName());
        assertNotSame(
            firstGeneration,
            secondGeneration,
            "a rebound contract must produce a fresh Class identity, not revive the old one"
        );
    }

    @Test
    void crossArtifactClassCollisionIsRejected() throws Exception {
        final Path artifactA = contractArtifact("a");
        // A second artifact compiled from different sources but shipping the
        // same binary name must not silently shadow the bound artifact.
        final Path artifactB = collidingArtifact();
        try (PublicEventContractCatalog catalog = catalog()) {
            final var lease = catalog.acquire(
                descriptor(pluginJson("dev.example.provider", artifactA)),
                pluginJar("dev.example.provider", artifactA)
            );
            try {
                // A different contract id carrying a colliding member name must reach
                // the class-ownership check, not the same-id/different-bytes check.
                final IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> catalog.acquire(
                        descriptor(pluginJson(
                            "dev.example.other", artifactB,
                            ARTIFACT_PATH, "acme.other.events"
                        )),
                        pluginJar("dev.example.other", artifactB)
                    )
                );
                assertTrue(failure.getMessage().contains(EVENT_TYPE));
                assertTrue(failure.getMessage().contains("already provided"));
            } finally {
                lease.close();
            }
        }
    }

    @Test
    void loosePluginClassCollidingWithContractMemberIsRejected() throws Exception {
        final Path artifact = contractArtifact("v1");
        final Path loose = compile(Map.of(
            // Same binary name as a contract member, defined as a loose plugin class.
            "com.acme.events.Greeting", """
                package com.acme.events;
                public record Greeting(String smuggled)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """,
            "dev.example.provider.ProviderPlugin", """
                package dev.example.provider;
                public final class ProviderPlugin
                    implements dev.turboism.sdk.plugin.TurboismPlugin {}
                """
        ));
        final Path jar = pluginJar(
            "dev.example.provider",
            artifact,
            loose
        );
        try (PublicEventContractCatalog catalog = catalog()) {
            final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> catalog.acquire(
                    descriptor(pluginJson("dev.example.provider", artifact)),
                    jar
                )
            );
            assertTrue(failure.getMessage().contains(EVENT_TYPE));
        }
    }

    @Test
    void catalogCloseRetainsLeasedLoaderUntilLastLeaseReleases() throws Exception {
        final Path artifact = contractArtifact("v1");
        final PublicEventContractCatalog catalog = catalog();
        final var lease = catalog.acquire(
            descriptor(pluginJson("dev.example.provider", artifact)),
            pluginJar("dev.example.provider", artifact)
        );
        final ClassLoader contractLoader = lease.delegates().get(EVENT_TYPE);
        catalog.close();
        // The leased binding stays fully usable: retained generations may still
        // execute against contract classes after the catalog retires admission.
        final Class<?> retained = contractLoader.loadClass(EVENT_TYPE);
        assertEquals(EVENT_TYPE, retained.getName());
        assertThrows(
            IllegalStateException.class,
            () -> catalog.acquire(
                descriptor(pluginJson("dev.example.other", artifact)),
                pluginJar("dev.example.other", artifact)
            ),
            "a retired catalog must not admit new bindings"
        );
        lease.close();
        // Deferred cleanup completed: the same id can now be re-admitted only by
        // a new catalog generation.
        try (PublicEventContractCatalog reopened = catalog()) {
            final var lease2 = reopened.acquire(
                descriptor(pluginJson("dev.example.provider", artifact)),
                pluginJar("dev.example.provider", artifact)
            );
            assertNotSame(
                retained,
                lease2.delegates().get(EVENT_TYPE).loadClass(EVENT_TYPE)
            );
            lease2.close();
        }
    }

    @Test
    void corruptExtractionCacheIsDetectedAndAtomicallyReplaced() throws Exception {
        final Path artifact = contractArtifact("v1");
        final Path extraction = temporary.resolve("corrupt-cache");
        Files.createDirectories(extraction);
        final byte[] bytes = Files.readAllBytes(artifact);
        final Path seeded = extraction.resolve(sha256Hex(bytes) + ".jar");
        Files.write(seeded, "corrupt".getBytes(StandardCharsets.UTF_8));
        try (PublicEventContractCatalog catalog =
                new PublicEventContractCatalog(extraction)) {
            final var lease = catalog.acquire(
                descriptor(pluginJson("dev.example.provider", artifact)),
                pluginJar("dev.example.provider", artifact)
            );
            try {
                assertEquals(
                    EVENT_TYPE,
                    lease.delegates().get(EVENT_TYPE).loadClass(EVENT_TYPE).getName()
                );
                assertEquals(
                    sha256Hex(bytes),
                    sha256Hex(Files.readAllBytes(seeded)),
                    "the corrupt cache entry must be replaced with verified artifact bytes"
                );
            } finally {
                lease.close();
            }
        }
    }

    @Test
    void staleCachedBytesMatchingDeclaredDigestAreReused() throws Exception {
        final Path artifact = contractArtifact("v1");
        final Path extraction = temporary.resolve("warm-cache");
        Files.createDirectories(extraction);
        final byte[] bytes = Files.readAllBytes(artifact);
        Files.write(extraction.resolve(sha256Hex(bytes) + ".jar"), bytes);
        try (PublicEventContractCatalog catalog =
                new PublicEventContractCatalog(extraction)) {
            final var lease = catalog.acquire(
                descriptor(pluginJson("dev.example.provider", artifact)),
                pluginJar("dev.example.provider", artifact)
            );
            try {
                assertEquals(
                    EVENT_TYPE,
                    lease.delegates().get(EVENT_TYPE).loadClass(EVENT_TYPE).getName()
                );
            } finally {
                lease.close();
            }
        }
    }

    @Test
    void artifactRestrictionsAreEnforcedAtBind() throws Exception {
        // Declared artifact missing from the plugin JAR.
        final Path emptyArtifact = Files.write(
            temporary.resolve("empty.jar"), new byte[0]
        );
        try (PublicEventContractCatalog catalog = catalog()) {
            final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> catalog.acquire(
                    descriptor(pluginJson(
                        "dev.example.provider", emptyArtifact, ARTIFACT_PATH
                    )),
                    pluginJar("dev.example.provider", null)
                )
            );
            assertTrue(failure.getMessage().contains("does not contain"));
        }

        // Undeclared artifact present alongside the declared one.
        final Path artifact = contractArtifact("v1");
        try (PublicEventContractCatalog catalog = catalog()) {
            final Path jar = pluginJarWithExtraEntry(
                "dev.example.provider",
                artifact,
                Map.of("META-INF/turboism/contracts/smuggled.jar", new byte[]{1})
            );
            final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> catalog.acquire(
                    descriptor(pluginJson("dev.example.provider", artifact)),
                    jar
                )
            );
            assertTrue(failure.getMessage().contains("undeclared"));
        }

        // Declared sha256 does not match the embedded bytes.
        try (PublicEventContractCatalog catalog = catalog()) {
            final String badDigest = "0".repeat(64);
            final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> catalog.acquire(
                    descriptor(pluginJson(
                        "dev.example.provider", artifact, ARTIFACT_PATH,
                        CONTRACT_ID, badDigest
                    )),
                    pluginJar("dev.example.provider", artifact)
                )
            );
            assertTrue(failure.getMessage().contains("mismatch"));
        }
    }

    /**
     * Amendment A-1.R ordering evidence: the declared-contract count is bounded
     * before any artifact bytes are read. 33 contracts pin a deliberately wrong
     * sha256 — if reads preceded the count check the sha mismatch would surface
     * first; "declares 33" proves the declaration bound precedes I/O.
     */
    @Test
    void oversizedDeclarationCountIsBoundedBeforeReadingArtifacts()
            throws Exception {
        final Path artifact = contractArtifact("v1");
        final String wrongSha = "0".repeat(64);
        final StringBuilder contracts = new StringBuilder();
        for (int i = 0; i < 33; i++) {
            if (i > 0) {
                contracts.append(',');
            }
            contracts.append("""
                {"id":"acme.events.c%d","version":"1.0.0",
                  "artifact":"META-INF/turboism/contracts/acme-%d.jar","sha256":"%s"}"""
                .formatted(i, i, wrongSha));
        }
        final String json = """
            {"format":"turboism.plugin.meta","schemaVersion":5,
            "id":"dev.example.provider","name":"dev.example.provider","version":"0.1.0",
            "description":"test","entrypoints":["dev.example.provider.ProviderPlugin"],
            "turboismApi":"[0.1.0,0.2.0)","authors":[{"name":"Tests"}],
            "license":"Test","website":"https://turboism.dev","resources":[],
            "i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]},
            "dependencies":[],"permissions":[],"capabilities":[],
            "environment":{"requiresCubism":false,"ui":"none"},
            "category":"workflow","eventContracts":[%s]}
            """.formatted(contracts);
        final Path jar = temporary.resolve("overdeclared.jar");
        try (JarOutputStream output = jarOutput(jar)) {
            for (int i = 0; i < 33; i++) {
                put(output, "META-INF/turboism/contracts/acme-" + i + ".jar",
                    Files.readAllBytes(artifact));
            }
            put(output, "META-INF/turboism/plugin.json",
                json.getBytes(StandardCharsets.UTF_8));
        }
        try (PublicEventContractCatalog catalog = catalog()) {
            final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> catalog.acquire(descriptor(json), jar)
            );
            assertTrue(failure.getMessage().contains("declares 33"),
                failure.getMessage());
        }
    }

    @Test
    void artifactWithManifestClassPathIsRejected() throws Exception {
        final Path classes = contractClasses("v1");
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Class-Path", "../evil.jar");
        final Path artifact = writeJar(
            temporary.resolve("classpath-contract.jar"), classes, manifest
        );
        try (PublicEventContractCatalog catalog = catalog()) {
            final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> catalog.acquire(
                    descriptor(pluginJson("dev.example.provider", artifact)),
                    pluginJar("dev.example.provider", artifact)
                )
            );
            assertTrue(failure.getMessage().contains("Class-Path"));
        }
    }

    @Test
    void nonClassPayloadIsRejected() throws Exception {
        final Path classes = contractClasses("v1");
        final Path artifact = temporary.resolve("payload-contract.jar");
        try (JarOutputStream output = jarOutput(artifact)) {
            writeEntries(output, classes);
            put(output, "com/acme/events/config.json", "{}".getBytes(StandardCharsets.UTF_8));
        }
        try (PublicEventContractCatalog catalog = catalog()) {
            final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> catalog.acquire(
                    descriptor(pluginJson("dev.example.provider", artifact)),
                    pluginJar("dev.example.provider", artifact)
                )
            );
            assertTrue(failure.getMessage().contains("class-only"));
        }
    }

    @Test
    void forbiddenClassPrefixesAreRejected() throws Exception {
        final Path classes = compile(Map.of(
            "dev.turboism.fake.Sneaky", """
                package dev.turboism.fake;
                public final class Sneaky {}
                """
        ));
        final Path artifact = writeJar(
            temporary.resolve("forbidden-contract.jar"), classes, null
        );
        try (PublicEventContractCatalog catalog = catalog()) {
            final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> catalog.acquire(
                    descriptor(pluginJson("dev.example.provider", artifact)),
                    pluginJar("dev.example.provider", artifact)
                )
            );
            assertTrue(failure.getMessage().contains("forbidden"));
        }
    }

    @Test
    void emptyArtifactIsRejected() throws Exception {
        final Path artifact = temporary.resolve("hollow-contract.jar");
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream output = jarOutput(artifact, manifest)) {
            // An artifact carrying only a manifest defines no classes. Bare
            // directory entries are rejected even earlier: the strict archive
            // policy requires the platform directory bit JDK writers never set.
        }
        try (PublicEventContractCatalog catalog = catalog()) {
            final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> catalog.acquire(
                    descriptor(pluginJson("dev.example.provider", artifact)),
                    pluginJar("dev.example.provider", artifact)
                )
            );
            assertTrue(failure.getMessage().contains("no classes"));
        }
    }

    @Test
    void contractParentAdmitsJdkAndSdkButNotRuntimeOrHost() throws Exception {
        final Path artifact = contractArtifact("v1");
        try (PublicEventContractCatalog catalog = catalog();
             var lease = catalog.acquire(
                 descriptor(pluginJson("dev.example.provider", artifact)),
                 pluginJar("dev.example.provider", artifact)
             )) {
            final ClassLoader contractLoader = lease.delegates().get(EVENT_TYPE);
            // JDK platform modules stay reachable through the restricted parent.
            assertEquals(
                String.class,
                contractLoader.loadClass("java.lang.String")
            );
            assertEquals(
                "jdk.httpserver",
                contractLoader.loadClass("com.sun.net.httpserver.HttpServer")
                    .getModule().getName()
            );
            assertEquals(
                "java.xml",
                contractLoader.loadClass("org.w3c.dom.Document").getModule().getName()
            );
            // The shared SDK surface resolves.
            assertSame(
                EventBus.class,
                contractLoader.loadClass("dev.turboism.sdk.event.EventBus")
            );
            // Runtime internals, the built-in core plugin, and shaded agent code
            // are invisible even though this JVM's classpath carries them.
            assertThrows(
                ClassNotFoundException.class,
                () -> contractLoader.loadClass(
                    "dev.turboism.core.event.RuntimeEventBroker"
                )
            );
            assertThrows(
                ClassNotFoundException.class,
                () -> contractLoader.loadClass(
                    "dev.turboism.plugin.core.CorePluginManagement"
                )
            );
            assertThrows(
                ClassNotFoundException.class,
                () -> contractLoader.loadClass("com.live2d.cubism.CubismFramework")
            );
        }
    }

    @Test
    void contractLoaderNeverResolvesNonOwnedNamesFromTheArtifact() throws Exception {
        // Every class physically present in the artifact is an owned member;
        // names outside that set must delegate strictly to the restricted
        // parent and never fall back to URLClassLoader.findClass on the JAR.
        final Path artifact = contractArtifact("v1");
        try (PublicEventContractCatalog catalog = catalog();
             var lease = catalog.acquire(
                 descriptor(pluginJson("dev.example.provider", artifact)),
                 pluginJar("dev.example.provider", artifact)
             )) {
            final ClassLoader contractLoader = lease.delegates().get(EVENT_TYPE);
            // Contract members load from the artifact itself.
            assertSame(
                contractLoader,
                contractLoader.loadClass(EVENT_TYPE).getClassLoader()
            );
            assertSame(
                contractLoader,
                contractLoader.loadClass("com.acme.events.GreetingPayload")
                    .getClassLoader()
            );
            // Everything else resolves strictly through the restricted parent.
            assertThrows(
                ClassNotFoundException.class,
                () -> contractLoader.loadClass("com.acme.events.Absent")
            );
        }
    }

    @Test
    void staleGenerationEventCannotPublishThroughReboundOwner() throws Exception {
        final Path artifactV1 = contractArtifact("v1");
        final Path artifactV2 = contractArtifact("v2");
        final RuntimeScheduler scheduler = scheduler();
        try (PublicEventContractCatalog contracts = catalog()) {
            final RuntimeEventBroker broker = new RuntimeEventBroker(
                scheduler, 64, ignored -> { }, ignored -> { }, contracts
            );

            // Generation 1: provider binds artifact v1 and activates.
            final PluginDescriptor providerV1 = descriptor(exportPluginJson(
                "dev.example.provider", artifactV1, EVENT_TYPE, "1.0.0"
            ));
            final var lease1 = contracts.acquire(providerV1, pluginJar(
                "dev.example.provider", artifactV1
            ));
            broker.preflight(providerV1, lease1);
            final RuntimeEventBroker.Owner owner1 = broker.admit(providerV1);
            owner1.beginInitializing();
            owner1.beginEnabling();
            owner1.activate();
            final Class<?> generationOne =
                lease1.delegates().get(EVENT_TYPE).loadClass(EVENT_TYPE);
            final Object stalePayload = newPayloadInstance(lease1);
            final EventBus.TurboismEvent staleEvent =
                (EventBus.TurboismEvent) generationOne
                    .getDeclaredConstructor(stalePayload.getClass())
                    .newInstance(stalePayload);

            // Generation 1 retires cleanly: owner closes, then the lease lets go.
            owner1.beginClosing();
            assertTrue(owner1.awaitQuiescence(java.time.Duration.ofSeconds(10)));
            owner1.close();
            lease1.close();

            // Generation 2: the same contract id binds a different artifact.
            final PluginDescriptor providerV2 = descriptor(exportPluginJson(
                "dev.example.provider", artifactV2, EVENT_TYPE, "1.1.0"
            ));
            final var lease2 = contracts.acquire(providerV2, pluginJar(
                "dev.example.provider", artifactV2
            ));
            broker.preflight(providerV2, lease2);
            final RuntimeEventBroker.Owner owner2 = broker.admit(providerV2);
            owner2.beginInitializing();
            owner2.beginEnabling();
            owner2.activate();

            // A stale instance created against generation 1 must not publish
            // through the newly active owner even though the binary name and the
            // declared ABI text match.
            final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> broker.publish(owner2.key(), staleEvent)
            );
            assertTrue(
                failure.getMessage().contains("stale or foreign"),
                failure.getMessage()
            );
            lease2.close();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void contractTypeWithoutDeclaredRouteCannotSubscribeOrPublish() throws Exception {
        // A plugin that embeds the contract but declares no export/import sees
        // the shared class identity — and still cannot use it: contract member
        // types are never treated as plugin-private events.
        final Path artifact = contractArtifact("v1");
        final RuntimeScheduler scheduler = scheduler();
        try (PublicEventContractCatalog contracts = catalog()) {
            final RuntimeEventBroker broker = new RuntimeEventBroker(
                scheduler, 64, ignored -> { }, ignored -> { }, contracts
            );
            final PluginDescriptor plugin = descriptor(
                pluginJson("dev.example.sneaky", artifact)
            );
            final var lease = contracts.acquire(
                plugin, pluginJar("dev.example.sneaky", artifact)
            );
            broker.preflight(plugin, lease);
            final RuntimeEventBroker.Owner owner = broker.admit(plugin);
            owner.beginInitializing();
            owner.beginEnabling();
            owner.activate();
            @SuppressWarnings("unchecked")
            final Class<? extends EventBus.TurboismEvent> contractType =
                (Class<? extends EventBus.TurboismEvent>) lease.delegates()
                    .get(EVENT_TYPE)
                    .loadClass(EVENT_TYPE);
            final IllegalArgumentException subscribeDenied = assertThrows(
                IllegalArgumentException.class,
                () -> broker.subscribe(owner.key(), contractType, ignored -> { })
            );
            assertTrue(
                subscribeDenied.getMessage()
                    .contains("requires a declared event export or import"),
                subscribeDenied.getMessage()
            );
            final Object payload = newPayloadInstance(lease);
            final EventBus.TurboismEvent event = (EventBus.TurboismEvent) contractType
                .getDeclaredConstructor(payload.getClass())
                .newInstance(payload);
            final IllegalArgumentException publishDenied = assertThrows(
                IllegalArgumentException.class,
                () -> broker.publish(owner.key(), event)
            );
            assertTrue(
                publishDenied.getMessage()
                    .contains("requires a declared event export or import"),
                publishDenied.getMessage()
            );
            lease.close();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void failedContractLoaderCloseSurfacesThroughLeaseAndBindingIsRetired()
        throws Exception {
        final Path artifact = contractArtifact("v1");
        final List<String> diagnostics = new ArrayList<>();
        RuntimeDiagnostics.install((level, component, message, failure) ->
            diagnostics.add(component + ":" + message)
        );
        try {
            try (PublicEventContractCatalog catalog = new PublicEventContractCatalog(
                Files.createDirectories(
                    temporary.resolve("contracts-" + System.nanoTime())
                ),
                FailingCloseContractLoader::new
            )) {
                final var lease = catalog.acquire(
                    descriptor(pluginJson("dev.example.provider", artifact)),
                    pluginJar("dev.example.provider", artifact)
                );
                final Class<?> generation =
                    lease.delegates().get(EVENT_TYPE).loadClass(EVENT_TYPE);
                // The disposal failure is caller-visible through the lease close
                // path, so the owning plugin loader close reports failure.
                final IOException first =
                    assertThrows(IOException.class, lease::close);
                // Sticky: an empty retry must rethrow the recorded first failure,
                // never report a false successful disposal.
                assertSame(first, assertThrows(IOException.class, lease::close));
                // Also reported through the process-wide diagnostics channel.
                assertTrue(
                    diagnostics.stream().anyMatch(entry ->
                        entry.contains("failed to close public event contract")),
                    "diagnostics must report the failed contract disposal: " + diagnostics
                );
                // The failed binding is quarantined from every lookup, never reused,
                // and the catalog keeps a strong reference to it (with the recorded
                // failure) for the rest of the session — the sticky retry must not
                // add a second entry or re-drive the close.
                assertEquals(
                    1,
                    catalog.quarantinedBindingCount(),
                    "the failed binding must stay quarantined, not dropped"
                );
                assertTrue(
                    !catalog.isContractBound(EVENT_TYPE),
                    "the failed binding must not remain bound"
                );
                // A later acquire binds a fresh generation with a new Class identity.
                final var rebound = catalog.acquire(
                    descriptor(pluginJson("dev.example.consumer", artifact)),
                    pluginJar("dev.example.consumer", artifact)
                );
                try {
                    assertNotSame(
                        generation,
                        rebound.delegates().get(EVENT_TYPE).loadClass(EVENT_TYPE),
                        "rebinding after a failed close must produce a fresh generation"
                    );
                } finally {
                    try {
                        rebound.close();
                    } catch (IOException expected) {
                        // The rebound generation uses the same failing seam.
                    }
                }
            }
        } finally {
            RuntimeDiagnostics.clear();
        }
    }

    @Test
    void contractClassReachableOnlyThroughGenericOwnerIsStillVerified() throws Exception {
        // Outer<ContractDto>.Inner: ContractDto only appears in the owner graph of
        // the parameterized inner type. If the closure walk skips getOwnerType(),
        // ContractDto is never visited and its leaked Hidden reference escapes
        // verification entirely.
        final Path hidden = compile(Map.of(
            "dev.acme.privatepkg.Hidden", """
                package dev.acme.privatepkg;
                public record Hidden(String secret) {}
                """
        ));
        final Path contract = compile(Map.of(
            "com.acme.events.Outer", """
                package com.acme.events;
                public class Outer<T> {
                    public final class Inner {
                        public T value() { return null; }
                    }
                }
                """,
            "com.acme.events.ContractDto", """
                package com.acme.events;
                public record ContractDto(dev.acme.privatepkg.Hidden leaked) {}
                """,
            "com.acme.events.OwnerBoundEvent", """
                package com.acme.events;
                public record OwnerBoundEvent(
                    Outer<ContractDto>.Inner payload
                ) implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """
        ), hidden);
        final Path artifact = writeJar(
            temporary.resolve("contract-owner-" + System.nanoTime() + ".jar"),
            contract,
            null
        );
        try (PublicEventContractCatalog catalog = catalog()) {
            final var lease = catalog.acquire(
                descriptor(pluginJson("dev.example.provider", artifact)),
                pluginJar("dev.example.provider", artifact)
            );
            try {
                final ClassLoader contractLoader =
                    lease.delegates().get("com.acme.events.OwnerBoundEvent");
                final Class<?> eventClass =
                    contractLoader.loadClass("com.acme.events.OwnerBoundEvent");
                final IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> PublicEventAbi.resolve(
                        "com.acme.events.OwnerBoundEvent",
                        eventAbi(artifact, "com.acme.events.OwnerBoundEvent"),
                        catalog
                    )
                );
                assertTrue(
                    failure.getMessage().contains("closure")
                        || failure.getMessage().contains("Hidden"),
                    failure.getMessage()
                );
            } finally {
                lease.close();
            }
        }
    }

    @Test
    void contractEventReferencingFrameworkTypeIsRejectedAtPreflight() throws Exception {
        // The event compiles against the test classpath, but RuntimeEventBroker
        // is not in the published closure: not a JDK platform type, not SDK, not
        // a member of the contract artifact. Admission must reject the contract
        // before any plugin code runs — a framework type can never ride a
        // contract payload.
        final Path contract = compile(Map.of(
            "com.acme.events.FrameworkBoundEvent", """
                package com.acme.events;
                public record FrameworkBoundEvent(
                    dev.turboism.core.event.RuntimeEventBroker injected
                ) implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """
        ));
        final Path artifact = writeJar(
            temporary.resolve("contract-framework-" + System.nanoTime() + ".jar"),
            contract,
            null
        );
        try (PublicEventContractCatalog catalog = catalog()) {
            final var lease = catalog.acquire(
                descriptor(pluginJson("dev.example.provider", artifact)),
                pluginJar("dev.example.provider", artifact)
            );
            try {
                final Class<?> eventClass = lease.delegates()
                    .get("com.acme.events.FrameworkBoundEvent")
                    .loadClass("com.acme.events.FrameworkBoundEvent");
                final IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> PublicEventAbi.resolve(
                        "com.acme.events.FrameworkBoundEvent",
                        eventAbi(artifact, "com.acme.events.FrameworkBoundEvent"),
                        catalog
                    )
                );
                assertTrue(
                    failure.getMessage().contains("RuntimeEventBroker")
                        || failure.getMessage().contains("closure"),
                    failure.getMessage()
                );
            } finally {
                lease.close();
            }
        }
    }

    @Test
    void incompatibleContractVersionRangeRejectsConsumerAdmission() throws Exception {
        // The provider exports contractVersion 1.0.0; the consumer pins a range
        // that excludes it. The event type and ABI digest still match — only the
        // declared contract version range is wrong, so admission must fail on
        // compatibility, not on resolution.
        final Path artifact = contractArtifact("v1");
        final RuntimeScheduler scheduler = scheduler();
        try (PublicEventContractCatalog contracts = catalog()) {
            final RuntimeEventBroker broker = new RuntimeEventBroker(
                scheduler, 64, ignored -> { }, ignored -> { }, contracts
            );
            final PluginDescriptor provider = descriptor(exportPluginJson(
                "dev.example.provider", artifact, EVENT_TYPE, "1.0.0"
            ));
            final var providerLease = contracts.acquire(
                provider, pluginJar("dev.example.provider", artifact)
            );
            broker.preflight(provider, providerLease);
            broker.admit(provider);

            final PluginDescriptor consumer = descriptor(importPluginJson(
                "dev.example.consumer", artifact, EVENT_TYPE, "[9.9.9,10.0.0)"
            ));
            final var consumerLease = contracts.acquire(
                consumer, pluginJar("dev.example.consumer", artifact)
            );
            broker.preflight(consumer, consumerLease);
            final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> broker.admit(consumer)
            );
            assertTrue(
                failure.getMessage().contains("contract version is incompatible"),
                failure.getMessage()
            );
            consumerLease.close();
            providerLease.close();
        } finally {
            scheduler.shutdown();
        }
    }

    // -- fixtures -------------------------------------------------------------

    private PublicEventContractCatalog catalog() throws IOException {
        return new PublicEventContractCatalog(
            Files.createDirectories(temporary.resolve("contracts-" + System.nanoTime()))
        );
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 8, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }

    private static PluginDescriptor descriptor(final String json) {
        try {
            return new PluginDescriptorParser().parse(json);
        } catch (dev.turboism.core.descriptor.DescriptorParseException failure) {
            throw new IllegalStateException("fixture descriptor is invalid", failure);
        }
    }

    private static Object newPayloadInstance(
        final PublicEventContractCatalog.ContractLease lease
    ) throws Exception {
        final Class<?> payload = lease.delegates().get(EVENT_TYPE)
            .loadClass("com.acme.events.GreetingPayload");
        return payload.getDeclaredConstructor(String.class).newInstance("hello");
    }

    /**
     * Compiles the shared contract artifact. {@code variant} changes the payload
     * component name so different artifacts hash and digest differently.
     */
    private Path contractArtifact(final String variant) throws IOException {
        return writeJar(
            temporary.resolve("contract-" + variant + "-" + System.nanoTime() + ".jar"),
            contractClasses(variant),
            null
        );
    }

    /** Compiles a second artifact family with the same member names. */
    private Path collidingArtifact() throws IOException {
        final Path classes = compile(Map.of(
            EVENT_TYPE, """
                package com.acme.events;
                public record Greeting(String payload)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """
        ));
        return writeJar(
            temporary.resolve("contract-colliding-" + System.nanoTime() + ".jar"),
            classes,
            null
        );
    }

    private Path contractClasses(final String variant) throws IOException {
        final String component = "payload" + variant;
        return compile(Map.of(
            EVENT_TYPE, """
                package com.acme.events;
                public record Greeting(com.acme.events.GreetingPayload %s)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """.formatted(component),
            "com.acme.events.GreetingPayload", """
                package com.acme.events;
                public record GreetingPayload(String text) {}
                """
        ));
    }

    private Path compile(final Map<String, String> sources) throws IOException {
        return compile(sources, new Path[0]);
    }

    /**
     * Compiles fixture sources; {@code extraClasspath} entries are visible to the
     * compiler but never packaged into the produced classes directory, which is how a
     * fixture can reference a type the shipped artifact does not contain.
     */
    private Path compile(
        final Map<String, String> sources,
        final Path... extraClasspath
    ) throws IOException {
        final Path sourceRoot = Files.createDirectories(
            temporary.resolve("src-" + System.nanoTime())
        );
        final Path classes = Files.createDirectories(
            temporary.resolve("classes-" + System.nanoTime())
        );
        final List<String> files = new java.util.ArrayList<>();
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
        for (final Path extra : extraClasspath) {
            classpath.append(java.io.File.pathSeparator).append(extra);
        }
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final List<String> arguments = new java.util.ArrayList<>(List.of(
            "-classpath", classpath.toString(),
            "-d", classes.toString()
        ));
        arguments.addAll(files);
        final int result = compiler.run(
            null, null, null, arguments.toArray(new String[0])
        );
        if (result != 0) {
            throw new IllegalStateException("fixture compilation failed");
        }
        return classes;
    }

    private static Path writeJar(
        final Path jar,
        final Path classes,
        final Manifest manifest
    ) throws IOException {
        try (JarOutputStream output = jarOutput(jar, manifest)) {
            writeEntries(output, classes);
        }
        return jar;
    }

    private static JarOutputStream jarOutput(final Path jar) throws IOException {
        return new JarOutputStream(Files.newOutputStream(jar));
    }

    private static JarOutputStream jarOutput(
        final Path jar,
        final Manifest manifest
    ) throws IOException {
        return manifest == null
            ? jarOutput(jar)
            : new JarOutputStream(Files.newOutputStream(jar), manifest);
    }

    private static void writeEntries(
        final JarOutputStream output,
        final Path classes
    ) throws IOException {
        try (var paths = Files.walk(classes)) {
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

    /** Builds a plugin JAR with the descriptor and the embedded contract artifact. */
    private Path pluginJar(
        final String pluginId,
        final Path contractArtifact
    ) throws IOException {
        return pluginJar(pluginId, contractArtifact, null);
    }

    private Path pluginJar(
        final String pluginId,
        final Path contractArtifact,
        final Path extraClasses
    ) throws IOException {
        final Path jar = temporary.resolve(
            pluginId + "-" + System.nanoTime() + ".jar"
        );
        try (JarOutputStream output = jarOutput(jar)) {
            if (contractArtifact != null) {
                put(output, ARTIFACT_PATH, Files.readAllBytes(contractArtifact));
            }
            if (extraClasses != null) {
                writeEntries(output, extraClasses);
            }
            put(
                output,
                "META-INF/turboism/plugin.json",
                pluginJson(pluginId, contractArtifact).getBytes(StandardCharsets.UTF_8)
            );
        }
        return jar;
    }

    private Path pluginJarWithExtraEntry(
        final String pluginId,
        final Path contractArtifact,
        final Map<String, byte[]> extraEntries
    ) throws IOException {
        final Path jar = temporary.resolve(
            pluginId + "-extra-" + System.nanoTime() + ".jar"
        );
        try (JarOutputStream output = jarOutput(jar)) {
            put(output, ARTIFACT_PATH, Files.readAllBytes(contractArtifact));
            for (final Map.Entry<String, byte[]> entry : extraEntries.entrySet()) {
                put(output, entry.getKey(), entry.getValue());
            }
            put(
                output,
                "META-INF/turboism/plugin.json",
                pluginJson(pluginId, contractArtifact).getBytes(StandardCharsets.UTF_8)
            );
        }
        return jar;
    }

    private static String pluginJson(
        final String pluginId,
        final Path contractArtifact
    ) throws IOException {
        return pluginJson(pluginId, contractArtifact, ARTIFACT_PATH);
    }

    private static String pluginJson(
        final String pluginId,
        final Path contractArtifact,
        final String artifactPath
    ) throws IOException {
        return pluginJson(pluginId, contractArtifact, artifactPath, CONTRACT_ID);
    }

    private static String pluginJson(
        final String pluginId,
        final Path contractArtifact,
        final String artifactPath,
        final String contractId
    ) throws IOException {
        final String sha256 = contractArtifact == null
            ? "0".repeat(64)
            : sha256Hex(Files.readAllBytes(contractArtifact));
        return pluginJson(pluginId, contractArtifact, artifactPath, contractId, sha256);
    }

    private static String pluginJson(
        final String pluginId,
        final Path contractArtifact,
        final String artifactPath,
        final String contractId,
        final String sha256
    ) {
        final String contracts = contractArtifact == null
            ? ""
            : """
              ,"eventContracts":[{"id":"%s","version":"1.0.0",
                "artifact":"%s","sha256":"%s"}]
              """.formatted(contractId, artifactPath, sha256);
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

    /** Descriptor for a provider that exports the contract event type. */
    private String exportPluginJson(
        final String pluginId,
        final Path contractArtifact,
        final String eventType,
        final String contractVersion
    ) throws IOException {
        final String abi = eventAbi(contractArtifact, eventType);
        final String sha256 = sha256Hex(Files.readAllBytes(contractArtifact));
        return """
            {"format":"turboism.plugin.meta","schemaVersion":5,
            "id":"%s","name":"%s","version":"0.1.0",
            "description":"test","entrypoints":["dev.example.provider.ProviderPlugin"],
            "turboismApi":"[0.1.0,0.2.0)","authors":[{"name":"Tests"}],
            "license":"Test","website":"https://turboism.dev","resources":[],
            "i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]},
            "dependencies":[],"permissions":[],"capabilities":[],
            "environment":{"requiresCubism":false,"ui":"none"},
            "category":"workflow",
            "eventExports":[{"id":"greeting","contractVersion":"%s",
              "eventType":"%s","abiSha256":"%s"}],
            "eventContracts":[{"id":"%s","version":"1.0.0",
              "artifact":"%s","sha256":"%s"}]}
            """.formatted(
                pluginId, pluginId, contractVersion, eventType, abi,
                CONTRACT_ID, ARTIFACT_PATH, sha256
            );
    }

    /**
     * Descriptor for a consumer importing the provider's contract event with a
     * correctly ordered required dependency, so only the declared contract
     * version range is under test.
     */
    private String importPluginJson(
        final String pluginId,
        final Path contractArtifact,
        final String eventType,
        final String contractRange
    ) throws IOException {
        final String abi = eventAbi(contractArtifact, eventType);
        final String sha256 = sha256Hex(Files.readAllBytes(contractArtifact));
        return """
            {"format":"turboism.plugin.meta","schemaVersion":5,
            "id":"%s","name":"%s","version":"0.1.0",
            "description":"test","entrypoints":["dev.example.consumer.ConsumerPlugin"],
            "turboismApi":"[0.1.0,0.2.0)","authors":[{"name":"Tests"}],
            "license":"Test","website":"https://turboism.dev","resources":[],
            "i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]},
            "dependencies":[{"id":"dev.example.provider","version":"[0.1.0,0.2.0)",
              "type":"required","ordering":"after"}],
            "permissions":[],"capabilities":[],
            "environment":{"requiresCubism":false,"ui":"none"},
            "category":"workflow",
            "eventImports":[{"provider":"dev.example.provider","eventId":"greeting",
              "contractVersion":"%s","eventType":"%s",
              "abiSha256":"%s","required":true}],
            "eventContracts":[{"id":"%s","version":"1.0.0",
              "artifact":"%s","sha256":"%s"}]}
            """.formatted(
                pluginId, pluginId, contractRange, eventType, abi,
                CONTRACT_ID, ARTIFACT_PATH, sha256
            );
    }

    /**
     * Computes the declared ABI digest for a fixture contract type through a
     * plain loader — the digest is structural, so any loader yields the pinned
     * value the runtime will recompute against the bound contract class.
     */
    private String eventAbi(
        final Path contractArtifact,
        final String eventType
    ) throws IOException {
        try (URLClassLoader loader = new URLClassLoader(
            new URL[]{contractArtifact.toUri().toURL()},
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

    private static String sha256Hex(final byte[] bytes) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    /** Contract loader whose real disposal always fails, for the sticky-failure test. */
    private static final class FailingCloseContractLoader
        extends ContractArtifactClassLoader {
        private FailingCloseContractLoader(
            final URL artifact,
            final Set<String> classNames
        ) {
            super(artifact, classNames);
        }

        @Override
        void closeDelegate() throws IOException {
            throw new IOException("simulated contract disposal failure");
        }
    }
}
