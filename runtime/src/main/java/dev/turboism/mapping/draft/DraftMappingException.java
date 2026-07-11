package dev.turboism.mapping.draft;

/** Fail-closed error raised by the local mapping review pipeline. */
public final class DraftMappingException extends RuntimeException {
    private final String code;

    public DraftMappingException(final String code, final String message) {
        super(message);
        this.code = code;
    }

    public DraftMappingException(final String code, final String message, final Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
