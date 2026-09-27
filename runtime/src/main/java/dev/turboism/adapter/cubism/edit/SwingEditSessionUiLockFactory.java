package dev.turboism.adapter.cubism.edit;

import java.util.Objects;

/**
 * Production {@link EditSessionUiLockFactory}: builds {@link EditSessionDialogLock} over the real
 * Swing dialog primitives.
 */
public final class SwingEditSessionUiLockFactory implements EditSessionUiLockFactory {

    private final EditSessionDialogPrimitives primitives;

    public SwingEditSessionUiLockFactory() {
        this(new SwingEditSessionDialogPrimitives());
    }

    public SwingEditSessionUiLockFactory(final EditSessionDialogPrimitives primitives) {
        this.primitives = Objects.requireNonNull(primitives, "primitives");
    }

    @Override
    public EditSessionUiLock create(final EditSessionUiLockContext context) {
        return new EditSessionDialogLock(primitives, context);
    }
}
