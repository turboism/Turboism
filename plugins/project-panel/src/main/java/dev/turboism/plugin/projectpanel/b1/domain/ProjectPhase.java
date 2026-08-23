package dev.turboism.plugin.projectpanel.b1.domain;

/**
 * The lifecycle phases of a project as the panel observes them.
 *
 * <p>Not a strictly linear sequence: {@link ProjectPanelStateModel} permits a project to move from a
 * terminal phase back into {@code OPENING} or {@code OPENED} when a new project is opened.
 */
public enum ProjectPhase {
    OPENING,
    OPENED,
    CLOSING,
    CLOSED
}
