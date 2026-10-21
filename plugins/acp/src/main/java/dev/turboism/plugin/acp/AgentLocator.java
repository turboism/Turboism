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
        return findExecutables(baseName).stream().findFirst();
    }

    static List<Path> findExecutables(final String baseName) {
        return findExecutables(baseName, searchDirectories(), isWindows());
    }

    static List<Path> findExecutables(final String baseName, final List<Path> directories, final boolean windows) {
        final String name = Objects.requireNonNull(baseName, "baseName").strip();
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("executable name is invalid");
        }
        final LinkedHashSet<Path> found = new LinkedHashSet<>();
        for (Path directory : directories) {
            for (String variant : executableVariants(name, windows)) {
                final Path candidate = directory.resolve(variant);
                if (usable(candidate, windows)) {
                    found.add(candidate.toAbsolutePath().normalize());
                }
            }
        }
        return List.copyOf(found);
    }

    private static Path validate(final Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("executable is not a regular file: " + path);
        }
        return path;
    }

    private static boolean usable(final Path candidate) {
        return usable(candidate, isWindows());
    }

    private static boolean usable(final Path candidate, final boolean windows) {
        try {
            if (!Files.isRegularFile(candidate)) return false;
            if (windows) {
                final Path fileName = candidate.getFileName();
                final String lower = fileName != null ? fileName.toString().toLowerCase(Locale.ROOT) : "";
                if (!lower.endsWith(".exe")
                        && !lower.endsWith(".cmd")
                        && !lower.endsWith(".bat")
                        && !lower.endsWith(".com")) {
                    return false;
                }
                return true;
            }
            return Files.isExecutable(candidate);
        } catch (SecurityException failure) {
            return false;
        }
    }

    /**
     * Probes whether the candidate executable can be spawned by the host operating system.
     * Non-destructive verification that uses {@code --version} and respects Windows batch shims.
     * Output is discarded so the child process cannot block on full pipe buffers.
     * Timed-out or interrupted probes attempt to stop the process and its descendants.
     */
    static boolean probe(final Path executable, final java.time.Duration timeout) {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(timeout, "timeout");
        if (!usable(executable)) return false;
        Process process = null;
        try {
            final List<String> command =
                    AcpProcessTransport.windowsCommandShim(List.of(executable.toString(), "--version"));
            final ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            final boolean finished = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                cleanupProcess(process);
                return false;
            }
            if (process.exitValue() != 0) {
                return false;
            }
            return true;
        } catch (java.io.IOException | IllegalArgumentException failure) {
            return false;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return false;
        } catch (SecurityException failure) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                cleanupProcess(process);
            }
        }
    }

    private static void cleanupProcess(final Process process) {
        try {
            process.descendants().forEach(handle -> {
                try {
                    handle.destroyForcibly();
                } catch (SecurityException ignored) {
                }
            });
        } catch (UnsupportedOperationException | SecurityException ignored) {
        }
        process.destroyForcibly();
        try {
            process.waitFor(200L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    /** Windows executables need a PATHEXT-style suffix; everywhere else the bare name is used. */
    private static List<String> executableVariants(final String baseName) {
        return executableVariants(baseName, isWindows());
    }

    private static List<String> executableVariants(final String baseName, final boolean windows) {
        if (!windows) return List.of(baseName);
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
        addCommonInstallDirectories(directories, home, env, isWindows());
    }

    static List<Path> commonInstallDirectories(
            final String home, final Map<String, String> env, final boolean windows) {
        final LinkedHashSet<Path> directories = new LinkedHashSet<>();
        addCommonInstallDirectories(directories, home, env, windows);
        return List.copyOf(directories);
    }

    private static void addCommonInstallDirectories(
            final LinkedHashSet<Path> directories,
            final String home,
            final Map<String, String> env,
            final boolean windows) {
        if (windows) {
            add(directories, env.get("APPDATA"), "npm");
            add(directories, env.get("LOCALAPPDATA"), "Programs");
            add(directories, env.get("LOCALAPPDATA"), "Microsoft", "WinGet", "Links");
            add(directories, env.get("LOCALAPPDATA"), "pnpm");
            add(directories, env.get("LOCALAPPDATA"), "Yarn", "bin");
            add(directories, home, ".cargo", "bin");
            add(directories, home, ".local", "bin");
            add(directories, home, ".bun", "bin");
            add(directories, home, ".volta", "bin");
            add(directories, home, "scoop", "shims");
            add(directories, env.get("ProgramFiles"), "nodejs");
            add(directories, env.get("NVM_SYMLINK"));
            add(directories, env.get("PNPM_HOME"));
            add(directories, env.get("NPM_CONFIG_PREFIX"));
            add(directories, env.get("PI_NPM_INSTALL_PREFIX"));
            add(directories, home, ".pi", "agent", "bin");
            add(directories, env.get("PI_CODING_AGENT_DIR"), "bin");
            add(directories, env.get("LOCALAPPDATA"), "pi-node", "current");
            addWindowsPackages(directories, env.get("LOCALAPPDATA"));
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
        add(directories, "/usr", "bin");
        add(directories, "/bin");
        // Per-version Node managers install global bins below versioned directories.
        addGlobbed(directories, home, ".nvm", "versions", "node");
        addGlobbed(directories, home, ".local", "share", "mise", "installs", "node");
    }

    private static void addWindowsPackages(final LinkedHashSet<Path> directories, final String localAppData) {
        if (localAppData == null || localAppData.isBlank()) return;
        final Path packages;
        try {
            packages = Path.of(localAppData).resolve("Microsoft/WinGet/Packages");
        } catch (java.nio.file.InvalidPathException ignored) {
            return;
        }
        if (!Files.isDirectory(packages)) return;
        try (var entries = Files.list(packages)) {
            entries.filter(Files::isDirectory)
                    .filter(path -> {
                        final String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.startsWith("anthropic.claudecode_") || name.startsWith("openjs.nodejs");
                    })
                    .sorted()
                    .limit(32)
                    .forEach(directories::add);
        } catch (java.io.IOException | SecurityException ignored) {
            // Missing or unreadable package directories are skipped.
        }
    }

    static void augmentWindowsPath(final Map<String, String> environment) {
        if (!isWindows()) return;
        augmentWindowsPath(environment, searchDirectories());
    }

    static void augmentWindowsPath(final Map<String, String> environment, final List<Path> directories) {
        final String pathKey = environment.keySet().stream()
                .filter(key -> "PATH".equalsIgnoreCase(key))
                .findFirst()
                .orElse("PATH");
        final LinkedHashSet<String> paths = new LinkedHashSet<>();
        final String inherited = environment.getOrDefault(pathKey, "");
        for (String entry : inherited.split(";")) if (!entry.isBlank()) paths.add(entry);
        for (Path directory : directories) if (Files.isDirectory(directory)) paths.add(directory.toString());
        environment.put(pathKey, String.join(";", paths));
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
