package dev.turboism.plugin.turboismwithfx;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Exact operating-system and CPU pair used to select one reviewed managed fx payload. */
record FxRuntimePlatform(OperatingSystem operatingSystem, Architecture architecture) {

    FxRuntimePlatform {
        operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
        architecture = Objects.requireNonNull(architecture, "architecture");
    }

    /** Detects the current JVM host without accepting partial or ambiguous architecture aliases. */
    static Optional<FxRuntimePlatform> detect() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static Optional<FxRuntimePlatform> detect(final String osName, final String architectureName) {
        final OperatingSystem os = OperatingSystem.parse(osName);
        final Architecture architecture = Architecture.parse(architectureName);
        return os == null || architecture == null
            ? Optional.empty()
            : Optional.of(new FxRuntimePlatform(os, architecture));
    }

    String id() {
        return operatingSystem.id + "-" + architecture.id;
    }

    String executableName() {
        return operatingSystem == OperatingSystem.WINDOWS ? "fx.exe" : "fx";
    }

    enum OperatingSystem {
        WINDOWS("windows"),
        LINUX("linux"),
        MACOS("macos");

        private final String id;

        OperatingSystem(final String id) {
            this.id = id;
        }

        private static OperatingSystem parse(final String value) {
            final String normalized = Objects.requireNonNullElse(value, "")
                .strip()
                .toLowerCase(Locale.ROOT);
            if (normalized.startsWith("windows")) return WINDOWS;
            if (normalized.equals("linux") || normalized.startsWith("linux ")) return LINUX;
            if (normalized.equals("mac os x") || normalized.equals("macos")
                || normalized.equals("darwin")) {
                return MACOS;
            }
            return null;
        }
    }

    enum Architecture {
        X86_64("x86_64"),
        AARCH64("aarch64");

        private final String id;

        Architecture(final String id) {
            this.id = id;
        }

        private static Architecture parse(final String value) {
            return switch (Objects.requireNonNullElse(value, "")
                .strip()
                .toLowerCase(Locale.ROOT)) {
                case "amd64", "x86_64", "x64" -> X86_64;
                case "aarch64", "arm64" -> AARCH64;
                default -> null;
            };
        }
    }
}
