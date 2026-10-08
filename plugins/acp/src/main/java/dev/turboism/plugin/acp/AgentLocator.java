package dev.turboism.plugin.acp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Read-only discovery of user-installed ACP agent executables.
 *
 * <p>Detection searches the inherited {@code PATH} plus well-known per-user install directories
 * for the executable names declared by an {@link AgentProfile}, or resolves a user-supplied
 * absolute path directly. The result is always an absolute path to a regular file so the reported
 * executable cannot silently change between detection and launch.</p>
 */
final class AgentLocator {

    private static final int MAX_DIRECTORIES = 256;

    private AgentLocator() {}

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** Finds the first executable candidate of {@code profile} on PATH or in common install dirs. */
    static Optional<Path> locate(final AgentProfile profile) {
        Objects.requireNonNull(profile, "profile");
        for (String candidate : profile.executableCandidates()) {
            final Optional<Path> found = findExecutable(candidate);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    /**
     * Resolves a user-supplied executable reference.
     *
     * @param value absolute path, or a bare executable name searched on PATH only
     * @return validated absolute path
     * @throws IllegalArgumentException when the value is empty or resolves to no regular file
     */
    static Path resolve(final String value) {
        final String text = Objects.requireNonNull(value, "executable").strip();
        if (text.isEmpty() || text.indexOf('\0') >= 0 || text.length() > 4096) {
            throw new IllegalArgumentException("executable is invalid");
        }
        final Path asPath;
        try {
            asPath = Path.of(text);
        } catch (java.nio.file.InvalidPathException failure) {
            throw new IllegalArgumentException("executable path is invalid", failure);
        }
        if (asPath.isAbsolute() || text.indexOf(File.separatorChar) >= 0 || text.indexOf('/') >= 0) {
            return validate(asPath.toAbsolutePath().normalize());
        }
        return findExecutable(text)
                .orElseThrow(() -> new IllegalArgumentException("executable not found on PATH: " + text));
    }

    /** Finds a bare executable name on the inherited PATH and in common per-user directories. */
    static Optional<Path> findExecutable(final String baseName) {
        final String name = Objects.requireNonNull(baseName, "baseName").strip();
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("executable name is invalid");
        }
        for (Path directory : searchDirectories()) {
            for (String variant : executableVariants(name)) {
                final Path candidate = directory.resolve(variant);
                if (usable(candidate)) {
                    return Optional.of(candidate.toAbsolutePath().normalize());
                }
            }
        }
        return Optional.empty();
    }

    private static Path validate(final Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("executable is not a regular file: " + path);
        }
        return path;
    }

    private static boolean usable(final Path candidate) {
        try {
            if (!Files.isRegularFile(candidate)) return false;
            if (isWindows()) return true;
            return Files.isExecutable(candidate);
        } catch (SecurityException failure) {
            return false;
        }
    }

    /** Windows executables need a PATHEXT-style suffix; everywhere else the bare name is used. */
    private static List<String> executableVariants(final String baseName) {
        if (!isWindows()) return List.of(baseName);
        final String lower = baseName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".cmd") || lower.endsWith(".bat")) {
            return List.of(baseName);
        }
        return List.of(baseName + ".exe", baseName + ".cmd", baseName + ".bat");
    }

    private static List<Path> searchDirectories() {
        final LinkedHashSet<Path> directories = new LinkedHashSet<>();
        addPathEntries(directories, System.getenv("PATH"));
        addCommonInstallDirectories(directories);
        return directories.stream().limit(MAX_DIRECTORIES).toList();
    }

    private static void addPathEntries(final LinkedHashSet<Path> directories, final String path) {
        if (path == null) return;
        for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) continue;
            try {
                directories.add(Path.of(entry));
            } catch (java.nio.file.InvalidPathException ignored) {
                // An unparsable PATH entry cannot host an executable.
            }
        }
    }

    private static void addCommonInstallDirectories(final LinkedHashSet<Path> directories) {
        final String home = System.getProperty("user.home", "");
        final Map<String, String> env = System.getenv();
        if (isWindows()) {
            add(directories, env.get("APPDATA"), "npm");
            add(directories, home, ".local", "bin");
            add(directories, home, "scoop", "shims");
            return;
        }
        add(directories, home, ".local", "bin");
        add(directories, home, "bin");
        add(directories, home, ".npm-global", "bin");
        add(directories, home, ".bun", "bin");
        add(directories, home, ".cargo", "bin");
        add(directories, home, ".volta", "bin");
        add(directories, "/usr", "local", "bin");
        add(directories, "/opt", "homebrew", "bin");
        add(directories, "/opt", "bin");
        // Per-version Node managers install global bins below versioned directories.
        addGlobbed(directories, home, ".nvm", "versions", "node");
        addGlobbed(directories, home, ".local", "share", "mise", "installs", "node");
    }

    private static void add(final LinkedHashSet<Path> directories, final String base, final String... segments) {
        if (base == null || base.isBlank()) return;
        try {
            Path path = Path.of(base);
            if (segments != null) {
                for (String segment : segments) {
                    if (segment != null) path = path.resolve(segment);
                }
            }
            directories.add(path);
        } catch (java.nio.file.InvalidPathException ignored) {
            // A malformed environment entry cannot host an executable.
        }
    }

    private static void addGlobbed(final LinkedHashSet<Path> directories, final String base, final String... segments) {
        if (base == null || base.isBlank()) return;
        try {
            Path root = Path.of(base);
            for (String segment : segments) root = root.resolve(segment);
            if (!Files.isDirectory(root)) return;
            try (java.util.stream.Stream<Path> versions = Files.list(root)) {
                versions.filter(Files::isDirectory)
                        .sorted(java.util.Comparator.reverseOrder())
                        .limit(8)
                        .forEach(version -> directories.add(version.resolve("bin")));
            }
        } catch (java.io.IOException | java.nio.file.InvalidPathException | SecurityException ignored) {
            // Missing or unreadable version managers are skipped.
        }
    }

    /** Returns the search directories used for detection, for diagnostics and tests. */
    static List<Path> searchedDirectories() {
        return List.copyOf(searchDirectories());
    }

    static List<String> candidateFileNames(final String baseName) {
        return executableVariants(baseName);
    }
}
