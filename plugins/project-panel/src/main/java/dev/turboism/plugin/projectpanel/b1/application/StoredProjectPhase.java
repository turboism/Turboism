package dev.turboism.plugin.projectpanel.b1.application;

/**
 * The project lifecycle phase as persisted in configuration.
 *
 * <p>Mirrors the domain {@code ProjectPhase} constants by name, plus {@code NONE} for "no
 * phase recorded" - the domain models that absence as null, which configuration storage
 * cannot represent. Renaming a constant here changes what already-stored files mean.
 */
public enum StoredProjectPhase {
    NONE,
    OPENING,
    OPENED,
    CLOSING,
    CLOSED
}
