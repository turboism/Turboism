package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.mapping.verification.selector.CubismSliceCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class CubismHostCompatibilityResolverTest {

    private static final String DECLARED_VERSION = "5.3.99";
    private static final int DECLARED_BUILD = 503990001;
    private static final String DECLARED_DATE = "2026/12/31";
    private static final String OWNER = "synthetic/host/SyntheticHost";
    private static final String SLICE = "editor-model";
    private static final String ADAPTER_SLICE = "adapter.editor-model.readwrite";

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void resolvesDeclaredUnknownHostToCompatibleDegradation() throws Exception {
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, true);

        final CompatibilityResolution resolution =
                CubismHostCompatibilityResolver.resolve(artifact, null, name -> null);

        assertEquals(CompatibilityResolution.Mode.COMPATIBLE, resolution.mode());
        assertEquals(DECLARED_VERSION, resolution.declaredVersion());
        assertFalse(resolution.runtimeAdmitted());
        assertFalse(resolution.slices().isEmpty());
        // Every slice failed closed: the embedded records pin real Cubism
        // classes, none of which exist in the synthetic artifact.
        for (final CompatibilityResolution.SliceResolution slice :
                resolution.slices().values()) {
            assertFalse(slice.admitted(), slice.sliceId() + " must not admit");
        }
        // The host is never relabeled as a reviewed version.
        assertEquals(Optional.of(DECLARED_VERSION), resolution.identity().map(CubismHostIdentity::version));
    }

    @Test
    void resolveRejectsArtifactMutatedDuringAdmissionResolution() throws Exception {
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, true);
        final CubismSliceCatalog.Candidate candidate =
                CubismSliceCatalog.candidates("editor-model").get(0);

        final byte[] record = recordBytes(
                candidate.cubismVersion(),
                candidate.verificationId(),
                candidate.adapterSliceId(),
                List.of(selectorSpec("synthetic.class", "class", "", "", 1, 0)));
        final ObjectNode node = (ObjectNode) mapper.readTree(record);
        node.put("profileId", candidate.profileId());
        final byte[] validRecord = mapper.writeValueAsBytes(node);

        // 1. Sanity check: valid record admits the slice when artifact remains intact
        final CompatibilityResolution unmutated = CubismHostCompatibilityResolver.resolve(
                artifact, null, name -> candidate.recordFileName().equals(name) ? validRecord : null);
        assertTrue(unmutated.slices().get("editor-model").admitted(), "sanity check: slice admitted with validRecord");

        // 2. Admission with mutation: mutate artifact only after candidate record was queried and cached
        final byte[] originalBytes = Files.readAllBytes(artifact);
        final java.util.concurrent.atomic.AtomicBoolean recordReturned =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean mutated = new java.util.concurrent.atomic.AtomicBoolean(false);

        final java.util.List<String> queried = new java.util.ArrayList<>();
        final CompatibilityResolution mutatedResolution =
                CubismHostCompatibilityResolver.resolve(artifact, null, name -> {
                    queried.add(name);
                    if (candidate.recordFileName().equals(name)) {
                        recordReturned.set(true);
                        return validRecord;
                    }
                    if (recordReturned.get() && mutated.compareAndSet(false, true)) {
                        try {
                            final long size = Files.size(artifact);
                            final var mtime = Files.getLastModifiedTime(artifact);
                            final byte[] bytes = originalBytes.clone();
                            bytes[bytes.length - 1] ^= 0x55;
                            Files.write(artifact, bytes);
                            assertEquals(size, Files.size(artifact));
                            Files.setLastModifiedTime(artifact, mtime);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    return null;
                });

        assertTrue(
                mutated.get(),
                "mutation hook must fire during resolution; candidate=" + candidate.recordFileName() + " queried="
                        + queried + " res=" + mutatedResolution.slices());
        assertEquals(CompatibilityResolution.Mode.REJECTED, mutatedResolution.mode());
        assertEquals(
                HostIdentityProbe.Status.DECLARATION_AMBIGUOUS,
                mutatedResolution.identityProbe().status());
        assertEquals(
                "host artifact changed during admission resolution",
                mutatedResolution.identityProbe().detail());

        // 3. Recovery check: restore original bytes and verify fresh admission succeeds without stale cache
        Files.write(artifact, originalBytes);
        final CompatibilityResolution recovered = CubismHostCompatibilityResolver.resolve(
                artifact, null, name -> candidate.recordFileName().equals(name) ? validRecord : null);
        assertTrue(recovered.slices().get("editor-model").admitted(), "cross-admission cache cleanly isolated");
    }

    @Test
    void rejectsArtifactWithoutCubismAnchor() throws Exception {
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, false);

        final CompatibilityResolution resolution =
                CubismHostCompatibilityResolver.resolve(artifact, null, name -> null);

        assertEquals(CompatibilityResolution.Mode.REJECTED, resolution.mode());
        assertEquals(
                HostIdentityProbe.Status.NOT_CUBISM, resolution.identityProbe().status());
    }

    @Test
    void rejectsMissingDeclaration() throws Exception {
        final Path artifact = jarWithoutDeclaration();
        final CompatibilityResolution resolution =
                CubismHostCompatibilityResolver.resolve(artifact, null, name -> null);

        assertEquals(CompatibilityResolution.Mode.REJECTED, resolution.mode());
        assertEquals(
                HostIdentityProbe.Status.DECLARATION_MISSING,
                resolution.identityProbe().status());
    }

    @Test
    void rejectsConflictingDeclarations() throws Exception {
        final Path artifact = conflictingJar();

        final CompatibilityResolution resolution =
                CubismHostCompatibilityResolver.resolve(artifact, null, name -> null);

        assertEquals(CompatibilityResolution.Mode.REJECTED, resolution.mode());
        assertEquals(
                HostIdentityProbe.Status.DECLARATION_AMBIGUOUS,
                resolution.identityProbe().status());
    }

    @Test
    void admitsSliceWhenWholeContractVerifiesStructurally() throws Exception {
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, true);
        final CubismHostIdentity identity = identity(artifact);
        final byte[] recordBytes = recordBytes(
                "5.3.02",
                "synthetic.editor-model",
                ADAPTER_SLICE,
                List.of(selectorSpec("synthetic.class", "class", "", "", 1, 0)));
        final CubismSliceCatalog.Candidate candidate =
                candidate(SLICE, "5.3.02", recordBytes, "synthetic.editor-model");

        final CompatibilityResolution.SliceResolution slice = CubismHostCompatibilityResolver.probeSlice(
                        SLICE,
                        identity,
                        artifact,
                        List.of(candidate),
                        new StaticVerificationRecordLoader(),
                        new StaticSelectorVerifier()::verifyStructure,
                        Map.of("cubism-5.3.02-editor-model.json", recordBytes)::get)
                .resolution();

        assertEquals(CompatibilityResolution.SliceStatus.ADMITTED, slice.status());
        final SliceContract contract = slice.contract().orElseThrow();
        assertTrue(contract.compatible());
        assertEquals("5.3.02", contract.sourceVersion());
        assertEquals(DECLARED_VERSION, contract.declaredVersion());
        assertEquals(SLICE, contract.sliceId());
    }

    @Test
    void mergesStructurallyEqualCandidates() throws Exception {
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, true);
        final CubismHostIdentity identity = identity(artifact);
        final byte[] recordA = recordBytes(
                "5.3.02",
                "synthetic.a",
                ADAPTER_SLICE,
                List.of(selectorSpec("synthetic.class", "class", "", "", 1, 0)));
        final byte[] recordB = recordBytes(
                "5.3.03",
                "synthetic.b",
                ADAPTER_SLICE,
                List.of(selectorSpec("synthetic.class", "class", "", "", 1, 0)));

        final CompatibilityResolution.SliceResolution slice = CubismHostCompatibilityResolver.probeSlice(
                        SLICE,
                        identity,
                        artifact,
                        List.of(
                                candidate(SLICE, "5.3.02", recordA, "synthetic.a"),
                                candidate(SLICE, "5.3.03", recordB, "synthetic.b")),
                        new StaticVerificationRecordLoader(),
                        new StaticSelectorVerifier()::verifyStructure,
                        Map.of(
                                "cubism-5.3.02-editor-model.json", recordA,
                                "cubism-5.3.03-editor-model.json", recordB)::get)
                .resolution();

        assertEquals(CompatibilityResolution.SliceStatus.ADMITTED, slice.status());
        assertEquals(Set.of("5.3.02", "5.3.03"), Set.copyOf(slice.matchedCandidates()));
    }

    @Test
    void admitsSliceWhenInheritsContractVerifies() throws Exception {
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, true);
        final CubismHostIdentity identity = identity(artifact);
        // The synthetic host class directly extends java.lang.Object.
        final byte[] recordBytes = recordBytes(
                "5.3.02",
                "synthetic.editor-model",
                ADAPTER_SLICE,
                List.of(
                        selectorSpec("synthetic.class", "class", "", "", 1, 0),
                        selectorSpec("synthetic.inherits.object", "inherits", "java/lang/Object", "", 0, 0)));
        final CubismSliceCatalog.Candidate candidate =
                candidate(SLICE, "5.3.02", recordBytes, "synthetic.editor-model");

        final CompatibilityResolution.SliceResolution slice = CubismHostCompatibilityResolver.probeSlice(
                        SLICE,
                        identity,
                        artifact,
                        List.of(candidate),
                        new StaticVerificationRecordLoader(),
                        new StaticSelectorVerifier()::verifyStructure,
                        Map.of("cubism-5.3.02-editor-model.json", recordBytes)::get)
                .resolution();

        assertEquals(CompatibilityResolution.SliceStatus.ADMITTED, slice.status());
    }

    @Test
    void matchingSelectorsDoNotMergeDifferentCapabilityRequirements() throws Exception {
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, true);
        final List<SelectorSpec> selectors = List.of(selectorSpec("synthetic.class", "class", "", "", 1, 0));
        final byte[] recordA = recordBytes(
                "5.3.02",
                "synthetic.a",
                ADAPTER_SLICE,
                selectors,
                List.of("synthetic.write"),
                Map.of("synthetic.write", List.of("structure")));
        final byte[] recordB = recordBytes(
                "5.3.03",
                "synthetic.b",
                ADAPTER_SLICE,
                selectors,
                List.of("synthetic.write"),
                Map.of("synthetic.write", List.of("hook:edit-api-dispatch")));

        final var slice = CubismHostCompatibilityResolver.probeSlice(
                        SLICE,
                        identity(artifact),
                        artifact,
                        List.of(
                                candidate(SLICE, "5.3.02", recordA, "synthetic.a"),
                                candidate(SLICE, "5.3.03", recordB, "synthetic.b")),
                        new StaticVerificationRecordLoader(),
                        new StaticSelectorVerifier()::verifyStructure,
                        Map.of("cubism-5.3.02-editor-model.json", recordA, "cubism-5.3.03-editor-model.json", recordB)
                                ::get)
                .resolution();

        assertEquals(CompatibilityResolution.SliceStatus.AMBIGUOUS, slice.status());
        assertTrue(slice.contract().isEmpty(), "matching members cannot erase a Hook prerequisite");
    }

    @Test
    void degradesSliceWhenInheritsContractBreaks() throws Exception {
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, true);
        final CubismHostIdentity identity = identity(artifact);
        // The synthetic host class extends Object, not String — a host whose
        // reviewed type graph moved cannot satisfy the contract.
        final byte[] recordBytes = recordBytes(
                "5.3.02",
                "synthetic.editor-model",
                ADAPTER_SLICE,
                List.of(
                        selectorSpec("synthetic.class", "class", "", "", 1, 0),
                        selectorSpec("synthetic.inherits.string", "inherits", "java/lang/String", "", 0, 0)));
        final CubismSliceCatalog.Candidate candidate =
                candidate(SLICE, "5.3.02", recordBytes, "synthetic.editor-model");

        final CompatibilityResolution.SliceResolution slice = CubismHostCompatibilityResolver.probeSlice(
                        SLICE,
                        identity,
                        artifact,
                        List.of(candidate),
                        new StaticVerificationRecordLoader(),
                        new StaticSelectorVerifier()::verifyStructure,
                        Map.of("cubism-5.3.02-editor-model.json", recordBytes)::get)
                .resolution();

        assertNotEquals(CompatibilityResolution.SliceStatus.ADMITTED, slice.status());
        assertTrue(slice.contract().isEmpty());
    }

    @Test
    void stripsConditionGatedCapabilitiesFromUnboundCompatibleContracts() throws Exception {
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, true);
        final CubismHostIdentity identity = identity(artifact);
        final List<String> capabilityIds = List.of(
                "cubism.editor-model.read",
                "cubism.editor-model.write",
                "cubism.editor-model.undo.revert",
                "cubism.editor-history.read",
                "cubism.editor-model.physics.read",
                "cubism.editor-model.warp-mirror");
        final Map<String, List<String>> conditions = Map.of(
                "cubism.editor-model.read", List.of("structure"),
                "cubism.editor-model.write", List.of("declaredGeneration"),
                "cubism.editor-model.undo.revert", List.of("declaredGeneration"),
                "cubism.editor-history.read", List.of("hook:native-edit-begin"),
                "cubism.editor-model.physics.read", List.of("hook:physics-editor"),
                "cubism.editor-model.warp-mirror", List.of("hook:warp-alt-mirror"));
        final byte[] recordBytes = recordBytes(
                "5.3.02",
                "synthetic.editor-model",
                ADAPTER_SLICE,
                List.of(selectorSpec("synthetic.class", "class", "", "", 1, 0)),
                capabilityIds,
                conditions);
        final CubismSliceCatalog.Candidate candidate =
                candidate(SLICE, "5.3.02", recordBytes, "synthetic.editor-model");

        final CompatibilityResolution.SliceResolution slice = CubismHostCompatibilityResolver.probeSlice(
                        SLICE,
                        identity,
                        artifact,
                        List.of(candidate),
                        new StaticVerificationRecordLoader(),
                        new StaticSelectorVerifier()::verifyStructure,
                        Map.of("cubism-5.3.02-editor-model.json", recordBytes)::get)
                .resolution();

        assertEquals(CompatibilityResolution.SliceStatus.ADMITTED, slice.status());
        final SliceContract contract = slice.contract().orElseThrow();
        // The host declared an unreviewed generation, so the slice bound to the
        // The structural preflight permits hooks with independent target proof to
        // proceed to installation; generation-only dependencies remain unavailable.
        assertEquals(
                Set.of("cubism.editor-model.read", "cubism.editor-history.read", "cubism.editor-model.warp-mirror"),
                contract.capabilities());
        assertFalse(contract.declaredGenerationBound());
        assertEquals(
                Map.of(
                        "cubism.editor-model.write", "declaredGeneration",
                        "cubism.editor-model.undo.revert", "declaredGeneration",
                        "cubism.editor-model.physics.read", "hook:physics-editor"),
                contract.droppedCapabilities());
    }

    @Test
    void retainsFullCapabilitiesWhenCompatibleSliceBindsDeclaredGeneration() throws Exception {
        // A resource-repacked reviewed build: the host declares 5.3.02 (a
        // reviewed runtime generation) and structurally binds the 5.3.02
        // contract, so declaredGeneration and declared-generation hook
        // conditions hold; only the artifact-digest hook contract drops.
        final Path artifact = hostJar("5.3.02", 503020001, DECLARED_DATE, true);
        final CubismHostIdentity identity = identity(artifact, "5.3.02", 503020001);
        final List<String> capabilityIds = List.of(
                "cubism.editor-model.read",
                "cubism.editor-model.write",
                "cubism.editor-history.read",
                "cubism.editor-model.warp-mirror");
        final Map<String, List<String>> conditions = Map.of(
                "cubism.editor-model.read", List.of("structure"),
                "cubism.editor-model.write", List.of("declaredGeneration"),
                "cubism.editor-history.read", List.of("hook:native-edit-begin"),
                "cubism.editor-model.warp-mirror", List.of("hook:warp-alt-mirror"));
        final byte[] recordBytes = recordBytes(
                "5.3.02",
                "synthetic.editor-model",
                ADAPTER_SLICE,
                List.of(selectorSpec("synthetic.class", "class", "", "", 1, 0)),
                capabilityIds,
                conditions);
        final CubismSliceCatalog.Candidate candidate =
                candidate(SLICE, "5.3.02", recordBytes, "synthetic.editor-model");

        final CompatibilityResolution.SliceResolution slice = CubismHostCompatibilityResolver.probeSlice(
                        SLICE,
                        identity,
                        artifact,
                        List.of(candidate),
                        new StaticVerificationRecordLoader(),
                        new StaticSelectorVerifier()::verifyStructure,
                        Map.of("cubism-5.3.02-editor-model.json", recordBytes)::get)
                .resolution();

        assertEquals(CompatibilityResolution.SliceStatus.ADMITTED, slice.status());
        final SliceContract contract = slice.contract().orElseThrow();
        assertTrue(contract.compatible());
        assertTrue(contract.declaredGenerationBound());
        assertEquals(
                Set.of(
                        "cubism.editor-model.read",
                        "cubism.editor-model.write",
                        "cubism.editor-history.read",
                        "cubism.editor-model.warp-mirror"),
                contract.capabilities());
        assertTrue(contract.droppedCapabilities().isEmpty());
    }

    @Test
    void unknownBuildOfReviewedVersionOnlyAdmitsProvenConditions() throws Exception {
        final Path artifact = hostJar("5.3.02", 503020002, DECLARED_DATE, true);
        final CubismHostIdentity identity = identity(artifact, "5.3.02", 503020002);
        final byte[] record = recordBytes(
                "5.3.02",
                "synthetic.editor-model",
                ADAPTER_SLICE,
                List.of(selectorSpec("synthetic.class", "class", "", "", 1, 0)),
                List.of("synthetic.read", "synthetic.write"),
                Map.of("synthetic.read", List.of("structure"), "synthetic.write", List.of("declaredGeneration")));

        final var slice = CubismHostCompatibilityResolver.probeSlice(
                        SLICE,
                        identity,
                        artifact,
                        List.of(candidate(SLICE, "5.3.02", record, "synthetic.editor-model")),
                        new StaticVerificationRecordLoader(),
                        new StaticSelectorVerifier()::verifyStructure,
                        Map.of("cubism-5.3.02-editor-model.json", record)::get)
                .resolution();

        assertTrue(slice.admitted(), "new build must still attempt structural compatibility");
        final SliceContract contract = slice.contract().orElseThrow();
        assertFalse(contract.declaredGenerationBound(), "a familiar version does not prove its new build");
        assertEquals(Set.of("synthetic.read"), contract.capabilities());
        assertEquals(Map.of("synthetic.write", "declaredGeneration"), contract.droppedCapabilities());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {503020001, 503020002})
    void onlyReviewedReleaseCanResolveConflictingBindingsByDeclaration(final int build) throws Exception {
        final Path artifact = hostJar("5.3.02", build, DECLARED_DATE, true);
        final CubismHostIdentity identity = identity(artifact, "5.3.02", build);
        final byte[] recordA = recordBytes(
                "5.3.02",
                "synthetic.a",
                ADAPTER_SLICE,
                List.of(selectorSpec("synthetic.class", "class", "", "", 1, 0)));
        final byte[] recordB = recordBytes(
                "5.3.03",
                "synthetic.b",
                ADAPTER_SLICE,
                List.of(
                        selectorSpec("synthetic.class", "class", "", "", 1, 0),
                        selectorSpec("synthetic.value", "method", "value", "()Ljava/lang/String;", 1, 8)));

        final var slice = CubismHostCompatibilityResolver.probeSlice(
                        SLICE,
                        identity,
                        artifact,
                        List.of(
                                candidate(SLICE, "5.3.02", recordA, "synthetic.a"),
                                candidate(SLICE, "5.3.03", recordB, "synthetic.b")),
                        new StaticVerificationRecordLoader(),
                        new StaticSelectorVerifier()::verifyStructure,
                        Map.of("cubism-5.3.02-editor-model.json", recordA, "cubism-5.3.03-editor-model.json", recordB)
                                ::get)
                .resolution();

        assertEquals(
                build == 503020001
                        ? CompatibilityResolution.SliceStatus.ADMITTED
                        : CompatibilityResolution.SliceStatus.AMBIGUOUS,
                slice.status());
    }

    @Test
    void keepsFullCapabilitiesOnExactContracts() throws Exception {
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, true);
        final CubismHostIdentity identity = identity(artifact);
        final byte[] recordBytes = recordBytes(
                "5.3.02",
                "synthetic.editor-model",
                ADAPTER_SLICE,
                List.of(selectorSpec("synthetic.class", "class", "", "", 1, 0)),
                List.of("cubism.editor-model.read", "cubism.editor-model.write"));
        final CubismSliceCatalog.Candidate candidate =
                candidate(SLICE, "5.3.02", recordBytes, "synthetic.editor-model");

        final CompatibilityResolution.SliceResolution slice = CubismHostCompatibilityResolver.admitted(
                SLICE,
                candidate,
                new StaticVerificationRecordLoader()
                        .load(recordBytes, "synthetic.json")
                        .record(),
                identity,
                false);

        assertEquals(CompatibilityResolution.SliceStatus.ADMITTED, slice.status());
        assertEquals(
                Set.of("cubism.editor-model.read", "cubism.editor-model.write"),
                slice.contract().orElseThrow().capabilities());
        assertFalse(slice.contract().orElseThrow().compatible());
    }

    @Test
    void rejectsAmbiguousContractBindings() throws Exception {
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, true);
        final CubismHostIdentity identity = identity(artifact);
        // Both candidates verify but bind different capability sets.
        final byte[] recordA = recordBytes(
                "5.3.02",
                "synthetic.a",
                ADAPTER_SLICE,
                List.of(selectorSpec("synthetic.class", "class", "", "", 1, 0)));
        final byte[] recordB = recordBytes(
                "5.3.03",
                "synthetic.b",
                ADAPTER_SLICE,
                List.of(
                        selectorSpec("synthetic.class", "class", "", "", 1, 0),
                        selectorSpec("synthetic.value", "method", "value", "()Ljava/lang/String;", 1, 8)));

        final CompatibilityResolution.SliceResolution slice = CubismHostCompatibilityResolver.probeSlice(
                        SLICE,
                        identity,
                        artifact,
                        List.of(
                                candidate(SLICE, "5.3.02", recordA, "synthetic.a"),
                                candidate(SLICE, "5.3.03", recordB, "synthetic.b")),
                        new StaticVerificationRecordLoader(),
                        new StaticSelectorVerifier()::verifyStructure,
                        Map.of("cubism-5.3.02-editor-model.json", recordA, "cubism-5.3.03-editor-model.json", recordB)
                                ::get)
                .resolution();

        assertEquals(CompatibilityResolution.SliceStatus.AMBIGUOUS, slice.status());
        assertTrue(slice.contract().isEmpty());
    }

    @Test
    void rejectsSelectorMismatch() throws Exception {
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, true);
        final CubismHostIdentity identity = identity(artifact);
        final byte[] record = recordBytes(
                "5.3.02",
                "synthetic.editor-model",
                ADAPTER_SLICE,
                List.of(selectorSpec("synthetic.missing", "class", "synthetic/host/Missing", "", "", 1, 0)));

        final CompatibilityResolution.SliceResolution slice = CubismHostCompatibilityResolver.probeSlice(
                        SLICE,
                        identity,
                        artifact,
                        List.of(candidate(SLICE, "5.3.02", record, "synthetic.editor-model")),
                        new StaticVerificationRecordLoader(),
                        new StaticSelectorVerifier()::verifyStructure,
                        Map.of("cubism-5.3.02-editor-model.json", record)::get)
                .resolution();

        assertEquals(CompatibilityResolution.SliceStatus.MISMATCHED, slice.status());
        assertTrue(slice.contract().isEmpty());
    }

    @Test
    void conflictingOptionalCapabilityDoesNotDisableSharedModelReadsOrReturnAfterRebinding() throws Exception {
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, true);
        final CubismHostIdentity identity = identity(artifact);
        final List<SelectorSpec> base = new java.util.ArrayList<>();
        for (final String alias : List.of(
                "cubism.editor-model.app-controller.instance",
                "cubism.editor-model.app-controller.current-document",
                "cubism.editor-model.modeling-document.class",
                "cubism.editor-model.modeling-document.model-source",
                "cubism.editor-model.model-source.current-instance",
                "cubism.editor-model.model.class",
                "cubism.editor-model.model-source.guid",
                "cubism.editor-model.guid.value")) {
            base.add(selectorSpec(alias, "class", "", "", 1, 0));
        }
        final List<SelectorSpec> withOptional = new java.util.ArrayList<>(base);
        withOptional.add(selectorSpec("synthetic.value", "method", "value", "()Ljava/lang/String;", 1, 8));
        final List<String> capabilities = List.of("cubism.editor-model.read", "synthetic.optional");
        final byte[] recordA = recordBytes("5.3.02", "synthetic.a", ADAPTER_SLICE, base, capabilities);
        final byte[] recordB = recordBytes("5.3.03", "synthetic.b", ADAPTER_SLICE, withOptional, capabilities);
        final var probed = CubismHostCompatibilityResolver.probeSlice(
                SLICE,
                identity,
                artifact,
                List.of(
                        candidate(SLICE, "5.3.02", recordA, "synthetic.a"),
                        candidate(SLICE, "5.3.03", recordB, "synthetic.b")),
                new StaticVerificationRecordLoader(),
                new StaticSelectorVerifier()::verifyStructure,
                Map.of("cubism-5.3.02-editor-model.json", recordA, "cubism-5.3.03-editor-model.json", recordB)::get);

        assertEquals(
                CompatibilityResolution.SliceStatus.ADMITTED,
                probed.resolution().status());
        assertEquals(
                Set.of("cubism.editor-model.read"),
                probed.resolution().contract().orElseThrow().capabilities());
        assertEquals(
                "ambiguous:candidate-bindings",
                probed.resolution()
                        .contract()
                        .orElseThrow()
                        .droppedCapabilities()
                        .get("synthetic.optional"));
        final Map<String, CompatibilityResolution.SliceResolution> slices = new LinkedHashMap<>();
        slices.put(SLICE, probed.resolution());
        CubismHostCompatibilityResolver.enforceGroupConsistency(slices, Map.of(SLICE, probed.passed()), identity);
        assertEquals(
                Set.of("cubism.editor-model.read"),
                slices.get(SLICE).contract().orElseThrow().capabilities());
        assertEquals(
                "ambiguous:candidate-bindings",
                slices.get(SLICE).contract().orElseThrow().droppedCapabilities().get("synthetic.optional"));
    }

    @Test
    void completeCoreExtensionPreservesBaseCapabilityInEitherCandidateOrder() throws Exception {
        final var basic = List.of(selectorSpec("synthetic.class", "class", "", "", 1, 0));
        final var complete =
                List.of(basic.get(0), selectorSpec("synthetic.value", "method", "value", "()Ljava/lang/String;", 1, 8));
        final byte[] base = recordBytes(
                "5.1.03",
                "synthetic.base",
                "adapter.core-model.readonly",
                basic,
                List.of("cubism.core.owned-moc.read"));
        final byte[] full = recordBytes(
                "5.2.03",
                "synthetic.full",
                "adapter.core-model.readonly",
                complete,
                List.of("cubism.core.owned-moc.read", "cubism.geometry.read"));
        final var a = candidate("core-model-read", "5.1.03", base, "synthetic.base");
        final var b = candidate("core-model-read", "5.2.03", full, "synthetic.full");
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, true);
        for (var order : List.of(List.of(a, b), List.of(b, a))) {
            final var probe = CubismHostCompatibilityResolver.probeSlice(
                    "core-model-read",
                    identity(artifact),
                    artifact,
                    order,
                    new StaticVerificationRecordLoader(),
                    new StaticSelectorVerifier()::verifyStructure,
                    name -> name.equals(a.recordFileName()) ? base : full);
            assertTrue(probe.resolution().admitted());
            assertEquals("5.2.03", probe.resolution().contract().orElseThrow().sourceVersion());
            assertEquals(
                    Set.of("cubism.core.owned-moc.read", "cubism.geometry.read"),
                    probe.resolution().contract().orElseThrow().capabilities());
        }
    }

    @Test
    void coreExtensionWithChangedBaseBindingOrConditionStillFailsClosed() throws Exception {
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, true);
        final var basic = List.of(selectorSpec("synthetic.class", "class", "", "", 1, 0));
        final byte[] base = recordBytes(
                "5.1.03",
                "synthetic.base",
                "adapter.core-model.readonly",
                basic,
                List.of("cubism.core.owned-moc.read"));
        final var a = candidate("core-model-read", "5.1.03", base, "synthetic.base");
        for (boolean changeBinding : List.of(true, false)) {
            final var fullSelectors = List.of(
                    changeBinding
                            ? selectorSpec("synthetic.class", "method", "value", "()Ljava/lang/String;", 1, 8)
                            : basic.get(0),
                    selectorSpec("synthetic.value", "method", "value", "()Ljava/lang/String;", 1, 8));
            final byte[] full = recordBytes(
                    "5.2.03",
                    "synthetic.full",
                    "adapter.core-model.readonly",
                    fullSelectors,
                    List.of("cubism.core.owned-moc.read", "cubism.geometry.read"),
                    Map.of(
                            "cubism.core.owned-moc.read",
                            changeBinding ? List.of("structure") : List.of("declaredGeneration"),
                            "cubism.geometry.read",
                            List.of("structure")));
            final var b = candidate("core-model-read", "5.2.03", full, "synthetic.full");
            final var probe = CubismHostCompatibilityResolver.probeSlice(
                    "core-model-read",
                    identity(artifact),
                    artifact,
                    List.of(a, b),
                    new StaticVerificationRecordLoader(),
                    new StaticSelectorVerifier()::verifyStructure,
                    name -> name.equals(a.recordFileName()) ? base : full);
            assertFalse(
                    probe.resolution().contract().orElseThrow().capabilities().contains("cubism.core.owned-moc.read"));
            assertEquals(
                    changeBinding ? "ambiguous:candidate-bindings" : "declaredGeneration",
                    probe.resolution()
                            .contract()
                            .orElseThrow()
                            .droppedCapabilities()
                            .get("cubism.core.owned-moc.read"));
        }
    }

    @Test
    void acceptsReserializedRecordAndBindsObservedBytes() throws Exception {
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, true);
        final byte[] original = recordBytes(
                "5.3.02",
                "synthetic.editor-model",
                ADAPTER_SLICE,
                List.of(selectorSpec("synthetic.class", "class", "", "", 1, 0)));
        final CubismSliceCatalog.Candidate candidate = candidate(SLICE, "5.3.02", original, "synthetic.editor-model");
        final byte[] formatted = (new String(original, java.nio.charset.StandardCharsets.UTF_8) + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final var slice = CubismHostCompatibilityResolver.probeSlice(
                        SLICE,
                        identity(artifact),
                        artifact,
                        List.of(candidate),
                        new StaticVerificationRecordLoader(),
                        new StaticSelectorVerifier()::verifyStructure,
                        name -> formatted)
                .resolution();
        assertEquals(CompatibilityResolution.SliceStatus.ADMITTED, slice.status());
        assertEquals(
                new StaticVerificationRecordLoader()
                        .load(formatted, candidate.recordFileName())
                        .sha256(),
                slice.contract().orElseThrow().recordSha256());
    }

    @Test
    void rejectsTamperedRecordBytes() throws Exception {
        final Path artifact = hostJar(DECLARED_VERSION, DECLARED_BUILD, DECLARED_DATE, true);
        final CubismHostIdentity identity = identity(artifact);
        final byte[] record = recordBytes(
                "5.3.02",
                "synthetic.editor-model",
                ADAPTER_SLICE,
                List.of(selectorSpec("synthetic.class", "class", "", "", 1, 0)));
        final CubismSliceCatalog.Candidate candidate = candidate(SLICE, "5.3.02", record, "synthetic.editor-model");
        final byte[] tampered = record.clone();
        tampered[tampered.length - 2] ^= 1;

        final CompatibilityResolution.SliceResolution slice = CubismHostCompatibilityResolver.probeSlice(
                        SLICE,
                        identity,
                        artifact,
                        List.of(candidate),
                        new StaticVerificationRecordLoader(),
                        new StaticSelectorVerifier()::verifyStructure,
                        name -> tampered)
                .resolution();

        assertEquals(CompatibilityResolution.SliceStatus.MISMATCHED, slice.status());
    }

    @Test
    void demotesGroupWithoutCommonGeneration() throws Exception {
        final CubismHostIdentity identity = identity(null);
        final Map<String, CompatibilityResolution.SliceResolution> slices = new LinkedHashMap<>();
        final Map<String, List<CubismHostCompatibilityResolver.CandidateOutcome>> passed = new LinkedHashMap<>();
        slices.put("ui-main-toolbar", admitted("ui-main-toolbar", "5.3.02"));
        slices.put("ui-top-menu", admitted("ui-top-menu", "5.3.03"));
        passed.put("ui-main-toolbar", List.of(outcome("ui-main-toolbar", "5.3.02")));
        passed.put("ui-top-menu", List.of(outcome("ui-top-menu", "5.3.03")));

        CubismHostCompatibilityResolver.enforceGroupConsistency(slices, passed, identity);

        assertEquals(
                CompatibilityResolution.SliceStatus.GROUP_CONFLICT,
                slices.get("ui-main-toolbar").status());
        assertEquals(
                CompatibilityResolution.SliceStatus.GROUP_CONFLICT,
                slices.get("ui-top-menu").status());
    }

    @Test
    void rebindsGroupToCommonGeneration() throws Exception {
        final CubismHostIdentity identity = identity(null);
        final Map<String, CompatibilityResolution.SliceResolution> slices = new LinkedHashMap<>();
        final Map<String, List<CubismHostCompatibilityResolver.CandidateOutcome>> passed = new LinkedHashMap<>();
        // toolbar preferred 5.3.03, top-menu only verified 5.3.02: the shared
        // chrome group must re-bind every member to the common 5.3.02 contract.
        slices.put("ui-main-toolbar", admitted("ui-main-toolbar", "5.3.03"));
        slices.put("ui-top-menu", admitted("ui-top-menu", "5.3.02"));
        passed.put(
                "ui-main-toolbar", List.of(outcome("ui-main-toolbar", "5.3.03"), outcome("ui-main-toolbar", "5.3.02")));
        passed.put("ui-top-menu", List.of(outcome("ui-top-menu", "5.3.02")));

        CubismHostCompatibilityResolver.enforceGroupConsistency(slices, passed, identity);

        assertEquals(
                CompatibilityResolution.SliceStatus.ADMITTED,
                slices.get("ui-main-toolbar").status());
        assertEquals(
                CompatibilityResolution.SliceStatus.ADMITTED,
                slices.get("ui-top-menu").status());
        assertEquals(
                "5.3.02", slices.get("ui-main-toolbar").contract().orElseThrow().sourceVersion());
        assertEquals(
                "5.3.02", slices.get("ui-top-menu").contract().orElseThrow().sourceVersion());
    }

    @Test
    void keepsConsistentGroupGeneration() throws Exception {
        final CubismHostIdentity identity = identity(null);
        final Map<String, CompatibilityResolution.SliceResolution> slices = new LinkedHashMap<>();
        final Map<String, List<CubismHostCompatibilityResolver.CandidateOutcome>> passed = new LinkedHashMap<>();
        slices.put("ui-main-toolbar", admitted("ui-main-toolbar", "5.3.02"));
        slices.put("ui-top-menu", admitted("ui-top-menu", "5.3.02"));
        passed.put("ui-main-toolbar", List.of(outcome("ui-main-toolbar", "5.3.02")));
        passed.put("ui-top-menu", List.of(outcome("ui-top-menu", "5.3.02")));

        CubismHostCompatibilityResolver.enforceGroupConsistency(slices, passed, identity);

        assertEquals(
                CompatibilityResolution.SliceStatus.ADMITTED,
                slices.get("ui-main-toolbar").status());
        assertEquals(
                CompatibilityResolution.SliceStatus.ADMITTED,
                slices.get("ui-top-menu").status());
    }

    private CubismHostCompatibilityResolver.CandidateOutcome outcome(final String sliceId, final String version)
            throws Exception {
        final byte[] record = recordBytes(
                version,
                "synthetic." + sliceId,
                ADAPTER_SLICE,
                List.of(selectorSpec("synthetic.class", "class", "", "", 1, 0)));
        final CubismSliceCatalog.Candidate candidate = candidate(sliceId, version, record, "synthetic." + sliceId);
        final var loaded = new StaticVerificationRecordLoader().load(record, "synthetic.json");
        return CubismHostCompatibilityResolver.CandidateOutcome.passed(
                candidate, loaded.record(), new HostArtifactDigest(0, "0".repeat(64)));
    }

    private CompatibilityResolution.SliceResolution admitted(final String sliceId, final String sourceVersion) {
        return new CompatibilityResolution.SliceResolution(
                sliceId,
                CompatibilityResolution.SliceStatus.ADMITTED,
                Optional.of(new SliceContract(
                        sliceId,
                        sourceVersion,
                        "record.json",
                        "a".repeat(64),
                        "synthetic." + sliceId,
                        "adapter." + sliceId,
                        DECLARED_VERSION,
                        DECLARED_BUILD,
                        new HostArtifactDigest(0, "0".repeat(64)),
                        true,
                        java.util.Set.of("test.capability"),
                        java.util.Map.of())),
                List.of(sourceVersion),
                "synthetic");
    }

    private CubismSliceCatalog.Candidate candidate(
            final String sliceId, final String version, final byte[] recordBytes, final String verificationId)
            throws Exception {
        return new CubismSliceCatalog.Candidate(
                sliceId,
                version,
                "cubism-" + version + "-" + sliceId + ".json",
                sha256(recordBytes),
                verificationId,
                new StaticVerificationRecordLoader()
                        .load(recordBytes, "synthetic.json")
                        .record()
                        .adapterSliceId(),
                "synthetic-profile");
    }

    private static String sha256(final byte[] bytes) throws Exception {
        final java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(digest.digest(bytes));
    }

    private CubismHostIdentity identity(final Path artifact) throws Exception {
        return identity(artifact, DECLARED_VERSION);
    }

    private CubismHostIdentity identity(final Path artifact, final String version) throws Exception {
        return identity(artifact, version, DECLARED_BUILD);
    }

    private CubismHostIdentity identity(final Path artifact, final String version, final int build) throws Exception {
        return new CubismHostIdentity(
                "Live2D Cubism Editor",
                version,
                Optional.of(DECLARED_DATE),
                build,
                "com/live2d/cubism/h",
                artifact == null ? new HostArtifactDigest(0, "0".repeat(64)) : HostArtifactDigest.from(artifact));
    }

    private record SelectorSpec(
            String alias, String kind, String owner, String member, String descriptor, int required, int forbidden) {}

    private SelectorSpec selectorSpec(
            final String alias,
            final String kind,
            final String member,
            final String descriptor,
            final int required,
            final int forbidden) {
        return new SelectorSpec(alias, kind, OWNER, member, descriptor, required, forbidden);
    }

    private SelectorSpec selectorSpec(
            final String alias,
            final String kind,
            final String owner,
            final String member,
            final String descriptor,
            final int required,
            final int forbidden) {
        return new SelectorSpec(alias, kind, owner, member, descriptor, required, forbidden);
    }

    private byte[] recordBytes(
            final String cubismVersion,
            final String verificationId,
            final String adapterSliceId,
            final List<SelectorSpec> selectors)
            throws Exception {
        return recordBytes(cubismVersion, verificationId, adapterSliceId, selectors, List.of("synthetic.capability"));
    }

    private byte[] recordBytes(
            final String cubismVersion,
            final String verificationId,
            final String adapterSliceId,
            final List<SelectorSpec> selectors,
            final List<String> capabilityIds)
            throws Exception {
        final Map<String, List<String>> conditions = new LinkedHashMap<>();
        for (final String capabilityId : capabilityIds) {
            conditions.put(capabilityId, List.of("structure"));
        }
        return recordBytes(cubismVersion, verificationId, adapterSliceId, selectors, capabilityIds, conditions);
    }

    private byte[] recordBytes(
            final String cubismVersion,
            final String verificationId,
            final String adapterSliceId,
            final List<SelectorSpec> selectors,
            final List<String> capabilityIds,
            final Map<String, List<String>> capabilityConditions)
            throws Exception {
        final ObjectNode root = mapper.createObjectNode();
        root.put("format", "turboism.static.verification.record");
        root.put("schemaVersion", 1);
        root.put("verificationId", verificationId);
        root.put("adapterSliceId", adapterSliceId);
        final ArrayNode capabilities = root.putArray("capabilityIds");
        for (final String capabilityId : capabilityIds) {
            capabilities.add(capabilityId);
        }
        final ObjectNode conditions = root.putObject("capabilityConditions");
        capabilityConditions.forEach((capabilityId, conditionList) -> {
            final ArrayNode entry = conditions.putArray(capabilityId);
            conditionList.forEach(entry::add);
        });
        root.put("cubismVersion", cubismVersion);
        root.put("profileId", "synthetic-profile");
        final ObjectNode artifact = root.putObject("artifact");
        artifact.put("name", "synthetic-host.jar");
        artifact.put("size", 1);
        artifact.put("sha256", "a".repeat(64));
        root.put("evidenceType", "JAR_METADATA");
        root.put("evidencePath", "synthetic/record.json");
        root.put("owner", "runtime-test");
        root.put("status", "VERIFIED_STATIC");
        root.put("verifiedBy", "runtime-test");
        root.put("verifiedAt", Instant.parse("2026-07-11T00:00:00Z").toString());
        root.put("safeMode", "Fail closed for synthetic test.");
        final ArrayNode selectorArray = root.putArray("selectors");
        for (final SelectorSpec spec : selectors) {
            final ObjectNode selector = selectorArray.addObject();
            selector.put("mappingId", "synthetic.mapping." + spec.alias());
            selector.put("alias", spec.alias());
            selector.put("kind", spec.kind());
            selector.put("ownerInternalName", spec.owner());
            if (spec.member().isEmpty()) selector.putNull("memberName");
            else selector.put("memberName", spec.member());
            if (spec.descriptor().isEmpty()) selector.putNull("descriptor");
            else selector.put("descriptor", spec.descriptor());
            selector.put("requiredAccessFlags", spec.required());
            selector.put("forbiddenAccessFlags", spec.forbidden());
            selector.put("status", "VERIFIED_STATIC");
        }
        return mapper.writeValueAsBytes(root);
    }

    private Path jarWithoutDeclaration() throws Exception {
        final Path artifact = tempDir.resolve("no-declaration.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(artifact))) {
            writeEntry(output, "com/live2d/cubism/CEAppCtrl.class", anchorClass());
        }
        return artifact;
    }

    private Path conflictingJar() throws Exception {
        final Path artifact = tempDir.resolve("conflicting.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(artifact))) {
            writeEntry(output, "com/live2d/cubism/CEAppCtrl.class", anchorClass());
            writeEntry(
                    output,
                    "com/live2d/cubism/a.class",
                    declarationClass("com/live2d/cubism/a", "5.3.99", DECLARED_DATE, DECLARED_BUILD));
            writeEntry(
                    output,
                    "com/live2d/cubism/b.class",
                    declarationClass("com/live2d/cubism/b", "5.3.98", DECLARED_DATE, DECLARED_BUILD));
        }
        return artifact;
    }

    private Path hostJar(final String version, final int build, final String date, final boolean withAnchor)
            throws Exception {
        final Path classes = Files.createDirectories(
                tempDir.resolve("classes" + Math.abs(version.hashCode()) + "-" + System.nanoTime()));
        final Path sources = Files.createDirectories(
                tempDir.resolve("src" + Math.abs(version.hashCode()) + "-" + System.nanoTime() + "/synthetic/host"));
        final Path source = sources.resolve("SyntheticHost.java");
        Files.writeString(source, """
            package synthetic.host;
            public final class SyntheticHost {
                public String value() { return "x"; }
            }
            """);
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, "-d", classes.toString(), source.toString()));

        final Path artifact = tempDir.resolve("host-" + version + "-" + System.nanoTime() + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(artifact))) {
            if (withAnchor) {
                writeEntry(output, "com/live2d/cubism/CEAppCtrl.class", anchorClass());
            }
            writeEntry(
                    output, "com/live2d/cubism/h.class", declarationClass("com/live2d/cubism/h", version, date, build));
            writeEntry(
                    output,
                    OWNER + ".class",
                    Files.readAllBytes(classes.resolve("synthetic/host/SyntheticHost.class")));
        }
        return artifact;
    }

    private static void writeEntry(final JarOutputStream output, final String name, final byte[] bytes)
            throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static byte[] anchorClass() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                "com/live2d/cubism/CEAppCtrl",
                null,
                "java/lang/Object",
                null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * Builds a minimal class assigning product/version/date/build to its own
     * static fields through ConstantValue attributes — the shape the release
     * detector reads without loading the class.
     */
    private static byte[] declarationClass(
            final String internalName, final String version, final String date, final int build) {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
        writer.visitField(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        "product",
                        "Ljava/lang/String;",
                        null,
                        "Live2D Cubism Editor")
                .visitEnd();
        writer.visitField(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        "version",
                        "Ljava/lang/String;",
                        null,
                        version)
                .visitEnd();
        writer.visitField(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        "date",
                        "Ljava/lang/String;",
                        null,
                        date)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "build", "I", null, build)
                .visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
