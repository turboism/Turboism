package dev.turboism.keybinding;

import dev.turboism.internal.core.KeybindingService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import javax.swing.KeyStroke;

/**
 * The pure binding model behind {@link RuntimeKeybindingService}: rows, per-row tri-state
 * user bindings, and the immutable {@link DispatchView} the AWT dispatcher resolves each key
 * event against. No AWT event types appear here — only canonical {@link KeyStroke}s and
 * canonical stroke text — so the whole model is exercised headless.
 */
final class KeybindingTable {

    /** Per-row user binding: unset (declared/native default), bound, or disabled. */
    enum State {
        UNSET,
        BOUND,
        DISABLED
    }

    /** A registered plugin action offered as a bindable row. */
    record PluginRow(String key, String pluginId, String actionId, String owner, String label, String declaredStroke) {
        PluginRow {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(pluginId, "pluginId");
            Objects.requireNonNull(actionId, "actionId");
            owner = owner == null ? "" : owner;
            label = label == null ? "" : label;
            declaredStroke = declaredStroke == null ? "" : declaredStroke;
        }
    }

    /** One native-command row: the host keystroke plus the user's binding state. */
    record NativeRow(
            String id, String label, String nativeStroke, State state, String boundStroke, boolean custom, int order) {
        NativeRow {
            Objects.requireNonNull(id, "id");
            label = label == null ? "" : label;
            nativeStroke = requireStroke(nativeStroke, "nativeStroke");
            Objects.requireNonNull(state, "state");
            boundStroke = boundStroke == null ? "" : boundStroke;
        }

        NativeRow withStroke(final String newNativeStroke) {
            return new NativeRow(id, label, newNativeStroke, state, boundStroke, custom, order);
        }
    }

    record Binding(State state, String stroke) {
        static final Binding UNSET = new Binding(State.UNSET, "");
    }

    /** One stroke source feeding conflict detection; the lowest rank claims the stroke. */
    private record RankedRow(int rank, String rowRef) {
        private RankedRow {
            Objects.requireNonNull(rowRef, "rowRef");
        }
    }

    /** Immutable resolution view consumed by the dispatcher on the EDT. */
    record DispatchView(
            Map<KeyStroke, PluginRow> pluginByStroke,
            Map<KeyStroke, NativeRow> nativeByStroke,
            Set<KeyStroke> suppressed,
            Set<Character> swallowedTyped,
            Set<String> conflicted) {
        static final DispatchView EMPTY = new DispatchView(Map.of(), Map.of(), Set.of(), Set.of(), Set.of());
    }

    private final Map<String, PluginRow> pluginRows = new TreeMap<>();
    private final Map<String, NativeRow> nativeRows = new LinkedHashMap<>();
    private final Map<String, Binding> pluginBindings = new TreeMap<>();
    private volatile DispatchView view = DispatchView.EMPTY;

    /** Replaces the enumerated plugin rows; user bindings keyed by row key survive. */
    void setPluginRows(final Map<String, PluginRow> rows) {
        pluginRows.clear();
        pluginRows.putAll(Objects.requireNonNull(rows, "rows"));
        rebuild();
    }

    Map<String, PluginRow> pluginRows() {
        return Collections.unmodifiableMap(pluginRows);
    }

    void putNativeRow(final NativeRow row) {
        nativeRows.put(row.id(), Objects.requireNonNull(row, "row"));
        rebuild();
    }

    NativeRow nativeRow(final String id) {
        final NativeRow row = nativeRows.get(id);
        if (row == null) {
            throw new IllegalArgumentException("unknown native keybinding row: " + id);
        }
        return row;
    }

    Map<String, NativeRow> nativeRows() {
        return Collections.unmodifiableMap(nativeRows);
    }

    void removeNativeRow(final String id) {
        final NativeRow row = nativeRow(id);
        if (!row.custom()) {
            throw new IllegalStateException("built-in native keybinding rows are not removable: " + id);
        }
        nativeRows.remove(id);
        rebuild();
    }

    void setPluginBinding(final String key, final State state, final String stroke) {
        if (!pluginRows.containsKey(Objects.requireNonNull(key, "key"))) {
            throw new IllegalArgumentException("unknown plugin keybinding row: " + key);
        }
        setBinding(pluginBindings, key, state, stroke);
    }

    void setNativeBinding(final String id, final State state, final String stroke) {
        final NativeRow row = nativeRow(id);
        final String text = state == State.BOUND ? requireStroke(stroke, "stroke") : "";
        nativeRows.put(id, new NativeRow(id, row.label(), row.nativeStroke(), state, text, row.custom(), row.order()));
        rebuild();
    }

    /**
     * Restores persisted plugin bindings without requiring the actions to be registered —
     * plugin rows enumerate asynchronously, so bindings for not-yet-registered actions are
     * held dormant and apply once the action appears. Invalid values are skipped and
     * returned so the caller can report them.
     */
    List<String> restorePluginBindings(final Map<String, String> states) {
        final List<String> rejected = new ArrayList<>();
        states.forEach((key, value) -> {
            try {
                final Binding binding = decodeBinding(value);
                if (binding.state() != State.UNSET) {
                    pluginBindings.put(key, binding);
                }
            } catch (RuntimeException invalid) {
                rejected.add(key);
            }
        });
        rebuild();
        return List.copyOf(rejected);
    }

    void setNativeStroke(final String id, final String nativeStroke) {
        nativeRows.put(id, nativeRow(id).withStroke(requireStroke(nativeStroke, "nativeStroke")));
        rebuild();
    }

    private void setBinding(
            final Map<String, Binding> target, final String key, final State state, final String stroke) {
        if (state == State.UNSET) {
            target.remove(key);
        } else {
            target.put(key, new Binding(state, state == State.BOUND ? requireStroke(stroke, "stroke") : ""));
        }
        rebuild();
    }

    /** Bindings as raw state for persistence: {@code "BOUND:<stroke>"} or {@code "DISABLED"}. */
    Map<String, String> pluginBindingStates() {
        final Map<String, String> out = new TreeMap<>();
        pluginBindings.forEach((key, binding) -> out.put(key, encodeBinding(binding)));
        return out;
    }

    DispatchView view() {
        return view;
    }

    /** Rows for the settings table: plugin rows in sorted order, then native rows. */
    List<KeybindingService.Row> describe() {
        final List<KeybindingService.Row> out = new ArrayList<>();
        final Set<String> conflicted = view.conflicted();
        for (PluginRow row : pluginRows.values()) {
            final Binding binding = pluginBindings.getOrDefault(row.key(), Binding.UNSET);
            final String desired = desiredPluginStroke(row, binding);
            out.add(new KeybindingService.Row(
                    pluginRowId(row.key()),
                    KeybindingService.Scope.PLUGIN,
                    row.owner(),
                    row.label(),
                    row.pluginId(),
                    row.actionId(),
                    row.declaredStroke(),
                    "",
                    desired,
                    binding.state() == State.DISABLED,
                    conflicted.contains(row.key()),
                    false));
        }
        for (NativeRow row : nativeRows.values()) {
            final String desired = desiredNativeStroke(row);
            out.add(new KeybindingService.Row(
                    nativeRowId(row.id()),
                    KeybindingService.Scope.NATIVE,
                    "",
                    row.label(),
                    "",
                    "",
                    "",
                    row.nativeStroke(),
                    desired,
                    row.state() == State.DISABLED,
                    conflicted.contains(row.id()),
                    row.custom()));
        }
        return List.copyOf(out);
    }

    /**
     * Rebuilds the immutable dispatch view after every mutation.
     *
     * <p>Every row contributes a candidate stroke source: a user-bound plugin binding (rank
     * 0) outranks a plugin-declared default (rank 1), which outranks a user-bound native
     * translation (rank 2), which outranks a native passthrough stroke (rank 3). The lowest
     * rank claims the stroke; losing sources are marked conflicted. Native strokes that were
     * rebound away or disabled are added to the suppressed set, which the dispatcher checks
     * after the plugin and translation maps so a plugin binding can still claim that key.</p>
     */
    private void rebuild() {
        final Map<KeyStroke, List<RankedRow>> sources = new LinkedHashMap<>();
        for (PluginRow row : pluginRows.values()) {
            final Binding binding = pluginBindings.getOrDefault(row.key(), Binding.UNSET);
            final String desired = desiredPluginStroke(row, binding);
            if (!desired.isBlank()) {
                source(sources, KeyStrokeCodec.parse(desired), binding.state() == State.BOUND ? 0 : 1, row.key());
            }
        }
        final Set<KeyStroke> suppressed = new java.util.LinkedHashSet<>();
        for (NativeRow row : nativeRows.values()) {
            final KeyStroke nativeStroke = KeyStrokeCodec.parse(row.nativeStroke());
            if (row.state() == State.DISABLED || rebound(row)) {
                suppressed.add(nativeStroke);
            }
            if (row.state() == State.DISABLED) {
                continue;
            }
            source(sources, KeyStrokeCodec.parse(desiredNativeStroke(row)), rebound(row) ? 2 : 3, row.id());
        }

        final Map<KeyStroke, PluginRow> plugins = new LinkedHashMap<>();
        final Map<KeyStroke, NativeRow> natives = new LinkedHashMap<>();
        final Set<Character> typed = new java.util.LinkedHashSet<>();
        final Set<String> conflicted = new java.util.LinkedHashSet<>();
        sources.forEach((stroke, rows) -> {
            final RankedRow winner = rows.stream()
                    .min(Comparator.comparingInt(RankedRow::rank).thenComparing(RankedRow::rowRef))
                    .orElseThrow();
            for (RankedRow source : rows) {
                if (!source.rowRef().equals(winner.rowRef())) {
                    conflicted.add(source.rowRef());
                }
            }
            final PluginRow plugin = pluginRows.get(winner.rowRef());
            if (plugin != null) {
                plugins.put(stroke, plugin);
                addTyped(typed, stroke);
                return;
            }
            final NativeRow nativeRow = nativeRows.get(winner.rowRef());
            if (nativeRow != null && rebound(nativeRow)) {
                natives.put(stroke, nativeRow);
                addTyped(typed, stroke);
            }
            // A winning passthrough native stroke (rank 3) dispatches nothing: the event
            // reaches the host unchanged on its own.
        });
        for (NativeRow row : nativeRows.values()) {
            if (row.state() == State.DISABLED || rebound(row)) {
                addTyped(typed, KeyStrokeCodec.parse(row.nativeStroke()));
            }
        }
        view = new DispatchView(
                Collections.unmodifiableMap(plugins),
                Collections.unmodifiableMap(natives),
                Collections.unmodifiableSet(suppressed),
                Collections.unmodifiableSet(typed),
                Collections.unmodifiableSet(conflicted));
    }

    private static void source(
            final Map<KeyStroke, List<RankedRow>> sources,
            final KeyStroke stroke,
            final int rank,
            final String rowRef) {
        sources.computeIfAbsent(stroke, ignored -> new ArrayList<>()).add(new RankedRow(rank, rowRef));
    }

    private static boolean rebound(final NativeRow row) {
        return row.state() == State.BOUND
                && !row.boundStroke().isBlank()
                && !row.boundStroke().equals(row.nativeStroke());
    }

    private void addTyped(final Set<Character> typed, final KeyStroke stroke) {
        if (!KeyStrokeCodec.isPlain(stroke)) {
            return;
        }
        final char c = KeyStrokeCodec.keyCharFor(stroke);
        if (c != java.awt.event.KeyEvent.CHAR_UNDEFINED) {
            typed.add(c);
        }
    }

    private String desiredPluginStroke(final PluginRow row, final Binding binding) {
        return switch (binding.state()) {
            case BOUND -> binding.stroke();
            case DISABLED -> "";
            case UNSET -> row.declaredStroke();
        };
    }

    private String desiredNativeStroke(final NativeRow row) {
        return switch (row.state()) {
            case BOUND -> row.boundStroke().isBlank() ? row.nativeStroke() : row.boundStroke();
            case DISABLED -> "";
            case UNSET -> row.nativeStroke();
        };
    }

    private static String encodeBinding(final Binding binding) {
        return switch (binding.state()) {
            case BOUND -> "BOUND:" + binding.stroke();
            case DISABLED -> "DISABLED";
            case UNSET -> "";
        };
    }

    static Binding decodeBinding(final String value) {
        if (value == null || value.isBlank()) {
            return Binding.UNSET;
        }
        if ("DISABLED".equals(value)) {
            return new Binding(State.DISABLED, "");
        }
        if (value.startsWith("BOUND:")) {
            return new Binding(State.BOUND, requireStroke(value.substring("BOUND:".length()), "stroke"));
        }
        throw new IllegalArgumentException("unknown binding state: " + value);
    }

    static String pluginRowId(final String key) {
        final int split = key.indexOf('\0');
        return "plugin:" + encodeSegment(key.substring(0, split)) + "/" + encodeSegment(key.substring(split + 1));
    }

    static String pluginKey(final String rowId) {
        if (!rowId.startsWith("plugin:")) {
            throw new IllegalArgumentException("not a plugin row id: " + rowId);
        }
        final String body = rowId.substring("plugin:".length());
        final int split = body.indexOf('/');
        if (split < 0) {
            throw new IllegalArgumentException("malformed plugin row id: " + rowId);
        }
        return decodeSegment(body.substring(0, split)) + '\0' + decodeSegment(body.substring(split + 1));
    }

    static String nativeRowId(final String id) {
        return "native:" + encodeSegment(id);
    }

    static String nativeId(final String rowId) {
        if (!rowId.startsWith("native:")) {
            throw new IllegalArgumentException("not a native row id: " + rowId);
        }
        return decodeSegment(rowId.substring("native:".length()));
    }

    static String pluginRowKey(final String pluginId, final String actionId) {
        return pluginId + '\0' + actionId;
    }

    private static String encodeSegment(final String raw) {
        return java.net.URLEncoder.encode(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String decodeSegment(final String encoded) {
        return java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String requireStroke(final String stroke, final String name) {
        Objects.requireNonNull(stroke, name);
        if (stroke.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return KeyStrokeCodec.display(stroke);
    }
}
