package dev.turboism.persistence.config;

import java.util.Map;

/**
 * Classified result of one {@link TypedConfigMigrationPolicy} walk.
 *
 * <p>The outcome carries no revision, error code or user-facing message: the caller owns the
 * document revision and decides how a failure is presented. Only a
 * {@link TypedConfigMigrationStatus#MIGRATED} outcome carries values.
 *
 * @param status how the stored document was classified
 * @param encodedValues the migrated values when {@code status} is {@code MIGRATED}, otherwise empty
 */
public record TypedConfigMigrationOutcome(TypedConfigMigrationStatus status, Map<String, String> encodedValues) {
    public TypedConfigMigrationOutcome {
        encodedValues = Map.copyOf(encodedValues);
    }

    /** Returns whether the document is at the registered schema version. */
    public boolean migrated() {
        return status == TypedConfigMigrationStatus.MIGRATED;
    }
}
