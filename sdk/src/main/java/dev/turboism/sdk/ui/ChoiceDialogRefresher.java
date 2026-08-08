package dev.turboism.sdk.ui;

import dev.turboism.sdk.PreviewApi;

import java.util.List;

/** Rebuilds the option list of an open choice dialog (e.g. theme reload). */
@PreviewApi
@FunctionalInterface
public interface ChoiceDialogRefresher {

    List<ChoiceDialogOption> refresh();
}
