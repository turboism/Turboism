package dev.turboism.exportsettings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.sdk.cubism.core.MocData;
import dev.turboism.sdk.cubism.core.MocLoader;
import dev.turboism.sdk.cubism.core.OwnedMoc;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
     * @param expectedParameterIds the copy's parameter ID set; staged must equal it
     * @param expectedPartIds the copy's part ID set; staged must equal it
     */
    public Validation validate(
        final File stagedPick,
        final List<String> reportedPaths,
        final Set<String> expectedDrawableIds,
        final Set<String> expectedParameterIds,
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
                    path, expectedDrawableIds, expectedParameterIds, expectedPartIds);
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
     * parameter/part IDs exactly preserved, zero deformers left after flatten.
     */
    private MocFailure validateMoc(
        final Path path,
        final Set<String> expectedDrawableIds,
        final Set<String> expectedParameterIds,
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
                    if (!parameterIds.equals(expectedParameterIds)) {
                        return new MocFailure(
                            "protected-export.moc3-parameter-ids",
                            setDiffDetail(parameterIds, expectedParameterIds));
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
    private static String bounded(final Set<String> ids) {
        final StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (String id : ids) {
            if (out.length() > 140) {
                out.append(",+").append(ids.size()).append("]");
                return out.toString();
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(id == null ? "null" : id);
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
     * <p>Files are first copied into a sibling scratch directory of the destination and
     * then moved into place, so a mid-publish failure leaves at most an orphaned scratch
     * directory — never half-copied target names. The scratch directory is removed on
     * success and on failure.</p>
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
        Objects.requireNonNull(stagedPick, "stagedPick");
        Objects.requireNonNull(stagedFiles, "stagedFiles");
        Objects.requireNonNull(realPick, "realPick");
        final Path destinationParent = realPick.toPath().toAbsolutePath().normalize()
            .getParent();
        if (destinationParent == null || !Files.isDirectory(destinationParent)
            || !Files.isWritable(destinationParent)) {
            throw new IOException("protected-export destination directory is not writable");
        }
        final Path scratch = Files.createTempDirectory(
            destinationParent, ".turboism-publish-");
        final List<Path> scratchCopies = new ArrayList<>();
        try {
            for (Path staged : stagedFiles) {
                final Path stagedParent = stagedPick.toPath().toAbsolutePath().normalize()
                    .getParent();
                final Path relative = stagedParent.relativize(
                    staged.toAbsolutePath().normalize());
                final Path copy = scratch.resolve(relative);
                Files.createDirectories(copy.getParent());
                Files.copy(staged, copy, StandardCopyOption.REPLACE_EXISTING);
                scratchCopies.add(copy);
            }
            final List<Path> published = new ArrayList<>();
            for (Path copy : scratchCopies) {
                final Path relative = scratch.relativize(copy);
                final Path target = destinationParent.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.move(copy, target, StandardCopyOption.REPLACE_EXISTING);
                published.add(target);
            }
            return List.copyOf(published);
        } finally {
            deleteRecursively(scratch);
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
                Files.deleteIfExists(path);
            }
        }
    }
}
