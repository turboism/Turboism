package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Real-host-sample identity/compatibility checks, gated on local evidence
 * paths. Set {@code turboism.test.cubismEditorJar} (and optionally
 * {@code turboism.test.cubismCoreJar} and {@code turboism.test.verificationDir})
 * to exercise the resolver against a physical {@code Live2D_Cubism.jar}.
 * These tests are skipped when the properties are absent; real-host launch
 * validation still goes through the repository host-validation queue.
 */
class CubismHostCompatibilityRealSampleTest {

    private static Optional<Path> property(final String name) {
        return Optional.ofNullable(System.getProperty(name))
            .map(Path::of)
            .filter(Files::isRegularFile);
    }

    private static Function<String, byte[]> recordSource() {
        final Path dir = Optional.ofNullable(System.getProperty("turboism.test.verificationDir"))
            .map(Path::of)
            .orElse(Path.of(System.getProperty("user.dir"))
                .resolve("../compatibility/cubism/verification").normalize());
        return name -> {
            try {
                final Path record = dir.resolve(name);
                return Files.isRegularFile(record) ? Files.readAllBytes(record) : null;
            } catch (java.io.IOException failure) {
                return null;
            }
        };
    }

    @Test
    void realEditorJarDeclaresCoherentIdentity() {
        final Path editorJar = property("turboism.test.cubismEditorJar").orElse(null);
        Assumptions.assumeTrue(editorJar != null,
            "no turboism.test.cubismEditorJar evidence supplied");

        final HostIdentityProbe probe = CubismEditorReleaseDetector.probe(editorJar);

        assertTrue(probe.declared(), () -> "probe rejected: " + probe.status());
        final CubismHostIdentity identity = probe.identity().orElseThrow();
        assertTrue(identity.isCubismEditor());
        assertTrue(identity.version().matches("\\d+\\.\\d+\\.\\d+"));
        assertTrue(identity.build() >= 100_000_000);
    }

    @Test
    void realReviewedJarResolvesVerifiedWithAllSlicesAdmitted() {
        final Path editorJar = property("turboism.test.cubismEditorJar").orElse(null);
        final Path coreJar = property("turboism.test.cubismCoreJar").orElse(null);
        Assumptions.assumeTrue(editorJar != null,
            "no turboism.test.cubismEditorJar evidence supplied");

        final CompatibilityResolution resolution = CubismHostCompatibilityResolver.resolve(
            editorJar, coreJar, recordSource());
        final CubismHostIdentity identity = resolution.identity().orElse(null);
        Assumptions.assumeTrue(
            identity != null
                && ReviewedHostArtifacts.cubismVersionOf(identity.artifact()).isPresent(),
            "supplied artifact is not a reviewed pinned artifact");

        assertEquals(CompatibilityResolution.Mode.VERIFIED, resolution.mode());
        assertTrue(resolution.runtimeAdmitted());
        assertEquals(identity.version(), resolution.declaredVersion());
        // Exact hosts admit every slice the reviewed version carries a record
        // for; the optional core slice needs the core artifact to be supplied.
        for (final Map.Entry<String, CompatibilityResolution.SliceResolution> entry :
            resolution.slices().entrySet()) {
            final CompatibilityResolution.SliceResolution slice = entry.getValue();
            if ("core-model-read".equals(entry.getKey()) && coreJar == null) {
                continue;
            }
            assertEquals(
                CompatibilityResolution.SliceStatus.ADMITTED,
                slice.status(),
                () -> entry.getKey() + ": " + slice.detail()
            );
            assertFalse(slice.contract().orElseThrow().compatible(),
                "exact admission must not be marked compatible");
        }
    }

    @Test
    void realJarProbeNeverRelabelsDeclaredVersion() {
        final Path editorJar = property("turboism.test.cubismEditorJar").orElse(null);
        Assumptions.assumeTrue(editorJar != null,
            "no turboism.test.cubismEditorJar evidence supplied");

        final CubismHostIdentity identity = CubismEditorReleaseDetector
            .probe(editorJar).identity().orElseThrow();
        final Optional<CubismEditorReleaseDeclaration> declaration =
            CubismEditorReleaseDetector.detect(editorJar);

        assertTrue(declaration.isPresent());
        assertEquals(identity.version(), declaration.orElseThrow().version());
        assertEquals(identity.build(), declaration.orElseThrow().build());
    }

    @Test
    void reviewedArtifactCannotStartWithoutItsBaseRecords() {
        final Path editorJar = reviewedEditorJar();

        final CompatibilityResolution resolution = CubismHostCompatibilityResolver.resolve(
            editorJar, null, name -> null);

        assertEquals(CompatibilityResolution.Mode.VERIFIED, resolution.mode());
        assertFalse(resolution.runtimeAdmitted(), "reviewed identity does not supply missing mappings");
        assertFalse(resolution.admitted());
        assertTrue(resolution.admittedCapabilityIds().isEmpty());
    }

    @Test
    void reviewedArtifactRejectsRecordWhoseBytesDifferFromCatalogPin() {
        final Path editorJar = reviewedEditorJar();
        final Function<String, byte[]> source = recordSource();

        final CompatibilityResolution resolution = CubismHostCompatibilityResolver.resolve(
            editorJar, null, name -> {
                final byte[] bytes = source.apply(name);
                if (bytes == null || !name.endsWith("-editor-model.json")) {
                    return bytes;
                }
                // Still valid JSON with identical fields, but no longer the reviewed record.
                final byte[] changed = java.util.Arrays.copyOf(bytes, bytes.length + 1);
                changed[bytes.length] = '\n';
                return changed;
            });

        assertFalse(resolution.slice("editor-model").admitted());
        assertFalse(resolution.runtimeAdmitted());
        assertTrue(resolution.slice("project-workspace").admitted(),
            "a bad record must not invalidate unrelated verified records");
    }

    @Test
    void missingOptionalRecordDoesNotBlockReviewedBaseRuntime() {
        final Path editorJar = reviewedEditorJar();
        final Function<String, byte[]> source = recordSource();

        final CompatibilityResolution resolution = CubismHostCompatibilityResolver.resolve(
            editorJar, null, name -> name.endsWith("-ui-top-menu.json") ? null : source.apply(name));

        assertTrue(resolution.runtimeAdmitted());
        assertFalse(resolution.slice("ui-top-menu").admitted());
        assertTrue(resolution.slice("editor-model").admitted());
    }

    @Test
    void reviewedEditorStillProbesARepackedCoreArtifact(
        @org.junit.jupiter.api.io.TempDir final Path temporary
    ) throws Exception {
        final Path editorJar = reviewedEditorJar();
        final Path coreJar = property("turboism.test.cubismCoreJar").orElse(null);
        Assumptions.assumeTrue(coreJar != null, "no Core evidence supplied");
        final Path repacked = temporary.resolve("repacked-core.jar");
        try (var input = new java.util.jar.JarFile(coreJar.toFile());
             var output = new java.util.jar.JarOutputStream(Files.newOutputStream(repacked))) {
            final var entries = input.entries();
            while (entries.hasMoreElements()) {
                final var entry = entries.nextElement();
                output.putNextEntry(new java.util.jar.JarEntry(entry.getName()));
                if (!entry.isDirectory()) {
                    try (var content = input.getInputStream(entry)) {
                        content.transferTo(output);
                    }
                }
                output.closeEntry();
            }
            output.setComment("repack without changing any Core class bytes");
        }

        final CompatibilityResolution resolution = CubismHostCompatibilityResolver.resolve(
            editorJar, repacked, recordSource());

        assertTrue(resolution.identity().isPresent());
        assertTrue(resolution.runtimeAdmitted());
        assertTrue(resolution.slice("editor-model").admitted());
        assertTrue(resolution.slice("core-model-read").admitted(), resolution.slice("core-model-read").detail());
        final SliceContract contract = resolution.contractFor("core-model-read").orElseThrow();
        assertTrue(contract.compatible(), "the repacked Core is not an exact reviewed artifact");
        assertEquals(HostArtifactDigest.from(repacked), contract.probedArtifact());
        assertNotEquals(HostArtifactDigest.from(coreJar), contract.probedArtifact());
    }

    private static Path reviewedEditorJar() {
        final Path editorJar = property("turboism.test.cubismEditorJar").orElse(null);
        Assumptions.assumeTrue(editorJar != null,
            "no turboism.test.cubismEditorJar evidence supplied");
        final CubismHostIdentity identity = CubismEditorReleaseDetector
            .probe(editorJar).identity().orElseThrow();
        Assumptions.assumeTrue(ReviewedHostArtifacts.cubismVersionOf(identity.artifact()).isPresent(),
            "supplied artifact is not a reviewed pinned artifact");
        return editorJar;
    }

    /**
     * Unknown-version fallback on a real class surface: copies the supplied
     * reviewed jar and rewrites only the host-declared version constant inside
     * the declaration class. The artifact digest stops matching any reviewed
     * pin, so resolution must take the structural-compatibility path while
     * the untouched class set still verifies the reviewed contracts.
     */
    @Test
    void unreviewedDeclaredVersionOnRealClassesResolvesByStructure(
        @org.junit.jupiter.api.io.TempDir Path tempDir
    ) throws Exception {
        final Path editorJar = property("turboism.test.cubismEditorJar").orElse(null);
        final Path coreJar = property("turboism.test.cubismCoreJar").orElse(null);
        Assumptions.assumeTrue(editorJar != null,
            "no turboism.test.cubismEditorJar evidence supplied");

        final String declaredVersion = CubismEditorReleaseDetector
            .probe(editorJar).identity().orElseThrow().version();
        final String unknown = declaredVersion.substring(
            0, declaredVersion.lastIndexOf('.') + 1) + "99";
        final Path patched = rewriteDeclaredVersion(editorJar, tempDir, unknown);

        final HostIdentityProbe probe = CubismEditorReleaseDetector.probe(patched);
        assertTrue(probe.declared(), () -> "patched probe rejected: " + probe.status());
        assertEquals(unknown, probe.identity().orElseThrow().version());
        assertTrue(ReviewedHostArtifacts.cubismVersionOf(
            probe.identity().orElseThrow().artifact()).isEmpty(),
            "patched artifact must not be byte-identical to a reviewed artifact");

        final CompatibilityResolution resolution = CubismHostCompatibilityResolver.resolve(
            patched, coreJar, recordSource());

        assertEquals(CompatibilityResolution.Mode.COMPATIBLE, resolution.mode());
        assertEquals(unknown, resolution.declaredVersion());
        final java.util.Map<String, CompatibilityResolution.SliceStatus> statuses =
            new java.util.LinkedHashMap<>();
        for (final var entry : resolution.slices().entrySet()) {
            statuses.put(entry.getKey(), entry.getValue().status());
        }
        // Real-class structural admission: the base runtime capability must
        // admit, and every admitted slice must carry a compatible contract
        // whose source version is a reviewed generation — never the declared
        // unknown version relabeled as reviewed.
        assertTrue(resolution.runtimeAdmitted(), () -> statuses.toString());
        assertTrue(resolution.admittedCapabilityIds().contains("cubism.editor-model.texture.read"));
        if (!resolution.admittedCapabilityIds().contains("cubism.editor-model.texture.write")) {
            assertEquals("ambiguous:candidate-bindings", resolution.contractFor("editor-model").orElseThrow()
                .droppedCapabilities().get("cubism.editor-model.texture.write"),
                "partial admission can expose both the 5.2 and 5.3 raw-image removal routes; neither may win by proximity");
        }
        // With one reviewed Editor-model contract supplied, its complete Undo/refresh
        // dependency set is sufficient even though the declared release is unknown.
        final var oneEditorContract = CubismHostCompatibilityResolver.resolve(patched, coreJar,
            name -> name.endsWith("-editor-model.json")
                && !name.equals("cubism-" + declaredVersion + "-editor-model.json")
                ? null : recordSource().apply(name));
        assertTrue(oneEditorContract.admittedCapabilityIds().contains("cubism.editor-model.texture.write"),
            "an unambiguous, complete texture write contract remains eligible on an unknown release");
        assertTrue(resolution.admittedCapabilityIds().containsAll(java.util.Set.of(
            "cubism.autobackup.settings", "cubism.autobackup.backup")),
            "native auto-backup operations have a complete selector contract and require no bytecode hook");
        for (final var entry : resolution.slices().entrySet()) {
            final var contract = entry.getValue().contract();
            if (contract.isPresent()) {
                assertTrue(contract.orElseThrow().compatible());
                assertEquals(unknown, contract.orElseThrow().declaredVersion());
                assertNotEquals(unknown, contract.orElseThrow().sourceVersion(),
                    "an unknown declared version must never be relabeled as the source");
                assertTrue(
                    ReviewedHostArtifacts.reviewedCubismVersions()
                        .contains(contract.orElseThrow().sourceVersion()),
                    "source version must be a reviewed generation label, was "
                        + contract.orElseThrow().sourceVersion()
                );
            }
        }
    }

    private static Path rewriteDeclaredVersion(
        final Path source,
        final Path dir,
        final String replacement
    ) throws Exception {
        return rewriteDeclaredVersion(source, dir, replacement, null);
    }

    @Test
    void realEditEntryMethodsProduceBytecodePatchesUsingTheHostLoader() throws Exception {
        final Path editor = reviewedEditorJar();
        final java.net.URL[] urls;
        try (var jars = Files.list(editor.getParent())) {
            urls = jars.filter(path -> path.getFileName().toString().endsWith(".jar"))
                .map(path -> {
                    try { return path.toUri().toURL(); }
                    catch (java.net.MalformedURLException failure) { throw new IllegalStateException(failure); }
                }).toArray(java.net.URL[]::new);
        }
        try (var loader = new java.net.URLClassLoader(urls, getClass().getClassLoader());
             var jar = new java.util.jar.JarFile(editor.toFile())) {
            for (final String owner : java.util.List.of(
                "com/live2d/cubism/doc/ACEditMode", "com/live2d/cubism/doc/modeling/CModelingEditMode_Main")) {
                final byte[] bytes;
                try (var input = jar.getInputStream(jar.getJarEntry(owner + ".class"))) {
                    bytes = input.readAllBytes();
                }
                final var transformer = new dev.turboism.adapter.cubism.editor.history.NativeEditBeginTransformer(
                    owner, "beginEdit", "(Ljava/lang/String;)Lcom/live2d/undo/GroupUndo;", loader, "test.native-edit");
                final byte[] patched = transformer.transform(null, loader, owner, null, null, bytes);
                org.junit.jupiter.api.Assertions.assertNotNull(patched, owner);
                org.junit.jupiter.api.Assertions.assertNull(transformer.transform(null, loader, owner, null, null, patched));
            }
            final String owner = "com/live2d/cubism/doc/webSocket/l";
            final byte[] bytes;
            try (var input = jar.getInputStream(jar.getJarEntry(owner + ".class"))) {
                bytes = input.readAllBytes();
            }
            final var transformer = new dev.turboism.adapter.cubism.integration.EditApiDispatchTransformer(
                owner, "a", "(Ljava/lang/String;Lorg/java_websocket/WebSocket;)V", loader, "test.edit-dispatch");
            org.junit.jupiter.api.Assertions.assertNotNull(
                transformer.transform(null, loader, owner, null, null, bytes), transformer.diagnostic());
        }
    }

    @Test
    void missingOptionalWriteMemberDoesNotPreventBaseRuntimeOrTextureReads(
        @org.junit.jupiter.api.io.TempDir final Path temporary
    ) throws Exception {
        final Path editor = reviewedEditorJar();
        final String version = CubismEditorReleaseDetector.probe(editor).identity().orElseThrow().version();
        final var record = new StaticVerificationRecordLoader().load(
            recordSource().apply("cubism-" + version + "-editor-model.json"), "reviewed").record();
        final var removed = record.selectors().stream()
            .filter(selector -> selector.alias().equals("cubism.editor-model.texture-handler.add-texture-atlas"))
            .findFirst().orElseThrow();
        final Path changed = rewriteDeclaredVersion(editor, temporary, version, removed);

        final var resolution = CubismHostCompatibilityResolver.resolve(changed, null, recordSource());

        assertTrue(resolution.runtimeAdmitted(), resolution.slice("editor-model").detail());
        final var contract = resolution.contractFor("editor-model").orElseThrow();
        assertTrue(contract.capabilities().contains("cubism.editor-model.texture.read"));
        assertFalse(contract.capabilities().contains("cubism.editor-model.texture.write"));
        assertEquals("selector:" + removed.alias(),
            contract.droppedCapabilities().get("cubism.editor-model.texture.write"));
    }

    private static Path rewriteDeclaredVersion(
        final Path source,
        final Path dir,
        final String replacement,
        final StaticSelector removed
    ) throws Exception {
        final String declaredClass = "com/live2d/cubism/h.class";
        final String probeVersion = CubismEditorReleaseDetector
            .probe(source).identity().orElseThrow().version();
        final Path target = dir.resolve("declared-" + replacement + ".jar");
        try (var jar = new java.util.jar.JarFile(source.toFile());
             var output = new java.util.jar.JarOutputStream(
                 java.nio.file.Files.newOutputStream(target))) {
            final var entries = jar.entries();
            while (entries.hasMoreElements()) {
                final var entry = entries.nextElement();
                final String name = entry.getName();
                // Strip signature metadata: the rewritten declaration bytes no
                // longer match the signed digests, and JarFile verifies signed
                // entries on read, which would fail before the probe sees them.
                if (name.startsWith("META-INF/")
                    && (name.endsWith(".SF") || name.endsWith(".RSA")
                        || name.endsWith(".DSA") || name.endsWith(".EC")
                        || name.contains("SIG-")
                        || name.equals("META-INF/MANIFEST.MF"))) {
                    continue;
                }
                output.putNextEntry(new java.util.jar.JarEntry(name));
                if (entry.isDirectory()) {
                    output.closeEntry();
                    continue;
                }
                byte[] bytes;
                try (var input = jar.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                if (declaredClass.equals(entry.getName())) {
                    bytes = replaceUtf8Constant(bytes, probeVersion, replacement);
                }
                if (removed != null && name.equals(removed.ownerInternalName() + ".class")) {
                    final var reader = new org.objectweb.asm.ClassReader(bytes);
                    final var writer = new org.objectweb.asm.ClassWriter(reader, 0);
                    reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
                        @Override public org.objectweb.asm.MethodVisitor visitMethod(
                            final int access, final String methodName, final String descriptor,
                            final String signature, final String[] exceptions
                        ) {
                            if (methodName.equals(removed.memberName()) && descriptor.equals(removed.descriptor())) {
                                return null;
                            }
                            return super.visitMethod(access, methodName, descriptor, signature, exceptions);
                        }
                    }, 0);
                    bytes = writer.toByteArray();
                }
                output.write(bytes);
                output.closeEntry();
            }
        }
        return target;
    }

    /** Replaces the first same-length UTF-8 constant-pool occurrence of {@code from}. */
    private static byte[] replaceUtf8Constant(
        final byte[] classBytes,
        final String from,
        final String to
    ) {
        final byte[] needle = from.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] replacement = to.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertEquals(
            needle.length, replacement.length, "same-length rewrite required");
        outer:
        for (int i = 0; i + needle.length + 2 < classBytes.length; i++) {
            // CONSTANT_Utf8 payload carries a two-byte length prefix.
            final int length = ((classBytes[i] & 0xFF) << 8) | (classBytes[i + 1] & 0xFF);
            if (length != needle.length) {
                continue;
            }
            for (int j = 0; j < needle.length; j++) {
                if (classBytes[i + 2 + j] != needle[j]) {
                    continue outer;
                }
            }
            final byte[] patched = classBytes.clone();
            System.arraycopy(replacement, 0, patched, i + 2, replacement.length);
            return patched;
        }
        throw new AssertionError("declared version constant not found in " + from);
    }
}
