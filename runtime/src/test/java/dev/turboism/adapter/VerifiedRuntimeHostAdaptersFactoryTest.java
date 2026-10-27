package dev.turboism.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.adapter.host.HostInstanceDescriptor;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.adapter.host.HostVerificationEvidence;
import dev.turboism.mapping.verification.ClipMaskVerificationManifest;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ProjectWorkspaceVerificationManifest;
import dev.turboism.mapping.verification.SliceContract;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;

class VerifiedRuntimeHostAdaptersFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void dualSliceCreateRejectsDifferentClassloadersBeforeResolverCreation() {
        ClassLoader projectClassLoader = new ClassLoader() {};
        ClassLoader clipClassLoader = new ClassLoader() {};

        assertThrows(
                IllegalArgumentException.class,
                () -> new VerifiedRuntimeHostAdaptersFactory()
                        .create(HostVerificationEvidence.withClipMask(
                                slice("project", "host/Live2D_Cubism.jar", projectClassLoader),
                                slice("clip", "host/Live2D_Cubism.jar", clipClassLoader))));
    }

    @Test
    void dualSliceCreateRejectsDifferentArtifactsBeforeResolverCreation() {
        ClassLoader hostClassLoader = new ClassLoader() {};

        assertThrows(
                IllegalArgumentException.class,
                () -> new VerifiedRuntimeHostAdaptersFactory()
                        .create(HostVerificationEvidence.withClipMask(
                                slice("project", "host/Live2D_Cubism.jar", hostClassLoader),
                                slice("clip", "host/another.jar", hostClassLoader))));
    }

    @Test
    void rejectsAnySelfIssuedRecordEvenWhenItsSelectorsAndSyntheticArtifactMatch() throws Exception {
        Path artifact = jarContaining(Host.class);
        Path record = recordFor(
                artifact,
                ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
                List.copyOf(ProjectWorkspaceVerificationManifest.CAPABILITY_IDS),
                ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES);

        assertThrows(
                IllegalArgumentException.class,
                () -> new VerifiedRuntimeHostAdaptersFactory()
                        .projectWorkspace(record, artifact, Host.class.getClassLoader()));
    }

    @Test
    void createRoutesCompatibilityBoundProjectAndClipMaskSlicesThroughCreateCompatible() throws Exception {
        Path artifact = jarContaining(Host.class);
        HostArtifactDigest artifactDigest = HostArtifactDigest.from(artifact);
        Path projectRecord = recordFor(
                artifact,
                "5.2.03",
                ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
                List.copyOf(ProjectWorkspaceVerificationManifest.CAPABILITY_IDS),
                ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES);
        Path clipRecord = recordFor(
                artifact,
                "5.2.03",
                ClipMaskVerificationManifest.ADAPTER_SLICE_ID,
                List.copyOf(ClipMaskVerificationManifest.CAPABILITY_IDS),
                ClipMaskVerificationManifest.REQUIRED_ALIASES);
        SliceContract projectContract = new SliceContract(
                "project-workspace",
                "5.2.03",
                projectRecord.getFileName().toString(),
                HostArtifactDigest.from(projectRecord).sha256(),
                "fixture.runtime",
                ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
                "5.2.03",
                502030002,
                artifactDigest,
                true,
                ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
                Map.of());
        SliceContract clipContract = new SliceContract(
                "clipmask",
                "5.2.03",
                clipRecord.getFileName().toString(),
                HostArtifactDigest.from(clipRecord).sha256(),
                "fixture.runtime",
                ClipMaskVerificationManifest.ADAPTER_SLICE_ID,
                "5.2.03",
                502030002,
                artifactDigest,
                true,
                ClipMaskVerificationManifest.CAPABILITY_IDS,
                Map.of());

        ClassLoader loader = new URLClassLoader(new URL[] {artifact.toUri().toURL()}, null);
        HostVerificationEvidence evidence = HostVerificationEvidence.withClipMask(
                new HostVerificationEvidence.Slice(projectRecord, artifact, loader, Optional.of(projectContract)),
                new HostVerificationEvidence.Slice(clipRecord, artifact, loader, Optional.of(clipContract)));

        RuntimeHostAdapters adapters = new VerifiedRuntimeHostAdaptersFactory().create(evidence);

        assertTrue(adapters.projectWorkspace().activeProject().isAvailable());
        assertEquals(
                Optional.empty(),
                adapters.projectWorkspace().activeProject().value().orElseThrow());
        assertTrue(adapters.clipMaskRead().clipMasks().isAvailable());
        assertEquals(List.of(), adapters.clipMaskRead().clipMasks().value().orElseThrow());

        try (HostSession session =
                new HostSession(() -> Optional.of(new HostInstanceDescriptor("compat-5203", evidence)))) {
            assertEquals(HostSession.State.ACTIVE, session.refresh());
            assertTrue(session.adapters().projectWorkspace().activeProject().isAvailable());
            assertTrue(session.adapters().clipMaskRead().clipMasks().isAvailable());
        }
    }

    private static HostVerificationEvidence.Slice slice(
            final String name, final String artifact, final ClassLoader classLoader) {
        return new HostVerificationEvidence.Slice(Path.of("records/" + name + ".json"), Path.of(artifact), classLoader);
    }

    private Path recordFor(
            final Path artifact,
            final String adapterSliceId,
            final List<String> capabilityIds,
            final java.util.Set<String> aliases)
            throws Exception {
        return recordFor(artifact, "5.3.02", adapterSliceId, capabilityIds, aliases);
    }

    private Path recordFor(
            final Path artifact,
            final String cubismVersion,
            final String adapterSliceId,
            final List<String> capabilityIds,
            final Set<String> aliases)
            throws Exception {
        var fingerprint = dev.turboism.mapping.verification.HostArtifactFingerprint.from(cubismVersion, artifact);
        String owner = Host.class.getName().replace('.', '/');
        Path record = Files.createTempFile("turboism-runtime-adapters", ".json");
        var capabilities = mapper.createArrayNode();
        var capabilityConditions = mapper.createObjectNode();
        for (String capabilityId : capabilityIds) {
            capabilities.add(capabilityId);
            capabilityConditions.putArray(capabilityId).add("structure");
        }
        var selectors = mapper.createArrayNode();
        for (String alias : aliases.stream().sorted().toList()) {
            var selector = mapper.createObjectNode();
            selector.put("mappingId", "fixture." + alias);
            selector.put("alias", alias);
            selector.put("ownerInternalName", owner);
            if (alias.endsWith(".class")) {
                selector.put("kind", "class");
                selector.putNull("memberName");
                selector.putNull("descriptor");
                selector.put("requiredAccessFlags", 0);
                selector.put("forbiddenAccessFlags", 0);
            } else if (alias.equals("cubism.app-controller.instance")
                    || alias.equals("cubism.clipmask.app-controller.instance")) {
                selector.put("kind", "method");
                selector.put("memberName", "instance");
                selector.put("descriptor", "()L" + owner + ";");
                selector.put("requiredAccessFlags", 9);
                selector.put("forbiddenAccessFlags", 0);
            } else {
                selector.put("kind", "method");
                selector.put("memberName", "placeholder");
                selector.put("descriptor", "()Ljava/lang/Object;");
                selector.put("requiredAccessFlags", 1);
                selector.put("forbiddenAccessFlags", 8);
            }
            selector.put("status", "VERIFIED_STATIC");
            selectors.add(selector);
        }
        var root = mapper.createObjectNode();
        root.put("format", "turboism.static.verification.record");
        root.put("schemaVersion", 1);
        root.put("verificationId", "fixture.runtime");
        root.put("adapterSliceId", adapterSliceId);
        root.set("capabilityIds", capabilities);
        root.set("capabilityConditions", capabilityConditions);
        root.put("cubismVersion", cubismVersion);
        root.put("profileId", "fixture");
        var artifactNode = root.putObject("artifact");
        artifactNode.put("name", artifact.getFileName().toString());
        artifactNode.put("size", fingerprint.size());
        artifactNode.put("sha256", fingerprint.sha256());
        root.put("evidenceType", "JAR_METADATA");
        root.put("evidencePath", "docs/migration/verification/static/fixture.json");
        root.put("owner", "runtime-adapter");
        root.put("verifiedBy", "test");
        root.put("verifiedAt", "2026-07-10T00:00:00Z");
        root.put("safeMode", "Fail closed.");
        root.put("status", "VERIFIED_STATIC");
        root.set("selectors", selectors);
        mapper.writeValue(record.toFile(), root);
        return record;
    }

    private static Path jarContaining(final Class<?> type) throws Exception {
        Path jar = Files.createTempFile("turboism-runtime-host", ".jar");
        List<String> entries = new ArrayList<>();
        entries.add(type.getName().replace('.', '/') + ".class");
        for (Class<?> nested : type.getDeclaredClasses()) {
            entries.add(nested.getName().replace('.', '/') + ".class");
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String entryName : entries) {
                try (InputStream input = type.getClassLoader().getResourceAsStream(entryName)) {
                    output.putNextEntry(new JarEntry(entryName));
                    input.transferTo(output);
                    output.closeEntry();
                }
            }
        }
        return jar;
    }

    public static final class Host {
        public static Host instance() {
            return null;
        }

        public Object placeholder() {
            return null;
        }
    }
}
