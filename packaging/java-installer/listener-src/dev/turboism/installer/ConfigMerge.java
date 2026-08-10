package dev.turboism.installer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.TreeSet;

/**
 * config.json read/merge/write for the Java installer (frozen spec
 * "Configuration" contract).
 *
 * Policy:
 *  - absent config: seed from the canonical runtime-config v1 template
 *    (bundled as a resource at /turboism/config.template.json);
 *  - existing config: parsed with the bounded parser; fields not owned by
 *    plugin selection are preserved; worktreeId/pluginDirs are forced to the
 *    installer-owned values; disabledPlugins becomes the sorted set union of
 *    existing disabled ids and bundled-but-unselected ids (Lite = every
 *    bundled id unselected);
 *  - invalid, oversized (> 64 KiB), symlinked, or escaping config targets
 *    fail closed without truncating the original;
 *  - the read is bounded on the actual bytes (not a raceable size check) and
 *    never follows a symlink;
 *  - writes use a temporary sibling plus an atomic replace only: if atomic
 *    replacement is unsupported or fails, the install aborts and the original
 *    config bytes remain untouched.
 */
final class ConfigMerge {

    static final long MAX_CONFIG_BYTES = 64L * 1024;
    static final String WORKTREE_ID = "turboism-runtime";
    static final String PLUGIN_DIR = "plugins";
    static final String CONFIG_FILE = "config.json";
    static final String INSTALLATION_STATE_FILE = "cubism-installations.json";
    static final String TEMPLATE_RESOURCE = "/turboism/config.template.json";
    private static final int MAX_INSTALLATIONS = 256;
    private static final int MAX_SHORTCUTS = 512;
    private static final int MAX_STATE_FIELD_LENGTH = 4096;
    private static final Pattern MANAGED_SHORTCUT = Pattern.compile(
            "^Turboism Cubism 5\\.[23](?:\\.\\d+)? \\[[\\p{L}\\p{Nd}._ \\-]{1,48}-[0-9A-F]{12}\\](?: - D3D)?\\.lnk$",
            Pattern.CASE_INSENSITIVE);

    static final class ConfigException extends Exception {
        ConfigException(String message) {
            super(message);
        }

        ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private ConfigMerge() {
    }

    /**
     * Loads an existing config.json below {@code home}. Returns {@code null}
     * when the file is absent. Throws ConfigException (fail closed) for
     * invalid JSON, oversized files, symlinked files, non-regular files, and
     * targets that resolve outside the install directory.
     */
    static Map<String, Object> loadExisting(Path home) throws ConfigException {
        Path config = configPath(home);
        // NOFOLLOW: a symlink (even one with a missing target) must not be
        // treated as an absent config; it fails closed below.
        if (!Files.exists(config, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            if (Files.isSymbolicLink(config)) {
                throw new ConfigException("config.json is a symbolic link; refusing to modify it");
            }
            if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
                throw new ConfigException("config.json is not a regular file");
            }
            Path realHome = home.toRealPath(); // config exists, so home exists
            Path realConfig = config.toRealPath();
            if (!realConfig.startsWith(realHome)) {
                throw new ConfigException("config.json resolves outside the install directory");
            }
            byte[] bytes = readBounded(config);
            Object parsed = BoundedJson.parse(decodeUtf8Strict(bytes));
            if (!(parsed instanceof Map)) {
                throw new ConfigException("config.json root must be a JSON object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            requireCanonical(map);
            return map;
        } catch (IOException e) {
            throw new ConfigException("cannot read config.json: " + e.getMessage(), e);
        } catch (BoundedJson.JsonException e) {
            throw new ConfigException("config.json is not valid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Strict UTF-8 decode of the existing config bytes (R14): malformed or
     * unmappable bytes fail closed through the typed {@link ConfigException}
     * instead of the permissive {@code new String(bytes, UTF_8)} replacement
     * behavior, so a damaged unrelated field can never be silently rewritten
     * or corrupted. The bundled template is build-controlled and keeps the
     * plain decode.
     */
    static String decodeUtf8Strict(byte[] bytes) throws ConfigException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ConfigException("config.json is not valid UTF-8: " + e.getMessage(), e);
        }
    }

    /**
     * Bounded, symlink-free read of the config file. The file is opened with
     * NOFOLLOW_LINKS (opening a symlink fails); the read consumes at most
     * MAX_CONFIG_BYTES + 1 bytes from the opened channel, so growth beyond
     * 64 KiB is detected from the bytes actually consumed, not from an earlier
     * channel.size() snapshot: any file (statically or concurrently grown)
     * that still has bytes to read after MAX_CONFIG_BYTES have been consumed
     * fails closed.
     */
    static byte[] readBounded(Path config) throws IOException, ConfigException {
        try (FileChannel channel = FileChannel.open(
                config, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) MAX_CONFIG_BYTES + 1);
            while (buffer.hasRemaining()) {
                int n = channel.read(buffer);
                if (n < 0) {
                    break;
                }
            }
            if (buffer.position() > MAX_CONFIG_BYTES) {
                throw new ConfigException("config.json exceeds the " + MAX_CONFIG_BYTES
                        + " byte limit: " + buffer.position()
                        + " bytes were consumed without reaching end of file");
            }
            byte[] bytes = new byte[buffer.position()];
            buffer.flip();
            buffer.get(bytes);
            return bytes;
        }
    }

    /**
     * Bounded Windows managed-launch cleanup shared with the regression jar.
     * The valid state is the only source of shortcut paths; malformed state
     * authorizes nothing and remains for a later retry.
     */
    static boolean cleanupManagedState(Path home, Path shortcutDirectory) {
        Path state = home.resolve(INSTALLATION_STATE_FILE).normalize();
        try {
            if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            if (Files.isSymbolicLink(state) || !Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (shortcutDirectory == null) {
                return false;
            }
            Path directory = shortcutDirectory.toAbsolutePath().normalize();
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))) {
                return false;
            }
            List<String> shortcuts = readOwnedShortcuts(state, directory);
            for (String raw : shortcuts) {
                Path shortcut = Paths.get(raw).toAbsolutePath().normalize();
                if (Files.isSymbolicLink(shortcut)
                        || (Files.exists(shortcut, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(shortcut, LinkOption.NOFOLLOW_LINKS))) {
                    return false;
                }
            }
            for (String raw : shortcuts) {
                try {
                    Files.deleteIfExists(Paths.get(raw).toAbsolutePath().normalize());
                } catch (IOException failure) {
                    return false;
                }
            }
            Files.deleteIfExists(state);
            return true;
        } catch (IOException | ConfigException | RuntimeException failure) {
            return false;
        }
    }

    private static List<String> readOwnedShortcuts(Path state, Path directory)
            throws IOException, ConfigException {
        Object parsed;
        try {
            parsed = BoundedJson.parse(decodeUtf8Strict(readBounded(state)));
        } catch (BoundedJson.JsonException e) {
            throw new ConfigException("managed state is not valid JSON: " + e.getMessage(), e);
        }
        if (!(parsed instanceof Map)) {
            throw new ConfigException("managed state root is not an object");
        }
        Map<?, ?> root = (Map<?, ?>) parsed;
        requireStateKeys(root, Set.of("format", "schemaVersion", "installations", "managedShortcuts"));
        if (!"turboism.cubism.installation-state".equals(root.get("format"))
                || !(root.get("schemaVersion") instanceof Long)
                || ((Long) root.get("schemaVersion")) != 1L) {
            throw new ConfigException("unsupported managed state schema");
        }
        Object installationsValue = root.get("installations");
        if (!(installationsValue instanceof List) || ((List<?>) installationsValue).size() > MAX_INSTALLATIONS) {
            throw new ConfigException("invalid installation entries");
        }
        Set<String> roots = new HashSet<>();
        for (Object value : (List<?>) installationsValue) {
            if (!(value instanceof Map)) {
                throw new ConfigException("invalid installation entry");
            }
            Map<?, ?> entry = (Map<?, ?>) value;
            requireStateKeys(entry, Set.of("root", "version", "selected"));
            if (!(entry.get("root") instanceof String)
                    || !(entry.get("version") instanceof String)
                    || !(entry.get("selected") instanceof Boolean)) {
                throw new ConfigException("invalid installation entry shape");
            }
            String rawRoot = (String) entry.get("root");
            String version = (String) entry.get("version");
            if (rawRoot.isEmpty() || rawRoot.length() > MAX_STATE_FIELD_LENGTH || version.length() > 16) {
                throw new ConfigException("invalid installation entry bounds");
            }
            Path rootPath = Paths.get(rawRoot).toAbsolutePath().normalize();
            if (!roots.add(rootPath.toString().toUpperCase(Locale.ROOT))) {
                throw new ConfigException("duplicate installation root");
            }
        }
        Object shortcutsValue = root.get("managedShortcuts");
        if (!(shortcutsValue instanceof List) || ((List<?>) shortcutsValue).size() > MAX_SHORTCUTS) {
            throw new ConfigException("invalid managed shortcuts");
        }
        Set<String> seen = new HashSet<>();
        List<String> shortcuts = new ArrayList<>();
        for (Object value : (List<?>) shortcutsValue) {
            if (!(value instanceof String)) {
                throw new ConfigException("invalid managed shortcut entry");
            }
            String raw = (String) value;
            if (raw.isEmpty() || raw.length() > MAX_STATE_FIELD_LENGTH || !isOwnedShortcut(raw, directory)) {
                throw new ConfigException("managed shortcut is outside the current-user Turboism directory");
            }
            Path normalized = Paths.get(raw).toAbsolutePath().normalize();
            if (!seen.add(normalized.toString().toUpperCase(Locale.ROOT))) {
                throw new ConfigException("duplicate managed shortcut");
            }
            shortcuts.add(raw);
        }
        return shortcuts;
    }

    private static void requireStateKeys(Map<?, ?> map, Set<String> expected) throws ConfigException {
        if (map.size() != expected.size() || !map.keySet().equals(expected)) {
            throw new ConfigException("unknown or missing managed state fields");
        }
    }

    private static boolean isOwnedShortcut(String raw, Path directory) {
        try {
            Path path = Paths.get(raw);
            if (!path.isAbsolute()) {
                return false;
            }
            Path normalized = path.toAbsolutePath().normalize();
            String expected = directory.toAbsolutePath().normalize().toString();
            String actualParent = normalized.getParent() == null ? "" : normalized.getParent().toString();
            return actualParent.equalsIgnoreCase(expected)
                    && MANAGED_SHORTCUT.matcher(normalized.getFileName().toString()).matches();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /**
     * Canonical runtime-config v1 identity (frozen spec): an existing config
     * is only merged when it has the exact {@code format} value
     * {@code turboism.runtime.config} and an integral {@code schemaVersion}
     * equal to 1. Absent, wrong, or type-invalid identity fails closed before
     * any source mutation; a fresh config always starts from the canonical
     * bundled template instead.
     */
    private static void requireCanonical(Map<String, Object> map) throws ConfigException {
        Object format = map.get("format");
        if (!(format instanceof String) || !"turboism.runtime.config".equals(format)) {
            throw new ConfigException("existing config.json is not canonical runtime-config v1 (format="
                    + format + ")");
        }
        Object schema = map.get("schemaVersion");
        if (schema instanceof Long) {
            if ((Long) schema != 1L) {
                throw new ConfigException("existing config.json schemaVersion is not 1: " + schema);
            }
        } else if (schema instanceof java.math.BigDecimal) {
            // Exact value comparison (R13): toBigIntegerExact() keeps the full
            // precision, so e.g. 18446744073709551617 (2^64+1) is not narrowed
            // modulo 2^64 to 1 and accepted, while integral spellings such as
            // 1.0 remain valid schemaVersion 1.
            java.math.BigInteger integral;
            try {
                integral = ((java.math.BigDecimal) schema).toBigIntegerExact();
            } catch (ArithmeticException e) {
                throw new ConfigException("existing config.json schemaVersion is not integral: " + schema);
            }
            if (!java.math.BigInteger.ONE.equals(integral)) {
                throw new ConfigException("existing config.json schemaVersion is not 1: " + schema);
            }
        } else {
            throw new ConfigException("existing config.json schemaVersion is missing or not a number: " + schema);
        }
    }
    /**
     * Loads the canonical template bundled with the installer. The template is
     * the single source of truth for fresh-install defaults and is taken from
     * the shared staged payload at build time.
     */
    static Map<String, Object> loadTemplate() throws ConfigException {
        try (java.io.InputStream in = ConfigMerge.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new ConfigException("bundled config template resource is missing");
            }
            byte[] bytes = in.readAllBytes();
            Object parsed = BoundedJson.parse(new String(bytes, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) {
                throw new ConfigException("bundled config template root must be a JSON object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            return map;
        } catch (IOException e) {
            throw new ConfigException("cannot read bundled config template: " + e.getMessage(), e);
        } catch (BoundedJson.JsonException e) {
            throw new ConfigException("bundled config template is not valid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Computes the final disabledPlugins list (frozen spec reselection
     * semantics): start from the existing disabled ids, remove every current
     * bundled id (so reselecting a previously disabled bundled plugin enables
     * it), then add the sorted bundled-but-unselected ids. In Lite mode every
     * bundled plugin id counts as unselected. Unrelated (non-bundled) disabled
     * ids remain.
     */
    static List<String> mergeDisabled(Map<String, Object> seed, Set<String> bundled, Set<String> selected, boolean lite)
            throws ConfigException {
        TreeSet<String> disabled = new TreeSet<>();
        Object existing = seed.get("disabledPlugins");
        if (existing != null) {
            if (!(existing instanceof List)) {
                throw new ConfigException("existing disabledPlugins is not an array");
            }
            for (Object entry : (List<?>) existing) {
                if (!(entry instanceof String) || ((String) entry).isEmpty()) {
                    throw new ConfigException("existing disabledPlugins contains a non-string or empty id");
                }
                disabled.add((String) entry);
            }
        }
        disabled.removeAll(bundled);
        for (String id : bundled) {
            if (lite || !selected.contains(id)) {
                disabled.add(id);
            }
        }
        return new ArrayList<>(disabled);
    }

    /**
     * Builds the final config object: preserves every field of the seed that
     * is not owned by plugin selection, forces the installer-owned fields,
     * and writes the merged disabledPlugins (omitted when empty).
     */
    static Map<String, Object> applyPolicy(Map<String, Object> seed, List<String> disabled) {
        Map<String, Object> merged = new LinkedHashMap<>(seed);
        merged.put("worktreeId", WORKTREE_ID);
        merged.put("pluginDirs", Arrays.asList(PLUGIN_DIR));
        if (disabled.isEmpty()) {
            merged.remove("disabledPlugins");
        } else {
            merged.put("disabledPlugins", disabled);
        }
        return merged;
    }

    /**
     * Atomic write of the merged config to {@code home}/config.json: a
     * temporary sibling is written first, then moved over the target with
     * ATOMIC_MOVE together with REPLACE_EXISTING only. If the atomic move is
     * unsupported or fails, the original config bytes are untouched and the
     * install fails closed; there is deliberately no non-atomic fallback.
     */
    static void write(Path home, Map<String, Object> config) throws IOException, ConfigException {
        if (home == null) {
            throw new ConfigException("install path is not set");
        }
        Files.createDirectories(home);
        Path target = configPath(home);
        String json = BoundedJson.serialize(config) + "\n";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Path tmp = home.resolve(".config.json.tmp" + Long.toHexString(System.nanoTime()));
        try {
            Files.write(tmp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new ConfigException("atomic replace of config.json is not supported: " + e.getMessage(), e);
            } catch (IOException | RuntimeException e) {
                throw new ConfigException("atomic replace of config.json failed: " + e.getMessage(), e);
            }
        } catch (IOException | ConfigException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort cleanup of the temporary sibling
            }
            throw e;
        }
    }

    static Path configPath(Path home) {
        return home.resolve(CONFIG_FILE);
    }
}
