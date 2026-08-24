package dev.turboism.installer;

import java.io.IOException;
import java.io.InputStream;
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
    static final String PLUGIN_JSON_ENTRY = "META-INF/turboism/plugin.json";
    /**
     * Retired official plugin ids (retirement slice): during a managed
     * upgrade, a JAR below the canonical plugins directory is removed only
     * when its embedded plugin.json id is one of these exact ids; filename
     * alone is never authorization.
     */
    static final Set<String> RETIRED_PLUGIN_IDS = Set.of(
            "dev.turboism.plugin.logfilter",
            "dev.turboism.plugin.clipmask",
            "dev.turboism.plugin.perfopt",
            "dev.turboism.plugin.renderopt");
    static final String INSTALLATION_STATE_FILE = "cubism-installations.json";
    static final String TEMPLATE_RESOURCE = "/turboism/config.template.json";
    private static final int MAX_INSTALLATIONS = 256;
    private static final int MAX_SHORTCUTS = 512;
    private static final int MAX_STATE_FIELD_LENGTH = 4096;
    private static final Pattern MANAGED_SHORTCUT = Pattern.compile(
            "^Turboism Cubism 5\\.[23](?:\\.\\d+)? \\[[\\p{L}\\p{Nd}._ \\-]{1,48}-[0-9A-F]{12}\\](?: - D3D)?\\.lnk$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONFINED_BACKUP = Pattern.compile(
            "(?i)^installer[\\\\/]shortcut-backups[\\\\/][0-9a-f]{64}\\.lnk$");

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
     * Retires previously installed official plugin JARs below the canonical
     * managed plugins directory ({@code home}/plugins). Deletion is authorized
     * only by the embedded plugin.json id: an entry is removed exactly when it
     * is a regular non-symlink .jar file whose embedded id is retired, no
     * matter what the filename is. Every other entry (symlink, directory,
     * non-JAR file, unreadable JAR, missing/unparsable descriptor, or a
     * different plugin id) is preserved with an actionable stdout diagnostic.
     * Nothing outside {@code home}/plugins is ever inspected or changed.
     *
     * A proven retired JAR that cannot be deleted fails closed through
     * {@link ConfigException} so the install aborts before any config write.
     * Preserved unverifiable entries are not left to config: every valid JAR
     * descriptor carrying a retired id is additionally denied by the runtime's
     * shared PluginJarContract boundary (PLUGIN_RETIRED_ID) before entrypoint
     * loading, on every distribution path.
     */
    static void retireManagedPlugins(Path home) throws ConfigException {
        Path plugins = home.resolve(PLUGIN_DIR);
        if (!Files.exists(plugins, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(plugins) || !Files.isDirectory(plugins, LinkOption.NOFOLLOW_LINKS)) {
            System.out.println("Turboism: preserved plugin directory " + plugins
                    + " (not a plain directory; nothing was deleted)");
            return;
        }
        List<Path> entries;
        try (java.util.stream.Stream<Path> stream = Files.list(plugins)) {
            entries = stream.sorted().toList();
        } catch (IOException e) {
            System.out.println("Turboism: preserved plugin directory " + plugins
                    + " (unreadable: " + e.getMessage() + "; nothing was deleted)");
            return;
        }
        for (Path entry : entries) {
            if (Files.isSymbolicLink(entry)) {
                System.out.println("Turboism: preserved " + entry + " (symbolic link; not verified)");
                continue;
            }
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                System.out.println("Turboism: preserved " + entry + " (directory; not a managed plugin JAR)");
                continue;
            }
            if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                System.out.println("Turboism: preserved " + entry + " (not a regular file; not verified)");
                continue;
            }
            String name = entry.getFileName().toString();
            if (!name.endsWith(".jar")) {
                System.out.println("Turboism: preserved " + entry + " (not a .jar; not verified)");
                continue;
            }
            String id = embeddedPluginId(entry);
            if (id == null) {
                System.out.println("Turboism: preserved " + entry
                        + " (no readable retired plugin id in " + PLUGIN_JSON_ENTRY + ")");
                continue;
            }
            if (!RETIRED_PLUGIN_IDS.contains(id)) {
                System.out.println("Turboism: preserved " + entry + " (plugin id " + id + " is not retired)");
                continue;
            }
            try {
                Files.delete(entry);
                System.out.println("Turboism: removed retired plugin " + entry + " (id=" + id + ")");
            } catch (IOException e) {
                throw new ConfigException("cannot delete retired plugin " + entry + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Reads the embedded plugin id from a JAR's canonical plugin.json entry.
     * Returns {@code null} (preserve, never delete) for any unreadable,
     * oversized, or non-canonical descriptor.
     */
    static String embeddedPluginId(Path jar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry(PLUGIN_JSON_ENTRY);
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            byte[] bytes;
            try (InputStream in = zip.getInputStream(entry)) {
                ByteBuffer buffer = ByteBuffer.allocate((int) MAX_CONFIG_BYTES + 1);
                byte[] chunk = new byte[4096];
                int total = 0;
                int n;
                while (total <= MAX_CONFIG_BYTES && (n = in.read(chunk)) >= 0) {
                    if ((long) total + n > MAX_CONFIG_BYTES) {
                        return null; // descriptor entry larger than the bound
                    }
                    buffer.put(chunk, 0, n);
                    total += n;
                }
                if (total > MAX_CONFIG_BYTES) {
                    return null;
                }
                bytes = new byte[buffer.position()];
                buffer.flip();
                buffer.get(bytes);
            }
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            Object parsed = BoundedJson.parse(text);
            if (!(parsed instanceof Map)) {
                return null;
            }
            Object id = ((Map<?, ?>) parsed).get("id");
            return (id instanceof String && !((String) id).isBlank()) ? (String) id : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Bounded managed-launch cleanup shared with the generated Java uninstaller
     * and its regression jar. Takeover records are restored from exact byte
     * backups only when the current shortcut is still the managed bytes (or
     * already the original bytes). Any conflict fails closed before deletion.
     */
    static boolean cleanupManagedState(Path home, Path shortcutDirectory) {
        Path state = home.resolve(INSTALLATION_STATE_FILE).normalize();
        try {
            if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
                removeEmptyBackupDirectories(home);
                return true;
            }
            if (Files.isSymbolicLink(state) || !Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS)
                    || shortcutDirectory == null) {
                return false;
            }
            Path directory = shortcutDirectory.toAbsolutePath().normalize();
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))) {
                return false;
            }
            ManagedState managed = readManagedState(state, home, directory);
            List<RestorePlan> plans = new ArrayList<>();
            long backupBytes = 0L;
            // R12: revalidate the normal non-reparse backup chain before any read.
            if (!managed.takeovers.isEmpty() && !isNormalBackupChain(home)) {
                return false;
            }
            for (Takeover takeover : managed.takeovers) {
                Path target = Paths.get(takeover.shortcutPath).toAbsolutePath().normalize();
                if (!isTakeoverShortcut(target, directory)) {
                    return false;
                }
                Path backup = confinedBackup(home, takeover.backupPath);
                if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(backup) || !Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
                        return false;
                    }
                    try {
                        backupBytes = Math.addExact(backupBytes, Files.size(backup));
                    } catch (ArithmeticException overflow) {
                        return false;
                    }
                    if (backupBytes > MAX_BACKUP_BYTES) {
                        return false;
                    }
                }
                boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
                if (targetExists && (Files.isSymbolicLink(target)
                        || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))) {
                    return false;
                }
                String current = targetExists ? sha256(target) : null;
                if (current == null || current.equalsIgnoreCase(takeover.managedSha256)) {
                    if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(backup)
                            || !sha256(backup).equalsIgnoreCase(takeover.originalSha256)) {
                        return false;
                    }
                    plans.add(new RestorePlan(target, takeover.backupPath, true, takeover.originalSha256));
                } else if (current.equalsIgnoreCase(takeover.originalSha256)) {
                    if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)
                            && (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(backup)
                            || !sha256(backup).equalsIgnoreCase(takeover.originalSha256))) {
                        return false;
                    }
                    plans.add(new RestorePlan(target, takeover.backupPath, false, takeover.originalSha256));
                } else {
                    // User-edited or foreign shortcut: do not touch state, backup,
                    // the shortcut, or the install home.
                    return false;
                }
            }

            Map<String, String> hashes = new java.util.HashMap<>();
            for (ShortcutHash hash : managed.hashes) {
                Path shortcut = Paths.get(hash.path).toAbsolutePath().normalize();
                if (!isOwnedShortcut(hash.path, directory) || hashes.put(shortcut.toString().toUpperCase(Locale.ROOT), hash.sha256) != null) {
                    return false;
                }
                if (Files.exists(shortcut, LinkOption.NOFOLLOW_LINKS)
                        && (Files.isSymbolicLink(shortcut) || !Files.isRegularFile(shortcut, LinkOption.NOFOLLOW_LINKS)
                        || !sha256(shortcut).equalsIgnoreCase(hash.sha256))) {
                    return false;
                }
            }
            for (String raw : managed.shortcuts) {
                Path shortcut = Paths.get(raw).toAbsolutePath().normalize();
                if (!isOwnedShortcut(raw, directory) || hashes.containsKey(shortcut.toString().toUpperCase(Locale.ROOT))) {
                    continue;
                }
                if (Files.exists(shortcut, LinkOption.NOFOLLOW_LINKS)
                        && (Files.isSymbolicLink(shortcut) || !Files.isRegularFile(shortcut, LinkOption.NOFOLLOW_LINKS))) {
                    return false;
                }
            }

            // R12: revalidate the normal backup chain immediately before restore.
            if (!plans.isEmpty() && !isNormalBackupChain(home)) {
                return false;
            }
            for (RestorePlan plan : plans) {
                if (!plan.restore) {
                    continue;
                }
                // R12: re-run exact-path resolution, chain validation, final
                // normal non-link file validation and original hash validation
                // immediately before every restore; never trust a cached path.
                if (!isNormalBackupChain(home)) {
                    return false;
                }
                Path backup = confinedBackup(home, plan.backupRelative);
                if (Files.isSymbolicLink(backup) || !Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
                        || !sha256(backup).equalsIgnoreCase(plan.expected)) {
                    return false;
                }
                publishExactBytes(backup, plan.target);
                if (!sha256(plan.target).equalsIgnoreCase(plan.expected)) {
                    return false;
                }
            }
            for (ShortcutHash hash : managed.hashes) {
                Path shortcut = Paths.get(hash.path).toAbsolutePath().normalize();
                Files.deleteIfExists(shortcut);
            }
            for (String raw : managed.shortcuts) {
                Path shortcut = Paths.get(raw).toAbsolutePath().normalize();
                Files.deleteIfExists(shortcut);
            }
            // R12: revalidate the normal backup chain immediately before delete.
            if (!managed.takeovers.isEmpty() && !isNormalBackupChain(home)) {
                return false;
            }
            for (Takeover takeover : managed.takeovers) {
                // R12: re-run exact-path resolution and chain validation at
                // deletion use time; when the backup is present it must be a
                // normal non-link file holding the recorded original hash.
                if (!isNormalBackupChain(home)) {
                    return false;
                }
                Path backup = confinedBackup(home, takeover.backupPath);
                if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(backup) || !Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
                            || !sha256(backup).equalsIgnoreCase(takeover.originalSha256)) {
                        return false;
                    }
                    Files.deleteIfExists(backup);
                }
            }
            Files.deleteIfExists(state);
            removeEmptyBackupDirectories(home);
            return true;
        } catch (IOException | ConfigException | RuntimeException failure) {
            return false;
        }
    }

    private static final int MAX_TAKEOVERS = 128;
    private static final long MAX_BACKUP_BYTES = 8L * 1024L * 1024L;

    private static final class ShortcutHash {
        final String path;
        final String sha256;

        ShortcutHash(String path, String sha256) {
            this.path = path;
            this.sha256 = sha256;
        }
    }

    private static final class Takeover {
        final String shortcutPath;
        final String backupPath;
        final String originalSha256;
        final String managedSha256;
        final String root;
        final String variant;
        final String status;

        Takeover(Map<?, ?> record) throws ConfigException {
            requireStateKeys(record, Set.of("shortcutPath", "backupPath", "originalSha256", "managedSha256", "root", "variant", "status"));
            shortcutPath = stringField(record, "shortcutPath", MAX_STATE_FIELD_LENGTH);
            backupPath = stringField(record, "backupPath", MAX_STATE_FIELD_LENGTH);
            originalSha256 = hashField(record, "originalSha256", true);
            managedSha256 = hashField(record, "managedSha256", false);
            root = stringField(record, "root", MAX_STATE_FIELD_LENGTH);
            variant = stringField(record, "variant", 16);
            status = stringField(record, "status", 16);
            if (!Set.of("normal", "d3d").contains(variant) || !Set.of("pending", "active", "conflict").contains(status)
                    || ("active".equals(status) && managedSha256.isEmpty())) {
                throw new ConfigException("invalid shortcut takeover record");
            }
        }
    }

    private static final class ManagedState {
        final List<String> shortcuts;
        final List<ShortcutHash> hashes;
        final List<Takeover> takeovers;

        ManagedState(List<String> shortcuts, List<ShortcutHash> hashes, List<Takeover> takeovers) {
            this.shortcuts = shortcuts;
            this.hashes = hashes;
            this.takeovers = takeovers;
        }
    }

    private static final class RestorePlan {
        final Path target;
        // R12: only the relative backup identity is retained; a cached validated
        // Path must not confer trust at restore time.
        final String backupRelative;
        final boolean restore;
        final String expected;

        RestorePlan(Path target, String backupRelative, boolean restore, String expected) {
            this.target = target;
            this.backupRelative = backupRelative;
            this.restore = restore;
            this.expected = expected;
        }
    }

    private static ManagedState readManagedState(Path state, Path home, Path directory)
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
        Set<String> required = Set.of("format", "schemaVersion", "installations", "managedShortcuts");
        Set<String> allowed = Set.of("format", "schemaVersion", "installations", "managedShortcuts", "launchMode", "shortcutTakeovers", "managedShortcutHashes");
        if (!root.keySet().containsAll(required) || !allowed.containsAll(root.keySet())) {
            throw new ConfigException("unknown or missing managed state fields");
        }
        if (!"turboism.cubism.installation-state".equals(root.get("format"))
                || !(root.get("schemaVersion") instanceof Long)
                || ((Long) root.get("schemaVersion")) != 1L) {
            throw new ConfigException("unsupported managed state schema");
        }
        Object mode = root.get("launchMode");
        if (mode != null && (!(mode instanceof String) || !Set.of("independent", "takeover").contains(mode))) {
            throw new ConfigException("invalid launch mode");
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
            if (!(entry.get("root") instanceof String) || !(entry.get("version") instanceof String)
                    || !(entry.get("selected") instanceof Boolean)) {
                throw new ConfigException("invalid installation entry shape");
            }
            String rawRoot = (String) entry.get("root");
            String version = (String) entry.get("version");
            if (rawRoot.isEmpty() || rawRoot.length() > MAX_STATE_FIELD_LENGTH || version.length() > 16
                    || !roots.add(Paths.get(rawRoot).toAbsolutePath().normalize().toString().toUpperCase(Locale.ROOT))) {
                throw new ConfigException("invalid installation entry bounds");
            }
        }
        Object shortcutsValue = root.get("managedShortcuts");
        if (!(shortcutsValue instanceof List) || ((List<?>) shortcutsValue).size() > MAX_SHORTCUTS) {
            throw new ConfigException("invalid managed shortcuts");
        }
        Set<String> seen = new HashSet<>();
        List<String> shortcuts = new ArrayList<>();
        for (Object value : (List<?>) shortcutsValue) {
            if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > MAX_STATE_FIELD_LENGTH
                    || !isOwnedShortcut((String) value, directory)) {
                throw new ConfigException("managed shortcut is outside the current-user Turboism directory");
            }
            String raw = (String) value;
            if (!seen.add(Paths.get(raw).toAbsolutePath().normalize().toString().toUpperCase(Locale.ROOT))) {
                throw new ConfigException("duplicate managed shortcut");
            }
            shortcuts.add(raw);
        }
        List<ShortcutHash> hashes = new ArrayList<>();
        Object hashValue = root.get("managedShortcutHashes");
        if (hashValue != null) {
            if (!(hashValue instanceof List) || ((List<?>) hashValue).size() > MAX_SHORTCUTS) {
                throw new ConfigException("invalid managed shortcut hashes");
            }
            for (Object value : (List<?>) hashValue) {
                if (!(value instanceof Map)) {
                    throw new ConfigException("invalid managed shortcut hash");
                }
                Map<?, ?> record = (Map<?, ?>) value;
                requireStateKeys(record, Set.of("path", "sha256"));
                String path = stringField(record, "path", MAX_STATE_FIELD_LENGTH);
                String hash = hashField(record, "sha256", true);
                if (!seen.contains(Paths.get(path).toAbsolutePath().normalize().toString().toUpperCase(Locale.ROOT))) {
                    throw new ConfigException("managed shortcut hash has no owned path");
                }
                hashes.add(new ShortcutHash(path, hash));
            }
        }
        List<Takeover> takeovers = new ArrayList<>();
        Object takeoverValue = root.get("shortcutTakeovers");
        if (takeoverValue != null) {
            if (!(takeoverValue instanceof List) || ((List<?>) takeoverValue).size() > MAX_TAKEOVERS) {
                throw new ConfigException("invalid shortcut takeovers");
            }
            Set<String> takeoverPaths = new HashSet<>();
            for (Object value : (List<?>) takeoverValue) {
                if (!(value instanceof Map)) {
                    throw new ConfigException("invalid shortcut takeover record");
                }
                Takeover takeover = new Takeover((Map<?, ?>) value);
                if (!isTakeoverShortcut(Paths.get(takeover.shortcutPath).toAbsolutePath().normalize(), directory)
                        || !confinedBackup(home, takeover.backupPath).toString().toUpperCase(Locale.ROOT).startsWith(home.toAbsolutePath().normalize().toString().toUpperCase(Locale.ROOT) + java.io.File.separator)) {
                    throw new ConfigException("shortcut takeover path is outside an allowed boundary");
                }
                if (!takeoverPaths.add(takeover.shortcutPath.toUpperCase(Locale.ROOT))) {
                    throw new ConfigException("duplicate shortcut takeover");
                }
                takeovers.add(takeover);
            }
        }
        return new ManagedState(shortcuts, hashes, takeovers);
    }

    private static String stringField(Map<?, ?> map, String key, int max) throws ConfigException {
        Object value = map.get(key);
        if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > max) {
            throw new ConfigException("invalid managed state field: " + key);
        }
        return (String) value;
    }

    private static String hashField(Map<?, ?> map, String key, boolean required) throws ConfigException {
        Object value = map.get(key);
        if (value == null && !required) {
            return "";
        }
        if (!(value instanceof String) || (!((String) value).isEmpty() && !((String) value).matches("(?i)[0-9a-f]{64}"))) {
            throw new ConfigException("invalid hash field: " + key);
        }
        if (required && ((String) value).isEmpty()) {
            throw new ConfigException("missing hash field: " + key);
        }
        return ((String) value).toUpperCase(Locale.ROOT);
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

    private static boolean isTakeoverShortcut(Path path, Path directory) {
        try {
            if (!path.isAbsolute() || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lnk")) {
                return false;
            }
            List<Path> roots = new ArrayList<>();
            roots.add(directory.toAbsolutePath().normalize());
            if (directory.getParent() != null) {
                roots.add(directory.getParent().toAbsolutePath().normalize());
            }
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                roots.add(Paths.get(appData, "Microsoft", "Windows", "Start Menu", "Programs").toAbsolutePath().normalize());
            }
            String userProfile = System.getenv("USERPROFILE");
            if (userProfile != null && !userProfile.isBlank()) {
                roots.add(Paths.get(userProfile, "Desktop").toAbsolutePath().normalize());
            }
            roots.add(Paths.get(System.getProperty("user.home", ""), "Desktop").toAbsolutePath().normalize());
            for (Path root : roots) {
                Path normalizedRoot = root.normalize();
                if (path.startsWith(normalizedRoot) && !path.equals(normalizedRoot)) {
                    Path cursor = path.getParent();
                    boolean safe = true;
                    while (cursor != null) {
                        if (Files.isSymbolicLink(cursor) || (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                                && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))) {
                            safe = false;
                            break;
                        }
                        if (cursor.equals(normalizedRoot)) {
                            if (safe) return true;
                            break;
                        }
                        cursor = cursor.getParent();
                    }
                }
            }
        } catch (RuntimeException failure) {
            return false;
        }
        return false;
    }

    /**
     * R12: only the exact relative name installer/shortcut-backups/<64-hex>.lnk
     * (either normal slash spelling) is admitted. Absolute paths, traversal,
     * nesting, alternate names, and alternate locations below home are rejected.
     * The path is rebuilt from the fixed parts with the platform separator, so
     * cross-platform regression runs resolve the same layout deterministically.
     */
    private static Path confinedBackup(Path home, String relative) throws ConfigException {
        if (relative == null || !CONFINED_BACKUP.matcher(relative).matches()) {
            throw new ConfigException("invalid shortcut backup path");
        }
        String[] parts = relative.replace('\\', '/').split("/");
        Path normalizedHome = home.toAbsolutePath().normalize();
        Path path = normalizedHome.resolve(parts[0]).resolve(parts[1]).resolve(parts[2]).normalize();
        if (!path.startsWith(normalizedHome)) {
            throw new ConfigException("shortcut backup escapes Turboism home");
        }
        return path;
    }

    /**
     * R12: the real Home -> installer -> shortcut-backups directory chain must
     * be normal non-reparse directories immediately before any backup use; a
     * link anywhere in the chain (or a missing chain) fails closed.
     */
    private static boolean isNormalBackupChain(Path home) {
        Path normalizedHome = home.toAbsolutePath().normalize();
        Path installer = normalizedHome.resolve("installer").normalize();
        Path backups = installer.resolve("shortcut-backups").normalize();
        for (Path path : new Path[]{normalizedHome, installer, backups}) {
            if (Files.isSymbolicLink(path) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes only the now-empty, fixed-name backup directory chain. Unknown
     * installer files make Files.delete fail with DirectoryNotEmptyException and
     * are preserved; linked/non-directory paths are never touched.
     */
    private static void removeEmptyBackupDirectories(Path home) throws IOException {
        Path normalizedHome = home.toAbsolutePath().normalize();
        Path installer = normalizedHome.resolve("installer").normalize();
        Path backups = installer.resolve("shortcut-backups").normalize();
        removeEmptyNormalDirectory(backups);
        removeEmptyNormalDirectory(installer);
    }

    private static void removeEmptyNormalDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.delete(directory);
        } catch (java.nio.file.DirectoryNotEmptyException occupied) {
            // Unknown or concurrent files are not installer-authorized deletion.
        }
    }


    private static String sha256(Path path) throws IOException {
        try (java.io.InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02X", value));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static void publishExactBytes(Path source, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw new IOException("shortcut parent is not a normal directory");
        }
        Path temporary = Files.createTempFile(parent, ".turboism-restore-", ".lnk");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("atomic shortcut restoration is unavailable", unsupported);
            }
        } finally {
            Files.deleteIfExists(temporary);
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
        // Retired ids are pruned even when no current bundle carries them. This
        // is safe because the runtime's shared PluginJarContract boundary denies
        // every valid JAR descriptor carrying a retired id (PLUGIN_RETIRED_ID)
        // before entrypoint loading on every distribution path, including
        // NSIS/manual/renamed leftovers this installer cannot verify or delete.
        // Config alone does not keep stale retired JARs inactive; the runtime
        // admission boundary does.
        disabled.removeAll(RETIRED_PLUGIN_IDS);
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
