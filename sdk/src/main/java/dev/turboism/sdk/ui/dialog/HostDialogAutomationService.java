package dev.turboism.sdk.ui.dialog;

import dev.turboism.sdk.PreviewApi;

import java.time.Duration;
import java.util.List;

/**
 * Automates host JVM confirmation dialogs for plugin/validation flows.
 *
 * <p>The implementation operates strictly inside the host JVM process on the
 * AWT/Swing component tree: it waits for the target dialog, matches a button
 * semantically (multilingual, ambiguity fail-closed), triggers it, and waits
 * for the dialog to close. It never uses Robot, coordinates, cross-process
 * windows, or OCR, and performs no action outside the UI (no file writes, no
 * document closing; CLOSE only dispatches a window-closing event).</p>
 *
 * <p>Requires the {@code turboism.ui.dialog.automate} permission. Flow
 * failures are returned as {@link HostDialogOutcome}; only illegal arguments
 * throw.</p>
 */
@PreviewApi
public interface HostDialogAutomationService {

    /**
     * Waits for a matching host dialog (100 ms polling), applies the semantic
     * action, and waits for the dialog to close.
     *
     * @param matcher target window/option-type condition
     * @param action  semantic action to apply
     * @param timeout overall deadline for dialog appearance and closure
     * @return {@link HostDialogOutcome#ACTED} on success; otherwise a fail-closed outcome
     * @throws IllegalArgumentException when {@code matcher}/{@code action} is null or {@code timeout} is not positive
     * @throws dev.turboism.sdk.permission.CubismPermissionException when the plugin lacks the permission
     */
    HostDialogOutcome act(HostDialogMatcher matcher, HostDialogAction action, Duration timeout);

    /**
     * Enumerates currently visible host dialogs for diagnostics/assertions.
     *
     * @return snapshots of visible modal (or active) AWT dialogs in the host JVM
     */
    List<HostDialogSnapshot> snapshots();
}
