package dev.turboism.installer;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Validates the one-line Programs known-folder output used during uninstall. */
final class WindowsProgramsPath {

    private WindowsProgramsPath() {
    }

    static Path parse(String raw) {
        if (raw == null) {
            return null;
        }
        String programs = raw.trim();
        if (programs.isEmpty() || programs.contains("\r") || programs.contains("\n")) {
            return null;
        }
        try {
            Path directory = Paths.get(programs);
            if (!directory.isAbsolute()) {
                return null;
            }
            return directory.toAbsolutePath().normalize().resolve("Turboism").normalize();
        } catch (RuntimeException failure) {
            return null;
        }
    }
}
