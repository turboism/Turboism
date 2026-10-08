package dev.turboism.core.net;

import java.net.URI;

/**
 * Admission policy for links the runtime may hand to the OS desktop browser. Plugin-provided
 * URLs reach these checks, so only absolute {@code http}/{@code https} URIs with a host are
 * accepted; {@code file:}, {@code smb:}, {@code javascript:} and custom schemes are refused.
 */
public final class HttpLinks {

    private HttpLinks() {}

    /**
     * Returns whether {@code value} parses as an allowed link target. Non-parseable input is
     * refused rather than raising.
     */
    public static boolean isAllowed(final String value) {
        try {
            return value != null && isAllowed(URI.create(value));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Returns whether {@code uri} is an allowed link target. */
    public static boolean isAllowed(final URI uri) {
        return uri != null
                && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null;
    }
}
