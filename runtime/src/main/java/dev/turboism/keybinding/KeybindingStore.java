package dev.turboism.keybinding;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Properties-file persistence for {@link RuntimeKeybindingService}, stored under the runtime
 * state directory. Only user deltas are persisted: plugin binding states, native binding
 * states, native-keystroke overrides for seeded rows, and complete custom native rows.
 * Seeded native rows are compiled into the service and never written.
 *
 * <p>The file is small by contract (bounded read), a missing file means defaults, and every
 * write is atomic via a sibling temp file so a crash cannot leave a half-written map.</p>
 */
final class KeybindingStore {

    static final long MAX_BYTES = 256L * 1024L;
    private static final String VERSION_KEY = "version";
    private static final String PLUGIN_BIND = "bind.plugin.";
    private static final String NATIVE_BIND = "bind.native.";
    private static final String NATIVE_KEY = "key.native.";
    private static final String CUSTOM_LABEL = "custom.native.";
    private static final String LABEL_SUFFIX = ".label";
    private static final String KEY_SUFFIX = ".key";
    private static final String ORDER_SUFFIX = ".order";

    private final Path file;

    KeybindingStore(final Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    /** A persisted custom native row. */
    record CustomNative(String id, String label, String nativeStroke, int order) {}

    /** Everything readable from disk; never {@code null}. */
    record Snapshot(
            Map<String, String> pluginStates,
            Map<String, String> nativeStates,
            Map<String, String> nativeKeyOverrides,
            List<CustomNative> customs,
            List<String> problems) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of(), List.of(), List.of());
        }
    }

    boolean exists() {
        return Files.exists(file, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Reads the store. A missing file yields {@link Snapshot#empty()}; unreadable or malformed
     * content yields a snapshot carrying the recovered entries plus problem descriptions —
     * persistence never throws on corrupt input because keybindings are advisory state.
     */
    Snapshot load() {
        if (!exists()) {
            return Snapshot.empty();
        }
        try {
            if (Files.isSymbolicLink(file)) {
                return new Snapshot(
                        Map.of(), Map.of(), Map.of(), List.of(), List.of("keybinding store is a symlink: " + file));
            }
            if (Files.size(file) > MAX_BYTES) {
                return new Snapshot(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        List.of(),
                        List.of("keybinding store exceeds " + MAX_BYTES + " bytes: " + file));
            }
        } catch (IOException failure) {
            return new Snapshot(Map.of(), Map.of(), Map.of(), List.of(), List.of(describe(failure)));
        }
        final Properties raw = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            raw.load(in);
        } catch (IOException | IllegalArgumentException failure) {
            return new Snapshot(Map.of(), Map.of(), Map.of(), List.of(), List.of(describe(failure)));
        }
        return decode(raw);
    }

    private Snapshot decode(final Properties raw) {
        final Map<String, String> pluginStates = new TreeMap<>();
        final Map<String, String> nativeStates = new TreeMap<>();
        final Map<String, String> nativeKeys = new TreeMap<>();
        final Map<String, CustomNative> customs = new LinkedHashMap<>();
        final List<String> problems = new ArrayList<>();
        for (String name : raw.stringPropertyNames()) {
            final String value = raw.getProperty(name);
            try {
                if (name.startsWith(PLUGIN_BIND)) {
                    pluginStates.put(decodePluginKey(name.substring(PLUGIN_BIND.length())), requireValue(name, value));
                } else if (name.startsWith(NATIVE_BIND)) {
                    nativeStates.put(decodeSegment(name.substring(NATIVE_BIND.length())), requireValue(name, value));
                } else if (name.startsWith(NATIVE_KEY)) {
                    nativeKeys.put(
                            decodeSegment(name.substring(NATIVE_KEY.length())),
                            KeyStrokeCodec.display(requireValue(name, value)));
                } else if (name.startsWith(CUSTOM_LABEL)) {
                    decodeCustom(name, value, customs);
                } else if (!VERSION_KEY.equals(name)) {
                    problems.add("unrecognized keybinding entry: " + name);
                }
            } catch (RuntimeException invalid) {
                problems.add(name + ": " + invalid.getMessage());
            }
        }
        final List<CustomNative> ordered = customs.values().stream()
                .sorted(java.util.Comparator.comparingInt(CustomNative::order).thenComparing(CustomNative::id))
                .toList();
        return new Snapshot(
                Map.copyOf(pluginStates),
                Map.copyOf(nativeStates),
                Map.copyOf(nativeKeys),
                ordered,
                List.copyOf(problems));
    }

    private void decodeCustom(final String name, final String value, final Map<String, CustomNative> customs) {
        // custom.native.<enc>.label | .key | .order
        final String body = name.substring(CUSTOM_LABEL.length());
        final int suffix = body.lastIndexOf('.');
        if (suffix < 0) {
            throw new IllegalArgumentException("malformed custom row key: " + name);
        }
        final String id = decodeSegment(body.substring(0, suffix));
        final String kind = body.substring(suffix);
        final CustomNative existing = customs.get(id);
        final String label = existing == null ? "" : existing.label();
        final String key = existing == null ? "" : existing.nativeStroke();
        final int order = existing == null ? 0 : existing.order();
        switch (kind) {
            case LABEL_SUFFIX -> customs.put(id, new CustomNative(id, value, key, order));
            case KEY_SUFFIX ->
                customs.put(id, new CustomNative(id, label, KeyStrokeCodec.display(requireValue(name, value)), order));
            case ORDER_SUFFIX -> customs.put(id, new CustomNative(id, label, key, Integer.parseInt(value.trim())));
            default -> throw new IllegalArgumentException("unknown custom row field: " + name);
        }
    }

    /**
     * Persists the given delta state. {@code pluginStates}/{@code nativeStates} hold only
     * non-unset bindings ({@code "BOUND:<stroke>"} or {@code "DISABLED"}); {@code nativeKeys}
     * holds seed-key overrides; {@code customs} holds complete custom rows.
     */
    synchronized void save(final Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        final Properties raw = new Properties();
        raw.setProperty(VERSION_KEY, "1");
        snapshot.pluginStates().forEach((key, state) -> raw.setProperty(PLUGIN_BIND + encodePluginKey(key), state));
        snapshot.nativeStates().forEach((id, state) -> raw.setProperty(NATIVE_BIND + encodeSegment(id), state));
        snapshot.nativeKeyOverrides().forEach((id, stroke) -> raw.setProperty(NATIVE_KEY + encodeSegment(id), stroke));
        for (CustomNative custom : snapshot.customs()) {
            raw.setProperty(CUSTOM_LABEL + encodeSegment(custom.id()) + LABEL_SUFFIX, custom.label());
            raw.setProperty(CUSTOM_LABEL + encodeSegment(custom.id()) + KEY_SUFFIX, custom.nativeStroke());
            raw.setProperty(CUSTOM_LABEL + encodeSegment(custom.id()) + ORDER_SUFFIX, Integer.toString(custom.order()));
        }
        write(raw);
    }

    private void write(final Properties raw) throws IOException {
        final Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            raw.store(out, "Turboism keybindings");
        }
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String requireValue(final String name, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("empty value for " + name);
        }
        return value.trim();
    }

    private static String decodePluginKey(final String encoded) {
        final int split = encoded.indexOf('/');
        if (split < 0) {
            throw new IllegalArgumentException("malformed plugin binding key: " + encoded);
        }
        return decodeSegment(encoded.substring(0, split)) + '\0' + decodeSegment(encoded.substring(split + 1));
    }

    private static String encodePluginKey(final String key) {
        final int split = key.indexOf('\0');
        if (split < 0) {
            throw new IllegalArgumentException("malformed plugin row key: " + key);
        }
        return encodeSegment(key.substring(0, split)) + '/' + encodeSegment(key.substring(split + 1));
    }

    private static String encodeSegment(final String raw) {
        return java.net.URLEncoder.encode(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String decodeSegment(final String encoded) {
        return java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String describe(final Exception failure) {
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }
}
