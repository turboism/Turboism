package dev.turboism.mapping.verification;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Binds runtime owner CodeSource and exposed class-resource bytes to the verified artifact. */
final class HostClassSourceAttestor {

    void attest(
        final Path verifiedArtifact,
        final ClassLoader hostClassLoader,
        final List<StaticSelector> selectors
    ) throws IOException {
        Objects.requireNonNull(verifiedArtifact, "verifiedArtifact");
        Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        Objects.requireNonNull(selectors, "selectors");
        final Set<String> owners = new HashSet<>();
        try (JarFile jar = new JarFile(verifiedArtifact.toFile(), false)) {
            for (StaticSelector selector : selectors) {
                if (!owners.add(selector.ownerInternalName())) {
                    continue;
                }
                attestOwner(verifiedArtifact, jar, hostClassLoader, selector.ownerInternalName());
            }
        }
    }

    private void attestOwner(
        final Path verifiedArtifact,
        final JarFile jar,
        final ClassLoader hostClassLoader,
        final String ownerInternalName
    ) throws IOException {
        final String entryName = ownerInternalName + ".class";
        final JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) {
            throw new IllegalArgumentException("verified artifact is missing selector owner " + ownerInternalName);
        }
        final Class<?> runtimeOwner;
        try {
            runtimeOwner = Class.forName(ownerInternalName.replace('/', '.'), false, hostClassLoader);
        } catch (ClassNotFoundException | LinkageError exception) {
            throw new IllegalArgumentException("runtime selector owner is unavailable " + ownerInternalName);
        }
        if (runtimeOwner.getClassLoader() != hostClassLoader) {
            throw new IllegalArgumentException("runtime selector owner was not defined by the attested host classloader");
        }
        final java.security.CodeSource codeSource = runtimeOwner.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            throw new IllegalArgumentException("runtime selector owner has no attestable code source");
        }
        final Path runtimeSource;
        try {
            runtimeSource = Path.of(codeSource.getLocation().toURI()).toRealPath();
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("runtime selector owner code source is invalid");
        }
        if (!runtimeSource.equals(verifiedArtifact.toRealPath())) {
            throw new IllegalArgumentException("runtime selector owner code source is not the verified artifact: "
                + ownerInternalName + " loaded from " + runtimeSource);
        }
        try (InputStream expected = jar.getInputStream(entry);
             InputStream actual = runtimeOwner.getResourceAsStream("/" + entryName)) {
            if (actual == null) {
                throw new IllegalArgumentException("runtime selector owner bytes are unavailable for attestation");
            }
            if (!digest(expected).equals(digest(actual))) {
                throw new IllegalArgumentException(
                    "runtime selector owner bytes do not match the verified artifact: " + ownerInternalName
                );
            }
        }
    }

    private static String digest(final InputStream input) throws IOException {
        final MessageDigest digest = HostArtifactDigest.sha256Digest();
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
