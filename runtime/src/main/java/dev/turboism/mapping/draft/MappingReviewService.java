package dev.turboism.mapping.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.core.schema.diagnostic.DiagnosticReportValidator;
import dev.turboism.mapping.verification.StaticVerificationRecordValidator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** End-to-end local workflow for one reviewed DRAFT class-runtime update. */
public final class MappingReviewService {
    private final Path root;
    private final JarScanPolicy policy;
    private final AtomicReplacement atomicReplacement;
    private final LockAcquirer lockAcquirer;
    private final BoundedJarScanner scanner;
    private final Clock clock;
    private final DraftMappingPackValidator packValidator = new DraftMappingPackValidator();
    private final MappingUpdateCandidateValidator candidateValidator = new MappingUpdateCandidateValidator();
    private final MappingReviewValidator reviewValidator = new MappingReviewValidator();
    private final MappingUpdateDiffValidator diffValidator = new MappingUpdateDiffValidator();
    private final DiagnosticReportValidator diagnosticValidator = new DiagnosticReportValidator();
    private final StaticVerificationRecordValidator staticVerificationRecordValidator = new StaticVerificationRecordValidator();

    public MappingReviewService(final Path root, final JarScanPolicy policy, final AtomicMover atomicMover) {
        this(root, policy, atomicMover, ArtifactSnapshotter.system(), LockAcquirer.system(), Clock.systemUTC());
    }

    MappingReviewService(
        final Path root,
        final JarScanPolicy policy,
        final AtomicMover atomicMover,
        final ArtifactSnapshotter snapshotter
    ) {
        this(root, policy, atomicMover, snapshotter, LockAcquirer.system(), Clock.systemUTC());
    }

    MappingReviewService(
        final Path root,
        final JarScanPolicy policy,
        final AtomicMover atomicMover,
        final ArtifactSnapshotter snapshotter,
        final LockAcquirer lockAcquirer
    ) {
        this(root, policy, atomicMover, snapshotter, lockAcquirer, Clock.systemUTC());
    }

    MappingReviewService(
        final Path root,
        final JarScanPolicy policy,
        final AtomicMover atomicMover,
        final ArtifactSnapshotter snapshotter,
        final LockAcquirer lockAcquirer,
        final Clock clock
    ) {
        this(root, policy, AtomicReplacement.system(root.toAbsolutePath().normalize(), atomicMover), snapshotter, lockAcquirer, clock);
    }

    MappingReviewService(
        final Path root,
        final JarScanPolicy policy,
        final AtomicReplacement atomicReplacement,
        final ArtifactSnapshotter snapshotter,
        final LockAcquirer lockAcquirer
    ) {
        this(root, policy, atomicReplacement, snapshotter, lockAcquirer, Clock.systemUTC());
    }

    MappingReviewService(
        final Path root,
        final JarScanPolicy policy,
        final AtomicReplacement atomicReplacement,
        final ArtifactSnapshotter snapshotter,
        final LockAcquirer lockAcquirer,
        final Clock clock
    ) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.policy = Objects.requireNonNull(policy, "policy");
        this.atomicReplacement = Objects.requireNonNull(atomicReplacement, "atomicReplacement");
        this.lockAcquirer = Objects.requireNonNull(lockAcquirer, "lockAcquirer");
        this.scanner = new BoundedJarScanner(policy, Objects.requireNonNull(snapshotter, "snapshotter"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Produces the reviewable bundle for one class-runtime update: a candidate, a pending review, a
     * presentation-only diff, and a diagnostic record.
     *
     * <p>Nothing in the mapping pack is modified. The target pack must be a git-tracked draft pack
     * inside the worktree; the artifact is scanned under {@link JarScanPolicy} limits and the
     * recipe must select exactly one runtime target. Every produced document is validated against
     * its schema before being written, and the four files are published together under a
     * worktree-scoped output directory.
     *
     * @param request the update recipe, target pack, artifact, and output location
     * @return the four generated paths together with their exact bytes
     * @throws DraftMappingException for any rejection, carrying a code such as
     *     {@code PACK_NOT_TRACKED}, {@code PACK_TARGET_NOT_CLASS}, {@code PACK_BEFORE_MISMATCH},
     *     {@code SCAN_EDGE_NOT_UNIQUE}, or a validation failure code
     */
    public GeneratedBundle generate(final GenerateRequest request) {
        ForbiddenSelectorTerms.requireAllowed(
            request.semanticName(), request.expectedOldRuntime(), request.callerOwner(), request.callerName(),
            request.callerDescriptor(), request.targetMethodName(), request.targetMethodDescriptor()
        );
        final Path artifact = checkedArtifact(request.artifact());
        final Path packPath = resolveTracked(request.targetPack());
        final byte[] packBytes = readBytes(packPath, "PACK_READ_FAILED");
        final JsonNode pack = parse(packBytes, "PACK_JSON_INVALID");
        requireValidPack(pack);
        final JsonNode entry = uniqueEntry(pack, request.semanticName());
        if (!"class".equals(entry.path("kind").asText())) {
            fail("PACK_TARGET_NOT_CLASS", "only class runtime entries may be updated");
        }
        if (!request.expectedOldRuntime().equals(entry.path("runtime").asText())) {
            fail("PACK_BEFORE_MISMATCH", "expected old runtime does not match the target entry");
        }

        final BoundedJarScanner.ArtifactScan scan = scanner.scanSnapshot(artifact, request);
        if (scan.targets().size() != 1) {
            fail("SCAN_EDGE_NOT_UNIQUE", "recipe must select exactly one runtime target");
        }
        final BoundedJarScanner.Edge edge = scan.targets().get(0);
        ForbiddenSelectorTerms.requireAllowed(edge.owner(), edge.name(), edge.descriptor());
        final ExactJsonRuntimeReplacement.Replacement replacement = ExactJsonRuntimeReplacement.replace(
            packBytes, pack, request.semanticName(), request.expectedOldRuntime(), edge.owner());
        final byte[] resultBytes = replacement.bytes();
        final JsonNode resultPack = parse(resultBytes, "RESULT_PACK_JSON_INVALID");
        requireValidPack(resultPack);

        final ObjectNode candidate = CandidateJson.MAPPER.createObjectNode();
        candidate.put("format", "turboism.mapping.update.candidate");
        candidate.put("schemaVersion", 1);
        candidate.put("operation", "UPDATE_CLASS_RUNTIME");
        candidate.putObject("target").put("pack", request.targetPack()).put("semanticName", request.semanticName());
        candidate.put("basePackSha256", sha256(packBytes));
        candidate.putObject("artifact")
            .put("name", artifact.getFileName().toString())
            .put("size", scan.size())
            .put("sha256", scan.sha256());
        candidate.set("scannerPolicy", policyJson());
        final ObjectNode evidence = candidate.putObject("evidence");
        evidence.putObject("caller")
            .put("owner", request.callerOwner()).put("name", request.callerName()).put("descriptor", request.callerDescriptor());
        evidence.putObject("selectedTarget")
            .put("owner", edge.owner()).put("name", edge.name()).put("descriptor", edge.descriptor());
        evidence.put("invocation", edge.invocation());
        candidate.putObject("before").put("kind", "class").put("runtime", request.expectedOldRuntime());
        candidate.putObject("after").put("kind", "class").put("runtime", edge.owner());
        candidate.put("resultPackSha256", sha256(resultBytes));
        requireValidCandidate(candidate);

        final byte[] candidateBytes = CandidateJson.write(candidate);
        final ObjectNode review = CandidateJson.MAPPER.createObjectNode();
        review.put("format", "turboism.mapping.update.review");
        review.put("schemaVersion", 1);
        review.put("decision", "PENDING");
        review.put("candidateSha256", sha256(candidateBytes));
        review.putNull("reviewer");
        review.putNull("reviewedAt");
        requireValidReview(review);
        final byte[] reviewBytes = CandidateJson.write(review);
        final ExactJsonRuntimeReplacement.Replacement verifiedDiff = ExactJsonRuntimeReplacement.verifyOnlyRuntimeChanged(
            packBytes, resultBytes, request.semanticName(), request.expectedOldRuntime(), edge.owner());
        final ObjectNode diff = diff(request.targetPack(), request.semanticName(), verifiedDiff, candidateBytes);
        final var diffErrors = diffValidator.validate(diff);
        if (!diffErrors.isEmpty()) fail("DIFF_VALIDATION_FAILED", diffErrors.toString());
        final byte[] diffBytes = CandidateJson.write(diff);
        final ObjectNode diagnostic = successDiagnostic(request.worktreeId(), request.semanticName(), clock.instant());
        final var diagnosticErrors = diagnosticValidator.validate(diagnostic);
        if (!diagnosticErrors.isEmpty()) fail("DIAGNOSTIC_VALIDATION_FAILED", diagnosticErrors.toString());
        final byte[] diagnosticBytes = CandidateJson.write(diagnostic);

        final Path output = outputDirectory(request.outputDirectory(), request.worktreeId());
        final String stem = safeStem(request.semanticName());
        final Path candidatePath = output.resolve(stem + ".candidate.json");
        final Path reviewPath = output.resolve(stem + ".review.json");
        final Path diffPath = output.resolve(stem + ".diff.json");
        final Path diagnosticPath = output.resolve(stem + ".diagnostic.json");
        publishBundle(output, List.of(
            new GeneratedFile(candidatePath, candidateBytes),
            new GeneratedFile(reviewPath, reviewBytes),
            new GeneratedFile(diffPath, diffBytes),
            new GeneratedFile(diagnosticPath, diagnosticBytes)
        ));
        return new GeneratedBundle(candidatePath, candidateBytes, reviewPath, reviewBytes, diffPath, diffBytes,
            diagnosticPath, diagnosticBytes);
    }

    /**
     * Re-verifies an approved candidate and, only when explicitly asked to write, replaces the
     * mapping pack atomically under a lock.
     *
     * <p>Every check is repeated at apply time rather than trusted from generation: the review must
     * carry decision {@code APPROVED}, its recorded hash must match these exact candidate bytes,
     * and the artifact's file name must match the one the candidate was generated against. The
     * generated {@code diff.json} is never consulted — it is presentation only.
     *
     * <p>When {@code request.write()} is {@code false} the pack is left untouched and the result
     * reports the hash the write would have produced, so a dry run is a genuine no-op.
     *
     * @param request the candidate, review, artifact, and whether to actually write
     * @return whether the pack was written, plus the resulting pack digest
     * @throws DraftMappingException for any rejection, carrying a code such as
     *     {@code REVIEW_NOT_APPROVED}, {@code REVIEW_CANDIDATE_HASH_MISMATCH}, or
     *     {@code ARTIFACT_MISMATCH}
     */
    public ApplyResult apply(final ApplyRequest request) {
        final byte[] candidateBytes = readBytes(request.candidate(), "CANDIDATE_READ_FAILED");
        final JsonNode candidate = parse(candidateBytes, "CANDIDATE_JSON_INVALID");
        requireValidCandidate(candidate);
        final JsonNode review = parse(readBytes(request.review(), "REVIEW_READ_FAILED"), "REVIEW_JSON_INVALID");
        requireValidReview(review);
        if (!"APPROVED".equals(review.path("decision").asText())) {
            fail("REVIEW_NOT_APPROVED", "review decision must be APPROVED");
        }
        if (!sha256(candidateBytes).equals(review.path("candidateSha256").asText())) {
            fail("REVIEW_CANDIDATE_HASH_MISMATCH", "review does not approve these exact candidate bytes");
        }

        final Path artifact = checkedArtifact(request.artifact());
        if (!artifact.getFileName().toString().equals(candidate.at("/artifact/name").asText())) {
            fail("ARTIFACT_MISMATCH", "artifact file name does not match the generated candidate");
        }
        final Path packPath = resolveTracked(candidate.at("/target/pack").asText());
        final ApplyPreparation preparation = prepareApply(candidate, artifact, packPath);
        if (!request.write()) return new ApplyResult(false, preparation.resultHash());
        return writeUnderLock(candidate, artifact, packPath);
    }

    private Path checkedArtifact(final Path supplied) {
        final Path normalized = supplied.toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
                fail("ARTIFACT_NOT_REGULAR", "artifact must be a regular non-symlink file");
            }
            return normalized;
        } catch (SecurityException exception) {
            throw new DraftMappingException("ARTIFACT_NOT_REGULAR", "artifact could not be checked", exception);
        }
    }

    private Path resolveTracked(final String relative) {
        return resolveTracked(root, relative);
    }

    static Path resolveTracked(final Path root, final String relative) {
        if (relative == null || !relative.matches("cubism-ref/mapping-packs/draft/[^/]+\\.json")) {
            fail("PACK_PATH_INVALID", "pack path must be a draft mapping pack JSON file");
        }
        final Path supplied = Path.of(relative);
        final Path resolved = root.resolve(supplied).normalize();
        try {
            FileSafety.requireSafeRoot(root);
            FileSafety.requireExistingParentChain(root, resolved, "PACK_PATH_INVALID");
            if (Files.isSymbolicLink(resolved) || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
                fail("PACK_PATH_INVALID", "pack must be a regular non-symlink file");
            }
            if (!resolved.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(root.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                fail("PACK_PATH_INVALID", "pack real path escaped the worktree");
            }
            final Process process = new ProcessBuilder(
                "git", "-C", root.toString(), "ls-files", "--error-unmatch", "--", relative
            ).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            if (process.waitFor() != 0) fail("PACK_NOT_TRACKED", "mapping pack must be tracked by git");
            return resolved;
        } catch (DraftMappingException exception) {
            throw exception;
        } catch (IOException | InterruptedException | SecurityException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new DraftMappingException("PACK_PATH_INVALID", "mapping pack path could not be verified", exception);
        }
    }

    private void rejectStaticVerificationReference(final String semanticName) {
        final Path directory = root.resolve("cubism-ref/verification");
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            FileSafety.requireDirectoryNoLinks(directory, "STATIC_VERIFICATION_RECORD_INVALID");
            try (var files = Files.walk(directory)) {
                boolean referenced = false;
                for (Path path : files.toList()) {
                    if (Files.isSymbolicLink(path)) {
                        fail("STATIC_VERIFICATION_RECORD_INVALID", "verification directory tree must not contain symlinks");
                    }
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        fail("STATIC_VERIFICATION_RECORD_INVALID", "verification tree contains a non-regular entry");
                    }
                    if (!path.toString().endsWith(".json")) continue;
                    final JsonNode record = parse(
                        FileSafety.readAllBytesNoFollow(path, "STATIC_VERIFICATION_RECORD_INVALID"),
                        "STATIC_VERIFICATION_RECORD_INVALID");
                    if (!staticVerificationRecordValidator.validate(record, root.relativize(path).toString()).isEmpty()) {
                        fail("STATIC_VERIFICATION_RECORD_INVALID", "verification record failed schema validation");
                    }
                    for (JsonNode selector : record.path("selectors")) {
                        if (semanticName.equals(selector.path("mappingId").asText())) referenced = true;
                    }
                }
                if (referenced) fail("PACK_REFERENCED_BY_VERIFIED_STATIC", "mapping is referenced by VERIFIED_STATIC evidence");
            }
        } catch (DraftMappingException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw new DraftMappingException("STATIC_VERIFICATION_RECORD_INVALID", "could not safely scan verification records", exception);
        }
    }

    private ApplyPreparation prepareApply(final JsonNode candidate, final Path artifact, final Path packPath) {
        final long expectedArtifactSize = candidate.at("/artifact/size").asLong();
        final long candidateLimit = candidate.at("/scannerPolicy/maxArtifactBytes").asLong();
        if (expectedArtifactSize > policy.maxArtifactBytes() || expectedArtifactSize > candidateLimit) {
            fail("ARTIFACT_MISMATCH", "artifact size exceeds the active or recorded scan policy");
        }
        final FileSafety.Digest artifactDigest = FileSafety.digest(
            artifact, "ARTIFACT_READ_FAILED", expectedArtifactSize, "ARTIFACT_MISMATCH");
        if (artifactDigest.size() != expectedArtifactSize
            || !artifactDigest.sha256().equals(candidate.at("/artifact/sha256").asText())) {
            fail("ARTIFACT_MISMATCH", "artifact does not match the generated candidate");
        }
        final byte[] packBytes = readBytes(packPath, "PACK_READ_FAILED");
        if (!sha256(packBytes).equals(candidate.path("basePackSha256").asText())) {
            fail("PACK_BASE_HASH_MISMATCH", "mapping pack changed after candidate generation");
        }
        final JsonNode pack = parse(packBytes, "PACK_JSON_INVALID");
        requireValidPack(pack);
        final String semanticName = candidate.at("/target/semanticName").asText();
        final JsonNode currentEntry = uniqueEntry(pack, semanticName);
        if (!candidate.at("/before/kind").asText().equals(currentEntry.path("kind").asText())
            || !candidate.at("/before/runtime").asText().equals(currentEntry.path("runtime").asText())) {
            fail("PACK_BEFORE_MISMATCH", "current target no longer matches candidate before state");
        }
        rejectStaticVerificationReference(semanticName);

        final ExactJsonRuntimeReplacement.Replacement replacement = ExactJsonRuntimeReplacement.replace(
            packBytes, pack, semanticName, candidate.at("/before/runtime").asText(), candidate.at("/after/runtime").asText());
        final byte[] resultBytes = replacement.bytes();
        final JsonNode resultPack = parse(resultBytes, "RESULT_PACK_JSON_INVALID");
        requireValidPack(resultPack);
        ExactJsonRuntimeReplacement.verifyOnlyRuntimeChanged(
            packBytes, resultBytes, semanticName, replacement.before(), replacement.after());
        final String resultHash = sha256(resultBytes);
        if (!resultHash.equals(candidate.path("resultPackSha256").asText())) {
            fail("RESULT_PACK_HASH_MISMATCH", "candidate result hash does not match the exact replacement bytes");
        }
        return new ApplyPreparation(packBytes, resultBytes, resultHash);
    }

    private ApplyResult writeUnderLock(final JsonNode candidate, final Path artifact, final Path target) {
        final Path lockPath = target.resolveSibling("." + target.getFileName() + ".mapping-review.lock");
        try (LockAcquirer.AcquiredLock ignored = lockAcquirer.acquire(lockPath)) {
            final Path lockedTarget = resolveTracked(candidate.at("/target/pack").asText());
            final ApplyPreparation locked = prepareApply(candidate, artifact, lockedTarget);
            atomicWrite(target, artifact, candidate.at("/artifact/sha256").asText(),
                candidate.path("basePackSha256").asText(), locked.resultBytes(), locked.resultHash());
            return new ApplyResult(true, locked.resultHash());
        } catch (DraftMappingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new DraftMappingException("APPLY_LOCK_FAILED", "could not lock mapping pack for replacement", exception);
        }
    }

    private void atomicWrite(
        final Path target,
        final Path artifact,
        final String expectedArtifactHash,
        final String expectedBaseHash,
        final byte[] bytes,
        final String expectedResultHash
    ) {
        final Path parent = target.getParent();
        final String prefix = "." + target.getFileName() + ".mapping-review-";
        Path temporary = null;
        boolean moved = false;
        DraftMappingException failure = null;
        try {
            final Set<PosixFilePermission> permissions = posixPermissions(target);
            temporary = Files.createTempFile(parent, prefix, ".tmp");
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            if (permissions != null) Files.setPosixFilePermissions(temporary, permissions);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            atomicReplacement.replace(
                temporary, target, artifact, expectedArtifactHash, expectedBaseHash);
            moved = true;
            final FileSafety.Digest written = FileSafety.digest(target, "PACK_READ_FAILED");
            if (!written.sha256().equals(expectedResultHash)) {
                fail("RESULT_PACK_POST_WRITE_MISMATCH", "written mapping pack does not match the reviewed result bytes");
            }
        } catch (DraftMappingException exception) {
            failure = exception;
            throw exception;
        } catch (IOException exception) {
            failure = new DraftMappingException("ATOMIC_WRITE_FAILED", "could not atomically replace mapping pack", exception);
            throw failure;
        } finally {
            if (!moved && temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanup) {
                    if (failure != null) failure.addSuppressed(cleanup);
                }
            }
        }
    }

    private static Set<PosixFilePermission> posixPermissions(final Path target) {
        try {
            return Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException exception) {
            return null;
        } catch (IOException exception) {
            throw new DraftMappingException("ATOMIC_WRITE_FAILED", "could not read mapping pack permissions", exception);
        }
    }

    private ObjectNode policyJson() {
        final ObjectNode node = CandidateJson.MAPPER.createObjectNode();
        node.put("maxArtifactBytes", policy.maxArtifactBytes());
        node.put("maxEntries", policy.maxEntries());
        node.put("maxClassEntries", policy.maxClassEntries());
        node.put("maxEntryBytes", policy.maxEntryBytes());
        node.put("maxExpandedBytes", policy.maxExpandedBytes());
        return node;
    }

    private static void keepDraft(final ObjectNode entry) {
        entry.put("status", "DRAFT");
        entry.put("verifiedBy", "none");
        entry.putNull("verifiedAt");
    }

    /**
     * Derived, deterministic reviewer presentation only. apply() does not read or trust this file.
     */
    private static ObjectNode diff(
        final String targetPack,
        final String semanticName,
        final ExactJsonRuntimeReplacement.Replacement replacement,
        final byte[] candidateBytes
    ) {
        final ObjectNode node = CandidateJson.MAPPER.createObjectNode();
        node.put("format", "turboism.mapping.update.diff");
        node.put("schemaVersion", 1);
        node.put("candidateSha256", sha256(candidateBytes));
        node.putObject("target").put("pack", targetPack).put("semanticName", semanticName);
        node.putArray("changes")
            .addObject()
            .put("path", "entries[semanticName=" + semanticName + "].runtime")
            .put("before", replacement.before())
            .put("after", replacement.after());
        return node;
    }

    private Path outputDirectory(final Path supplied, final String worktreeId) {
        if (worktreeId == null || !worktreeId.matches("[a-z][a-z0-9-]{2,63}")) {
            fail(worktreeId == null ? "WORKTREE_ID_MISSING" : "WORKTREE_ID_INVALID", "a valid worktree ID is required");
        }
        final Path output = supplied == null
            ? root.resolve("build/worktree").resolve(worktreeId).resolve("mapping-review")
            : (supplied.isAbsolute() ? supplied : root.resolve(supplied)).toAbsolutePath().normalize();
        if (!output.startsWith(root) || output.equals(root)) {
            fail("OUTPUT_PATH_INVALID", "output directory must remain below the worktree root");
        }
        try {
            FileSafety.requireSafeRoot(root);
            FileSafety.requireExistingParentChain(root, output.resolve("bundle.marker"), "OUTPUT_PATH_INVALID");
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(output) || !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS))) {
                fail("OUTPUT_PATH_INVALID", "output path must be a real directory");
            }
            return output;
        } catch (SecurityException exception) {
            throw new DraftMappingException("OUTPUT_PATH_INVALID", "output directory could not be verified", exception);
        }
    }

    private static ObjectNode successDiagnostic(
        final String worktreeId,
        final String semanticName,
        final Instant createdAt
    ) {
        final ObjectNode diagnostic = CandidateJson.MAPPER.createObjectNode();
        diagnostic.put("format", "turboism.diagnostic.report");
        diagnostic.put("schemaVersion", 1);
        diagnostic.put("createdAt", createdAt.toString());
        diagnostic.put("worktreeId", worktreeId);
        diagnostic.putArray("problems").addObject()
            .put("code", "MAPPING_UPDATE_CANDIDATE_GENERATED")
            .put("severity", "INFO")
            .put("message", "Generated one DRAFT mapping update candidate for human review")
            .put("path", "mapping:" + semanticName);
        return diagnostic;
    }

    private void publishBundle(final Path output, final List<GeneratedFile> files) {
        Path temporary = null;
        final List<FileSafety.PublicationOwnership> published = new ArrayList<>();
        try {
            Files.createDirectories(output);
            FileSafety.requireDirectoryNoLinks(output, "OUTPUT_PATH_INVALID");
            for (GeneratedFile file : files) {
                if (Files.exists(file.path(), LinkOption.NOFOLLOW_LINKS)) {
                    fail("GENERATED_FILE_EXISTS", "generated review file already exists: " + file.path().getFileName());
                }
            }
            temporary = Files.createTempDirectory(output.getParent(), ".mapping-review-bundle-", privateDirectoryAttributes());
            final List<Path> staged = new ArrayList<>();
            for (GeneratedFile file : files) {
                final Path stagedFile = temporary.resolve(file.path().getFileName());
                Files.write(stagedFile, file.bytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                staged.add(stagedFile);
            }
            for (int index = 0; index < files.size(); index++) {
                FileSafety.copyCreateNewNoFollow(
                    staged.get(index), files.get(index).path(), published, "GENERATED_FILE_WRITE_FAILED");
            }
        } catch (DraftMappingException exception) {
            cleanupPublished(published, exception);
            throw exception;
        } catch (IOException | SecurityException exception) {
            final DraftMappingException failure = new DraftMappingException(
                Files.exists(output, LinkOption.NOFOLLOW_LINKS) ? "GENERATED_FILE_WRITE_FAILED" : "OUTPUT_PATH_INVALID",
                "could not safely publish generated review bundle", exception);
            cleanupPublished(published, failure);
            throw failure;
        } finally {
            deleteSnapshotDirectory(temporary);
        }
    }

    static void cleanupPublished(
        final List<FileSafety.PublicationOwnership> published,
        final DraftMappingException failure
    ) {
        for (FileSafety.PublicationOwnership ownership : published) {
            try {
                final BasicFileAttributes current = Files.readAttributes(
                    ownership.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (ownership.fileKey() == null || current.fileKey() == null) {
                    failure.addSuppressed(new IOException(
                        "cleanup retained publication because filesystem identity is unavailable: "
                            + ownership.path().getFileName()));
                    continue;
                }
                if (!ownership.fileKey().equals(current.fileKey())) {
                    failure.addSuppressed(new IOException(
                        "cleanup retained publication because pathname ownership changed: "
                            + ownership.path().getFileName()));
                    continue;
                }
                final BasicFileAttributes owned = ownership.attributes();
                if (owned.size() != current.size()
                    || !owned.lastModifiedTime().equals(current.lastModifiedTime())) {
                    failure.addSuppressed(new IOException(
                        "cleanup retained publication because pathname contents changed: "
                            + ownership.path().getFileName()));
                    continue;
                }
                Files.delete(ownership.path());
            } catch (java.nio.file.NoSuchFileException ignored) {
                // The owned publication is already absent.
            } catch (IOException | SecurityException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    private void requireValidPack(final JsonNode node) {
        final var errors = packValidator.validate(node);
        if (!errors.isEmpty()) fail("PACK_VALIDATION_FAILED", errors.toString());
    }

    private void requireValidCandidate(final JsonNode node) {
        final var errors = candidateValidator.validate(node);
        if (!errors.isEmpty()) fail("CANDIDATE_VALIDATION_FAILED", errors.toString());
    }

    private void requireValidReview(final JsonNode node) {
        final var errors = reviewValidator.validate(node);
        if (!errors.isEmpty()) fail("REVIEW_VALIDATION_FAILED", errors.toString());
    }

    private static JsonNode uniqueEntry(final JsonNode pack, final String semanticName) {
        final List<JsonNode> matches = new ArrayList<>();
        for (JsonNode entry : pack.path("entries")) {
            if (semanticName.equals(entry.path("semanticName").asText())) matches.add(entry);
        }
        if (matches.size() != 1) fail("PACK_SEMANTIC_NOT_UNIQUE", "semanticName must select exactly one entry");
        return matches.get(0);
    }

    private static JsonNode parse(final byte[] bytes, final String code) {
        return StrictJson.read(bytes, code);
    }

    private static byte[] readBytes(final Path path, final String code) {
        return FileSafety.readAllBytesNoFollow(path.toAbsolutePath().normalize(), code);
    }

    private static FileAttribute<?>[] privateDirectoryAttributes() {
        try {
            return new FileAttribute<?>[]{java.nio.file.attribute.PosixFilePermissions.asFileAttribute(Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            ))};
        } catch (UnsupportedOperationException exception) {
            return new FileAttribute<?>[0];
        }
    }

    private static void deleteSnapshotDirectory(final Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup; generation failure/success remains authoritative.
        }
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String safeStem(final String semanticName) {
        return semanticName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void fail(final String code, final String message) {
        throw new DraftMappingException(code, message);
    }

    private record ApplyPreparation(byte[] baseBytes, byte[] resultBytes, String resultHash) {
        ApplyPreparation {
            baseBytes = baseBytes.clone();
            resultBytes = resultBytes.clone();
        }
        @Override public byte[] baseBytes() { return baseBytes.clone(); }
        @Override public byte[] resultBytes() { return resultBytes.clone(); }
    }

    private record GeneratedFile(Path path, byte[] bytes) {
        GeneratedFile { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
