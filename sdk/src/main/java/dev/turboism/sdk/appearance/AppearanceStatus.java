package dev.turboism.sdk.appearance;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

/**
 * The appearance state currently in force, as observed from the host.
 *
 * <p>{@code revision} is the optimistic-concurrency token: pass the value seen here as
 * {@link AppearanceRequest#expectedRevision()} so a request built against a stale view is rejected
 * rather than silently overwriting another plugin's change. The compact constructor rejects
 * {@code null} components, a negative revision, and blank (as opposed to absent) optional text.
 *
 * @param availability whether appearance control is usable at all on this host
 * @param source whether the Editor's own appearance or a plugin overlay is in force
 * @param appearanceId the overlay's identity, empty while {@code source} is {@code NATIVE}
 * @param base the light/dark foundation currently in force
 * @param revision monotonic change counter, {@code 0} when nothing has been applied
 * @param diagnosticId host diagnostic reference explaining a non-available state, never blank when
 *     present
 */
@PreviewApi
public record AppearanceStatus(
    Availability availability,
    Source source,
    Optional<String> appearanceId,
    AppearanceBase base,
    long revision,
    Optional<String> diagnosticId
) {
    public AppearanceStatus {
        availability = Objects.requireNonNull(availability, "availability");
        source = Objects.requireNonNull(source, "source");
        appearanceId = text(appearanceId, "appearanceId");
        base = Objects.requireNonNull(base, "base");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        diagnosticId = text(diagnosticId, "diagnosticId");
    }

    @PreviewApi
    public enum Availability {
        AVAILABLE,
        UNAVAILABLE,
        UNSUPPORTED,
        SAFE_MODE
    }

    @PreviewApi
    public enum Source {
        NATIVE,
        PLUGIN_OVERLAY
    }

    private static Optional<String> text(final Optional<String> value, final String name) {
        Objects.requireNonNull(value, name);
        return value.map(text -> {
            if (text.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return text;
        });
    }
}
