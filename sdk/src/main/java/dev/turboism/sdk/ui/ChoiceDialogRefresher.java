package dev.turboism.sdk.ui;


import java.util.List;

/** Rebuilds the option list of an open choice dialog (e.g. theme reload). */
@FunctionalInterface
public interface ChoiceDialogRefresher {

    /** Returns the replacement option list for the open dialog. */
    List<ChoiceDialogOption> refresh();
}
