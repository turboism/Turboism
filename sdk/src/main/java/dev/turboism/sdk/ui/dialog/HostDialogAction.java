package dev.turboism.sdk.ui.dialog;

/**
 * Semantic action applied to a matched host dialog.
 *
 * <p>Button actions (OK/YES/NO/CANCEL) match button labels semantically across
 * the supported locales (en/zh-CN/zh-TW/ja); ambiguity fails closed. CLOSE
 * dispatches a {@code WINDOW_CLOSING} event and lets the host decide the
 * consequence — it never triggers keyboard or coordinate input.</p>
 */
public enum HostDialogAction {
    OK,
    YES,
    NO,
    CANCEL,
    CLOSE
}
