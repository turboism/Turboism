package dev.turboism.core.runtime.sidecar;

public record SidecarResult(
    Kind kind,
    String payload,
    String errorCode,
    String errorMessage
) {

    public SidecarResult {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        payload = payload == null ? "" : payload;
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static SidecarResult success(final String payload) {
        return new SidecarResult(Kind.SUCCESS, payload, "", "");
    }

    public static SidecarResult error(final String errorCode, final String errorMessage) {
        return new SidecarResult(Kind.ERROR, "", errorCode, errorMessage);
    }

    public static SidecarResult timeout(final String errorMessage) {
        return new SidecarResult(Kind.TIMEOUT, "", "SIDECAR_TIMEOUT", errorMessage);
    }

    public enum Kind {
        SUCCESS,
        ERROR,
        TIMEOUT
    }
}
