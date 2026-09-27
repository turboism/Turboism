package dev.turboism.adapter.cubism.edit;

/**
 * Creates the UI lock for one admitted edit session.
 *
 * <p>Production wiring uses the Swing dialog implementation; tests inject a factory that returns
 * a recording lock so no real dialog ever opens on the test JVM.</p>
 */
@FunctionalInterface
public interface EditSessionUiLockFactory {

    /** Creates the lock instance for one session; called on the host UI thread. */
    EditSessionUiLock create(EditSessionUiLockContext context);
}
