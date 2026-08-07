package dev.turboism.plugin.backup.webdav;

import java.net.URI;
import java.util.Objects;

/**
 * Immutable WebDAV endpoint configuration. The password is never rendered by
 * {@link #toString()} (redacted) and never written to logs by the client.
 *
 * <p>{@code verifyTls=false} disables server certificate validation; it exists
 * for private self-signed NAS endpoints and should stay {@code true} by
 * default.</p>
 */
public record WebDavConfig(
    boolean enabled,
    URI url,
    String username,
    String password,
    String remotePath,
    boolean verifyTls,
    int retryMax,
    long retryBaseDelayMs,
    int timeoutSeconds
) {

    public WebDavConfig {
        Objects.requireNonNull(url, "url");
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("url must use http or https");
        }
        if (url.getUserInfo() != null) {
            throw new IllegalArgumentException("url must not embed userinfo");
        }
        if (retryMax < 0 || retryMax > 10) {
            throw new IllegalArgumentException("retryMax must be within [0,10]");
        }
        if (retryBaseDelayMs < 0 || retryBaseDelayMs > 60_000) {
            throw new IllegalArgumentException("retryBaseDelayMs must be within [0,60000]");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("timeoutSeconds must be within [1,300]");
        }
        String normalizedPath = normalizePath(remotePath == null ? "" : remotePath);
        remotePath = normalizedPath;
    }

    /**
     * Normalizes a remote collection path: single leading slash, no trailing
     * slash (the empty root becomes "/"), collapsed dot segments, and no
     * parent-escape segments.
     */
    public static String normalizePath(final String raw) {
        String value = Objects.requireNonNull(raw, "raw").replace('\\', '/');
        boolean trailingSlash = value.endsWith("/");
        String[] segments = value.split("/");
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (stack.isEmpty()) {
                    throw new IllegalArgumentException("remotePath must not escape the collection root");
                }
                stack.removeLast();
                continue;
            }
            stack.addLast(segment);
        }
        String joined = String.join("/", stack);
        if (joined.isEmpty()) {
            return "/";
        }
        return trailingSlash ? "/" + joined + "/" : "/" + joined;
    }

    @Override
    public String toString() {
        return "WebDavConfig[enabled=" + enabled + ", url=" + url
            + ", username=" + username + ", password=<redacted>"
            + ", remotePath=" + remotePath + ", verifyTls=" + verifyTls
            + ", retryMax=" + retryMax + ", retryBaseDelayMs=" + retryBaseDelayMs
            + ", timeoutSeconds=" + timeoutSeconds + "]";
    }
}
