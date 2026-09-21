package dev.turboism.adapter.cubism.integration;

/**
 * Error codes emitted inside an official-shaped response envelope.
 *
 * <p>The wire names match the host's {@code j$e} enum
 * ({@code host-evidence/integration-54compat/dispatcher-internals.md} §7): the host serializes
 * them as {@code { "ErrorType" : "<wireName>" }} in the {@code Data} field of a
 * {@code Type="Error"} envelope. {@link #INVALID_EDIT_OPERATION} is an editing-API code the
 * low-version enum does not carry; it is minted by this layer and never written back into host
 * state.</p>
 */
public enum EditApiErrorCode {
    INVALID_JSON("InvalidJson"),
    UNSUPPORTED_VERSION("UnsupportedVersion"),
    METHOD_NOT_FOUND("MethodNotFound"),
    INVALID_TYPE("InvalidType"),
    INVALID_DATA("InvalidData"),
    INVALID_PARAMETER("InvalidParameter"),
    INVALID_MODEL("InvalidModel"),
    INVALID_DOCUMENT("InvalidDocument"),
    INVALID_VIEW("InvalidView"),
    PLUGIN_NOT_REGISTERED("PluginNotRegistered"),
    INVALID_EDIT_OPERATION("InvalidEditOperation"),
    NO_ERROR("NoError");

    private final String wireName;

    EditApiErrorCode(final String wireName) {
        this.wireName = wireName;
    }

    /** The exact string the official responder places in {@code Data.ErrorType}. */
    public String wireName() {
        return wireName;
    }
}
