package dev.turboism.plugin.acp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Computes lowercase hexadecimal SHA-256 digests for ACP identifiers. */
final class AcpDigests {

    private static final String ALGORITHM = "SHA-256";

    private AcpDigests() {}

    static String sha256Hex(final String text) {
        Objects.requireNonNull(text, "text");
        try {
            final MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
