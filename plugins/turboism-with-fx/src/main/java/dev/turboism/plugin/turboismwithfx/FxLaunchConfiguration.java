package dev.turboism.plugin.turboismwithfx;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable, validated managed-or-custom fx process launch configuration. */
record FxLaunchConfiguration(
    String executable,
    Path workingDirectory,
    FxSecurityMode securityMode,
    ManagedRuntimeIdentity managedRuntime,
    java.util.Map<String, String> environment
) {
    FxLaunchConfiguration(
        final String executable,
        final Path workingDirectory,
        final FxSecurityMode securityMode
    ) {
        this(executable, workingDirectory, securityMode, null, java.util.Map.of());
    }

    FxLaunchConfiguration(
        final String executable,
        final Path workingDirectory,
        final FxSecurityMode securityMode,
        final ManagedRuntimeIdentity managedRuntime
    ) {
        this(executable, workingDirectory, securityMode, managedRuntime, java.util.Map.of());
    }

    FxLaunchConfiguration {
        executable = requireExecutable(executable);
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
            .toAbsolutePath().normalize();
        securityMode = Objects.requireNonNull(securityMode, "securityMode");
        if (managedRuntime != null) {
            managedRuntime = Objects.requireNonNull(managedRuntime, "managedRuntime");
        }
        final java.util.LinkedHashMap<String, String> checkedEnvironment =
            new java.util.LinkedHashMap<>();
        Objects.requireNonNull(environment, "environment").forEach((name, value) -> {
            final String key = Objects.requireNonNull(name, "environment name");
            final String text = Objects.requireNonNull(value, "environment value");
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                || text.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("process environment is invalid");
            }
            checkedEnvironment.put(key, text);
        });
        environment = java.util.Map.copyOf(checkedEnvironment);
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
