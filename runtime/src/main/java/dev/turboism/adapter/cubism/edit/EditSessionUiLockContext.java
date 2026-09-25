package dev.turboism.adapter.cubism.edit;

import java.util.Objects;
import java.util.Optional;

/**
 * Everything a {@link EditSessionUiLock} needs to engage on behalf of one session.
 *
 * @param mainWindow the main window handle to disable, or empty when the verified window chain
 *     is unavailable on this host — the lock still engages its dialogs without it
 * @param cancelRequest invoked when the user requests cancellation through the status dialog's
 *     cancel control; runs on the host UI thread
 */
public record EditSessionUiLockContext(Optional<Object> mainWindow, Runnable cancelRequest) {

    public EditSessionUiLockContext {
        mainWindow = Objects.requireNonNull(mainWindow, "mainWindow");
        cancelRequest = Objects.requireNonNull(cancelRequest, "cancelRequest");
    }
}
