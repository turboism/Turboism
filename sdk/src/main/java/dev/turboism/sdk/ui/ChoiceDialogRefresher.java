package dev.turboism.sdk.ui;


import java.util.List;

/** Rebuilds the option list of an open choice dialog (e.g. theme reload). */
@FunctionalInterface
public interface ChoiceDialogRefresher {

    List<ChoiceDialogOption> refresh();
}
