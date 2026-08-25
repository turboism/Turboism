package dev.turboism.mapping.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.core.schema.diagnostic.DiagnosticReportValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.PrintStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MappingReviewPipelineTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PACK = "cubism-ref/mapping-packs/draft/fixture.json";
    private static final String SEMANTIC = "fixture.target.class";
    private static final String OLD_RUNTIME = "fixture/OldTarget";
    private static final String CALLER = "fixture/Anchor";
    private static final String CALLER_NAME = "anchor";
    private static final String CALLER_DESCRIPTOR = "()V";
    private static final String TARGET_NAME = "selected";
    private static final String TARGET_DESCRIPTOR = "()V";

    @TempDir
    Path temp;

    @Test
    void cliPrintsHelpAndRejectsIncompleteGenerateArguments() {
        ByteArrayOutputStream helpOutput = new ByteArrayOutputStream();
        int helpExit = MappingReviewCli.run(new String[]{"--help"}, new PrintStream(helpOutput), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(0, helpExit);
        assertTrue(helpOutput.toString(StandardCharsets.UTF_8).contains("MappingReviewCli generate"));

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int invalidExit = MappingReviewCli.run(new String[]{"generate"}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        assertEquals(2, invalidExit);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("missing required --artifact"));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("usage:"));
    }

    @Test
    void cliRejectsUnknownMissingAndDuplicateOptionsWithoutAccessingPathsForHelp() {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exit = MappingReviewCli.run(
            new String[]{"generate", "--unknown", "value"},
            new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(errors)
        );
        assertEquals(2, exit);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("unknown option for generate: --unknown"));

        errors.reset();
        exit = MappingReviewCli.run(new String[]{"apply", "--candidate"},
            new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        assertEquals(2, exit);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("missing value for --candidate"));

        errors.reset();
        exit = MappingReviewCli.run(new String[]{"apply", "--artifact", "one.jar", "--artifact", "two.jar"},
            new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        assertEquals(2, exit);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("duplicate option: --artifact"));

        Path inaccessible = temp.resolve("does-not-exist");
        ByteArrayOutputStream help = new ByteArrayOutputStream();
        exit = MappingReviewCli.run(new String[]{"generate", "--root", inaccessible.toString(), "--help"},
            new PrintStream(help), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(0, exit);
        assertTrue(help.toString(StandardCharsets.UTF_8).contains("usage:"));
        assertFalse(Files.exists(inaccessible));

        errors.reset();
        exit = MappingReviewCli.run(new String[]{"unknown"},
            new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        assertEquals(2, exit);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("unknown command: unknown"));
    }

    @Test
    void cliRequiresValidatedWorktreeIdForDefaultGenerateOutput() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        String previous = System.getProperty("turboism.worktree.id");
        System.clearProperty("turboism.worktree.id");
        try {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int exit = MappingReviewCli.run(generateCliArgs(workspace, jar, null),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
            assertEquals(1, exit);
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("WORKTREE_ID_MISSING"));
        } finally {
            if (previous == null) System.clearProperty("turboism.worktree.id");
            else System.setProperty("turboism.worktree.id", previous);
        }
    }

    @Test
    void directCliDefaultsToWorktreeIsolatedOutput() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        String previous = System.getProperty("turboism.worktree.id");
        System.setProperty("turboism.worktree.id", "m15-cli-test");
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int exit = MappingReviewCli.run(generateCliArgs(workspace, jar, null),
                new PrintStream(output), new PrintStream(new ByteArrayOutputStream()));
            assertEquals(0, exit);
            Path expected = workspace.root().resolve("build/worktree/m15-cli-test/mapping-review");
            for (String suffix : List.of("candidate", "review", "diff", "diagnostic")) {
                Path generated = expected.resolve("fixture.target.class." + suffix + ".json");
                assertTrue(Files.isRegularFile(generated), generated.toString());
                String text = Files.readString(generated);
                assertFalse(text.contains(workspace.root().toAbsolutePath().toString()));
                assertFalse(text.contains(jar.toAbsolutePath().toString()));
                assertFalse(generated.getFileName().toString().toLowerCase().contains("latest"));
            }
            String stdout = output.toString(StandardCharsets.UTF_8);
            assertTrue(stdout.contains("diagnostic=build/worktree/m15-cli-test/mapping-review/fixture.target.class.diagnostic.json"));
            assertFalse(stdout.contains(workspace.root().toAbsolutePath().toString()));
            assertFalse(stdout.contains(jar.toAbsolutePath().toString()));
        } finally {
            if (previous == null) System.clearProperty("turboism.worktree.id");
            else System.setProperty("turboism.worktree.id", previous);
        }
    }

    @Test
    void generateFindsOneExactEdgeWithoutExecutingStaticInitializerAndIsByteIdentical() throws Exception {
        TestWorkspace workspace = workspace();
        Path marker = temp.resolve("must-not-exist.marker");
        Path jar = jar(Map.of(
            "fixture/Anchor.class", callerClass(List.of("fixture/NewTarget"), marker.toString()),
            "fixture/NewTarget.class", plainClass("fixture/NewTarget")
        ));

        GeneratedBundle first = workspace.service().generate(request(jar, workspace.root().resolve("build/first"), "m15-test"));
        GeneratedBundle second = workspace.service().generate(request(jar, workspace.root().resolve("build/second"), "m15-test"));

        assertArrayEquals(first.candidateBytes(), second.candidateBytes());
        assertArrayEquals(first.reviewBytes(), second.reviewBytes());
        assertArrayEquals(first.diffBytes(), second.diffBytes());
        assertArrayEquals(first.diagnosticBytes(), second.diagnosticBytes());
        assertFalse(Files.exists(marker), "class initialization must never execute");
        JsonNode candidate = JSON.readTree(first.candidateBytes());
        assertEquals("fixture/NewTarget", candidate.at("/after/runtime").asText());
        assertEquals(OLD_RUNTIME, candidate.at("/before/runtime").asText());
        assertEquals(CALLER, candidate.at("/evidence/caller/owner").asText());
        assertEquals(TARGET_DESCRIPTOR, candidate.at("/evidence/selectedTarget/descriptor").asText());
        assertEquals("PENDING", JSON.readTree(first.reviewBytes()).get("decision").asText());
        assertTrue(first.diffPath().getFileName().toString().endsWith(".diff.json"));
        JsonNode diff = JSON.readTree(first.diffBytes());
        assertEquals("turboism.mapping.update.diff", diff.path("format").asText());
        assertEquals(OLD_RUNTIME, diff.at("/changes/0/before").asText());
        assertEquals("fixture/NewTarget", diff.at("/changes/0/after").asText());
        assertEquals(sha256(first.candidateBytes()), diff.path("candidateSha256").asText());
        assertTrue(new MappingUpdateDiffValidator().validate(diff).isEmpty());
        JsonNode diagnostic = JSON.readTree(first.diagnosticBytes());
        assertEquals("turboism.diagnostic.report", diagnostic.path("format").asText());
        assertEquals("m15-test", diagnostic.path("worktreeId").asText());
        assertEquals("2026-07-10T12:34:56Z", diagnostic.path("createdAt").asText());
        assertTrue(new DiagnosticReportValidator().validate(diagnostic).isEmpty());
        String diagnosticText = new String(first.diagnosticBytes(), StandardCharsets.UTF_8);
        assertFalse(diagnosticText.contains(workspace.root().toAbsolutePath().toString()));
        assertFalse(diagnosticText.contains(jar.toAbsolutePath().toString()));
    }

    @Test
    void generatePublishesBundleWithoutOverwritingAndCleansFailedPublication() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        Path output = workspace.root().resolve("build/review-output");
        Files.createDirectories(output);
        Path existingDiff = output.resolve("fixture.target.class.diff.json");
        Files.writeString(existingDiff, "approved-review-must-survive");

        GenerateRequest request = request(jar, output, "m15-test");
        assertCode("GENERATED_FILE_EXISTS", () -> workspace.service().generate(request));
        assertEquals("approved-review-must-survive", Files.readString(existingDiff));
        assertFalse(Files.exists(output.resolve("fixture.target.class.candidate.json")));
        assertFalse(Files.exists(output.resolve("fixture.target.class.review.json")));
        assertFalse(Files.exists(output.resolve("fixture.target.class.diagnostic.json")));
        try (var paths = Files.list(output.getParent())) {
            assertEquals(0, paths.filter(path -> path.getFileName().toString().startsWith(".mapping-review-bundle-")).count());
        }
    }

    @Test
    void copyPrimitiveRegistersCurrentPartialTargetAndNeverDeletesCreateNewCompetitor() throws Exception {
        Path source = Files.writeString(temp.resolve("source.txt"), "source");
        Path target = temp.resolve("target.txt");
        List<FileSafety.PublicationOwnership> owned = new ArrayList<>();

        Path unreadableSource = temp.resolve("source-directory");
        Files.createDirectory(unreadableSource);
        assertCode("COPY_FAILED", () -> FileSafety.copyCreateNewNoFollow(unreadableSource, target, owned, "COPY_FAILED"));
        assertEquals(List.of(target), owned.stream().map(FileSafety.PublicationOwnership::path).toList(), "the current partial target must be registered immediately after CREATE_NEW");
        assertTrue(Files.isRegularFile(target));
        Files.delete(target);

        Files.writeString(target, "competitor");
        owned.clear();
        assertCode("COPY_FAILED", () -> FileSafety.copyCreateNewNoFollow(source, target, owned, "COPY_FAILED"));
        assertTrue(owned.isEmpty(), "a FileAlreadyExists competitor was never acquired by this operation");
        assertEquals("competitor", Files.readString(target));
    }

    @Test
    void cleanupRetainsCompetitorThatReplacesOwnedPublicationAndDiagnosesIdentityChange() throws Exception {
        Path source = Files.writeString(temp.resolve("cleanup-source.txt"), "owned");
        Path target = temp.resolve("cleanup-target.txt");
        List<FileSafety.PublicationOwnership> owned = new ArrayList<>();

        FileSafety.copyCreateNewNoFollow(source, target, owned, "COPY_FAILED");
        assertEquals(1, owned.size(), "CREATE_NEW publication must be operation-owned");
        final Object ownedFileKey = owned.get(0).fileKey();
        Files.delete(target);
        Files.writeString(target, "competitor", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        DraftMappingException failure = new DraftMappingException("PUBLICATION_FAILED", "trigger cleanup");
        MappingReviewService.cleanupPublished(owned, failure);

        assertEquals("competitor", Files.readString(target), "cleanup must retain the replacement competitor");
        assertEquals(1, failure.getSuppressed().length);
        final String diagnostic = failure.getSuppressed()[0].getMessage();
        if (ownedFileKey != null && !ownedFileKey.equals(
            Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())) {
            assertTrue(
                diagnostic.contains("pathname ownership changed"),
                "cleanup must attach a suppressed filesystem-identity diagnostic");
        } else {
            assertTrue(
                diagnostic.contains("pathname contents changed"),
                "cleanup must attach a suppressed filesystem-content diagnostic when identity is reused");
        }
        assertTrue(diagnostic.contains(target.getFileName().toString()));
    }

    @Test
    void generateRejectsOutputSymlinkEscape() throws Exception {
        TestWorkspace workspace = workspace();
        Path outside = temp.resolve("outside-output");
        Files.createDirectories(outside);
        Path link = workspace.root().resolve("linked-output");
        try {
            Files.createSymbolicLink(link, outside);
            assertCode("OUTPUT_PATH_INVALID", () -> workspace.service().generate(request(happyJar(), link, "m15-test")));
        } catch (UnsupportedOperationException exception) {
            // Filesystem does not support symlinks.
        }
    }

    @Test
    void generateIsIndependentOfArchiveEntryOrder() throws Exception {
        TestWorkspace workspace = workspace();
        byte[] caller = callerClass(List.of("fixture/NewTarget"), "unused");
        byte[] target = plainClass("fixture/NewTarget");
        LinkedHashMap<String, byte[]> forwardEntries = new LinkedHashMap<>();
        forwardEntries.put("fixture/Anchor.class", caller);
        forwardEntries.put("fixture/NewTarget.class", target);
        LinkedHashMap<String, byte[]> reverseEntries = new LinkedHashMap<>();
        reverseEntries.put("fixture/NewTarget.class", target);
        reverseEntries.put("fixture/Anchor.class", caller);

        GeneratedBundle forward = workspace.service().generate(request(
            jar(forwardEntries), workspace.root().resolve("build/forward"), "m15-test"));
        GeneratedBundle reverse = workspace.service().generate(request(
            jar(reverseEntries), workspace.root().resolve("build/reverse"), "m15-test"));

        JsonNode forwardCandidate = JSON.readTree(forward.candidateBytes());
        JsonNode reverseCandidate = JSON.readTree(reverse.candidateBytes());
        assertEquals(forwardCandidate.path("after"), reverseCandidate.path("after"));
        assertEquals(forwardCandidate.path("evidence"), reverseCandidate.path("evidence"));
        assertEquals(forwardCandidate.path("resultPackSha256"), reverseCandidate.path("resultPackSha256"));
        assertNotEquals(forwardCandidate.at("/artifact/sha256"), reverseCandidate.at("/artifact/sha256"),
            "candidate must still bind the exact archive bytes");
    }

    @Test
    void generateFailsClosedForMissingCallerOwnerAndMethod() throws Exception {
        TestWorkspace workspace = workspace();
        Path missingOwner = jar(Map.of(
            "fixture/Other.class", plainClass("fixture/Other"),
            "fixture/NewTarget.class", plainClass("fixture/NewTarget")
        ));
        assertCode("SCAN_CALLER_OWNER_MISSING", () -> workspace.service().generate(request(missingOwner)));

        Path missingMethod = jar(Map.of(
            "fixture/Anchor.class", classWithoutSelectedMethod(CALLER),
            "fixture/NewTarget.class", plainClass("fixture/NewTarget")
        ));
        assertCode("SCAN_CALLER_METHOD_MISSING", () -> workspace.service().generate(request(missingMethod)));
    }

    @Test
    void generateFailsClosedForZeroAndMultipleMatches() throws Exception {
        TestWorkspace workspace = workspace();
        Path zero = jar(Map.of("fixture/Anchor.class", callerClass(List.of(), "unused")));
        DraftMappingException noMatch = assertThrows(DraftMappingException.class,
            () -> workspace.service().generate(request(zero)));
        assertEquals("SCAN_EDGE_NOT_UNIQUE", noMatch.code());

        Path multiple = temp.resolve("multiple.jar");
        writeJar(multiple, Map.of(
            "fixture/Anchor.class", callerClass(List.of("fixture/A", "fixture/B"), "unused"),
            "fixture/A.class", plainClass("fixture/A"),
            "fixture/B.class", plainClass("fixture/B")
        ));
        DraftMappingException many = assertThrows(DraftMappingException.class,
            () -> workspace.service().generate(request(multiple)));
        assertEquals("SCAN_EDGE_NOT_UNIQUE", many.code());
    }

    @Test
    void candidateDoesNotLeakSecretMethodBodyDataOrAbsolutePaths() throws Exception {
        TestWorkspace workspace = workspace();
        String secret = "TOP-SECRET-METHOD-BODY-STRING";
        Path jar = jar(Map.of(
            "fixture/Anchor.class", callerClassWithSecret("fixture/NewTarget", secret),
            "fixture/NewTarget.class", plainClass("fixture/NewTarget")
        ));

        GeneratedBundle bundle = workspace.service().generate(request(jar));
        String candidate = new String(bundle.candidateBytes(), StandardCharsets.UTF_8);
        assertFalse(candidate.contains(secret));
        assertFalse(candidate.contains(jar.toAbsolutePath().toString()));
        for (String forbidden : List.of("opcode", "offset", "occurrence", "controlFlow", "branch", "tryCatch", "lineNumber", "localVariable", "frame", "methodBody", "callGraph")) {
            assertFalse(candidate.contains(forbidden), forbidden);
        }
        assertTrue(candidate.endsWith("\n"));
        assertFalse(candidate.contains("\r"));
    }

    @Test
    void generateCleansActualPrivateSnapshotAfterSuccessAndFailure() throws Exception {
        TestWorkspace workspace = workspace();
        List<Path> snapshots = new ArrayList<>();
        MappingReviewService service = new MappingReviewService(
            workspace.root(), JarScanPolicy.defaults(), AtomicMover.system(),
            (original, maxBytes) -> {
                ArtifactSnapshotter.ArtifactSnapshot snapshot = ArtifactSnapshotter.system().create(original, maxBytes);
                snapshots.add(snapshot.path());
                return snapshot;
            }
        );

        service.generate(request(happyJar()));
        assertSnapshotDeleted(snapshots.remove(0));

        Path malformed = Files.write(temp.resolve("malformed-cleanup.jar"), new byte[]{1, 2, 3});
        assertCode("JAR_MALFORMED", () -> service.generate(request(malformed)));
        assertSnapshotDeleted(snapshots.remove(0));
        assertTrue(snapshots.isEmpty());
    }

    @Test
    void generateSnapshotsArtifactAndRejectsReplacementBeforeScanning() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        Path replacement = jar(Map.of(
            "fixture/Anchor.class", callerClass(List.of("fixture/OtherTarget"), "unused"),
            "fixture/OtherTarget.class", plainClass("fixture/OtherTarget")
        ));
        MappingReviewService service = new MappingReviewService(
            workspace.root(), JarScanPolicy.defaults(), AtomicMover.system(),
            (original, maxBytes) -> {
                ArtifactSnapshotter.ArtifactSnapshot snapshot = ArtifactSnapshotter.system().create(original, maxBytes);
                Files.copy(replacement, original, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return snapshot;
            }
        );

        assertCode("ARTIFACT_CHANGED_DURING_SNAPSHOT", () -> service.generate(request(jar)));
    }

    @Test
    void snapshotRecheckRejectsOversizedReplacementWithStableCode() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        JarScanPolicy policy = new JarScanPolicy(Files.size(jar) + 32, 20, 20, 100_000, 1_000_000);
        MappingReviewService service = new MappingReviewService(
            workspace.root(), policy, AtomicMover.system(),
            (original, maxBytes) -> {
                ArtifactSnapshotter.ArtifactSnapshot snapshot = ArtifactSnapshotter.system().create(original, maxBytes);
                Files.write(original, new byte[(int) policy.maxArtifactBytes() + 1]);
                return snapshot;
            }
        );

        assertCode("JAR_SIZE_LIMIT", () -> service.generate(request(jar)));
    }

    @Test
    void jarPolicyRejectsClassIdentityMismatchAndMissingTargetOwner() throws Exception {
        TestWorkspace workspace = workspace();
        assertCode("CLASS_IDENTITY_MISMATCH", () -> workspace.service().generate(request(jar(Map.of(
            "fixture/Anchor.class", callerClass(List.of("fixture/NewTarget"), "unused"),
            "fixture/WrongPath.class", plainClass("fixture/NewTarget")
        )))));
        assertCode("SCAN_TARGET_OWNER_MISSING", () -> workspace.service().generate(request(jar(Map.of(
            "fixture/Anchor.class", callerClass(List.of("fixture/NewTarget"), "unused")
        )))));
        assertCode("SCAN_TARGET_METHOD_MISSING", () -> workspace.service().generate(request(jar(Map.of(
            "fixture/Anchor.class", callerClass(List.of("fixture/NewTarget"), "unused"),
            "fixture/NewTarget.class", classWithoutSelectedMethod("fixture/NewTarget")
        )))));
    }

    @Test
    void jarPolicyRejectsDuplicateInternalClassIdentity() throws Exception {
        TestWorkspace workspace = workspace();
        Path duplicateInternal = temp.resolve("duplicate-internal.jar");
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("fixture/Anchor.class", callerClass(List.of("fixture/NewTarget"), "unused"));
        entries.put("fixture/NewTarget.class", plainClass("fixture/NewTarget"));
        entries.put("fixture/Alias.class", plainClass("fixture/NewTarget"));
        writeJar(duplicateInternal, entries);
        assertCode("CLASS_IDENTITY_MISMATCH", () -> workspace.service().generate(request(duplicateInternal)));
    }

    @Test
    void classIdentityValidationCodeIsIndependentOfArchiveOrder() throws Exception {
        TestWorkspace workspace = workspace();
        byte[] caller = callerClass(List.of("fixture/NewTarget"), "unused");
        byte[] target = plainClass("fixture/NewTarget");
        LinkedHashMap<String, byte[]> forward = new LinkedHashMap<>();
        forward.put("fixture/Anchor.class", caller);
        forward.put("fixture/NewTarget.class", target);
        forward.put("fixture/Alias.class", target);
        LinkedHashMap<String, byte[]> reverse = new LinkedHashMap<>();
        reverse.put("fixture/Alias.class", target);
        reverse.put("fixture/NewTarget.class", target);
        reverse.put("fixture/Anchor.class", caller);

        assertCode("CLASS_IDENTITY_MISMATCH", () -> workspace.service().generate(request(jar(forward))));
        assertCode("CLASS_IDENTITY_MISMATCH", () -> workspace.service().generate(request(jar(reverse))));
    }

    @Test
    void jarPolicyRejectsMalformedHighMajorDuplicateIllegalOversizeMultiReleaseAndNestedJar() throws Exception {
        TestWorkspace workspace = workspace();
        assertCode("JAR_MALFORMED", () -> workspace.service().generate(request(Files.write(temp.resolve("bad.jar"), new byte[]{1, 2, 3}))));

        byte[] highMajor = callerClass(List.of("fixture/NewTarget"), "unused");
        highMajor[6] = 0;
        highMajor[7] = 62;
        assertCode("CLASS_UNSUPPORTED_MAJOR", () -> workspace.service().generate(request(jar(Map.of("fixture/Anchor.class", highMajor)))));

        Path duplicate = temp.resolve("duplicate.jar");
        writeRawStoredZip(duplicate, List.of(
            new RawEntry("fixture/Anchor.class", callerClass(List.of("fixture/NewTarget"), "unused")),
            new RawEntry("fixture/Anchor.class", callerClass(List.of("fixture/NewTarget"), "unused"))
        ));
        assertCode("JAR_DUPLICATE_ENTRY", () -> workspace.service().generate(request(duplicate)));

        for (String illegalName : List.of(
            "../escape.class", "/absolute.class", "\\absolute.class", "fixture\\Backslash.class", "C:/absolute.class"
        )) {
            assertCode("JAR_ILLEGAL_ENTRY_NAME", () -> workspace.service().generate(request(
                jar(Map.of(illegalName, plainClass("fixture/Escape"))))));
        }

        JarScanPolicy tiny = new JarScanPolicy(128, 20, 20, 1024, 4096);
        MappingReviewService tinyService = new MappingReviewService(workspace.root(), tiny, AtomicMover.system());
        assertCode("JAR_SIZE_LIMIT", () -> tinyService.generate(request(jar(Map.of("fixture/Anchor.class", callerClass(List.of("fixture/NewTarget"), "unused"))))));

        assertCode("JAR_MULTI_RELEASE_UNSUPPORTED", () -> workspace.service().generate(request(jar(Map.of(
            "META-INF/versions/17/fixture/Anchor.class", callerClass(List.of("fixture/NewTarget"), "unused"))))));
        Path manifestMr = temp.resolve("manifest-mr.jar");
        writeJarWithManifest(manifestMr, Map.of(
            "fixture/Anchor.class", callerClass(List.of("fixture/NewTarget"), "unused"),
            "fixture/NewTarget.class", plainClass("fixture/NewTarget")
        ), true);
        assertCode("JAR_MULTI_RELEASE_UNSUPPORTED", () -> workspace.service().generate(request(manifestMr)));

        Path oversizedManifest = temp.resolve("oversized-manifest.jar");
        writeJar(oversizedManifest, Map.of(
            "META-INF/MANIFEST.MF", ("Manifest-Version: 1.0\nPadding: " + "x".repeat(512) + "\n\n").getBytes(StandardCharsets.UTF_8),
            "fixture/Anchor.class", callerClass(List.of("fixture/NewTarget"), "unused"),
            "fixture/NewTarget.class", plainClass("fixture/NewTarget")
        ));
        JarScanPolicy manifestEntryLimit = new JarScanPolicy(1_000_000, 20, 20, 128, 1_000_000);
        assertCode("JAR_ENTRY_SIZE_LIMIT", () -> new MappingReviewService(
            workspace.root(), manifestEntryLimit, AtomicMover.system()).generate(request(oversizedManifest)));

        long manifestSize = "Manifest-Version: 1.0\n\n".getBytes(StandardCharsets.UTF_8).length;
        Path expandedManifest = temp.resolve("expanded-manifest.jar");
        writeJar(expandedManifest, Map.of(
            "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n\n".getBytes(StandardCharsets.UTF_8),
            "fixture/Anchor.class", callerClass(List.of("fixture/NewTarget"), "unused"),
            "fixture/NewTarget.class", plainClass("fixture/NewTarget")
        ));
        JarScanPolicy manifestExpandedLimit = new JarScanPolicy(1_000_000, 20, 20, 100_000, manifestSize - 1);
        assertCode("JAR_EXPANDED_LIMIT", () -> new MappingReviewService(
            workspace.root(), manifestExpandedLimit, AtomicMover.system()).generate(request(expandedManifest)));

        assertCode("JAR_NESTED_UNSUPPORTED", () -> workspace.service().generate(request(jar(Map.of("lib/nested.jar", new byte[]{1, 2, 3})))));
    }

    @Test
    void jarPolicyEnforcesEveryArchiveResourceLimit() throws Exception {
        TestWorkspace workspace = workspace();
        Path happy = happyJar();
        JarScanPolicy entryCount = new JarScanPolicy(1_000_000, 1, 10, 100_000, 1_000_000);
        assertCode("JAR_ENTRY_LIMIT", () -> new MappingReviewService(workspace.root(), entryCount, AtomicMover.system())
            .generate(request(happy)));

        JarScanPolicy classCount = new JarScanPolicy(1_000_000, 10, 1, 100_000, 1_000_000);
        assertCode("JAR_CLASS_LIMIT", () -> new MappingReviewService(workspace.root(), classCount, AtomicMover.system())
            .generate(request(happy)));

        JarScanPolicy entrySize = new JarScanPolicy(1_000_000, 10, 10, 8, 1_000_000);
        assertCode("JAR_ENTRY_SIZE_LIMIT", () -> new MappingReviewService(workspace.root(), entrySize, AtomicMover.system())
            .generate(request(happy)));

        long firstEntrySize = callerClass(List.of("fixture/NewTarget"), "unused").length;
        JarScanPolicy expanded = new JarScanPolicy(1_000_000, 10, 10, 100_000, firstEntrySize);
        assertCode("JAR_EXPANDED_LIMIT", () -> new MappingReviewService(workspace.root(), expanded, AtomicMover.system())
            .generate(request(happy)));
    }

    @Test
    void jarPolicyRejectsNonRegularSymlinkAndTruncatedClass() throws Exception {
        TestWorkspace workspace = workspace();
        Path directory = temp.resolve("artifact-directory");
        Files.createDirectory(directory);
        assertCode("ARTIFACT_NOT_REGULAR", () -> workspace.service().generate(request(directory)));

        Path real = jar(Map.of("fixture/Anchor.class", callerClass(List.of("fixture/NewTarget"), "unused")));
        Path link = temp.resolve("link.jar");
        try {
            Files.createSymbolicLink(link, real.getFileName());
            assertCode("ARTIFACT_NOT_REGULAR", () -> workspace.service().generate(request(link)));
        } catch (UnsupportedOperationException exception) {
            // Filesystem does not support symlinks; the regular-file checks are covered elsewhere.
        }
        assertCode("CLASS_MALFORMED", () -> workspace.service().generate(request(jar(Map.of("fixture/Anchor.class", new byte[]{(byte) 0xca, (byte) 0xfe})))));
    }

    @Test
    void invokedynamicMatchingRecipeFailsClosed() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = jar(Map.of("fixture/Anchor.class", callerClassWithInvokeDynamic()));
        GenerateRequest indy = new GenerateRequest(jar, PACK, SEMANTIC, OLD_RUNTIME,
            CALLER, CALLER_NAME, CALLER_DESCRIPTOR, "dyn", "()V", InvocationConstraint.ANY);
        assertCode("SCAN_INVOKEDYNAMIC_UNSUPPORTED", () -> workspace.service().generate(indy));
    }

    @Test
    void applyRequiresApprovedExactCandidateAndArtifactHashes() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        GeneratedBundle bundle = workspace.service().generate(request(jar));

        Path pending = write(temp.resolve("pending.json"), bundle.reviewBytes());
        assertCode("REVIEW_NOT_APPROVED", () -> workspace.service().apply(new ApplyRequest(bundle.candidatePath(), pending, jar, false)));

        Path rejected = review(bundle, "REJECTED", "reviewer", Instant.parse("2026-07-10T00:00:00Z"));
        assertCode("REVIEW_NOT_APPROVED", () -> workspace.service().apply(new ApplyRequest(bundle.candidatePath(), rejected, jar, false)));

        Path wrongHash = approvedReview(bundle, "0".repeat(64));
        assertCode("REVIEW_CANDIDATE_HASH_MISMATCH", () -> workspace.service().apply(new ApplyRequest(bundle.candidatePath(), wrongHash, jar, false)));

        Path approved = approvedReview(bundle, sha256(bundle.candidateBytes()));
        Path sameBytesWrongName = temp.resolve("renamed-artifact.jar");
        Files.copy(jar, sameBytesWrongName);
        assertCode("ARTIFACT_MISMATCH", () -> workspace.service().apply(
            new ApplyRequest(bundle.candidatePath(), approved, sameBytesWrongName, false)));

        Files.write(jar, new byte[]{0}, StandardOpenOption.APPEND);
        assertCode("ARTIFACT_MISMATCH", () -> workspace.service().apply(new ApplyRequest(bundle.candidatePath(), approved, jar, false)));
    }

    @Test
    void generateAndApplyPreserveEveryByteOutsideTheEscapedRuntimeToken() throws Exception {
        TestWorkspace workspace = workspace();
        workspace.writeCustomPack();
        Path jar = happyJar();
        byte[] base = Files.readAllBytes(workspace.pack());
        byte[] oldToken = "\"fixture\\/Old\\u0054arget\"".getBytes(StandardCharsets.UTF_8);
        byte[] newToken = "\"fixture/NewTarget\"".getBytes(StandardCharsets.UTF_8);
        int tokenStart = indexOf(base, oldToken);
        assertTrue(tokenStart >= 0);
        byte[] expected = replaceToken(base, tokenStart, oldToken.length, newToken);

        GeneratedBundle bundle = workspace.service().generate(request(jar));
        JsonNode candidate = JSON.readTree(bundle.candidateBytes());
        assertEquals(sha256(expected), candidate.path("resultPackSha256").asText());
        JsonNode diff = JSON.readTree(bundle.diffBytes());
        assertEquals(OLD_RUNTIME, diff.at("/changes/0/before").asText());
        assertEquals("fixture/NewTarget", diff.at("/changes/0/after").asText());

        Path approved = approvedReview(bundle, sha256(bundle.candidateBytes()));
        Path lock = workspace.pack().resolveSibling(".fixture.json.mapping-review.lock");
        ApplyResult dry = workspace.service().apply(new ApplyRequest(bundle.candidatePath(), approved, jar, false));
        assertFalse(dry.written());
        assertFalse(Files.exists(lock), "dry-run must not create a lock file");
        assertEquals(0, mappingReviewTemps(workspace.pack().getParent()));
        assertArrayEquals(base, Files.readAllBytes(workspace.pack()));

        Set<PosixFilePermission> permissions = null;
        if (Files.getFileStore(workspace.pack()).supportsFileAttributeView("posix")) {
            permissions = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ);
            Files.setPosixFilePermissions(workspace.pack(), permissions);
        }
        ApplyResult written = workspace.service().apply(new ApplyRequest(bundle.candidatePath(), approved, jar, true));
        assertTrue(written.written());
        assertArrayEquals(expected, Files.readAllBytes(workspace.pack()));
        assertArrayEquals(java.util.Arrays.copyOfRange(base, 0, tokenStart),
            java.util.Arrays.copyOfRange(expected, 0, tokenStart));
        assertArrayEquals(java.util.Arrays.copyOfRange(base, tokenStart + oldToken.length, base.length),
            java.util.Arrays.copyOfRange(expected, tokenStart + newToken.length, expected.length));
        assertEquals(sha256(expected), written.resultPackSha256());
        if (permissions != null) assertEquals(permissions, Files.getPosixFilePermissions(workspace.pack()));
        assertEquals(0, mappingReviewTemps(workspace.pack().getParent()));
    }

    @Test
    void generateRejectsNoOpRuntimeChange() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = jar(Map.of(
            "fixture/Anchor.class", callerClass(List.of(OLD_RUNTIME), "unused"),
            OLD_RUNTIME + ".class", plainClass(OLD_RUNTIME)
        ));

        assertCode("NO_CHANGE", () -> workspace.service().generate(request(jar)));
    }

    @Test
    void dryRunWritesNothingAndWriteChangesOnlyOneClassRuntimeWithoutPromotion() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        GeneratedBundle bundle = workspace.service().generate(request(jar));
        Path approved = approvedReview(bundle, sha256(bundle.candidateBytes()));
        byte[] before = Files.readAllBytes(workspace.pack());

        ApplyResult dry = workspace.service().apply(new ApplyRequest(bundle.candidatePath(), approved, jar, false));
        assertFalse(dry.written());
        assertArrayEquals(before, Files.readAllBytes(workspace.pack()));

        ApplyResult written = workspace.service().apply(new ApplyRequest(bundle.candidatePath(), approved, jar, true));
        assertTrue(written.written());
        JsonNode result = JSON.readTree(workspace.pack().toFile());
        assertEquals("DRAFT", result.get("status").asText());
        assertEquals("fixture/NewTarget", result.at("/entries/0/runtime").asText());
        assertEquals("DRAFT", result.at("/entries/0/status").asText());
        assertEquals("none", result.at("/entries/0/verifiedBy").asText());
        assertTrue(result.at("/entries/0/verifiedAt").isNull());
        assertEquals("fixture/Unchanged", result.at("/entries/1/runtime").asText());
        assertEquals(sha256(Files.readAllBytes(workspace.pack())), written.resultPackSha256());
    }

    @Test
    void applyRejectsCandidateWhoseAfterRuntimeDoesNotMatchSelectedTargetOwner() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        GeneratedBundle bundle = workspace.service().generate(request(jar));
        ObjectNode candidate = (ObjectNode) JSON.readTree(bundle.candidateBytes());
        candidate.with("after").put("runtime", "fixture/OtherTarget");
        CandidateFiles altered = writeCandidateAndApprove(candidate, jar);

        assertCode("CANDIDATE_VALIDATION_FAILED", () -> workspace.service().apply(altered.request(false)));
        assertEquals(OLD_RUNTIME, JSON.readTree(workspace.pack().toFile()).at("/entries/0/runtime").asText());
    }

    @Test
    void applyRejectsArtifactThatExceedsCandidateOrActivePolicyBeforeReadingPastExactSize() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        GeneratedBundle bundle = workspace.service().generate(request(jar));
        Path approved = approvedReview(bundle, sha256(bundle.candidateBytes()));
        Files.write(jar, new byte[1024 * 1024], StandardOpenOption.APPEND);

        assertCode("ARTIFACT_MISMATCH", () -> workspace.service().apply(
            new ApplyRequest(bundle.candidatePath(), approved, jar, false)));
        assertEquals(OLD_RUNTIME, JSON.readTree(workspace.pack().toFile()).at("/entries/0/runtime").asText());
    }

    @Test
    void applyRejectsChangedResultHashAndNonDraftEntryState() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        GeneratedBundle bundle = workspace.service().generate(request(jar));

        ObjectNode candidate = (ObjectNode) JSON.readTree(bundle.candidateBytes());
        candidate.put("resultPackSha256", "0".repeat(64));
        CandidateFiles wrongResult = writeCandidateAndApprove(candidate, jar);
        assertCode("RESULT_PACK_HASH_MISMATCH", () -> workspace.service().apply(wrongResult.request(false)));

        ObjectNode pack = (ObjectNode) JSON.readTree(workspace.pack().toFile());
        ((ObjectNode) pack.at("/entries/0")).put("status", "VERIFIED_STATIC");
        Files.write(workspace.pack(), CandidateJson.write(pack));
        candidate = (ObjectNode) JSON.readTree(bundle.candidateBytes());
        candidate.put("basePackSha256", sha256(Files.readAllBytes(workspace.pack())));
        CandidateFiles promotedEntry = writeCandidateAndApprove(candidate, jar);
        assertCode("PACK_VALIDATION_FAILED", () -> workspace.service().apply(promotedEntry.request(false)));
    }

    @Test
    void applyFailsClosedForBaseBeforeSemanticAndValidatorDrift() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        GeneratedBundle bundle = workspace.service().generate(request(jar));
        Path approved = approvedReview(bundle, sha256(bundle.candidateBytes()));

        ObjectNode changedPack = (ObjectNode) JSON.readTree(workspace.pack().toFile());
        changedPack.put("source", "changed");
        Files.write(workspace.pack(), CandidateJson.write(changedPack));
        assertCode("PACK_BASE_HASH_MISMATCH", () -> workspace.service().apply(new ApplyRequest(bundle.candidatePath(), approved, jar, false)));

        workspace.writePack(OLD_RUNTIME);
        ObjectNode candidate = (ObjectNode) JSON.readTree(bundle.candidateBytes());
        candidate.with("before").put("runtime", "fixture/Wrong");
        CandidateFiles beforeAltered = writeCandidateAndApprove(candidate, jar);
        assertCode("PACK_BEFORE_MISMATCH", () -> workspace.service().apply(beforeAltered.request(false)));

        workspace.writePack(OLD_RUNTIME);
        candidate = (ObjectNode) JSON.readTree(bundle.candidateBytes());
        candidate.with("target").put("semanticName", "missing");
        CandidateFiles semanticAltered = writeCandidateAndApprove(candidate, jar);
        assertCode("PACK_SEMANTIC_NOT_UNIQUE", () -> workspace.service().apply(semanticAltered.request(false)));

        workspace.writeDuplicateSemanticPack();
        candidate = (ObjectNode) JSON.readTree(bundle.candidateBytes());
        candidate.put("basePackSha256", sha256(Files.readAllBytes(workspace.pack())));
        CandidateFiles duplicateAltered = writeCandidateAndApprove(candidate, jar);
        assertCode("PACK_VALIDATION_FAILED", () -> workspace.service().apply(duplicateAltered.request(false)));

        workspace.writePack(OLD_RUNTIME);
        ObjectNode invalid = (ObjectNode) JSON.readTree(workspace.pack().toFile());
        ((ObjectNode) invalid.at("/entries/0")).put("verifiedBy", "someone");
        Files.write(workspace.pack(), CandidateJson.write(invalid));
        candidate = (ObjectNode) JSON.readTree(bundle.candidateBytes());
        candidate.put("basePackSha256", sha256(Files.readAllBytes(workspace.pack())));
        CandidateFiles invalidAltered = writeCandidateAndApprove(candidate, jar);
        assertCode("PACK_VALIDATION_FAILED", () -> workspace.service().apply(invalidAltered.request(false)));
    }

    @Test
    void targetPackMustBeDraftTrackedAndCannotEscapeThroughSymlinks() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();

        workspace.untrackPack();
        assertCode("PACK_NOT_TRACKED", () -> workspace.service().generate(request(jar)));
        workspace.trackPack();

        Path outside = temp.resolve("outside.json");
        Files.write(outside, Files.readAllBytes(workspace.pack()));
        Files.delete(workspace.pack());
        try {
            Files.createSymbolicLink(workspace.pack(), outside);
            workspace.trackPack();
            assertCode("PACK_PATH_INVALID", () -> workspace.service().generate(request(jar)));
        } catch (UnsupportedOperationException exception) {
            // Filesystem does not support symlinks.
        }

        GenerateRequest wrongDirectory = new GenerateRequest(jar, "cubism-ref/mapping-packs/fixture.json", SEMANTIC, OLD_RUNTIME,
            CALLER, CALLER_NAME, CALLER_DESCRIPTOR, TARGET_NAME, TARGET_DESCRIPTOR, InvocationConstraint.INSTANCE);
        assertCode("PACK_PATH_INVALID", () -> workspace.service().generate(wrongDirectory));
    }

    @Test
    void applyRejectsInvalidStaticVerificationRecordAndCompleteVerifiedReference() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        GeneratedBundle bundle = workspace.service().generate(request(jar));
        Path approved = approvedReview(bundle, sha256(bundle.candidateBytes()));

        Files.writeString(workspace.verificationRecord(), "{\"format\":\"turboism.static.verification.record\",\"schemaVersion\":1}");
        assertCode("STATIC_VERIFICATION_RECORD_INVALID", () -> workspace.service().apply(
            new ApplyRequest(bundle.candidatePath(), approved, jar, false)));

        workspace.writeVerificationRecord(SEMANTIC);
        assertCode("PACK_REFERENCED_BY_VERIFIED_STATIC", () -> workspace.service().apply(
            new ApplyRequest(bundle.candidatePath(), approved, jar, false)));
    }

    @Test
    void writeRevalidatesTargetAndArtifactUnderLockAndBeforeMove() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        GeneratedBundle bundle = workspace.service().generate(request(jar));
        Path approved = approvedReview(bundle, sha256(bundle.candidateBytes()));
        byte[] originalPack = Files.readAllBytes(workspace.pack());
        final Path initialCandidatePath = bundle.candidatePath();
        final Path initialApproved = approved;
        final Path initialJar = jar;

        MappingReviewService targetChangedAfterLock = new MappingReviewService(
            workspace.root(), JarScanPolicy.defaults(), AtomicMover.system(),
            ArtifactSnapshotter.system(),
            lockPath -> {
                LockAcquirer.AcquiredLock acquired = LockAcquirer.system().acquire(lockPath);
                try {
                    Files.writeString(workspace.pack(), " ", StandardOpenOption.APPEND);
                    return acquired;
                } catch (IOException | RuntimeException exception) {
                    acquired.close();
                    throw exception;
                }
            }
        );
        assertCode("PACK_BASE_HASH_MISMATCH", () -> targetChangedAfterLock.apply(
            new ApplyRequest(initialCandidatePath, initialApproved, initialJar, true)));

        Files.write(workspace.pack(), originalPack);
        MappingReviewService targetUntrackedAfterLock = new MappingReviewService(
            workspace.root(), JarScanPolicy.defaults(), AtomicMover.system(),
            ArtifactSnapshotter.system(),
            lockPath -> {
                LockAcquirer.AcquiredLock acquired = LockAcquirer.system().acquire(lockPath);
                try {
                    try {
                        workspace.untrackPack();
                    } catch (Exception exception) {
                        throw new IOException(exception);
                    }
                    return acquired;
                } catch (IOException | RuntimeException exception) {
                    acquired.close();
                    throw exception;
                }
            }
        );
        assertCode("PACK_NOT_TRACKED", () -> targetUntrackedAfterLock.apply(
            new ApplyRequest(initialCandidatePath, initialApproved, initialJar, true)));
        workspace.trackPack();

        Files.write(workspace.pack(), originalPack);
        Path symlinkOutside = temp.resolve("after-lock-outside.json");
        Files.write(symlinkOutside, originalPack);
        MappingReviewService targetSymlinkAfterLock = new MappingReviewService(
            workspace.root(), JarScanPolicy.defaults(), AtomicMover.system(),
            ArtifactSnapshotter.system(),
            lockPath -> {
                LockAcquirer.AcquiredLock acquired = LockAcquirer.system().acquire(lockPath);
                try {
                    Files.delete(workspace.pack());
                    Files.createSymbolicLink(workspace.pack(), symlinkOutside);
                    return acquired;
                } catch (IOException | RuntimeException exception) {
                    acquired.close();
                    throw exception;
                }
            }
        );
        try {
            assertCode("PACK_PATH_INVALID", () -> targetSymlinkAfterLock.apply(
                new ApplyRequest(initialCandidatePath, initialApproved, initialJar, true)));
        } catch (UnsupportedOperationException exception) {
            // Filesystem does not support symlinks.
        } finally {
            Files.deleteIfExists(workspace.pack());
            Files.write(workspace.pack(), originalPack);
            workspace.trackPack();
        }

        MappingReviewService artifactChangedAfterLock = new MappingReviewService(
            workspace.root(), JarScanPolicy.defaults(), AtomicMover.system(),
            ArtifactSnapshotter.system(),
            lockPath -> {
                LockAcquirer.AcquiredLock acquired = LockAcquirer.system().acquire(lockPath);
                try {
                    Files.write(initialJar, new byte[]{0}, StandardOpenOption.APPEND);
                    return acquired;
                } catch (IOException | RuntimeException exception) {
                    acquired.close();
                    throw exception;
                }
            }
        );
        assertCode("ARTIFACT_MISMATCH", () -> artifactChangedAfterLock.apply(
            new ApplyRequest(initialCandidatePath, initialApproved, initialJar, true)));

        jar = happyJar();
        bundle = workspace.service().generate(request(
            jar,
            workspace.root().resolve("build/worktree/mapping-review-local/mapping-review-second"),
            "mapping-review-local"
        ));
        approved = approvedReview(bundle, sha256(bundle.candidateBytes()));
        GeneratedBundle finalBundle = bundle;
        Path finalApproved = approved;
        Path finalJar = jar;
        MappingReviewService targetChangedBeforeMove = new MappingReviewService(
            workspace.root(), JarScanPolicy.defaults(),
            (temporary, target, artifact, expectedArtifactHash, expectedBaseHash) -> {
                writeStringUnchecked(target, " ", StandardOpenOption.APPEND);
                AtomicReplacement.system(workspace.root(), AtomicMover.system()).replace(
                    temporary, target, artifact, expectedArtifactHash, expectedBaseHash);
            },
            ArtifactSnapshotter.system(), LockAcquirer.system()
        );
        GeneratedBundle targetChangedBundle = finalBundle;
        Path targetChangedApproved = finalApproved;
        Path targetChangedJar = finalJar;
        assertCode("PACK_CHANGED_BEFORE_MOVE", () -> targetChangedBeforeMove.apply(
            new ApplyRequest(targetChangedBundle.candidatePath(), targetChangedApproved, targetChangedJar, true)));

        Files.write(workspace.pack(), originalPack);
        MappingReviewService artifactChangedBeforeMove = new MappingReviewService(
            workspace.root(), JarScanPolicy.defaults(),
            (temporary, target, artifact, expectedArtifactHash, expectedBaseHash) -> {
                writeUnchecked(artifact, new byte[]{0}, StandardOpenOption.APPEND);
                AtomicReplacement.system(workspace.root(), AtomicMover.system()).replace(
                    temporary, target, artifact, expectedArtifactHash, expectedBaseHash);
            },
            ArtifactSnapshotter.system(), LockAcquirer.system()
        );
        GeneratedBundle artifactChangedBundle = finalBundle;
        Path artifactChangedApproved = finalApproved;
        Path artifactChangedJar = finalJar;
        assertCode("ARTIFACT_MISMATCH", () -> artifactChangedBeforeMove.apply(
            new ApplyRequest(artifactChangedBundle.candidatePath(), artifactChangedApproved, artifactChangedJar, true)));

        finalJar = happyJar();
        finalBundle = workspace.service().generate(request(
            finalJar,
            workspace.root().resolve("build/worktree/mapping-review-local/mapping-review-third"),
            "mapping-review-local"
        ));
        finalApproved = approvedReview(finalBundle, sha256(finalBundle.candidateBytes()));
        Path beforeMoveOutside = temp.resolve("before-move-outside.json");
        Files.write(beforeMoveOutside, originalPack);
        MappingReviewService targetSymlinkBeforeMove = new MappingReviewService(
            workspace.root(), JarScanPolicy.defaults(),
            (temporary, target, artifact, expectedArtifactHash, expectedBaseHash) -> {
                deleteUnchecked(target);
                createSymbolicLinkUnchecked(target, beforeMoveOutside);
                AtomicReplacement.system(workspace.root(), AtomicMover.system()).replace(
                    temporary, target, artifact, expectedArtifactHash, expectedBaseHash);
            },
            ArtifactSnapshotter.system(), LockAcquirer.system()
        );
        try {
            GeneratedBundle symlinkBundle = finalBundle;
            Path symlinkApproved = finalApproved;
            Path symlinkJar = finalJar;
            assertCode("PACK_PATH_INVALID", () -> targetSymlinkBeforeMove.apply(
                new ApplyRequest(symlinkBundle.candidatePath(), symlinkApproved, symlinkJar, true)));
        } catch (UnsupportedOperationException exception) {
            // Filesystem does not support symlinks.
        } finally {
            Files.deleteIfExists(workspace.pack());
            Files.write(workspace.pack(), originalPack);
            workspace.trackPack();
        }
        assertEquals(0, mappingReviewTemps(workspace.pack().getParent()));
    }

    @Test
    void noFollowReadsAndLockRejectFifosWithoutBlocking() throws Exception {
        Path fifo = temp.resolve("special.fifo");
        createFifo(fifo);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
            () -> assertCode("FIFO_REJECTED", () -> FileSafety.readAllBytesNoFollow(fifo, "FIFO_REJECTED")));
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
            () -> assertCode("FIFO_REJECTED", () -> FileSafety.openRegularNoFollow(
                fifo, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE), "FIFO_REJECTED")));
    }

    @Test
    void candidateReviewArtifactAndLockFifosFailWithoutBlocking() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        GeneratedBundle bundle = workspace.service().generate(request(jar));
        Path approved = approvedReview(bundle, sha256(bundle.candidateBytes()));

        Path candidateFifo = temp.resolve("candidate.fifo");
        createFifo(candidateFifo);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> assertCode("CANDIDATE_READ_FAILED",
            () -> workspace.service().apply(new ApplyRequest(candidateFifo, approved, jar, false))));

        Path reviewFifo = temp.resolve("review.fifo");
        createFifo(reviewFifo);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> assertCode("REVIEW_READ_FAILED",
            () -> workspace.service().apply(new ApplyRequest(bundle.candidatePath(), reviewFifo, jar, false))));

        Path artifactFifo = temp.resolve("artifact.fifo");
        createFifo(artifactFifo);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> assertCode("ARTIFACT_NOT_REGULAR",
            () -> workspace.service().apply(new ApplyRequest(bundle.candidatePath(), approved, artifactFifo, false))));

        Path lock = workspace.pack().resolveSibling(".fixture.json.mapping-review.lock");
        createFifo(lock);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> assertCode("APPLY_LOCK_FAILED",
            () -> workspace.service().apply(new ApplyRequest(bundle.candidatePath(), approved, jar, true))));
    }

    @Test
    void verificationRecordsRejectSymlinksAndFifosWithoutBlocking() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        GeneratedBundle bundle = workspace.service().generate(request(jar));
        Path approved = approvedReview(bundle, sha256(bundle.candidateBytes()));
        Path record = workspace.verificationRecord();

        Path realRecord = temp.resolve("real-verification.json");
        workspace.writeVerificationRecord(SEMANTIC);
        Files.move(record, realRecord);
        try {
            Files.createSymbolicLink(record, realRecord);
            assertCode("STATIC_VERIFICATION_RECORD_INVALID", () -> workspace.service().apply(
                new ApplyRequest(bundle.candidatePath(), approved, jar, false)));
        } catch (UnsupportedOperationException exception) {
            // Filesystem does not support symlinks.
        } finally {
            Files.deleteIfExists(record);
        }

        createFifo(record);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> assertCode(
            "STATIC_VERIFICATION_RECORD_INVALID", () -> workspace.service().apply(
                new ApplyRequest(bundle.candidatePath(), approved, jar, false))));
    }

    @Test
    void writeRejectsSymlinkLockFile() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        GeneratedBundle bundle = workspace.service().generate(request(jar));
        Path approved = approvedReview(bundle, sha256(bundle.candidateBytes()));
        Path outside = temp.resolve("outside-lock");
        Files.writeString(outside, "outside");
        Path lock = workspace.pack().resolveSibling(".fixture.json.mapping-review.lock");
        try {
            Files.createSymbolicLink(lock, outside);
            assertCode("APPLY_LOCK_FAILED", () -> workspace.service().apply(
                new ApplyRequest(bundle.candidatePath(), approved, jar, true)));
            assertEquals("outside", Files.readString(outside));
        } catch (UnsupportedOperationException exception) {
            // Filesystem does not support symlinks.
        }
    }

    @Test
    void applyRejectsVerifiedStaticReferenceAndAtomicMoveFailure() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        GeneratedBundle bundle = workspace.service().generate(request(jar));
        Path approved = approvedReview(bundle, sha256(bundle.candidateBytes()));
        workspace.writeVerificationRecord(SEMANTIC);
        assertCode("PACK_REFERENCED_BY_VERIFIED_STATIC", () -> workspace.service().apply(new ApplyRequest(bundle.candidatePath(), approved, jar, false)));

        Files.delete(workspace.verificationRecord());
        AtomicMover unsupported = (source, target) -> { throw new java.nio.file.AtomicMoveNotSupportedException(source.toString(), target.toString(), "fixture"); };
        final MappingReviewService unsupportedService = new MappingReviewService(workspace.root(), JarScanPolicy.defaults(), unsupported);
        byte[] before = Files.readAllBytes(workspace.pack());
        assertCode("ATOMIC_MOVE_UNSUPPORTED", () -> unsupportedService.apply(new ApplyRequest(bundle.candidatePath(), approved, jar, true)));
        assertArrayEquals(before, Files.readAllBytes(workspace.pack()));
        assertEquals(0, mappingReviewTemps(workspace.pack().getParent()));

        AtomicMover failing = (source, target) -> { throw new IOException("ordinary move failure"); };
        final MappingReviewService failingService = new MappingReviewService(workspace.root(), JarScanPolicy.defaults(), failing);
        assertCode("ATOMIC_WRITE_FAILED", () -> failingService.apply(new ApplyRequest(bundle.candidatePath(), approved, jar, true)));
        assertArrayEquals(before, Files.readAllBytes(workspace.pack()));
        assertEquals(0, mappingReviewTemps(workspace.pack().getParent()));
    }

    @Test
    void candidateAndReviewValidatorsAreStrict() throws Exception {
        TestWorkspace workspace = workspace();
        GeneratedBundle bundle = workspace.service().generate(request(happyJar()));
        assertTrue(new MappingUpdateCandidateValidator().validate(JSON.readTree(bundle.candidateBytes())).isEmpty());
        assertTrue(new MappingReviewValidator().validate(JSON.readTree(bundle.reviewBytes())).isEmpty());

        ObjectNode candidate = (ObjectNode) JSON.readTree(bundle.candidateBytes());
        candidate.put("extra", true);
        assertFalse(new MappingUpdateCandidateValidator().validate(candidate).isEmpty());
        ObjectNode review = (ObjectNode) JSON.readTree(bundle.reviewBytes());
        review.put("decision", "YES");
        assertFalse(new MappingReviewValidator().validate(review).isEmpty());

        candidate = (ObjectNode) JSON.readTree(bundle.candidateBytes());
        candidate.with("target").put("pack", "cubism-ref/mapping-packs/draft/nested/unsafe.json");
        assertFalse(new MappingUpdateCandidateValidator().validate(candidate).isEmpty());
        candidate = (ObjectNode) JSON.readTree(bundle.candidateBytes());
        candidate.with("evidence").with("caller").put("name", 7);
        assertFalse(new MappingUpdateCandidateValidator().validate(candidate).isEmpty());
    }

    @Test
    void generateRejectsForbiddenReadableSelectorTerms() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        GenerateRequest forbidden = new GenerateRequest(jar, PACK, "fixture.security.bypass", OLD_RUNTIME,
            CALLER, CALLER_NAME, CALLER_DESCRIPTOR, TARGET_NAME, TARGET_DESCRIPTOR, InvocationConstraint.INSTANCE);
        assertCode("FORBIDDEN_SELECTOR_TERM", () -> workspace.service().generate(forbidden));
        assertDoesNotThrow(() -> workspace.service().generate(request(jar)));
    }

    @Test
    void targetMethodAccessMustMatchInvocation() throws Exception {
        TestWorkspace workspace = workspace();
        Path jar = happyJar();
        GenerateRequest staticRecipe = new GenerateRequest(jar, PACK, SEMANTIC, OLD_RUNTIME,
            CALLER, CALLER_NAME, CALLER_DESCRIPTOR, TARGET_NAME, TARGET_DESCRIPTOR, InvocationConstraint.STATIC);
        assertCode("SCAN_EDGE_NOT_UNIQUE", () -> workspace.service().generate(staticRecipe));
    }

    private TestWorkspace workspace() throws Exception {
        Path root = temp.resolve("worktree-" + System.nanoTime());
        TestWorkspace workspace = new TestWorkspace(root);
        workspace.writePack(OLD_RUNTIME);
        Files.createDirectories(root.resolve("cubism-ref/verification"));
        return workspace;
    }

    private Path happyJar() throws Exception {
        return jar(Map.of(
            "fixture/Anchor.class", callerClass(List.of("fixture/NewTarget"), "unused"),
            "fixture/NewTarget.class", plainClass("fixture/NewTarget")
        ));
    }

    private GenerateRequest request(Path jar) {
        return request(jar, null, null);
    }

    private GenerateRequest request(Path jar, Path output, String worktreeId) {
        return new GenerateRequest(jar, PACK, SEMANTIC, OLD_RUNTIME,
            CALLER, CALLER_NAME, CALLER_DESCRIPTOR,
            TARGET_NAME, TARGET_DESCRIPTOR, InvocationConstraint.INSTANCE, output, worktreeId);
    }

    private String[] generateCliArgs(TestWorkspace workspace, Path jar, Path output) {
        List<String> arguments = new ArrayList<>(List.of(
            "generate", "--root", workspace.root().toString(),
            "--artifact", jar.toString(), "--pack", PACK,
            "--semantic-name", SEMANTIC, "--expected-old-runtime", OLD_RUNTIME,
            "--caller-owner", CALLER, "--caller-name", CALLER_NAME,
            "--caller-descriptor", CALLER_DESCRIPTOR,
            "--target-method-name", TARGET_NAME,
            "--target-method-descriptor", TARGET_DESCRIPTOR,
            "--invocation", "INSTANCE"
        ));
        if (output != null) arguments.addAll(List.of("--output", output.toString()));
        return arguments.toArray(String[]::new);
    }

    private Path approvedReview(GeneratedBundle bundle, String candidateHash) throws Exception {
        ObjectNode review = JSON.createObjectNode();
        review.put("format", "turboism.mapping.update.review");
        review.put("schemaVersion", 1);
        review.put("decision", "APPROVED");
        review.put("candidateSha256", candidateHash);
        review.put("reviewer", "fixture-reviewer");
        review.put("reviewedAt", "2026-07-10T00:00:00Z");
        return write(temp.resolve("approved-" + System.nanoTime() + ".json"), CandidateJson.write(review));
    }

    private Path review(GeneratedBundle bundle, String decision, String reviewer, Instant at) throws Exception {
        ObjectNode review = JSON.createObjectNode();
        review.put("format", "turboism.mapping.update.review");
        review.put("schemaVersion", 1);
        review.put("decision", decision);
        review.put("candidateSha256", sha256(bundle.candidateBytes()));
        review.put("reviewer", reviewer);
        review.put("reviewedAt", at.toString());
        return write(temp.resolve("review-" + System.nanoTime() + ".json"), CandidateJson.write(review));
    }

    private CandidateFiles writeCandidateAndApprove(ObjectNode candidate, Path jar) throws Exception {
        byte[] bytes = CandidateJson.write(candidate);
        Path candidatePath = write(temp.resolve("candidate-" + System.nanoTime() + ".json"), bytes);
        ObjectNode review = JSON.createObjectNode();
        review.put("format", "turboism.mapping.update.review");
        review.put("schemaVersion", 1);
        review.put("decision", "APPROVED");
        review.put("candidateSha256", sha256(bytes));
        review.put("reviewer", "fixture-reviewer");
        review.put("reviewedAt", "2026-07-10T00:00:00Z");
        Path reviewPath = write(temp.resolve("review-" + System.nanoTime() + ".json"), CandidateJson.write(review));
        return new CandidateFiles(candidatePath, reviewPath, jar);
    }

    private Path jar(Map<String, byte[]> entries) throws IOException {
        Path path = temp.resolve("fixture-" + System.nanoTime() + ".jar");
        writeJar(path, entries);
        return path;
    }

    private static void writeJar(Path path, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private static byte[] callerClass(List<String> targetOwners, String marker) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, "java/lang/Object", null);
        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/io/File");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitLdcInsn(marker);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false);
        clinit.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "createNewFile", "()Z", false);
        clinit.visitInsn(Opcodes.POP);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(3, 0);
        clinit.visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, CALLER_NAME, CALLER_DESCRIPTOR, null, null);
        method.visitCode();
        for (String owner : targetOwners) {
            method.visitTypeInsn(Opcodes.NEW, owner);
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", "()V", false);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, TARGET_NAME, TARGET_DESCRIPTOR, false);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] callerClassWithSecret(String owner, String secret) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, CALLER_NAME, CALLER_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitLdcInsn(secret);
        method.visitInsn(Opcodes.POP);
        method.visitTypeInsn(Opcodes.NEW, owner);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, TARGET_NAME, TARGET_DESCRIPTOR, false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] callerClassWithInvokeDynamic() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, CALLER_NAME, CALLER_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitInvokeDynamicInsn("dyn", "()V", new org.objectweb.asm.Handle(
            Opcodes.H_INVOKESTATIC, "fixture/Bootstrap", "bootstrap",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false));
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithoutSelectedMethod(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] plainClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        MethodVisitor selected = writer.visitMethod(Opcodes.ACC_PUBLIC, TARGET_NAME, TARGET_DESCRIPTOR, null, null);
        selected.visitCode();
        selected.visitInsn(Opcodes.RETURN);
        selected.visitMaxs(0, 1);
        selected.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeJarWithManifest(Path path, Map<String, byte[]> entries, boolean multiRelease) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", Boolean.toString(multiRelease));
        try (java.util.jar.JarOutputStream output = new java.util.jar.JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private static int indexOf(byte[] bytes, byte[] token) {
        outer: for (int index = 0; index <= bytes.length - token.length; index++) {
            for (int offset = 0; offset < token.length; offset++) {
                if (bytes[index + offset] != token[offset]) continue outer;
            }
            return index;
        }
        return -1;
    }

    private static byte[] replaceToken(byte[] bytes, int start, int length, byte[] replacement) {
        byte[] result = new byte[bytes.length - length + replacement.length];
        System.arraycopy(bytes, 0, result, 0, start);
        System.arraycopy(replacement, 0, result, start, replacement.length);
        System.arraycopy(bytes, start + length, result, start + replacement.length, bytes.length - start - length);
        return result;
    }

    private static void writeStringUnchecked(Path path, String value, java.nio.file.OpenOption... options) {
        try {
            Files.writeString(path, value, options);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static void writeUnchecked(Path path, byte[] value, java.nio.file.OpenOption... options) {
        try {
            Files.write(path, value, options);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static void deleteUnchecked(Path path) {
        try {
            Files.delete(path);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static void createSymbolicLinkUnchecked(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static void assertSnapshotDeleted(Path snapshot) {
        assertFalse(Files.exists(snapshot), "snapshot file must be deleted: " + snapshot);
        assertFalse(Files.exists(snapshot.getParent()), "snapshot directory must be deleted: " + snapshot.getParent());
    }

    private static long snapshotDirectories(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("turboism-mapping-snapshot-")).count();
        }
    }

    private static long mappingReviewTemps(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith(".fixture.json.mapping-review-")
                && path.getFileName().toString().endsWith(".tmp")).count();
        }
    }

    private static void createFifo(Path path) throws Exception {
        Process process = new ProcessBuilder("mkfifo", path.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private static Path write(Path path, byte[] bytes) throws IOException {
        Files.write(path, bytes);
        return path;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void assertCode(String expected, ThrowingRunnable runnable) {
        DraftMappingException error = assertThrows(DraftMappingException.class, runnable::run);
        assertEquals(expected, error.code(), error.getMessage());
    }

    private static void writeRawStoredZip(Path path, List<RawEntry> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(output);
        List<Central> central = new ArrayList<>();
        for (RawEntry entry : entries) {
            byte[] name = entry.name().getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(entry.bytes());
            int offset = output.size();
            writeIntLE(data, 0x04034b50);
            writeShortLE(data, 20);
            writeShortLE(data, 0x0800);
            writeShortLE(data, 0);
            writeShortLE(data, 0);
            writeShortLE(data, 0);
            writeIntLE(data, (int) crc.getValue());
            writeIntLE(data, entry.bytes().length);
            writeIntLE(data, entry.bytes().length);
            writeShortLE(data, name.length);
            writeShortLE(data, 0);
            data.write(name);
            data.write(entry.bytes());
            central.add(new Central(entry, name, (int) crc.getValue(), offset));
        }
        int centralOffset = output.size();
        for (Central item : central) {
            writeIntLE(data, 0x02014b50);
            writeShortLE(data, 20);
            writeShortLE(data, 20);
            writeShortLE(data, 0x0800);
            writeShortLE(data, 0);
            writeShortLE(data, 0);
            writeShortLE(data, 0);
            writeIntLE(data, item.crc());
            writeIntLE(data, item.entry().bytes().length);
            writeIntLE(data, item.entry().bytes().length);
            writeShortLE(data, item.name().length);
            writeShortLE(data, 0);
            writeShortLE(data, 0);
            writeShortLE(data, 0);
            writeShortLE(data, 0);
            writeIntLE(data, 0);
            writeIntLE(data, item.offset());
            data.write(item.name());
        }
        int centralSize = output.size() - centralOffset;
        writeIntLE(data, 0x06054b50);
        writeShortLE(data, 0);
        writeShortLE(data, 0);
        writeShortLE(data, central.size());
        writeShortLE(data, central.size());
        writeIntLE(data, centralSize);
        writeIntLE(data, centralOffset);
        writeShortLE(data, 0);
        Files.write(path, output.toByteArray());
    }

    private static void writeShortLE(DataOutputStream data, int value) throws IOException {
        data.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array());
    }

    private static void writeIntLE(DataOutputStream data, int value) throws IOException {
        data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }

    private record RawEntry(String name, byte[] bytes) { }
    private record Central(RawEntry entry, byte[] name, int crc, int offset) { }
    private record CandidateFiles(Path candidate, Path review, Path jar) {
        ApplyRequest request(boolean write) { return new ApplyRequest(candidate, review, jar, write); }
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    private final class TestWorkspace {
        private final Path root;

        private TestWorkspace(Path root) throws Exception {
            this.root = root;
            Files.createDirectories(root);
            git("init", "-q");
            git("config", "user.email", "fixture@example.invalid");
            git("config", "user.name", "Fixture");
        }
        Path root() { return root; }
        Path pack() { return root.resolve(PACK); }
        Path verificationRecord() { return root.resolve("cubism-ref/verification/fixture.json"); }
        MappingReviewService service() {
            return new MappingReviewService(
                root,
                JarScanPolicy.defaults(),
                AtomicMover.system(),
                ArtifactSnapshotter.system(),
                LockAcquirer.system(),
                Clock.fixed(Instant.parse("2026-07-10T12:34:56Z"), ZoneOffset.UTC)
            );
        }

        void writePack(String runtime) throws Exception {
            Files.createDirectories(pack().getParent());
            ObjectNode rootNode = JSON.createObjectNode();
            rootNode.put("format", "turboism.mapping.pack");
            rootNode.put("schemaVersion", 1);
            rootNode.put("status", "DRAFT");
            rootNode.put("source", "fixture");
            rootNode.put("cubismVersion", "1.0");
            var entries = rootNode.putArray("entries");
            entries.add(entry(SEMANTIC, runtime));
            entries.add(entry("fixture.unchanged.class", "fixture/Unchanged"));
            Files.write(pack(), CandidateJson.write(rootNode));
            trackPack();
        }

        void writeCustomPack() throws Exception {
            Files.createDirectories(pack().getParent());
            String custom = "{\n"
                + "  \"entries\" : [\n"
                + "    { \"runtime\" : \"fixture\\/Old\\u0054arget\", \"semanticName\" : \"fixture.target.class\","
                + " \"kind\":\"class\", \"owner\":\"fixture\", \"name\":\"fixture.target.class\","
                + " \"profile\":\"fixture\", \"status\":\"DRAFT\", \"stability\":\"experimental\","
                + " \"source\":\"fixture\", \"verifiedBy\":\"none\", \"verifiedAt\":null, \"confidence\":\"high\" },\n"
                + "    { \"semanticName\":\"fixture.unchanged.class\", \"kind\":\"class\", \"owner\":\"fixture\","
                + " \"name\":\"fixture.unchanged.class\", \"runtime\":\"fixture/Unchanged\", \"profile\":\"fixture\","
                + " \"status\":\"DRAFT\", \"stability\":\"experimental\", \"source\":\"fixture\","
                + " \"verifiedBy\":\"none\", \"verifiedAt\":null, \"confidence\":\"high\" }\n"
                + "  ],\n"
                + "  \"source\" : \"fixture\", \"status\" : \"DRAFT\", \"cubismVersion\" : \"1.0\",\n"
                + "  \"schemaVersion\" : 1, \"format\" : \"turboism.mapping.pack\"\n"
                + "}\n";
            Files.writeString(pack(), custom, StandardCharsets.UTF_8);
            trackPack();
        }

        void writeDuplicateSemanticPack() throws Exception {
            ObjectNode rootNode = (ObjectNode) JSON.readTree(pack().toFile());
            rootNode.withArray("entries").add(entry(SEMANTIC, OLD_RUNTIME));
            Files.write(pack(), CandidateJson.write(rootNode));
        }

        ObjectNode entry(String semantic, String runtime) {
            ObjectNode entry = JSON.createObjectNode();
            entry.put("semanticName", semantic);
            entry.put("kind", "class");
            entry.put("owner", "fixture");
            entry.put("name", semantic);
            entry.put("runtime", runtime);
            entry.put("profile", "fixture");
            entry.put("status", "DRAFT");
            entry.put("stability", "experimental");
            entry.put("source", "fixture");
            entry.put("verifiedBy", "none");
            entry.putNull("verifiedAt");
            entry.put("confidence", "high");
            return entry;
        }

        void writeVerificationRecord(String mappingId) throws Exception {
            Files.createDirectories(verificationRecord().getParent());
            ObjectNode record = JSON.createObjectNode();
            record.put("format", "turboism.static.verification.record");
            record.put("schemaVersion", 1);
            record.put("verificationId", "fixture.static");
            record.put("adapterSliceId", "adapter.fixture");
            record.putArray("capabilityIds").add("cubism.fixture.read");
            record.put("cubismVersion", "1.0.0");
            record.put("profileId", "fixture-profile");
            record.putObject("artifact").put("name", "fixture.jar").put("size", 1).put("sha256", "0".repeat(64));
            record.put("evidenceType", "JAR_METADATA");
            record.put("evidencePath", "docs/migration/verification/static/fixture.json");
            record.put("owner", "runtime-adapter");
            record.put("status", "VERIFIED_STATIC");
            record.put("verifiedBy", "fixture-verifier");
            record.put("verifiedAt", "2026-07-10T00:00:00Z");
            record.put("safeMode", "fail closed");
            var selector = record.putArray("selectors").addObject();
            selector.put("mappingId", mappingId);
            selector.put("alias", "fixture.target");
            selector.put("kind", "class");
            selector.put("ownerInternalName", "fixture/NewTarget");
            selector.putNull("memberName");
            selector.putNull("descriptor");
            selector.put("requiredAccessFlags", 1);
            selector.put("forbiddenAccessFlags", 0);
            selector.put("status", "VERIFIED_STATIC");
            Files.write(verificationRecord(), CandidateJson.write(record));
        }

        void trackPack() throws Exception { git("add", PACK); }

        void untrackPack() throws Exception { git("rm", "--cached", "-q", "--", PACK); }

        private void git(String... arguments) throws Exception {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(root.toString());
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), output);
        }
    }
}
