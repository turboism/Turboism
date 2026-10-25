package dev.turboism.sdk.ui;

import java.util.List;

/** Receives a preview dialog result once, with stable selected row identities on acceptance. */
@FunctionalInterface
public interface SelectionTableDialogResultListener {
    /** Cancellation (including window close) supplies false and an empty list. */
    void onResult(boolean accepted, List<String> selectedIds);
}
