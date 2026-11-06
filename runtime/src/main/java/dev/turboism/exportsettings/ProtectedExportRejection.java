package dev.turboism.exportsettings;

import java.util.Objects;

/** Bounded rejection identity for an unplannable census or an unverifiable protected-export plan. */
final class ProtectedExportRejection extends RuntimeException {

    ProtectedExportRejection(final String messageKey) {
        super(Objects.requireNonNull(messageKey, "messageKey"));
    }
}
