package dev.turboism.sdk.ui.dialog;

import java.util.List;

/**
 * Diagnostic snapshot of one visible host dialog ({@link HostDialogAutomationService#snapshots}).
 *
 * @param windowClassName the dialog window's {@code Class#getName()}
 * @param modal           whether the dialog is modal
 * @param optionType      JOptionPane option type, or {@code -1} when the dialog has no JOptionPane
 * @param buttonLabels    visible and enabled button texts
 */
public record HostDialogSnapshot(
    String windowClassName,
    boolean modal,
    int optionType,
    List<String> buttonLabels
) {
    public HostDialogSnapshot {
        buttonLabels = List.copyOf(buttonLabels);
    }
}
