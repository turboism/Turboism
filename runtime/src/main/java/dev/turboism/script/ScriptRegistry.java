package dev.turboism.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.sdk.script.ScriptDescriptor;
import dev.turboism.sdk.script.ScriptId;
import dev.turboism.sdk.script.ScriptLanguage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Bounded discovery for schema-2 scripts under {@code <turboism.home>/scripts}.
 * Source bodies remain lazy; discovery records metadata and the manifest-pinned
 * digest that execution must match.
 */
class ScriptRegistry {

    private static final int MAX_SCRIPTS = 256;
    private static final int MAX_DIRECTORY_ENTRIES = 4096;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    // Worst-case JSON escaping expands one source byte to six protocol chars.
    // 384 KiB leaves room for the maximum argument map inside the 4 MiB RUN frame.
    private static final int MAX_SOURCE_BYTES = 384 * 1024;
    private static final Set<String> MANIFEST_FIELDS = Set.of(
        "schemaVersion", "id", "name", "version", "language", "entry", "sourceSha256", "permissions"
    );
    private static final Set<String> KNOWN_PERMISSIONS = Set.of(
        "turboism.ui.menu", "turboism.ui.toolbar", "turboism.ui.palette",
        "turboism.cubism.project.read", "turboism.cubism.model.read", "turboism.cubism.model.write",
        "turboism.cubism.model.observe", "turboism.cubism.model.intercept",
        "turboism.cubism.parameter.read", "turboism.cubism.mesh.read",
        "turboism.cubism.recent-file.read", "turboism.file.read", "turboism.file.write",
        "turboism.network.fetch", "turboism.process.run", "turboism.action.register",
        "turboism.ui.menu.contribute", "turboism.ui.toolbar.main.contribute",
        "turboism.ui.toolbar.palette.contribute", "turboism.ui.context-menu.contribute",
        "turboism.ui.context-source.read", "turboism.ui.overlay.contribute",
        "turboism.ui.viewport.read", "turboism.ui.recent-preview.contribute",
        "turboism.ui.dialog.contribute", "turboism.ui.dialog.automate",
        "turboism.ui.panel.contribute", "turboism.ui.file-chooser.request",
        "turboism.ui.status.notify", "turboism.ui.appearance.modify",
        "turboism.ui.toolbar.contribute", "turboism.config.plugin.read",
        "turboism.config.plugin.write", "turboism.event.subscribe", "turboism.event.publish",
        "turboism.performance.stats.read", "turboism.host.unsafe"
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path confinementRoot;
    private final Path scriptsRoot;
    private final Consumer<String> diagnostics;
    private final FileOpener fileOpener;

    ScriptRegistry(final Path turboismHome, final Consumer<String> diagnostics) {
        this(turboismHome, diagnostics, path -> Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS));
    }

    ScriptRegistry(
        final Path turboismHome,
        final Consumer<String> diagnostics,
        final FileOpener fileOpener
    ) {
        final Path home = Objects.requireNonNull(turboismHome, "turboismHome")
            .toAbsolutePath().normalize();
        this.confinementRoot = home;
        this.scriptsRoot = home.resolve("scripts").normalize();
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.fileOpener = Objects.requireNonNull(fileOpener, "fileOpener");
    }

    @FunctionalInterface
    interface FileOpener {
        InputStream open(Path path) throws IOException;
    }

    List<InstalledScript> discover() {
        final DirectoryIdentity root;
        try {
            root = directoryIdentity(scriptsRoot);
        } catch (IOException | RuntimeException invalid) {
            return List.of();
        }
        final PriorityQueue<Path> candidates = new PriorityQueue<>(
            Comparator.comparing((Path candidate) -> candidate.getFileName().toString()).reversed()
        );
        boolean limitReached = false;
        int entriesInspected = 0;
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(scriptsRoot)) {
            for (Path candidate : directories) {
                entriesInspected++;
                if (entriesInspected > MAX_DIRECTORY_ENTRIES) {
                    limitReached = true;
                    break;
                }
                if (!isDirectoryWithoutLinks(candidate)) {
                    continue;
                }
                candidates.offer(candidate);
                if (candidates.size() > MAX_SCRIPTS) {
                    candidates.poll();
                    limitReached = true;
                }
            }
        } catch (IOException | java.nio.file.DirectoryIteratorException failure) {
            diagnostics.accept("SCRIPT_DISCOVERY_FAILED: " + safeMessage(failure));
            return List.of();
        }
        if (limitReached) {
            diagnostics.accept(
                "SCRIPT_LIMIT_REACHED: inspected at most " + MAX_DIRECTORY_ENTRIES
                    + " entries and admitted at most " + MAX_SCRIPTS + " scripts"
            );
        }
        final List<Path> admitted = new ArrayList<>(candidates);
        admitted.sort(Comparator.comparing(candidate -> candidate.getFileName().toString()));
        final Map<ScriptId, List<InstalledScript>> grouped = new HashMap<>();
        for (Path candidate : admitted) {
            try {
                final InstalledScript script = read(candidate, root);
                grouped.computeIfAbsent(script.descriptor().id(), ignored -> new ArrayList<>()).add(script);
            } catch (RuntimeException | IOException invalid) {
                diagnostics.accept("SCRIPT_INVALID " + candidate.getFileName() + ": " + safeMessage(invalid));
            }
        }
        final List<InstalledScript> unique = new ArrayList<>();
        grouped.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(ScriptId::value)))
            .forEach(group -> {
                if (group.getValue().size() == 1) {
                    unique.add(group.getValue().get(0));
                } else {
                    diagnostics.accept("SCRIPT_DUPLICATE_ID: " + group.getKey());
                }
            });
        return List.copyOf(unique);
    }

    Optional<InstalledScript> find(final ScriptId id) {
        Objects.requireNonNull(id, "id");
        return discover().stream().filter(script -> script.descriptor().id().equals(id)).findFirst();
    }

    private InstalledScript read(final Path rawRoot, final DirectoryIdentity scriptsDirectory) throws IOException {
        final Path root = rawRoot.toAbsolutePath().normalize();
        if (!root.startsWith(scriptsRoot)) {
            throw new IllegalArgumentException("Script directory escaped the scripts root");
        }
        final DirectoryIdentity scriptDirectory = directoryIdentity(root);
        scriptsDirectory.requireCurrent("scripts root");
        final StableFile manifest = readBoundedRegularFile(
            root.resolve("script.json").normalize(), MAX_MANIFEST_BYTES, "manifest", fileOpener
        );
        manifest.requireCurrent("manifest");
        final JsonNode node = mapper.readTree(manifest.bytes());
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("script.json must contain one JSON object");
        }
        node.fieldNames().forEachRemaining(field -> {
            if (!MANIFEST_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unknown script.json field: " + field);
            }
        });
        final JsonNode schemaVersion = node.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isIntegralNumber()
            || !schemaVersion.canConvertToInt() || schemaVersion.intValue() != 2) {
            throw new IllegalArgumentException("Only script schemaVersion 2 is supported");
        }
        final ScriptId id = new ScriptId(requiredText(node, "id", 128));
        final String name = requiredText(node, "name", 256);
        final String version = requiredText(node, "version", 128);
        final ScriptLanguage language = ScriptLanguage.fromId(requiredText(node, "language", 32));
        final String entry = requiredText(node, "entry", 512);
        final List<String> permissions = permissions(node.path("permissions"));
        final byte[] sourceDigest = sourceDigest(node);
        final Path sourcePath = resolveEntry(root, entry);
        final FileIdentity sourceIdentity = regularFileIdentity(sourcePath, "source");
        manifest.requireCurrent("manifest");
        scriptDirectory.requireCurrent("script directory");
        scriptsDirectory.requireCurrent("scripts root");
        return new InstalledScript(
            new ScriptDescriptor(id, name, version, language, entry, permissions),
            root,
            sourcePath,
            scriptsDirectory,
            scriptDirectory,
            manifest,
            sourceIdentity,
            sourceDigest,
            fileOpener
        );
    }

    private Path resolveEntry(final Path root, final String entry) throws IOException {
        final Path relative = Path.of(entry);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Script entry must be relative");
        }
        final Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Script entry escaped its script directory");
        }
        if (!pathHasNoLinks(resolved)) {
            throw new IllegalArgumentException("Script entry must not traverse a symbolic link or junction");
        }
        return resolved;
    }

    private boolean isDirectoryWithoutLinks(final Path path) {
        try {
            directoryIdentity(path);
            return true;
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private DirectoryIdentity directoryIdentity(final Path path) throws IOException {
        if (!pathHasNoLinks(path)) {
            throw new IllegalArgumentException("Script directory must not traverse a symbolic link or junction");
        }
        final BasicFileAttributes attributes = Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isDirectory()) {
            throw new IllegalArgumentException("Script directory is missing or is not a directory");
        }
        return new DirectoryIdentity(path, FileIdentity.from(attributes));
    }

    private StableFile readBoundedRegularFile(
        final Path path,
        final int maxBytes,
        final String label,
        final FileOpener fileOpener
    ) throws IOException {
        final FileIdentity identity = regularFileIdentity(path, label);
        final byte[] bytes;
        try (InputStream input = fileOpener.open(path)) {
            bytes = input.readNBytes(maxBytes + 1);
        }
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("Script " + label + " exceeded " + maxBytes + " bytes");
        }
        return new StableFile(path, identity, digest(bytes), bytes);
    }

    private static byte[] sourceDigest(final JsonNode manifest) {
        final String value = requiredText(manifest, "sourceSha256", 64);
        if (!value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(
                "sourceSha256 must contain exactly 64 hexadecimal characters"
            );
        }
        return java.util.HexFormat.of().parseHex(value);
    }

    private FileIdentity regularFileIdentity(
        final Path path,
        final String label
    ) throws IOException {
        if (!pathHasNoLinks(path)) {
            throw new IllegalArgumentException(
                "Script " + label + " must not traverse a symbolic link or junction"
            );
        }
        final BasicFileAttributes attributes = Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isRegularFile()) {
            throw new IllegalArgumentException(
                "Script " + label + " is missing or is not a regular file"
            );
        }
        return FileIdentity.from(attributes);
    }

    // Inspect only the configured Turboism home and its descendants. Wine can fail
    // BasicFileAttributes reads for higher Z: ancestors such as Z:\home; those
    // ancestors are outside the registry's trust boundary and must not hide valid scripts.
    private boolean pathHasNoLinks(final Path path) {
        final Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(confinementRoot)) {
            return false;
        }
        for (Path current = normalized; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current) || isWindowsReparsePoint(current)) {
                return false;
            }
            if (current.equals(confinementRoot)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWindowsReparsePoint(final Path path) {
        try {
            // The Windows BasicFileAttributes provider classifies every non-symlink
            // reparse point, including directory junctions, as "other" when links
            // are not followed. There is no standard dos:reparsePoint attribute;
            // requesting it makes ordinary paths fail under Windows and Wine.
            return Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            ).isOther();
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException unavailable) {
            // On Windows, failure to inspect an untrusted path component must not
            // downgrade a junction/reparse-point check to "not a reparse point".
            return isWindows();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT)
            .contains("win");
    }

    private static List<String> permissions(final JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > 64) {
            throw new IllegalArgumentException("permissions must be an array of at most 64 permission ids");
        }
        final List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException("permissions must contain non-blank strings");
            }
            final String permission = value.textValue();
            if (!KNOWN_PERMISSIONS.contains(permission)) {
                throw new IllegalArgumentException("Unknown script permission: " + permission);
            }
            if (!values.contains(permission)) {
                values.add(permission);
            }
        });
        return List.copyOf(values);
    }

    private static String requiredText(final JsonNode node, final String field, final int max) {
        final JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Missing string field: " + field);
        }
        final String text = value.textValue().trim();
        if (text.isEmpty() || text.length() > max) {
            throw new IllegalArgumentException(field + " must contain 1-" + max + " characters");
        }
        return text;
    }

    private static byte[] digest(final byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static String safeMessage(final Throwable failure) {
        final String raw = failure.getMessage();
        if (raw == null || raw.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        final String normalized = raw.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= 1024 ? normalized : normalized.substring(0, 1024);
    }

    private record FileIdentity(Object fileKey, long size, java.nio.file.attribute.FileTime modified) {

        private static FileIdentity from(final BasicFileAttributes attributes) {
            return new FileIdentity(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime());
        }

        private boolean matches(final BasicFileAttributes attributes) {
            return Objects.equals(fileKey, attributes.fileKey())
                && size == attributes.size()
                && modified.equals(attributes.lastModifiedTime());
        }
    }

    private final class DirectoryIdentity {
        private final Path path;
        private final FileIdentity identity;

        private DirectoryIdentity(final Path path, final FileIdentity identity) {
            this.path = path;
            this.identity = identity;
        }

        private void requireCurrent(final String label) throws IOException {
            if (!pathHasNoLinks(path)) {
                throw new IllegalArgumentException("Script " + label + " traversed a symbolic link or junction");
            }
            final BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            if (!attributes.isDirectory() || !identity.matches(attributes)) {
                throw new IllegalArgumentException("Script " + label + " changed while reading");
            }
        }
    }

    private final class StableFile {
        private final Path path;
        private final FileIdentity identity;
        private final byte[] digest;
        private final byte[] bytes;

        private StableFile(
            final Path path,
            final FileIdentity identity,
            final byte[] digest,
            final byte[] bytes
        ) {
            this.path = path;
            this.identity = identity;
            this.digest = digest;
            this.bytes = bytes;
        }

        private byte[] bytes() {
            return bytes;
        }

        private FileIdentity identity() {
            return identity;
        }

        private byte[] digest() {
            return digest;
        }

        private void requireCurrent(final String label) throws IOException {
            if (!pathHasNoLinks(path)) {
                throw new IllegalArgumentException("Script " + label + " traversed a symbolic link or junction");
            }
            final BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            if (!attributes.isRegularFile() || !identity.matches(attributes)) {
                throw new IllegalArgumentException("Script " + label + " changed while reading");
            }
            final byte[] current;
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                current = input.readNBytes(bytes.length + 1);
            }
            if (current.length != bytes.length
                || !MessageDigest.isEqual(digest, ScriptRegistry.digest(current))) {
                throw new IllegalArgumentException("Script " + label + " changed while reading");
            }
        }
    }

    final class InstalledScript {
        private final ScriptDescriptor descriptor;
        private final Path root;
        private final Path sourcePath;
        private final DirectoryIdentity scriptsDirectory;
        private final DirectoryIdentity scriptDirectory;
        private final StableFile manifest;
        private final FileIdentity sourceIdentity;
        private final byte[] sourceDigest;
        private final FileOpener fileOpener;

        private InstalledScript(
            final ScriptDescriptor descriptor,
            final Path root,
            final Path sourcePath,
            final DirectoryIdentity scriptsDirectory,
            final DirectoryIdentity scriptDirectory,
            final StableFile manifest,
            final FileIdentity sourceIdentity,
            final byte[] sourceDigest,
            final FileOpener fileOpener
        ) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
            this.root = Objects.requireNonNull(root, "root");
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
            this.scriptsDirectory = Objects.requireNonNull(
                scriptsDirectory, "scriptsDirectory"
            );
            this.scriptDirectory = Objects.requireNonNull(
                scriptDirectory, "scriptDirectory"
            );
            this.manifest = Objects.requireNonNull(manifest, "manifest");
            this.sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
            this.sourceDigest = Objects.requireNonNull(sourceDigest, "sourceDigest").clone();
            this.fileOpener = Objects.requireNonNull(fileOpener, "fileOpener");
        }

        ScriptDescriptor descriptor() {
            return descriptor;
        }

        Path root() {
            return root;
        }

        Path sourcePath() {
            return sourcePath;
        }

        String source() {
            try {
                scriptsDirectory.requireCurrent("scripts root");
                scriptDirectory.requireCurrent("script directory");
                manifest.requireCurrent("manifest");
                final StableFile source = readBoundedRegularFile(
                    sourcePath, MAX_SOURCE_BYTES, "source", fileOpener
                );
                source.requireCurrent("source");
                if (!sourceIdentity.equals(source.identity())
                    || !MessageDigest.isEqual(sourceDigest, source.digest())) {
                    throw new IllegalArgumentException("Script source changed after discovery");
                }
                scriptsDirectory.requireCurrent("scripts root");
                scriptDirectory.requireCurrent("script directory");
                manifest.requireCurrent("manifest");
                return new String(source.bytes(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new IllegalArgumentException(
                    "Script source could not be read: " + safeMessage(failure),
                    failure
                );
            }
        }
    }
}
