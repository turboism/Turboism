package dev.turboism.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.sdk.script.ScriptDescriptor;
import dev.turboism.sdk.script.ScriptId;
import dev.turboism.sdk.script.ScriptLanguage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Bounded discovery for user-installed scripts under {@code <turboism.home>/scripts}. */
final class ScriptRegistry {

    private static final int MAX_SCRIPTS = 256;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int MAX_SOURCE_BYTES = 2 * 1024 * 1024;
    private static final Set<String> MANIFEST_FIELDS = Set.of(
        "schemaVersion", "id", "name", "version", "language", "entry", "permissions"
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
    private final Path scriptsRoot;
    private final Consumer<String> diagnostics;

    ScriptRegistry(final Path turboismHome, final Consumer<String> diagnostics) {
        final Path home = Objects.requireNonNull(turboismHome, "turboismHome")
            .toAbsolutePath().normalize();
        this.scriptsRoot = home.resolve("scripts").normalize();
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    List<InstalledScript> discover() {
        if (!Files.isDirectory(scriptsRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        final List<InstalledScript> result = new ArrayList<>();
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(scriptsRoot)) {
            for (Path candidate : directories) {
                if (result.size() >= MAX_SCRIPTS) {
                    diagnostics.accept("SCRIPT_LIMIT_REACHED: at most " + MAX_SCRIPTS + " scripts are admitted");
                    break;
                }
                if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    result.add(read(candidate));
                } catch (RuntimeException | IOException invalid) {
                    diagnostics.accept("SCRIPT_INVALID " + candidate.getFileName() + ": "
                        + safeMessage(invalid));
                }
            }
        } catch (IOException failure) {
            diagnostics.accept("SCRIPT_DISCOVERY_FAILED: " + safeMessage(failure));
            return List.of();
        }
        final Set<ScriptId> seen = new HashSet<>();
        final List<InstalledScript> unique = result.stream()
            .sorted(Comparator.comparing(script -> script.descriptor().id().value()))
            .filter(script -> {
                if (seen.add(script.descriptor().id())) {
                    return true;
                }
                diagnostics.accept("SCRIPT_DUPLICATE_ID: " + script.descriptor().id());
                return false;
            })
            .toList();
        return List.copyOf(unique);
    }

    Optional<InstalledScript> find(final ScriptId id) {
        Objects.requireNonNull(id, "id");
        return discover().stream().filter(script -> script.descriptor().id().equals(id)).findFirst();
    }

    private InstalledScript read(final Path rawRoot) throws IOException {
        final Path root = rawRoot.toAbsolutePath().normalize();
        if (!root.startsWith(scriptsRoot)) {
            throw new IllegalArgumentException("Script directory escaped the scripts root");
        }
        final Path manifest = root.resolve("script.json").normalize();
        final byte[] manifestBytes = readBoundedRegularFile(manifest, MAX_MANIFEST_BYTES, "manifest");
        final JsonNode node = mapper.readTree(manifestBytes);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("script.json must contain one JSON object");
        }
        node.fieldNames().forEachRemaining(field -> {
            if (!MANIFEST_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unknown script.json field: " + field);
            }
        });
        if (node.path("schemaVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException("Only script schemaVersion 1 is supported");
        }
        final ScriptId id = new ScriptId(requiredText(node, "id", 128));
        final String name = requiredText(node, "name", 256);
        final String version = requiredText(node, "version", 128);
        final ScriptLanguage language = ScriptLanguage.fromId(requiredText(node, "language", 32));
        final String entry = requiredText(node, "entry", 512);
        final List<String> permissions = permissions(node.path("permissions"));
        final Path source = resolveEntry(root, entry);
        final byte[] sourceBytes = readBoundedRegularFile(source, MAX_SOURCE_BYTES, "source");
        final String sourceText = new String(sourceBytes, StandardCharsets.UTF_8);
        return new InstalledScript(
            new ScriptDescriptor(id, name, version, language, entry, permissions),
            root,
            source,
            sourceText
        );
    }

    private static Path resolveEntry(final Path root, final String entry) {
        final Path relative = Path.of(entry);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Script entry must be relative");
        }
        final Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Script entry escaped its script directory");
        }
        return resolved;
    }

    private static byte[] readBoundedRegularFile(
        final Path path,
        final int maxBytes,
        final String label
    ) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Script " + label + " is missing or is not a regular file");
        }
        final long size = Files.size(path);
        if (size < 0L || size > maxBytes) {
            throw new IllegalArgumentException("Script " + label + " exceeded " + maxBytes + " bytes");
        }
        final byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("Script " + label + " exceeded " + maxBytes + " bytes");
        }
        return bytes;
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

    private static String safeMessage(final Throwable failure) {
        final String raw = failure.getMessage();
        if (raw == null || raw.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        final String normalized = raw.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= 1024 ? normalized : normalized.substring(0, 1024);
    }

    record InstalledScript(
        ScriptDescriptor descriptor,
        Path root,
        Path sourcePath,
        String source
    ) {
        InstalledScript {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            root = Objects.requireNonNull(root, "root");
            sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
            source = Objects.requireNonNull(source, "source");
        }
    }
}
