package dev.turboism.core.descriptor;

/**
 * Checked failure raised when a plugin manifest cannot be turned into a descriptor.
 *
 * <p>Carries a stable machine-readable {@link #code()} alongside the human-readable message;
 * callers should branch on the code and treat the message as diagnostic text only.</p>
 */
public final class DescriptorParseException extends Exception {

    private final String code;
    private final String path;

    public DescriptorParseException(String code, String message) {
        super(message);
        this.code = code;
        this.path = "";
    }

    public DescriptorParseException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.path = "";
    }

    public DescriptorParseException(String code, String message, String path) {
        super(message);
        this.code = code;
        this.path = path;
    }

    /** @return the stable diagnostic code identifying which manifest rule was broken */
    public String code() {
        return code;
    }

    /**
     * @return the JSON pointer or field path within the manifest that caused the failure; empty
     *     string when the failure is not attributable to one location
     */
    public String path() {
        return path;
    }
}
