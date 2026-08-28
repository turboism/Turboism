package dev.turboism.plugin.turboismwithfx;

import dev.turboism.sdk.plugin.PluginPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Resolves and verifies the managed fx executable, with an explicit advanced override. */
final class FxRuntimeResolver {

    private static final int DIGEST_BUFFER_BYTES = 64 * 1024;

    private final PluginPaths paths;
    private final PlatformDetector platformDetector;

    FxRuntimeResolver(final PluginPaths paths) {
        this(paths, FxRuntimePlatform::detect);
    }

    FxRuntimeResolver(final PluginPaths paths, final PlatformDetector platformDetector) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.platformDetector = Objects.requireNonNull(platformDetector, "platformDetector");
    }

    /**
     * Resolves the executable to launch. A non-blank custom value remains an advanced user-owned
     * override; otherwise the current platform must have an installed, hash-matching managed payload.
     */
    Resolution resolve(final String customExecutable) {
        if (customExecutable != null && !customExecutable.isBlank()) {
            return resolveCustom(customExecutable.strip());
        }
        final FxRuntimePlatform platform = platformDetector.detect().orElse(null);
        if (platform == null) return new Resolution.Unavailable(Problem.PLATFORM_UNSUPPORTED, null);
        final FxRuntimeManifest.Entry entry = FxRuntimeManifest.entry(platform).orElse(null);
        if (entry == null) return new Resolution.Unavailable(Problem.PLATFORM_UNSUPPORTED, platform.id());
        final Path executable;
        try {
            final Path managedRoot = confinedManagedRoot();
            executable = managedRoot.resolve(
                FxRuntimeManifest.VERSION + "/" + platform.id() + "/" + platform.executableName()
            ).normalize();
            if (!executable.startsWith(managedRoot)
                || !hasOrdinaryManagedAncestors(managedRoot, executable.getParent())) {
                return new Resolution.Unavailable(Problem.RUNTIME_INVALID, platform.id());
            }
            if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(executable)
                || Files.size(executable) != entry.executableSize()) {
                return new Resolution.Unavailable(Problem.RUNTIME_MISSING, platform.id());
            }
            if (!entry.executableSha256().equals(sha256(executable))) {
                return new Resolution.Unavailable(Problem.RUNTIME_INVALID, platform.id());
            }
            if (!hasOrdinaryManagedAncestors(managedRoot, executable.getParent())
                || !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(executable)) {
                return new Resolution.Unavailable(Problem.RUNTIME_INVALID, platform.id());
            }
            if (!ownerExecutable(executable)) {
                return new Resolution.Unavailable(Problem.RUNTIME_INVALID, platform.id());
            }
            return new Resolution.Available(
                executable.toString(),
                Source.MANAGED,
                platform.id(),
                new FxLaunchConfiguration.ManagedRuntimeIdentity(
                    entry.executableSize(),
                    entry.executableSha256()
                )
            );
        } catch (IOException | RuntimeException failure) {
            return new Resolution.Unavailable(Problem.RUNTIME_INVALID, platform.id());
        }
    }

    private Resolution resolveCustom(final String customExecutable) {
        final Path executable;
        try {
            executable = Path.of(customExecutable);
        } catch (RuntimeException failure) {
            return new Resolution.Unavailable(Problem.RUNTIME_INVALID, null);
        }
        if (!executable.isAbsolute()) {
            return new Resolution.Unavailable(Problem.RUNTIME_INVALID, null);
        }
        final Path normalized = executable.normalize();
        try {
            if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)) {
                return new Resolution.Unavailable(Problem.RUNTIME_INVALID, null);
            }
            return new Resolution.Available(normalized.toString(), Source.CUSTOM, null, null);
        } catch (RuntimeException failure) {
            return new Resolution.Unavailable(Problem.RUNTIME_INVALID, null);
        }
    }

    Path managedRoot() {
        return confinedManagedRoot();
    }

    private Path confinedManagedRoot() {
        final Path data = paths.dataDir().toAbsolutePath().normalize();
        final Path parent = data.getParent();
        final Path grandparent = parent == null ? null : parent.getParent();
        if (grandparent == null || !"data".equals(parent.getFileName().toString())) {
            throw new IllegalStateException("plugin data directory is outside the Turboism home layout");
        }
        return grandparent.resolve("runtimes/fx").toAbsolutePath().normalize();
    }

    private static boolean hasOrdinaryManagedAncestors(
        final Path managedRoot,
        final Path parent
    ) {
        final Path home = managedRoot.getParent().getParent();
        if (home == null || Files.isSymbolicLink(home)
            || !Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path current = home;
        for (Path segment : home.relativize(parent)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            if (Files.isSymbolicLink(current)
                || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
        }
        return true;
    }

    private static boolean ownerExecutable(final Path executable) throws IOException {
        try {
            return Files.getPosixFilePermissions(executable, LinkOption.NOFOLLOW_LINKS)
                .contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
        } catch (UnsupportedOperationException ignored) {
            // Windows and non-POSIX filesystems use their native executable semantics.
            return true;
        }
    }

    static String sha256(final Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        try (InputStream input = Files.newInputStream(path)) {
            final byte[] buffer = new byte[DIGEST_BUFFER_BYTES];
            while (true) {
                final int read = input.read(buffer);
                if (read < 0) break;
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    enum Source {
        MANAGED,
        CUSTOM
    }

    enum Problem {
        PLATFORM_UNSUPPORTED,
        RUNTIME_MISSING,
        RUNTIME_INVALID
    }

    sealed interface Resolution permits Resolution.Available, Resolution.Unavailable {
        record Available(
            String executable,
            Source source,
            String platformId,
            FxLaunchConfiguration.ManagedRuntimeIdentity managedRuntime
        ) implements Resolution {
            public Available {
                executable = Objects.requireNonNull(executable, "executable");
                source = Objects.requireNonNull(source, "source");
                if (source == Source.MANAGED) {
                    managedRuntime = Objects.requireNonNull(managedRuntime, "managedRuntime");
                } else if (managedRuntime != null) {
                    throw new IllegalArgumentException("custom runtime cannot carry managed identity");
                }
            }
        }

        record Unavailable(Problem problem, String platformId) implements Resolution {
            public Unavailable {
                problem = Objects.requireNonNull(problem, "problem");
            }
        }
    }

    @FunctionalInterface
    interface PlatformDetector {
        Optional<FxRuntimePlatform> detect();
    }
}
