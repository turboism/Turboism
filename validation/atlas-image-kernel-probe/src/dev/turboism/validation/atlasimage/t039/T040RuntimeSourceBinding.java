package dev.turboism.validation.atlasimage.t039;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;

/**
 * Finite runtime-source binding for the official shadow path.
 *
 * <p>The ProtectionDomain supplies the observed source. The bounded property is
 * a task-local list of trusted target candidates, not a build-machine golden
 * path. Exactly one regular file is permitted and its bytes must match the
 * fixed official JAR hash before a candidate can be returned.</p>
 */
final class T040RuntimeSourceBinding {
    static final String TARGET_PROTECTION_DOMAIN = "target-pd";

    private T040RuntimeSourceBinding() {
    }

    static Binding bind(
        final ProtectionDomain protectionDomain,
        final ClassLoader loader,
        final String expectedLoaderClass,
        final String expectedJarSha256,
        final String trustedSourcePaths
    ) throws IOException {
        if (loader == null || expectedLoaderClass == null
                || !expectedLoaderClass.equals(loader.getClass().getName())) {
            throw new IllegalArgumentException("runtime-source-loader-gate");
        }
        final Path observed = observedFile(protectionDomain);
        final Set<Path> trusted = parseTrustedSourcePaths(trustedSourcePaths);
        if (trusted.size() != 1) {
            throw new IllegalArgumentException("runtime-source-candidate-count");
        }
        final Path onlyTrusted = trusted.iterator().next();
        if (!observed.equals(onlyTrusted)) {
            throw new IllegalArgumentException("runtime-source-path-gate");
        }
        final String actualHash = sha256File(observed);
        if (!expectedJarSha256.equals(actualHash)) {
            throw new IllegalArgumentException("runtime-source-jar-hash-gate");
        }
        return new Binding(observed, actualHash, loader);
    }

    static void verify(
        final Binding binding,
        final ProtectionDomain protectionDomain,
        final ClassLoader loader,
        final String expectedLoaderClass,
        final String expectedJarSha256
    ) throws IOException {
        if (binding == null || loader == null || expectedLoaderClass == null
                || !expectedLoaderClass.equals(loader.getClass().getName())
                || binding.loader() != loader) {
            throw new IllegalArgumentException("runtime-source-binding-loader-gate");
        }
        final Path observed = observedFile(protectionDomain);
        if (!binding.source().equals(observed)) {
            throw new IllegalArgumentException("runtime-source-binding-path-gate");
        }
        final String actualHash = sha256File(observed);
        if (!expectedJarSha256.equals(actualHash)
                || !binding.jarSha256().equals(actualHash)) {
            throw new IllegalArgumentException("runtime-source-binding-hash-gate");
        }
    }

    private static Set<Path> parseTrustedSourcePaths(final String value) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 4096) {
            throw new IllegalArgumentException("runtime-source-candidates-missing");
        }
        final String[] parts = value.split(java.util.regex.Pattern.quote(
            java.io.File.pathSeparator), -1);
        final Set<Path> result = new HashSet<>();
        for (final String part : parts) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("runtime-source-candidate-empty");
            }
            final Path path = Path.of(part).toRealPath();
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("runtime-source-candidate-not-file");
            }
            if (!result.add(path)) {
                throw new IllegalArgumentException("runtime-source-candidate-duplicate");
            }
        }
        return result;
    }

    private static Path observedFile(final ProtectionDomain protectionDomain) {
        if (protectionDomain == null) {
            throw new IllegalArgumentException("runtime-source-protection-domain-missing");
        }
        final CodeSource codeSource = protectionDomain.getCodeSource();
        if (codeSource == null) {
            throw new IllegalArgumentException("runtime-source-code-source-missing");
        }
        final URL location = codeSource.getLocation();
        if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
            throw new IllegalArgumentException("runtime-source-non-file");
        }
        try {
            final URI uri = location.toURI();
            final Path path = Path.of(uri).toRealPath();
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("runtime-source-not-file");
            }
            return path;
        } catch (final IOException exception) {
            throw new IllegalArgumentException("runtime-source-unreadable", exception);
        } catch (final Exception exception) {
            throw new IllegalArgumentException("runtime-source-uri", exception);
        }
    }

    private static String sha256File(final Path path) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(path)) {
                final byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            return hex(digest.digest());
        } catch (final NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }

    private static String hex(final byte[] value) {
        final StringBuilder result = new StringBuilder(value.length * 2);
        for (final byte next : value) {
            result.append(String.format("%02x", next & 0xff));
        }
        return result.toString();
    }

    record Binding(Path source, String jarSha256, ClassLoader loader) {
    }
}
