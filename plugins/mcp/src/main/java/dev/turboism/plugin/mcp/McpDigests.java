package dev.turboism.plugin.mcp;

import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Computes deterministic canonical JSON representations and lowercase SHA-256 hex digests for MCP. */
final class McpDigests {

    private static final String ALGORITHM = "SHA-256";

    private McpDigests() {}

    static String sha256Hex(final byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(ALGORITHM).digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static Object canonical(final Object value) {
        if (value instanceof Map<?, ?> map) {
            final var sorted = new TreeMap<String, Object>();
            map.forEach((k, v) -> sorted.put((String) k, canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(McpDigests::canonical).toList();
        }
        return value;
    }

    static String canonicalJsonSha256Hex(final Object value) {
        final String encoded = Json.stringify(Map.of("value", canonical(value)));
        return sha256Hex(encoded.getBytes(StandardCharsets.UTF_8));
    }
}
