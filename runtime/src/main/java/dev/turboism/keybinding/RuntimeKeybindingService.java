package dev.turboism.keybinding;

import dev.turboism.core.action.RuntimeActionRegistry;
import dev.turboism.internal.core.KeybindingService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.action.RuntimeEditorUiActionRouter;
import dev.turboism.ui.host.EdtDispatch;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.KeyStroke;
import javax.swing.text.JTextComponent;

/**
 * Runtime implementation of {@link KeybindingService} and the global key interceptor.
 *
 * <p>Interception installs one {@link java.awt.KeyEventDispatcher} on the host JVM's
 * {@link KeyboardFocusManager} — the funnel every host key event travels through before any
 * component, menu accelerator or native key handler sees it. That is the "native key
 * capture" seam: it needs no versioned host mapping because it is pure JDK AWT, and it sees
 * host and plugin windows alike.</p>
 *
 * <p>Resolution order for each press/release pair:</p>
 * <ol>
 *   <li>a winning plugin binding invokes the action through the action router (which already
 *       dispatches plugin handlers off the EDT) and consumes the event;</li>
 *   <li>a winning native rebind consumes the original event and redispatches a translated
 *       event carrying the native keystroke to the same focus owner, so the host processes
 *       its original shortcut unchanged;</li>
 *   <li>a native keystroke that was rebound away or disabled is consumed (suppressed);</li>
 *   <li>everything else passes through untouched.</li>
 * </ol>
 *
 * <p>Safety rules: while an editable text component holds focus, plain (modifier-free)
 * bindings do not fire so typing stays intact; {@link #suspendInterception()} bypasses all
 * interception (used by the capture dialog); synthesized translation events are flagged so
 * the dispatcher never re-translates its own output.</p>
 */
public final class RuntimeKeybindingService implements KeybindingService, AutoCloseable {

    /** Built-in native rows. Ids are stable — persisted overrides reference them. */
    private static final List<KeybindingTable.NativeRow> SEEDS = List.of(
            seed("new", "New Project", "Ctrl+N", 0),
            seed("open", "Open", "Ctrl+O", 1),
            seed("save", "Save", "Ctrl+S", 2),
            seed("save-as", "Save As", "Ctrl+Shift+S", 3),
            seed("undo", "Undo", "Ctrl+Z", 4),
            seed("redo", "Redo", "Ctrl+Y", 5),
            seed("cut", "Cut", "Ctrl+X", 6),
            seed("copy", "Copy", "Ctrl+C", 7),
            seed("paste", "Paste", "Ctrl+V", 8),
            seed("select-all", "Select All", "Ctrl+A", 9),
            seed("deselect", "Deselect", "Ctrl+D", 10),
            seed("delete", "Delete", "Delete", 11),
            seed("find", "Find", "Ctrl+F", 12));

    private final KeybindingStore store;
    private final KeybindingTable table = new KeybindingTable();
    private final RuntimeEditorUiActionRouter actionRouter;
    private final Function<String, String> pluginNames;
    private final Consumer<String> diagnostic;
    private final Object lock = new Object();
    private final AtomicInteger suspensions = new AtomicInteger();
    private final AtomicBoolean installed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Registration registryListener;
    /** Per-action-registry listeners, attached while the registry is live; guarded by {@link #lock}. */
    private final Map<RuntimeActionRegistry, Registration> actionListeners = new java.util.LinkedHashMap<>();

    private final java.awt.KeyEventDispatcher dispatcher = this::dispatchKeyEvent;
    /** Set on the EDT while a translated event is being redispatched so it passes through. */
    private final ThreadLocal<Boolean> translating = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public RuntimeKeybindingService(
            final Path storeFile,
            final RuntimeEditorUiActionRouter actionRouter,
            final Function<String, String> pluginNames,
            final Consumer<String> diagnostic) {
        this.store = new KeybindingStore(Objects.requireNonNull(storeFile, "storeFile"));
        this.actionRouter = Objects.requireNonNull(actionRouter, "actionRouter");
        this.pluginNames = Objects.requireNonNull(pluginNames, "pluginNames");
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        // Action rows enumerate lazily after plugins load: re-scan the router whenever an
        // owner's registry set changes so dormant persisted bindings activate without the
        // keybindings window ever having to open.
        this.registryListener = actionRouter.listen(this::registriesChanged);
    }

    /**
     * Loads persisted state and seeds the built-in native rows. Invalid persisted entries are
     * skipped and reported through the diagnostic sink; a missing file means defaults.
     * The file read and the plugin-action scan run without the mutation lock so no IO can
     * stall service mutations elsewhere.
     */
    public void load() {
        final KeybindingStore.Snapshot snapshot = store.load();
        final PluginScan scan = scanPluginRows();
        synchronized (lock) {
            for (KeybindingTable.NativeRow seed : SEEDS) {
                table.putNativeRow(seed);
            }
            snapshot.problems().forEach(problem -> diagnostic.accept("keybindings: " + problem));
            int order = 0;
            for (KeybindingStore.CustomNative custom : snapshot.customs()) {
                table.putNativeRow(new KeybindingTable.NativeRow(
                        custom.id(),
                        custom.label(),
                        custom.nativeStroke(),
                        KeybindingTable.State.UNSET,
                        "",
                        true,
                        order++));
            }
            snapshot.nativeKeyOverrides().forEach((id, stroke) -> {
                try {
                    table.setNativeStroke(id, stroke);
                } catch (RuntimeException unknown) {
                    diagnostic.accept("keybindings: native key override for unknown row " + id);
                }
            });
            snapshot.nativeStates().forEach((id, state) -> {
                try {
                    final KeybindingTable.Binding binding = KeybindingTable.decodeBinding(state);
                    if (binding.state() != KeybindingTable.State.UNSET) {
                        table.setNativeBinding(id, binding.state(), binding.stroke());
                    }
                } catch (RuntimeException invalid) {
                    diagnostic.accept("keybindings: invalid native binding for " + id);
                }
            });
            table.restorePluginBindings(snapshot.pluginStates())
                    .forEach(key -> diagnostic.accept("keybindings: invalid plugin binding for " + key));
            applyScan(scan);
        }
    }

    /**
     * Installs the global key dispatcher on the host focus manager. Must be called before
     * plugins load so bindings are live from the first key event; failures are reported and
     * leave the service usable for the settings UI but non-intercepting.
     */
    public void install() {
        if (closed.get()) {
            return;
        }
        try {
            EdtDispatch.call("keybinding dispatcher install", () -> {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
                installed.set(true);
                return null;
            });
        } catch (RuntimeException failure) {
            diagnostic.accept("keybindings: dispatcher install failed: " + failure.getMessage());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            registryListener.close();
        } catch (RuntimeException failure) {
            diagnostic.accept("keybindings: listener removal failed: " + failure.getMessage());
        }
        synchronized (lock) {
            actionListeners.values().forEach(Registration::close);
            actionListeners.clear();
        }
        if (installed.compareAndSet(true, false)) {
            EdtDispatch.runEventually(
                    "keybinding dispatcher removal",
                    () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher));
        }
    }

    // ------------------------------------------------------------------ service surface

    @Override
    public List<Row> snapshot() {
        final PluginScan scan = scanPluginRows();
        synchronized (lock) {
            applyScan(scan);
            return table.describe();
        }
    }

    @Override
    public void bind(final String rowId, final String keystroke) {
        Objects.requireNonNull(rowId, "rowId");
        final String text = KeyStrokeCodec.display(keystroke);
        synchronized (lock) {
            if (isPluginRow(rowId)) {
                table.setPluginBinding(KeybindingTable.pluginKey(rowId), KeybindingTable.State.BOUND, text);
            } else {
                table.setNativeBinding(KeybindingTable.nativeId(rowId), KeybindingTable.State.BOUND, text);
            }
        }
        persist();
    }

    @Override
    public void disable(final String rowId) {
        Objects.requireNonNull(rowId, "rowId");
        synchronized (lock) {
            if (isPluginRow(rowId)) {
                table.setPluginBinding(KeybindingTable.pluginKey(rowId), KeybindingTable.State.DISABLED, "");
            } else {
                table.setNativeBinding(KeybindingTable.nativeId(rowId), KeybindingTable.State.DISABLED, "");
            }
        }
        persist();
    }

    @Override
    public void reset(final String rowId) {
        Objects.requireNonNull(rowId, "rowId");
        synchronized (lock) {
            if (isPluginRow(rowId)) {
                table.setPluginBinding(KeybindingTable.pluginKey(rowId), KeybindingTable.State.UNSET, "");
            } else {
                table.setNativeBinding(KeybindingTable.nativeId(rowId), KeybindingTable.State.UNSET, "");
            }
        }
        persist();
    }

    @Override
    public void setNativeStroke(final String rowId, final String nativeStroke) {
        Objects.requireNonNull(rowId, "rowId");
        final String text = KeyStrokeCodec.display(nativeStroke);
        synchronized (lock) {
            table.setNativeStroke(KeybindingTable.nativeId(rowId), text);
        }
        persist();
    }

    @Override
    public String addNativeCommand(final String label, final String nativeStroke) {
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("native command label must not be blank");
        }
        final String text = KeyStrokeCodec.display(nativeStroke);
        final String id;
        synchronized (lock) {
            id = nextCustomId();
            final int order = table.nativeRows().size();
            table.putNativeRow(new KeybindingTable.NativeRow(
                    id, label.trim(), text, KeybindingTable.State.UNSET, "", true, order));
        }
        persist();
        return KeybindingTable.nativeRowId(id);
    }

    @Override
    public void removeRow(final String rowId) {
        Objects.requireNonNull(rowId, "rowId");
        synchronized (lock) {
            table.removeNativeRow(KeybindingTable.nativeId(rowId));
        }
        persist();
    }

    @Override
    public Registration suspendInterception() {
        suspensions.incrementAndGet();
        final AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                suspensions.decrementAndGet();
            }
        };
    }

    // ------------------------------------------------------------------ dispatch

    /**
     * The {@link java.awt.KeyEventDispatcher} body, running on the host EDT before any
     * component or menu accelerator sees the event.
     */
    private boolean dispatchKeyEvent(final KeyEvent event) {
        if (closed.get() || suspensions.get() > 0 || Boolean.TRUE.equals(translating.get())) {
            return false;
        }
        final KeybindingTable.DispatchView view = table.view();
        if (event.getID() == KeyEvent.KEY_TYPED) {
            return !textEditing() && view.swallowedTyped().contains(event.getKeyChar());
        }
        if (event.getID() != KeyEvent.KEY_PRESSED && event.getID() != KeyEvent.KEY_RELEASED) {
            return false;
        }
        final KeyStroke stroke = KeyStrokeCodec.normalize(KeyStroke.getKeyStrokeForEvent(event));
        final KeybindingTable.PluginRow plugin = view.pluginByStroke().get(stroke);
        if (plugin != null) {
            if (KeyStrokeCodec.isPlain(stroke) && textEditing()) {
                return false;
            }
            if (event.getID() == KeyEvent.KEY_PRESSED) {
                invoke(plugin);
            }
            return true;
        }
        final KeybindingTable.NativeRow nativeRow = view.nativeByStroke().get(stroke);
        if (nativeRow != null) {
            if (KeyStrokeCodec.isPlain(stroke) && textEditing()) {
                return false;
            }
            translate(event, KeyStrokeCodec.parse(nativeRow.nativeStroke()));
            return true;
        }
        if (view.suppressed().contains(stroke)) {
            return !KeyStrokeCodec.isPlain(stroke) || !textEditing();
        }
        return false;
    }

    private void invoke(final KeybindingTable.PluginRow row) {
        try {
            actionRouter.invoke(row.pluginId(), row.actionId());
        } catch (RuntimeException failure) {
            diagnostic.accept("keybindings: action invoke failed for " + row.pluginId() + "/" + row.actionId() + ": "
                    + failure.getMessage());
        }
    }

    /**
     * Consumes the bound event and redispatches a synthesized event carrying the native
     * keystroke to the same focus owner, so the host handles its original shortcut. The
     * re-entrancy flag lets the synthesized event pass this dispatcher untouched.
     */
    private void translate(final KeyEvent event, final KeyStroke target) {
        final Component source = event.getSource() instanceof Component component
                ? component
                : KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (source == null) {
            return;
        }
        final KeyEvent translated = new KeyEvent(
                source,
                event.getID(),
                event.getWhen(),
                target.getModifiers(),
                target.getKeyCode(),
                KeyStrokeCodec.keyCharFor(target),
                KeyEvent.KEY_LOCATION_STANDARD);
        translating.set(Boolean.TRUE);
        try {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(source, translated);
        } finally {
            translating.set(Boolean.FALSE);
        }
    }

    /** Whether the current focus owner is an editable text component. */
    private static boolean textEditing() {
        final Component focus =
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focus instanceof JTextComponent text) {
            return text.isEditable();
        }
        return focus instanceof java.awt.TextComponent text && text.isEditable();
    }

    // ------------------------------------------------------------------ internals

    /**
     * Router change callback — runs inline on the registering/unregistering thread (plugin
     * load, never the EDT hot path). Rebuilds the row table under the service lock so the
     * next dispatched key event sees the new action set immediately.
     */
    private void registriesChanged() {
        if (closed.get()) {
            return;
        }
        final PluginScan scan = scanPluginRows();
        synchronized (lock) {
            applyScan(scan);
        }
    }

    /** Plugin rows plus the runtime registries seen during enumeration. */
    private record PluginScan(
            Map<String, KeybindingTable.PluginRow> rows, java.util.Set<RuntimeActionRegistry> registries) {}

    /**
     * Enumerates registered plugin actions without holding {@link #lock}: router and registry
     * snapshots are concurrent structures, and resolving owner names may reach the plugin
     * catalog — all of which must stay outside the mutation lock. Each live runtime registry
     * gets the per-action listener attached (under a brief lock) before its snapshot is read,
     * so a registration racing the enumeration is redelivered through
     * {@link #registriesChanged()} instead of slipping through unobserved.
     */
    private PluginScan scanPluginRows() {
        final Map<String, KeybindingTable.PluginRow> rows = new TreeMap<>();
        final java.util.Set<RuntimeActionRegistry> live = new java.util.HashSet<>();
        actionRouter.snapshot().forEach((pluginId, registries) -> {
            if (registries.isEmpty()) {
                return;
            }
            final ActionRegistry registry = registries.get(registries.size() - 1);
            if (!(registry instanceof RuntimeActionRegistry runtime)) {
                return;
            }
            live.add(runtime);
            synchronized (lock) {
                actionListeners.computeIfAbsent(runtime, target -> target.listen(this::registriesChanged));
            }
            runtime.snapshot().forEach((actionId, action) -> {
                final String key = KeybindingTable.pluginRowKey(pluginId, actionId);
                rows.put(
                        key,
                        new KeybindingTable.PluginRow(
                                key,
                                pluginId,
                                actionId,
                                pluginNames.apply(pluginId),
                                action.label(),
                                declaredStroke(action)));
            });
        });
        return new PluginScan(rows, live);
    }

    /** Applies an enumeration under {@link #lock}: drops stale listeners, replaces the rows. */
    private void applyScan(final PluginScan scan) {
        final java.util.Iterator<Map.Entry<RuntimeActionRegistry, Registration>> entries =
                actionListeners.entrySet().iterator();
        while (entries.hasNext()) {
            final Map.Entry<RuntimeActionRegistry, Registration> entry = entries.next();
            if (!scan.registries().contains(entry.getKey())) {
                entry.getValue().close();
                entries.remove();
            }
        }
        table.setPluginRows(scan.rows());
    }

    private String declaredStroke(final ActionRegistry.Action action) {
        try {
            return action.defaultShortcut().map(KeyStrokeCodec::display).orElse("");
        } catch (RuntimeException invalid) {
            diagnostic.accept("keybindings: invalid declared default shortcut on action " + action.id());
            return "";
        }
    }

    /**
     * Writes the current state. Serialized on its own monitor so the last writer always
     * persists the newest complete snapshot; the write itself runs without the mutation
     * lock so a slow disk cannot stall binding changes.
     */
    private final Object persistLock = new Object();

    private void persist() {
        synchronized (persistLock) {
            final KeybindingStore.Snapshot snap;
            synchronized (lock) {
                snap = currentSnapshot();
            }
            try {
                store.save(snap);
            } catch (IOException failure) {
                diagnostic.accept("keybindings: persistence failed: " + failure.getMessage());
            }
        }
    }

    private KeybindingStore.Snapshot currentSnapshot() {
        final Map<String, String> pluginStates = new TreeMap<>(table.pluginBindingStates());
        final Map<String, String> nativeStates = new TreeMap<>();
        final Map<String, String> nativeKeys = new TreeMap<>();
        final List<KeybindingStore.CustomNative> customs = new ArrayList<>();
        final Map<String, String> seedDefaults = seedDefaults();
        for (KeybindingTable.NativeRow row : table.nativeRows().values()) {
            if (row.state() != KeybindingTable.State.UNSET) {
                nativeStates.put(row.id(), encodeState(row.state(), row.boundStroke()));
            }
            if (row.custom()) {
                customs.add(new KeybindingStore.CustomNative(row.id(), row.label(), row.nativeStroke(), row.order()));
            } else if (!row.nativeStroke().equals(seedDefaults.get(row.id()))) {
                nativeKeys.put(row.id(), row.nativeStroke());
            }
        }
        return new KeybindingStore.Snapshot(
                Map.copyOf(pluginStates), Map.copyOf(nativeStates), Map.copyOf(nativeKeys), customs, List.of());
    }

    private static String encodeState(final KeybindingTable.State state, final String stroke) {
        return switch (state) {
            case BOUND -> "BOUND:" + stroke;
            case DISABLED -> "DISABLED";
            case UNSET -> "";
        };
    }

    private static Map<String, String> seedDefaults() {
        final Map<String, String> defaults = new TreeMap<>();
        SEEDS.forEach(row -> defaults.put(row.id(), row.nativeStroke()));
        return defaults;
    }

    private String nextCustomId() {
        int index = 1;
        while (table.nativeRows().containsKey("custom." + index)) {
            index++;
        }
        return "custom." + index;
    }

    private static boolean isPluginRow(final String rowId) {
        return rowId.startsWith("plugin:");
    }

    private static KeybindingTable.NativeRow seed(
            final String id, final String label, final String stroke, final int order) {
        return new KeybindingTable.NativeRow(
                id, label, KeyStrokeCodec.display(stroke), KeybindingTable.State.UNSET, "", false, order);
    }
}
