package dev.turboism.sdk.cubism.textureatlas;


import java.util.Objects;
import java.util.Optional;

/** Closed result for a texture-atlas layout apply attempt. */
public record TextureAtlasLayoutApplyResult(
    Optional<TextureAtlasLayoutApplyStatus> status,
    Optional<TextureAtlasLayoutFailureCode> failureCode,
    Optional<String> message
) {
    public TextureAtlasLayoutApplyResult {
        status = Objects.requireNonNull(status, "status");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        message = Objects.requireNonNull(message, "message");
        if (status.isPresent() == failureCode.isPresent()) {
            throw new IllegalArgumentException("exactly one of status/failureCode must be present");
        }
        if (status.isPresent() && message.isPresent()) {
            throw new IllegalArgumentException("successful results must not contain a failure message");
        }
        if (failureCode.isPresent() && message.filter(value -> !value.isBlank()).isEmpty()) {
            throw new IllegalArgumentException("failed results require a non-blank message");
        }
    }

    /** @return a successful result recording that the host layout was changed */
    public static TextureAtlasLayoutApplyResult applied() {
        return success(TextureAtlasLayoutApplyStatus.APPLIED);
    }

    /**
     * @return a successful result recording that the requested layout already matched the host's,
     *     so nothing was written; this is a success, not a rejection
     */
    public static TextureAtlasLayoutApplyResult noChange() {
        return success(TextureAtlasLayoutApplyStatus.NO_CHANGE);
    }

    /**
     * Builds a failed result. The message is operator-facing diagnostic text and is mandatory:
     * the canonical constructor rejects a failure whose message is absent or blank.
     *
     * @param code machine-readable reason the apply did not go through
     * @param message non-blank explanation of the failure
     * @return a result carrying the failure code and message and no status
     * @throws NullPointerException if {@code code} or {@code message} is null
     * @throws IllegalArgumentException if {@code message} is blank
     */
    public static TextureAtlasLayoutApplyResult failed(
        final TextureAtlasLayoutFailureCode code,
        final String message
    ) {
        return new TextureAtlasLayoutApplyResult(
            Optional.empty(),
            Optional.of(Objects.requireNonNull(code, "code")),
            Optional.of(Objects.requireNonNull(message, "message"))
        );
    }

    private static TextureAtlasLayoutApplyResult success(final TextureAtlasLayoutApplyStatus status) {
        return new TextureAtlasLayoutApplyResult(Optional.of(status), Optional.empty(), Optional.empty());
    }
}
