package dev.turboism.mapping.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinnedVerifiedResolverWorkflowTest {

    private static final String VERSION = "9.8.7";
    private static final String PROFILE = "synthetic-profile";
    private static final String SLICE = "adapter.synthetic.readonly";
    private static final String CAPABILITY = "synthetic.read";
    private static final String CLASS_ALIAS = "synthetic.host.class";
    private static final String STATIC_ALIAS = "synthetic.host.instance";
    private static final String INSTANCE_ALIAS = "synthetic.host.value";
    private static final String OWNER = "synthetic/host/SyntheticHost";

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final PinnedVerifiedResolverWorkflow workflow = new PinnedVerifiedResolverWorkflow();

    @Test
    void createsResolverFromPinnedSyntheticArtifactAndExercisesPublicSurface() throws Exception {
        Fixture fixture = fixture("verified");

        try (URLClassLoader loader = fixture.loader()) {
            VerifiedMemberResolver resolver = workflow.create(
                fixture.record(), fixture.artifact(), loader, fixture.manifest()
            );
            Object instance = resolver.invokeStatic(STATIC_ALIAS);

            assertEquals("verified", resolver.invoke(INSTANCE_ALIAS, instance));
            assertTrue(resolver.isInstance(CLASS_ALIAS, instance));
            assertTrue(resolver.authorizes(SLICE, Set.of(CAPABILITY), fixture.aliases()));
            assertFalse(resolver.authorizes(SLICE, Set.of(CAPABILITY), Set.of(CLASS_ALIAS)));
            assertEquals(VERSION, resolver.cubismVersion());
            assertTrue(resolver.isExactCubismVersion(VERSION));
            assertFalse(resolver.isExactCubismVersion("9.8.8"));
        }
    }

    @Test
    void rejectsRecordDigestMismatch() throws Exception {
        Fixture fixture = fixture("verified");
        var wrong = manifest(fixture, "0".repeat(64), fixture.digest(), fixture.aliases(), Set.of(CAPABILITY));
        try (URLClassLoader loader = fixture.loader()) {
            assertThrows(IllegalArgumentException.class, () -> workflow.create(
                fixture.record(), fixture.artifact(), loader, wrong
            ));
        }
    }

    @Test
    void rejectsArtifactDigestAndSizeMismatch() throws Exception {
        Fixture fixture = fixture("verified");
        Path tamperedArtifact = tempDir.resolve("tampered-size.jar");
        Files.copy(fixture.artifact(), tamperedArtifact, StandardCopyOption.REPLACE_EXISTING);
        Files.write(tamperedArtifact, new byte[]{0}, StandardOpenOption.APPEND);

        try (URLClassLoader loader = fixture.loader()) {
            var failure = assertThrows(IllegalArgumentException.class, () -> workflow.create(
                fixture.record(), tamperedArtifact, loader, fixture.manifest()
            ));
            assertEquals("host artifact is not the reviewed Cubism artifact", failure.getMessage());
        }
    }

    @Test
    void rejectsSameSizeArtifactDigestMismatch() throws Exception {
        Fixture fixture = fixture("verified");
        Path tamperedArtifact = tempDir.resolve("tampered-digest.jar");
        Files.copy(fixture.artifact(), tamperedArtifact, StandardCopyOption.REPLACE_EXISTING);
        byte[] bytes = Files.readAllBytes(tamperedArtifact);
        bytes[bytes.length - 1] ^= 1;
        Files.write(tamperedArtifact, bytes);
        assertEquals(fixture.size(), Files.size(tamperedArtifact), "tampering must preserve artifact size");

        try (URLClassLoader loader = fixture.loader()) {
            var failure = assertThrows(IllegalArgumentException.class, () -> workflow.create(
                fixture.record(), tamperedArtifact, loader, fixture.manifest()
            ));
            assertEquals("host artifact is not the reviewed Cubism artifact", failure.getMessage());
        }
    }

    @Test
    void rejectsCapabilityAndAliasMismatch() throws Exception {
        Fixture fixture = fixture("verified");
        try (URLClassLoader loader = fixture.loader()) {
            assertThrows(IllegalArgumentException.class, () -> workflow.create(
                fixture.record(), fixture.artifact(), loader,
                manifest(fixture, fixture.recordDigest(), fixture.digest(), fixture.aliases(), Set.of("wrong.capability"))
            ));
            assertThrows(IllegalArgumentException.class, () -> workflow.create(
                fixture.record(), fixture.artifact(), loader,
                manifest(fixture, fixture.recordDigest(), fixture.digest(), Set.of(CLASS_ALIAS), Set.of(CAPABILITY))
            ));
        }
    }

    @Test
    void rejectsRuntimeClassesFromWrongCodeSource() throws Exception {
        Fixture reviewed = fixture("verified");
        Fixture other = fixture("other-bytes");
        try (URLClassLoader wrongLoader = other.loader()) {
            assertThrows(IllegalArgumentException.class, () -> workflow.create(
                reviewed.record(), reviewed.artifact(), wrongLoader, reviewed.manifest()
            ));
        }
    }

    private Fixture fixture(final String value) throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("fixture-" + value));
        Path sources = Files.createDirectories(root.resolve("src/synthetic/host"));
        Path classes = Files.createDirectory(root.resolve("classes"));
        Path source = sources.resolve("SyntheticHost.java");
        Files.writeString(source, """
            package synthetic.host;
            public final class SyntheticHost {
                private static final SyntheticHost INSTANCE = new SyntheticHost();
                public static SyntheticHost instance() { return INSTANCE; }
                public String value() { return "%s"; }
            }
            """.formatted(value));
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", classes.toString(), source.toString());
        assertEquals(0, exit, "synthetic host compilation failed");

        Path artifact = root.resolve("synthetic-host.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(artifact))) {
            Path classFile = classes.resolve(OWNER + ".class");
            output.putNextEntry(new JarEntry(OWNER + ".class"));
            Files.copy(classFile, output);
            output.closeEntry();
        }
        HostArtifactDigest digest = HostArtifactDigest.from(artifact);
        Path record = root.resolve("record.json");
        mapper.writeValue(record.toFile(), record(digest));
        String recordDigest = HostArtifactDigest.from(record).sha256();
        Set<String> aliases = Set.of(CLASS_ALIAS, STATIC_ALIAS, INSTANCE_ALIAS);
        Fixture fixture = new Fixture(record, artifact, digest.size(), digest.sha256(), recordDigest, aliases, null);
        return new Fixture(record, artifact, digest.size(), digest.sha256(), recordDigest, aliases,
            manifest(fixture, recordDigest, digest.sha256(), aliases, Set.of(CAPABILITY)));
    }

    private ObjectNode record(final HostArtifactDigest digest) {
        ObjectNode root = mapper.createObjectNode();
        root.put("format", "turboism.static.verification.record");
        root.put("schemaVersion", 1);
        root.put("verificationId", "synthetic.static");
        root.put("adapterSliceId", SLICE);
        root.putArray("capabilityIds").add(CAPABILITY);
        root.put("cubismVersion", VERSION);
        root.put("profileId", PROFILE);
        ObjectNode artifact = root.putObject("artifact");
        artifact.put("name", "synthetic-host.jar");
        artifact.put("size", digest.size());
        artifact.put("sha256", digest.sha256());
        root.put("evidenceType", "JAR_METADATA");
        root.put("evidencePath", "synthetic/record.json");
        root.put("owner", "runtime-test");
        root.put("status", "VERIFIED_STATIC");
        root.put("verifiedBy", "runtime-test");
        root.put("verifiedAt", Instant.parse("2026-07-11T00:00:00Z").toString());
        root.put("safeMode", "Fail closed for synthetic test.");
        ArrayNode selectors = root.putArray("selectors");
        selector(selectors, "synthetic.mapping.class", CLASS_ALIAS, "class", "", "", 1, 0);
        selector(selectors, "synthetic.mapping.instance", STATIC_ALIAS, "method", "instance",
            "()Lsynthetic/host/SyntheticHost;", 9, 0);
        selector(selectors, "synthetic.mapping.value", INSTANCE_ALIAS, "method", "value",
            "()Ljava/lang/String;", 1, 8);
        return root;
    }

    private void selector(
        final ArrayNode selectors,
        final String mappingId,
        final String alias,
        final String kind,
        final String member,
        final String descriptor,
        final int required,
        final int forbidden
    ) {
        ObjectNode selector = selectors.addObject();
        selector.put("mappingId", mappingId);
        selector.put("alias", alias);
        selector.put("kind", kind);
        selector.put("ownerInternalName", OWNER);
        if (member.isEmpty()) selector.putNull("memberName"); else selector.put("memberName", member);
        if (descriptor.isEmpty()) selector.putNull("descriptor"); else selector.put("descriptor", descriptor);
        selector.put("requiredAccessFlags", required);
        selector.put("forbiddenAccessFlags", forbidden);
        selector.put("status", "VERIFIED_STATIC");
    }

    private PinnedVerifiedResolverWorkflow.Manifest manifest(
        final Fixture fixture,
        final String recordDigest,
        final String artifactDigest,
        final Set<String> aliases,
        final Set<String> capabilities
    ) {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            "synthetic.static", recordDigest, VERSION, PROFILE, fixture.size(), artifactDigest,
            SLICE, capabilities, aliases
        );
    }

    private record Fixture(
        Path record,
        Path artifact,
        long size,
        String digest,
        String recordDigest,
        Set<String> aliases,
        PinnedVerifiedResolverWorkflow.Manifest manifest
    ) {
        URLClassLoader loader() throws Exception {
            return new URLClassLoader(new URL[]{artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        }
    }
}
