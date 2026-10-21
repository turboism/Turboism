package dev.turboism.plugin.atlasnesting.layout;

/** Expected layout rejection, translated at the plugin boundary without coupling the kernel to UI services. */
public final class NestingFailure extends IllegalArgumentException {
    private final String messageKey;
    private final Object[] arguments;

    public NestingFailure(final String code, final Object... arguments) {
        super("Bitmask Nesting: " + code + " " + java.util.Arrays.toString(arguments));
        this.messageKey = "texture-atlas.nesting.error." + code;
        this.arguments = arguments.clone();
    }

    /** Returns the plugin catalog key describing this rejection. */
    public String messageKey() {
        return messageKey;
    }

    /** Returns a defensive copy of the values to substitute into the translated message. */
    public Object[] arguments() {
        return arguments.clone();
    }
}
