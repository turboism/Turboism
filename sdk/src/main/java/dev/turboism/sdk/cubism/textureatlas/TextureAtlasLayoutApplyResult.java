package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

/** Closed result for a texture-atlas layout apply attempt. */
@PreviewApi
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

    public static TextureAtlasLayoutApplyResult applied() {
        return success(TextureAtlasLayoutApplyStatus.APPLIED);
    }

    public static TextureAtlasLayoutApplyResult noChange() {
        return success(TextureAtlasLayoutApplyStatus.NO_CHANGE);
    }

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
