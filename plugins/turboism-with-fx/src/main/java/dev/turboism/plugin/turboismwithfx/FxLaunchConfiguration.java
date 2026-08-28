package dev.turboism.plugin.turboismwithfx;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable, validated managed-or-custom fx process launch configuration. */
record FxLaunchConfiguration(
    String executable,
    Path workingDirectory,
    FxSecurityMode securityMode,
    ManagedRuntimeIdentity managedRuntime
) {
    FxLaunchConfiguration(
        final String executable,
        final Path workingDirectory,
        final FxSecurityMode securityMode
    ) {
        this(executable, workingDirectory, securityMode, null);
    }

    FxLaunchConfiguration {
        executable = requireExecutable(executable);
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
            .toAbsolutePath().normalize();
        securityMode = Objects.requireNonNull(securityMode, "securityMode");
        if (managedRuntime != null) {
            managedRuntime = Objects.requireNonNull(managedRuntime, "managedRuntime");
        }
    }

    /**
     * Returns the fixed ACP command. Arguments are never interpreted by a shell, preventing a
     * persisted executable path from gaining command-string semantics.
     */
    List<String> command() {
        return List.of(executable, "acp");
    }

    boolean permitsStockFx() {
        return securityMode == FxSecurityMode.FX_NATIVE_TOOLS;
    }

    record ManagedRuntimeIdentity(long size, String sha256) {
        ManagedRuntimeIdentity {
            if (size <= 0L) {
                throw new IllegalArgumentException("managed runtime size must be positive");
            }
            sha256 = Objects.requireNonNull(sha256, "sha256");
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("managed runtime SHA-256 is invalid");
            }
        }
    }

    private static String requireExecutable(final String value) {
        final String executable = Objects.requireNonNull(value, "executable").strip();
        if (executable.isEmpty() || executable.length() > 4096
            || executable.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("executable is invalid");
        }
        return executable;
    }
}
