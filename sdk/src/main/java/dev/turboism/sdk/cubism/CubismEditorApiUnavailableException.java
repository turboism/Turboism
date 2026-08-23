package dev.turboism.sdk.cubism;


import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Thrown before an annotated SDK call reaches a host adapter when the active
 * exact Cubism Editor version is unavailable for that declaration.
 *
 * <p>The structured fields are the stable diagnostic contract. The exception
 * message is intended for humans and must not be parsed.</p>
 */
public final class CubismEditorApiUnavailableException extends UnsupportedOperationException {

    private final String apiId;
    private final Optional<String> activeVersion;
    private final List<String> supportedVersions;

    /**
     * Creates an exact-version availability failure.
     *
     * @param apiId stable SDK method identity
     * @param activeVersion exact active Editor version, or empty when no verified Editor is active
     * @param supportedVersions exact reviewed versions admitted by every applicable declaration;
     *     empty when the declarations intentionally prohibit all reviewed versions
     */
    public CubismEditorApiUnavailableException(
        final String apiId,
        final Optional<String> activeVersion,
        final List<String> supportedVersions
    ) {
        super(message(apiId, activeVersion, supportedVersions));
        this.apiId = requireText(apiId, "apiId");
        this.activeVersion = Objects.requireNonNull(activeVersion, "activeVersion")
            .map(version -> requireText(version, "activeVersion"));
        this.supportedVersions = List.copyOf(
            Objects.requireNonNull(supportedVersions, "supportedVersions")
        );
    }

    /** @return stable SDK method identity in {@code owner#method(parameterTypes)} form */
    public String apiId() {
        return apiId;
    }

    /** @return exact active Editor version, or empty when no verified Editor is active */
    public Optional<String> activeVersion() {
        return activeVersion;
    }

    /**
     * @return immutable exact versions admitted by all applicable declarations, in reviewed-version
     *     order; possibly empty when every reviewed version is prohibited
     */
    public List<String> supportedVersions() {
        return supportedVersions;
    }

    private static String message(
        final String apiId,
        final Optional<String> activeVersion,
        final List<String> supportedVersions
    ) {
        final String id = requireText(apiId, "apiId");
        final Optional<String> version = Objects.requireNonNull(activeVersion, "activeVersion");
        final List<String> supported = List.copyOf(
            Objects.requireNonNull(supportedVersions, "supportedVersions")
        );
        return "Cubism SDK API " + id + " is unavailable on Editor "
            + version.orElse("<no verified host>") + "; supported exact versions: "
            + String.join(", ", supported) + ".";
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
