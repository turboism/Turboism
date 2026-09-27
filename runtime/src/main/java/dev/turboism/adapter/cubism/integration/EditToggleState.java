package dev.turboism.adapter.cubism.integration;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turboism-side state source for the native 「编辑」 edit checkbox (spec 051, Phase 1).
 *
 * <p>Mirrors the role the remote-connect checkbox's selected state plays for remote
 * connections: a single global boolean the dialog shows and the approval layer consults.
 * The default is {@code false} — fail closed, matching the no-native-edit-approval posture
 * of low-version hosts. Nothing here persists or grants anything by itself; the
 * {@link NativeEditToggleInjector} drives it from the injected checkbox's item events, and
 * {@link EditToggleApprovalGate} is the not-yet-wired {@link EditApprovalGate} attachment
 * point for Phase 2.</p>
 */
public final class EditToggleState {

    /** Listener notified on the event thread that produced the change (today: the EDT). */
    @FunctionalInterface
    public interface Listener {

        /** Called once per effective state transition, never for no-op writes. */
        void editToggleChanged(boolean enabled);
    }

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /** {@return the current edit-toggle state; {@code false} until a verified toggle flips it} */
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * Sets the toggle state and notifies listeners on transitions. Called by the injected
     * checkbox's item listener on the EDT; safe to call from any thread.
     */
    public void setEnabled(final boolean value) {
        if (enabled.compareAndSet(!value, value)) {
            for (final Listener listener : listeners) {
                listener.editToggleChanged(value);
            }
        }
    }

    /** Registers {@code listener} for state transitions; never invoked for no-op writes. */
    public void addListener(final Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }
}
