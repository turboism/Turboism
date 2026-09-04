package dev.turboism.sdk.mcp;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Credential-free loopback HTTP connection to a Turboism-owned MCP server. */
public final class McpHttpConnection {

    private final URI endpoint;
    private final String protocolVersion;

    /**
     * Creates a validated loopback MCP connection snapshot.
     *
     * @param endpoint loopback HTTP or HTTPS endpoint without user-info, query, or fragment
     * @param protocolVersion negotiated MCP protocol version
     */
    public McpHttpConnection(
        final URI endpoint,
        final String protocolVersion
    ) {
        this.endpoint = requireEndpoint(endpoint);
        this.protocolVersion = requireText(protocolVersion, "protocolVersion", 64);
    }

    /** @return the loopback Streamable HTTP endpoint */
    public URI endpoint() {
        return endpoint;
    }

    /** @return the MCP protocol version advertised by the server */
    public String protocolVersion() {
        return protocolVersion;
    }

    @Override
    public String toString() {
        return "McpHttpConnection[endpoint=" + endpoint
            + ", protocolVersion=" + protocolVersion + "]";
    }

    private static URI requireEndpoint(final URI value) {
        final URI endpoint = Objects.requireNonNull(value, "endpoint");
        final String scheme = endpoint.getScheme();
        final String host = endpoint.getHost();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("endpoint must use HTTP or HTTPS");
        }
        if (host == null || !(host.equals("127.0.0.1") || host.equals("::1")
            || host.toLowerCase(Locale.ROOT).equals("localhost"))) {
            throw new IllegalArgumentException("endpoint must use a loopback host");
        }
        if (endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
            || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("endpoint must not contain user-info, query, or fragment");
        }
        return endpoint;
    }

    private static String requireText(final String value, final String name, final int maximumLength) {
        final String text = Objects.requireNonNull(value, name);
        if (text.isBlank() || text.length() > maximumLength
            || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return text;
    }
}
