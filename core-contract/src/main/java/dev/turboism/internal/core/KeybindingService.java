package dev.turboism.internal.core;

import dev.turboism.sdk.plugin.Registration;
import java.util.List;
import java.util.Objects;

/**
 * Runtime-owned keyboard shortcut authority consumed by the framework shell.
 *
 * <p>The service intercepts the host's AWT key dispatch for two target families:</p>
 * <ul>
 *   <li>{@link Scope#PLUGIN} rows — one per registered action of every plugin (including the
 *       {@code turboism.core} shell identity); a bound key invokes the action through the
 *       normal action router.</li>
 *   <li>{@link Scope#NATIVE} rows — Cubism menu/tool shortcuts modelled as a native keystroke
 *       the host already understands plus the keystroke the user wants to press instead.
 *       Rebound keys are translated into the native keystroke before the host sees them, and
 *       the replaced native keystroke is consumed so the old binding no longer fires.</li>
 * </ul>
 *
 * <p>Every row carries a tri-state user binding: unset (declared/native default applies),
 * bound to an explicit keystroke, or disabled. Bindings are persisted by the runtime; the
 * shell only reads snapshots and issues mutations through this interface.</p>
 */
public interface KeybindingService {

    /**
     * Returns every known row in stable display order: plugin rows first (sorted by owner and
     * action id), then native rows (seeded rows followed by custom rows in insertion order).
     * Plugin rows appear and disappear with action registration, so callers re-read the
     * snapshot after refreshing.
     */
    List<Row> snapshot();

    /**
     * Binds {@code rowId} to {@code keystroke} in canonical text form such as
     * {@code "Ctrl+Shift+S"}. If another row already holds the keystroke it is shadowed, not
     * cleared — {@link Row#conflict()} marks it in the next snapshot.
     *
     * @throws IllegalArgumentException if the row is unknown or the keystroke text is invalid
     */
    void bind(String rowId, String keystroke);

    /**
     * Disables {@code rowId}: plugin rows stop invoking, and for native rows the native
     * keystroke itself is consumed so the host shortcut is fully suppressed.
     */
    void disable(String rowId);

    /**
     * Clears the user binding on {@code rowId}: plugin rows fall back to their declared
     * default shortcut, native rows fall back to their native keystroke.
     */
    void reset(String rowId);

    /**
     * Edits the native keystroke a {@link Scope#NATIVE} row translates into — the key the host
     * application already treats as that command's shortcut.
     *
     * @throws IllegalArgumentException if the row is unknown, not native, or the text is invalid
     */
    void setNativeStroke(String rowId, String nativeStroke);

    /**
     * Adds a custom {@link Scope#NATIVE} row translating a user-named command's existing host
     * keystroke. Returns the new row id.
     *
     * @throws IllegalArgumentException if the label or keystroke text is invalid
     */
    String addNativeCommand(String label, String nativeStroke);

    /**
     * Removes a custom native row. Built-in seeded rows and plugin rows are not removable.
     *
     * @throws IllegalStateException if the row does not exist or is not removable
     */
    void removeRow(String rowId);

    /**
     * Suspends global interception until the returned registration is closed — used by the
     * keybinding capture dialog so the keys being recorded are not themselves intercepted.
     * Suspensions nest; interception resumes when the outermost one closes.
     */
    Registration suspendInterception();

    /** The target family a keybinding row addresses. */
    enum Scope {
        /** A keystroke translated into the host's own shortcut. */
        NATIVE,
        /** A keystroke invoking a registered plugin action. */
        PLUGIN
    }

    /**
     * One row in the keybinding table.
     *
     * @param id stable row identity accepted by every mutating call
     * @param scope {@link Scope#NATIVE} or {@link Scope#PLUGIN}
     * @param owner display owner: the plugin display name for plugin rows, empty for native rows
     * @param label display label of the command or action
     * @param pluginId owning plugin id for plugin rows, empty otherwise
     * @param actionId action id for plugin rows, empty otherwise
     * @param declaredStroke the plugin-declared default shortcut for plugin rows, empty when none
     * @param nativeStroke the host keystroke a native row translates into, empty for plugin rows
     * @param effectiveStroke the keystroke currently triggering the row, empty when unbound
     * @param disabled whether the user explicitly disabled this row
     * @param conflict whether {@code effectiveStroke} is shadowed by another row's binding
     * @param removable whether {@link #removeRow(String)} accepts this row
     */
    record Row(
            String id,
            Scope scope,
            String owner,
            String label,
            String pluginId,
            String actionId,
            String declaredStroke,
            String nativeStroke,
            String effectiveStroke,
            boolean disabled,
            boolean conflict,
            boolean removable) {
        public Row {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(scope, "scope");
            owner = owner == null ? "" : owner;
            label = label == null ? "" : label;
            pluginId = pluginId == null ? "" : pluginId;
            actionId = actionId == null ? "" : actionId;
            declaredStroke = declaredStroke == null ? "" : declaredStroke;
            nativeStroke = nativeStroke == null ? "" : nativeStroke;
            effectiveStroke = effectiveStroke == null ? "" : effectiveStroke;
        }

        /** Whether this row currently has a keystroke that fires it. */
        public boolean bound() {
            return !effectiveStroke.isBlank();
        }
    }

    /**
     * Inert singleton for compositions without a keybinding authority: mutations are no-ops,
     * snapshots are empty, and suspending interception is a no-op registration.
     */
    static KeybindingService unavailable() {
        return new KeybindingService() {
            @Override
            public List<Row> snapshot() {
                return List.of();
            }

            @Override
            public void bind(final String rowId, final String keystroke) {}

            @Override
            public void disable(final String rowId) {}

            @Override
            public void reset(final String rowId) {}

            @Override
            public void setNativeStroke(final String rowId, final String nativeStroke) {}

            @Override
            public String addNativeCommand(final String label, final String nativeStroke) {
                return "";
            }

            @Override
            public void removeRow(final String rowId) {}

            @Override
            public Registration suspendInterception() {
                return () -> {};
            }
        };
    }
}
