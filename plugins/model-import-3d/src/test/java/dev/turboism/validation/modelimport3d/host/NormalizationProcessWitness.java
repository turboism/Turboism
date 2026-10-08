package dev.turboism.validation.modelimport3d.host;

import java.util.Base64;
import java.util.HexFormat;
import java.security.MessageDigest;

/** Test-only independent JVM witness; no filesystem, network or editor access. */
public final class NormalizationProcessWitness {
    public static void main(String[] args) throws Exception {
        byte[] normalized=ModelInputNormalizer.normalize(Base64.getDecoder().decode(args[0]));
        System.out.println(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized)));
    }
}
