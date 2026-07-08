package dev.turboism.sdk.cubism;

import java.util.Objects;

public class CubismServiceException extends Exception {

    private final String code;

    public CubismServiceException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public CubismServiceException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }

    public String message() {
        return getMessage();
    }
}
