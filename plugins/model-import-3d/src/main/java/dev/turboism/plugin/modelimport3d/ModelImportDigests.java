package dev.turboism.plugin.modelimport3d;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Computes lowercase hexadecimal SHA-256 digests for deterministic 3D model import artifacts. */
final class ModelImportDigests {

    private static final String ALGORITHM = "SHA-256";

    private ModelImportDigests() {}

    static String sha256Hex(final byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(ALGORITHM).digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
