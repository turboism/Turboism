package dev.turboism.automesh;

import dev.turboism.core.io.DurableFile;
import dev.turboism.core.io.WriteOptions;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Properties-file persistence for the Auto Mesh preset list and the last-used selection.
 *
 * <p>A user who renames, saves or deletes a preset expects that decision to survive an Editor
 * restart, so the customized list lives next to the runtime state directory instead of the
 * session. Every write is atomic, because a half-written preset file would lose the whole list.</p>
 *
 * <p>The store is advisory state and therefore fails open: a missing file means "no customization
 * yet", and a symlinked, oversized or corrupt file yields an empty snapshot plus one diagnostic
 * line. No persistence problem may stop the Editor from opening its Auto Mesh dialog.</p>
 */
public final class AutoMeshPresetStore {

    /** Largest store the runtime reads; a bigger file counts as corrupt. */
    public static final long MAX_BYTES = 256L * 1024L;

    private static final String VERSION_KEY = "version";
    private static final String VERSION_VALUE = "1";
    private static final String FILE_COMMENT = "Turboism auto mesh presets";
    private static final String ALGORITHM_PREFIX = "algorithm.";
    private static final String KEY_SEPARATOR = ".";
    private static final String CUSTOMIZED_SUFFIX = ".customized";
    private static final String SELECTED_PRESET_SUFFIX = ".selectedPreset";
    private static final String PRESET_SEGMENT = ".preset.";
    private static final String PRESET_NAME_SUFFIX = ".name";
    private static final String VALUE_SEGMENT = ".value.";
    private static final String ENCODED_DOT = "%2E";

    /** {@code null} for the in-memory store, which never touches a file. */
    private final Path file;

    private final Consumer<String> diagnostics;

    /**
     * Creates the store backed by one file.
     *
     * @param file the properties file that carries the preset list; {@code null} keeps everything in
     *     memory
     * @param diagnostics sink for every refused read or write; it must not throw
     */
    public AutoMeshPresetStore(final Path file, final Consumer<String> diagnostics) {
        this.file = file == null ? null : file.toAbsolutePath().normalize();
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /**
     * Returns a store that keeps nothing on disk.
     *
     * <p>Headless tests and a runtime without a state directory still construct the selection
     * state, so the no-op store keeps that path free of filesystem side effects.</p>
     *
     * @return a store whose {@link #save} writes nothing and whose {@link #load} is empty
     */
    public static AutoMeshPresetStore inMemory() {
        return new AutoMeshPresetStore(null, message -> {});
    }

    /**
     * Reads every persisted algorithm state.
     *
     * @return the readable snapshot, empty when the file is absent or rejected
     */
    public Snapshot load() {
        if (file == null || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Snapshot.empty();
        }
        try {
            if (Files.isSymbolicLink(file)) {
                report("auto mesh preset store is a symlink: " + file);
                return Snapshot.empty();
            }
            if (Files.size(file) > MAX_BYTES) {
                report("auto mesh preset store exceeds " + MAX_BYTES + " bytes: " + file);
                return Snapshot.empty();
            }
        } catch (IOException unreadable) {
            report(describe(unreadable));
            return Snapshot.empty();
        }
        final Properties raw = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            raw.load(in);
        } catch (IOException | IllegalArgumentException unreadable) {
            report(describe(unreadable));
            return Snapshot.empty();
        }
        return decode(raw);
    }

    /**
     * Persists every algorithm state, replacing the previous file.
     *
     * <p>The write reports a failure instead of throwing, because a preset file that cannot be
     * replaced must not abort the dialog session that triggered the save.</p>
     *
     * @param snapshot the state to persist; the in-memory store ignores it
     */
    public synchronized void save(final Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (file == null) {
            return;
        }
        final Properties raw = new Properties();
        raw.setProperty(VERSION_KEY, VERSION_VALUE);
        new TreeMap<>(snapshot.algorithms()).forEach((algorithmId, state) -> encode(algorithmId, state, raw));
        try {
            final Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // A preferred atomic replace keeps a crash from truncating the list, while a file
            // system that refuses ATOMIC_MOVE still receives the new state.
            DurableFile.at(file)
                    .withOptions(WriteOptions.DEFAULT.withAtomicity(WriteOptions.Atomicity.PREFERRED))
                    .replaceStreaming(out -> raw.store(out, FILE_COMMENT));
        } catch (IOException | RuntimeException failure) {
            report("could not persist auto mesh presets: " + describe(failure));
        }
    }

    /**
     * Everything readable from disk.
     *
     * @param algorithms one state per algorithm id; empty when nothing was customized
     */
    public record Snapshot(Map<String, AlgorithmState> algorithms) {

        public Snapshot {
            algorithms = Map.copyOf(Objects.requireNonNull(algorithms, "algorithms"));
        }

        /**
         * Returns the snapshot a missing file answers.
         *
         * @return a snapshot that carries no algorithm state
         */
        public static Snapshot empty() {
            return new Snapshot(Map.of());
        }
    }

    /**
     * The persisted state of one algorithm.
     *
     * @param customized whether the user owns the preset list of this algorithm
     * @param presets the customized preset list, in display order; ignored when not customized
     * @param selectedPreset the applied preset name, or empty when the values were hand-edited
     * @param values the current canonical parameter values, or empty when never edited
     */
    public record AlgorithmState(
            boolean customized,
            List<AlgorithmPreset> presets,
            Optional<String> selectedPreset,
            Map<String, String> values) {

        public AlgorithmState {
            presets = List.copyOf(Objects.requireNonNull(presets, "presets"));
            selectedPreset = Objects.requireNonNull(selectedPreset, "selectedPreset");
            values = Map.copyOf(Objects.requireNonNull(values, "values"));
        }
    }

    private Snapshot decode(final Properties raw) {
        final Map<String, Draft> drafts = new TreeMap<>();
        for (final String name : raw.stringPropertyNames()) {
            if (VERSION_KEY.equals(name)) {
                continue;
            }
            try {
                decodeEntry(name, raw.getProperty(name, ""), drafts);
            } catch (RuntimeException invalid) {
                report(name + ": " + invalid.getMessage());
            }
        }
        final Map<String, AlgorithmState> algorithms = new TreeMap<>();
        drafts.forEach((algorithmId, draft) -> algorithms.put(algorithmId, draft.toState()));
        return new Snapshot(algorithms);
    }

    private void decodeEntry(final String key, final String value, final Map<String, Draft> drafts) {
        if (!key.startsWith(ALGORITHM_PREFIX)) {
            throw new IllegalArgumentException("unrecognized auto mesh preset entry");
        }
        final String body = key.substring(ALGORITHM_PREFIX.length());
        final int separator = body.indexOf('.');
        if (separator < 0) {
            throw new IllegalArgumentException("malformed auto mesh preset key");
        }
        final String algorithmId = decodeSegment(body.substring(0, separator));
        final String field = body.substring(separator);
        final Draft draft = drafts.computeIfAbsent(algorithmId, id -> new Draft(id));
        if (CUSTOMIZED_SUFFIX.equals(field)) {
            draft.customized = Boolean.parseBoolean(value.trim());
        } else if (SELECTED_PRESET_SUFFIX.equals(field)) {
            draft.selectedPreset = Optional.of(requireText(value));
        } else if (field.startsWith(PRESET_SEGMENT)) {
            decodePresetEntry(field.substring(PRESET_SEGMENT.length()), value, draft);
        } else if (field.startsWith(VALUE_SEGMENT)) {
            draft.values.put(decodeSegment(field.substring(VALUE_SEGMENT.length())), value);
        } else {
            throw new IllegalArgumentException("unrecognized auto mesh preset field");
        }
    }

    private static void decodePresetEntry(final String body, final String value, final Draft draft) {
        final int separator = body.indexOf('.');
        if (separator < 0) {
            throw new IllegalArgumentException("malformed auto mesh preset entry");
        }
        final int index = Integer.parseInt(body.substring(0, separator));
        final String field = body.substring(separator);
        if (PRESET_NAME_SUFFIX.equals(field)) {
            draft.presetNames.put(index, requireText(value));
        } else if (field.startsWith(VALUE_SEGMENT)) {
            draft.presetValues
                    .computeIfAbsent(index, position -> new TreeMap<>())
                    .put(decodeSegment(field.substring(VALUE_SEGMENT.length())), value);
        } else {
            throw new IllegalArgumentException("unrecognized auto mesh preset field");
        }
    }

    private static void encode(final String algorithmId, final AlgorithmState state, final Properties raw) {
        final String prefix = ALGORITHM_PREFIX + encodeSegment(algorithmId);
        if (state.customized()) {
            raw.setProperty(prefix + CUSTOMIZED_SUFFIX, Boolean.TRUE.toString());
            for (int index = 0; index < state.presets().size(); index++) {
                final AlgorithmPreset preset = state.presets().get(index);
                final String presetPrefix = prefix + PRESET_SEGMENT + index;
                raw.setProperty(presetPrefix + PRESET_NAME_SUFFIX, preset.name());
                preset.values()
                        .forEach((parameterId, value) ->
                                raw.setProperty(presetPrefix + VALUE_SEGMENT + encodeSegment(parameterId), value));
            }
        }
        state.selectedPreset().ifPresent(name -> raw.setProperty(prefix + SELECTED_PRESET_SUFFIX, name));
        state.values()
                .forEach((parameterId, value) ->
                        raw.setProperty(prefix + VALUE_SEGMENT + encodeSegment(parameterId), value));
    }

    /** One algorithm as the file describes it, before the preset list is assembled. */
    private final class Draft {

        private final String algorithmId;
        private final Map<Integer, String> presetNames = new TreeMap<>();
        private final Map<Integer, Map<String, String>> presetValues = new TreeMap<>();
        private final Map<String, String> values = new TreeMap<>();
        private boolean customized;
        private Optional<String> selectedPreset = Optional.empty();

        private Draft(final String algorithmId) {
            this.algorithmId = algorithmId;
        }

        private AlgorithmState toState() {
            final List<AlgorithmPreset> presets = new ArrayList<>(presetNames.size());
            presetNames.forEach((index, presetName) -> {
                try {
                    presets.add(new AlgorithmPreset(presetName, presetValues.getOrDefault(index, Map.of())));
                } catch (RuntimeException invalid) {
                    // A preset the SDK rejects was never usable, so dropping it keeps the rest.
                    report("auto mesh preset " + presetName + " of " + algorithmId + ": " + invalid.getMessage());
                }
            });
            return new AlgorithmState(customized, presets, selectedPreset, values);
        }
    }

    private void report(final String message) {
        try {
            diagnostics.accept(message);
        } catch (RuntimeException ignored) {
            // A diagnostic sink must never break the state it reports on.
        }
    }

    private static String requireText(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("empty text");
        }
        return value.trim();
    }

    private static String encodeSegment(final String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace(KEY_SEPARATOR, ENCODED_DOT);
    }

    private static String decodeSegment(final String encoded) {
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    private static String describe(final Exception failure) {
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }
}
