package dev.turboism.sdk.mcp;

import dev.turboism.sdk.Incubating;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Loopback HTTP connection to a Turboism-owned MCP server. The snapshot carries no credential;
 * the bearer that authorizes mutating operations is read only by the stdio bridge process inside
 * the MCP plugin state directory.
 */
@Incubating
public final class McpHttpConnection {

    private final URI endpoint;
    private final String protocolVersion;
    private final McpStdioLaunch stdioLaunch;

    /**
     * Creates a validated loopback MCP connection snapshot.
     *
     * @param endpoint loopback HTTP or HTTPS endpoint without user-info, query, or fragment
     * @param protocolVersion negotiated MCP protocol version
     */
    public McpHttpConnection(final URI endpoint, final String protocolVersion) {
        this(endpoint, protocolVersion, null);
    }

    /**
     * Creates a validated loopback MCP connection snapshot with an optional stdio launch.
     *
     * @param endpoint loopback HTTP or HTTPS endpoint without user-info, query, or fragment
     * @param protocolVersion negotiated MCP protocol version
     * @param stdioLaunch credential-free stdio bridge launch descriptor, or {@code null}
     */
    public McpHttpConnection(final URI endpoint, final String protocolVersion, final McpStdioLaunch stdioLaunch) {
        this.endpoint = requireEndpoint(endpoint);
        this.protocolVersion = requireText(protocolVersion, "protocolVersion", 64);
        this.stdioLaunch = stdioLaunch;
    }

    /** @return the loopback Streamable HTTP endpoint */
    public URI endpoint() {
        return endpoint;
    }

    /** @return the MCP protocol version advertised by the server */
    public String protocolVersion() {
        return protocolVersion;
    }

    /**
     * Returns the credential-free stdio bridge launch descriptor when the publisher computed
     * one for this endpoint.
     *
     * @return the stdio launch descriptor, or empty when stdio attachment is unavailable
     */
    public Optional<McpStdioLaunch> stdioLaunch() {
        return Optional.ofNullable(stdioLaunch);
    }

    @Override
    public String toString() {
        return "McpHttpConnection[endpoint=" + endpoint + ", protocolVersion=" + protocolVersion + "]";
    }

    private static URI requireEndpoint(final URI value) {
        final URI endpoint = Objects.requireNonNull(value, "endpoint");
        final String scheme = endpoint.getScheme();
        final String host = endpoint.getHost();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("endpoint must use HTTP or HTTPS");
        }
        if (host == null
                || !(host.equals("127.0.0.1")
                        || host.equals("::1")
                        || host.toLowerCase(Locale.ROOT).equals("localhost"))) {
            throw new IllegalArgumentException("endpoint must use a loopback host");
        }
        if (endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("endpoint must not contain user-info, query, or fragment");
        }
        return endpoint;
    }

    private static String requireText(final String value, final String name, final int maximumLength) {
        final String text = Objects.requireNonNull(value, name);
        if (text.isBlank() || text.length() > maximumLength || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return text;
    }
}
