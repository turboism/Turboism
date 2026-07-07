package dev.turboism.core.descriptor;

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

    public String code() {
        return code;
    }

    public String path() {
        return path;
    }
}
