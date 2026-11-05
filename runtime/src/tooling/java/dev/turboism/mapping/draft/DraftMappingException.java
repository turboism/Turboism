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

    /**
     * @return the stable failure code the pipeline reports and the CLI prints ahead of the
     *     message, such as {@code WORKTREE_ID_INVALID} or {@code JSON_WRITE_FAILED}; branch on this
     *     rather than on the message text
     */
    public String code() {
        return code;
    }
}
