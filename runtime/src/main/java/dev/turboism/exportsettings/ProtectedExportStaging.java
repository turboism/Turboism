package dev.turboism.exportsettings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.sdk.cubism.core.MocData;
import dev.turboism.sdk.cubism.core.MocLoader;
import dev.turboism.sdk.cubism.core.OwnedMoc;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Staged-output validation and all-or-nothing publication for protected export.
 *
 * <p>The native worker writes wholly into a task-owned staging directory; this class is
 * the publication boundary. Validation requires every reported output to be a non-empty
 * regular file under the staged pick's parent directory, every {@code .moc3} to load
 * through the verified Core runtime, and every {@code *.model3.json} FileReferences
 * entry to resolve inside staging. Only after validation does {@link #publish} copy the
 * complete set to the user's real destination.</p>
 */
public final class ProtectedExportStaging {

    /**
     * Expected per-parameter contract of the exported model. Captured from the bound
     * copy's source census before any mutation; the staged moc3 must reproduce every
     * field, so a flatten or export that altered a parameter's evaluable contract
     * (range, default, repeat flag, or the baked key positions) is rejected rather
     * than published.
     */
    public record ParameterExpectation(
        String id,
        float minimumValue,
        float maximumValue,
        float defaultValue,
        Boolean repeat,
        List<Float> keys
    ) {
        public ParameterExpectation {
            id = Objects.requireNonNull(id, "id");
            keys = keys == null ? List.of() : List.copyOf(keys);
        }
    }

    /** Move primitive — {@code Files.move} in production, replaceable in tests. */
    interface MoveOp {
        void move(Path source, Path target) throws IOException;
    }

    /** Outcome of a staged-output validation. */
    public record Validation(
        boolean valid,
        String failureKey,
        String failureDetail,
        List<Path> stagedFiles
    ) {
        static Validation ok(final List<Path> files) {
            return new Validation(true, null, null, List.copyOf(files));
        }

        static Validation rejected(final String failureKey) {
            return rejected(failureKey, null);
        }

        static Validation rejected(final String failureKey,
            final String failureDetail
        ) {
            return new Validation(false, failureKey, failureDetail, List.of());
        }
    }

    private final MocLoader mocLoader;
    private final ObjectMapper json = new ObjectMapper();

    public ProtectedExportStaging(final MocLoader mocLoader) {
        this.mocLoader = mocLoader; // may be null; validated lazily when a moc3 is staged
    }

    /**
     * Validates the native worker's staged output.
     *
     * <p>Beyond file integrity, the loaded moc3 must prove the session's identity
     * contract: every exported drawable ID is one of the planned obfuscation tokens,
     * parameter and part IDs exactly match the copy's census, and no deformer survives
     * the flatten pass into the output.</p>
     *
     * @param stagedPick the redirected staging {@code File} the worker wrote beside
     * @param reportedPaths absolute output paths reported by the native callback
     * @param expectedDrawableIds planned obfuscation tokens; staged drawable IDs must
     *     be a subset (unplaced ArtMeshes are legitimately dropped by the exporter)
     * @param expectedParameters parameter ID → expected contract (range, default,
     *     repeat, baked key positions); staged parameters must match every field
     * @param expectedPartIds the copy's part ID set; staged must equal it
     */
    public Validation validate(
        final File stagedPick,
        final List<String> reportedPaths,
        final Set<String> expectedDrawableIds,
        final Map<String, ParameterExpectation> expectedParameters,
        final Set<String> expectedPartIds
    ) {
        if (stagedPick == null || reportedPaths == null || reportedPaths.isEmpty()) {
            return Validation.rejected("protected-export.staging-empty");
        }
        final Path stagedParent = stagedPick.toPath().toAbsolutePath().normalize()
            .getParent();
        if (stagedParent == null) {
            return Validation.rejected("protected-export.staging-root-missing");
        }

        final Set<Path> staged = new LinkedHashSet<>();
        for (String reported : reportedPaths) {
            final Path path;
            try {
                path = Path.of(reported).toAbsolutePath().normalize();
            } catch (RuntimeException failure) {
                return Validation.rejected("protected-export.staging-path-invalid");
            }
            if (!path.startsWith(stagedParent)) {
                return Validation.rejected("protected-export.staging-path-escape");
            }
            try {
                if (!Files.isRegularFile(path) || Files.size(path) <= 0L) {
                    return Validation.rejected("protected-export.staging-file-missing");
                }
            } catch (IOException failure) {
                return Validation.rejected("protected-export.staging-file-unreadable");
            }
            staged.add(path);
        }

        boolean sawMoc = false;
        for (Path path : staged) {
            final String name = path.getFileName().toString();
            if (name.endsWith(".moc3")) {
                sawMoc = true;
                final MocFailure failure = validateMoc(
                    path, expectedDrawableIds, expectedParameters, expectedPartIds);
                if (failure != null) {
                    return Validation.rejected(failure.key(), failure.detail());
                }
            } else if (name.endsWith(".model3.json")) {
                if (!validateModelJson(path, staged)) {
                    return Validation.rejected("protected-export.model3-invalid");
                }
            }
        }
        if (!sawMoc) {
            return Validation.rejected("protected-export.moc3-missing");
        }
        return Validation.ok(new ArrayList<>(staged));
    }

    /**
     * Loads the staged moc3 through the verified Core runtime and asserts the session's
     * identity contract materialized: drawable IDs ⊆ planned obfuscation tokens,
     * parameter/part IDs exactly preserved, zero deformers left after flatten, and
     * every staged parameter's evaluable contract (range, default, repeat, baked key
     * positions) identical to the pre-mutation census.
     */
    private MocFailure validateMoc(
        final Path path,
        final Set<String> expectedDrawableIds,
        final Map<String, ParameterExpectation> expectedParameters,
        final Set<String> expectedPartIds
    ) {
        if (mocLoader == null) {
            return new MocFailure("protected-export.moc3-loader-absent", null);
        }
        try {
            final byte[] bytes = Files.readAllBytes(path);
            try (OwnedMoc moc = mocLoader.load(MocData.copyOf(bytes))) {
                if (moc == null) {
                    return new MocFailure("protected-export.moc3-null", null);
                }
                try (var model = moc.instantiateModel()) {
                    if (model == null) {
                        return new MocFailure(
                            "protected-export.moc3-model-null", null);
                    }
                    final Set<String> drawableIds = model.drawables().stream()
                        .map(d -> d.id())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                    if (!expectedDrawableIds.containsAll(drawableIds)) {
                        return new MocFailure(
                            "protected-export.moc3-drawable-ids",
                            "unexpected=" + bounded(diff(drawableIds,
                                expectedDrawableIds)));
                    }
                    final Set<String> parameterIds = model.parameters().stream()
                        .map(p -> p.id())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                    if (!parameterIds.equals(expectedParameters.keySet())) {
                        return new MocFailure(
                            "protected-export.moc3-parameter-ids",
                            setDiffDetail(parameterIds, expectedParameters.keySet()));
                    }
                    final MocFailure behavior = validateParameterContracts(
                        model.parameters(), expectedParameters);
                    if (behavior != null) {
                        return behavior;
                    }
                    final Set<String> partIds = model.parts().stream()
                        .map(p -> p.id())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                    if (!partIds.equals(expectedPartIds)) {
                        return new MocFailure(
                            "protected-export.moc3-part-ids",
                            setDiffDetail(partIds, expectedPartIds));
                    }
                    if (!model.deformers().isEmpty()) {
                        final Set<String> deformerIds = model.deformers().stream()
                            .map(d -> d.id())
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                        return new MocFailure(
                            "protected-export.moc3-deformers-remain",
                            "remaining=" + bounded(deformerIds));
                    }
                    return null;
                }
            }
        } catch (Throwable failure) {
            final String message = failure.getMessage();
            return new MocFailure(
                "protected-export.moc3-invalid",
                failure.getClass().getSimpleName() + ": "
                    + (message == null
                        ? ""
                        : message.substring(0, Math.min(160, message.length()))));
        }
    }

    /**
     * Per-parameter behavior check: the staged moc3 must carry the same evaluable
     * contract the copy's source census recorded — range, default, repeat flag and
     * the baked key positions flatten produced. Exact float equality is required;
     * any drift means the published model would not behave like the authored one.
     * Parameters without recorded key positions keep the contract check only — an
     * unbound parameter may legitimately serialize a different implicit key list.
     */
    private MocFailure validateParameterContracts(
        final List<dev.turboism.sdk.cubism.core.OwnedParameter> stagedParameters,
        final Map<String, ParameterExpectation> expectedParameters
    ) {
        for (var staged : stagedParameters) {
            final ParameterExpectation expected = expectedParameters.get(staged.id());
            if (expected == null) {
                return new MocFailure(
                    "protected-export.moc3-parameter-contract",
                    "unplanned parameter id=" + staged.id());
            }
            final StringBuilder drift = new StringBuilder();
            if (Float.compare(staged.minimumValue(), expected.minimumValue()) != 0) {
                drift.append("min ").append(staged.minimumValue())
                    .append("!=").append(expected.minimumValue()).append(';');
            }
            if (Float.compare(staged.maximumValue(), expected.maximumValue()) != 0) {
                drift.append("max ").append(staged.maximumValue())
                    .append("!=").append(expected.maximumValue()).append(';');
            }
            if (Float.compare(staged.defaultValue(), expected.defaultValue()) != 0) {
                drift.append("default ").append(staged.defaultValue())
                    .append("!=").append(expected.defaultValue()).append(';');
            }
            if (expected.repeat() != null
                && staged.repeat().isPresent()
                && !staged.repeat().orElseThrow().equals(expected.repeat())) {
                drift.append("repeat ").append(staged.repeat().orElseThrow())
                    .append("!=").append(expected.repeat()).append(';');
            }
            if (!expected.keys().isEmpty()) {
                final Set<Float> stagedKeys = new LinkedHashSet<>(staged.keyValues());
                final Set<Float> expectedKeys = new LinkedHashSet<>(expected.keys());
                if (!stagedKeys.equals(expectedKeys)) {
                    drift.append("keys unexpected=")
                        .append(bounded(floatDiff(stagedKeys, expectedKeys)))
                        .append(",missing=")
                        .append(bounded(floatDiff(expectedKeys, stagedKeys)))
                        .append(';');
                }
            }
            if (drift.length() > 0) {
                final String detail = "id=" + staged.id() + " " + drift;
                return new MocFailure(
                    "protected-export.moc3-parameter-contract",
                    detail.length() > 160 ? detail.substring(0, 160) : detail);
            }
        }
        return null;
    }

    /** {@code actual \ expected} for float key sets. */
    private static Set<Float> floatDiff(final Set<Float> actual, final Set<Float> expected) {
        final Set<Float> out = new LinkedHashSet<>(actual);
        expected.forEach(out::remove);
        return out;
    }

    /** Bounded moc3 rejection: a stable key plus a one-line cause detail. */
    private record MocFailure(String key, String detail) {
    }

    /** {@code actual \ expected} preserving iteration order. */
    private static Set<String> diff(final Set<String> actual, final Set<String> expected) {
        final Set<String> out = new LinkedHashSet<>(actual);
        out.removeAll(expected);
        return out;
    }

    /** Both directions of a set mismatch, bounded for the evidence line. */
    private static String setDiffDetail(final Set<String> actual, final Set<String> expected) {
        return "unexpected=" + bounded(diff(actual, expected))
            + ",missing=" + bounded(diff(expected, actual));
    }

    /** Joins ids into a bounded {@code [a,b,...]} rendering. */
    private static String bounded(final Set<?> ids) {
        final StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (Object id : ids) {
            if (out.length() > 140) {
                out.append(",+").append(ids.size()).append("]");
                return out.toString();
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(id == null ? "null" : id.toString());
        }
        return out.append(']').toString();
    }

    /**
     * Every {@code FileReferences} entry in a {@code model3.json} must name a file that
     * was itself staged — a reference outside the staged set means the output is
     * incomplete or escaped the task-owned directory.
     */
    private boolean validateModelJson(final Path modelJson, final Set<Path> staged) {
        try {
            final JsonNode root = json.readTree(Files.readAllBytes(modelJson));
            final JsonNode references = root.get("FileReferences");
            if (references == null || !references.isObject()) {
                return false;
            }
            final Path parent = modelJson.getParent();
            final List<String> names = new ArrayList<>();
            references.fields().forEachRemaining(entry -> collectNames(entry.getValue(), names));
            for (String name : names) {
                final Path resolved = parent.resolve(name).toAbsolutePath().normalize();
                if (!staged.contains(resolved)) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static void collectNames(final JsonNode node, final List<String> names) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            names.add(node.asText());
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectNames(child, names));
        }
    }

    /**
     * Publishes validated staged files to the user's real destination.
     *
     * <p>Publication is all-or-nothing. Every staged file is copied into a sibling
     * scratch directory, every target is preflighted before the first move (an
     * existing non-regular target or an escaping path rejects the whole publish
     * without touching the destination), then each move swaps the previous target
     * aside into the scratch backup before the new file lands. Any failure rolls
     * every touched target back to its prior state — an existing target keeps its
     * old bytes, a previously-absent target is removed again, and directories the
     * publish itself created are removed when they end up empty. The scratch
     * directory is removed on success and on failure; a scratch-cleanup failure on
     * the failure path is recorded as suppressed on the publish exception rather
     * than silently masked.</p>
     *
     * @param stagedPick the staged pick (basename carried to the real destination)
     * @param stagedFiles validated staged files
     * @param realPick the user's originally picked destination {@code File}
     * @return the published destination files
     */
    public List<Path> publish(
        final File stagedPick,
        final List<Path> stagedFiles,
        final File realPick
    ) throws IOException {
        return publish(stagedPick, stagedFiles, realPick,
            (source, target) -> Files.move(source, target,
                StandardCopyOption.REPLACE_EXISTING));
    }

    List<Path> publish(
        final File stagedPick,
        final List<Path> stagedFiles,
        final File realPick,
        final MoveOp moveOp
    ) throws IOException {
        Objects.requireNonNull(stagedPick, "stagedPick");
        Objects.requireNonNull(stagedFiles, "stagedFiles");
        Objects.requireNonNull(realPick, "realPick");
        final Path destinationParent = realPick.toPath().toAbsolutePath().normalize()
            .getParent();
        if (destinationParent == null || !Files.isDirectory(destinationParent)
            || !Files.isWritable(destinationParent)) {
            throw new IOException("protected-export destination directory is not writable");
        }
        final Path stagedParent = stagedPick.toPath().toAbsolutePath().normalize()
            .getParent();
        final Path scratch = Files.createTempDirectory(
            destinationParent, ".turboism-publish-");
        final Path incoming = scratch.resolve("incoming");
        final Path backups = scratch.resolve("backups");
        final List<Path> placed = new ArrayList<>();
        final List<Path> restore = new ArrayList<>();
        final List<Path> createdDirs = new ArrayList<>();
        Throwable failure = null;
        try {
            final List<Path> copies = new ArrayList<>();
            final List<Path> targets = new ArrayList<>();
            for (Path staged : stagedFiles) {
                final Path relative = stagedParent.relativize(
                    staged.toAbsolutePath().normalize());
                final Path copy = incoming.resolve(relative);
                Files.createDirectories(copy.getParent());
                Files.copy(staged, copy, StandardCopyOption.REPLACE_EXISTING);
                copies.add(copy);
                targets.add(targetFor(destinationParent, relative));
            }
            // Preflight every target before the first move: a single unusable
            // destination aborts the publish with the destination untouched.
            for (Path target : targets) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(
                        "protected-export destination is not a regular file: " + target);
                }
            }
            for (int i = 0; i < copies.size(); i++) {
                final Path copy = copies.get(i);
                final Path target = targets.get(i);
                createMissingParents(target.getParent(), createdDirs);
                Path backup = null;
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    backup = backups.resolve(incoming.relativize(copy));
                    Files.createDirectories(backup.getParent());
                    moveOp.move(target, backup);
                }
                try {
                    moveOp.move(copy, target);
                } catch (IOException | RuntimeException | Error moveFailure) {
                    restoreTarget(moveOp, target, backup, moveFailure);
                    throw moveFailure;
                }
                placed.add(target);
                if (backup != null) {
                    restore.add(backup);
                    restore.add(target);
                }
            }
        } catch (Throwable publishFailure) {
            failure = publishFailure;
            rollback(moveOp, placed, restore, createdDirs, failure);
        }
        try {
            deleteRecursively(scratch);
        } catch (Throwable cleanup) {
            if (failure == null) {
                throw cleanup;
            }
            failure.addSuppressed(cleanup);
        }
        if (failure != null) {
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IOException("protected-export publish failed", failure);
        }
        return List.copyOf(placed);
    }

    /** Resolves a staged relative path to a destination target; escapes reject. */
    private static Path targetFor(final Path destinationParent, final Path relative)
        throws IOException {
        final Path target = destinationParent.resolve(relative).normalize();
        if (!target.startsWith(destinationParent)) {
            throw new IOException(
                "protected-export publish target escapes destination: " + relative);
        }
        return target;
    }

    /**
     * Creates {@code parent} and any missing ancestors, recording the directories
     * this publish created so a rollback can remove the ones it left empty.
     */
    private static void createMissingParents(
        final Path parent,
        final List<Path> createdDirs
    ) throws IOException {
        if (parent == null || Files.isDirectory(parent)) {
            return;
        }
        final List<Path> missing = new ArrayList<>();
        for (Path current = parent; current != null && !Files.exists(current);
             current = current.getParent()) {
            missing.add(0, current);
        }
        Files.createDirectories(parent);
        createdDirs.addAll(missing);
    }

    /**
     * Restores one target after its new file failed to land: when the prior entry was
     * moved aside, move it back; when the new file partially appeared, remove it.
     */
    private static void restoreTarget(
        final MoveOp moveOp,
        final Path target,
        final Path backup,
        final Throwable failure
    ) {
        try {
            deleteIfExists(target);
        } catch (Throwable cleanup) {
            failure.addSuppressed(cleanup);
        }
        if (backup != null) {
            try {
                moveOp.move(backup, target);
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    /**
     * Unwinds a failed publish: every target that received a new file loses it again,
     * every moved-aside original comes back, and directories this publish created are
     * removed while they are empty. Rollback failures are suppressed onto the primary
     * failure so a damaged destination is never reported as cleanly untouched.
     */
    private static void rollback(
        final MoveOp moveOp,
        final List<Path> placed,
        final List<Path> restore,
        final List<Path> createdDirs,
        final Throwable failure
    ) {
        for (int i = placed.size() - 1; i >= 0; i--) {
            try {
                deleteIfExists(placed.get(i));
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
        for (int i = restore.size() - 2; i >= 0; i -= 2) {
            final Path backup = restore.get(i);
            final Path target = restore.get(i + 1);
            try {
                moveOp.move(backup, target);
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
        for (int i = createdDirs.size() - 1; i >= 0; i--) {
            try {
                Files.delete(createdDirs.get(i));
            } catch (DirectoryNotEmptyException foreign) {
                // Another writer put content there — not publish-owned residue.
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    /** Best-effort recursive delete used for staging and publish scratch cleanup. */
    public static void deleteRecursively(final Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            final List<Path> paths = walk.sorted((a, b) -> b.compareTo(a)).toList();
            for (Path path : paths) {
                deleteIfExists(path);
            }
        }
    }

    /**
     * Deletes a single entry, tolerating the read-only attribute the editor puts
     * on saved copies — a Windows JVM refuses to delete read-only files with
     * {@link AccessDeniedException}. Clears the attribute up front when the
     * entry is not writable, and retries once on a denied delete.
     */
    public static boolean deleteIfExists(final Path path) throws IOException {
        if (!Files.exists(path)) {
            return false;
        }
        if (!Files.isWritable(path)) {
            path.toFile().setWritable(true);
        }
        try {
            return Files.deleteIfExists(path);
        } catch (AccessDeniedException denied) {
            if (!path.toFile().setWritable(true)) {
                throw denied;
            }
            return Files.deleteIfExists(path);
        }
    }
}
