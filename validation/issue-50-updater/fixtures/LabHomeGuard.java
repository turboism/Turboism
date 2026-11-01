package dev.turboism.lab;

// LAB ONLY. Shared guard for the issue-50 real-machine recovery fixtures.
// Uses only JDK 17 APIs so it compiles standalone against the staged agent closure.
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Confines the recovery fixtures to an explicit, marked, isolated lab home and refuses anything
 * that could be a user installation, a Cubism application root, a path reached through a reparse
 * point (symlink/junction), or a lab currently referenced by a live JVM.
 *
 * <p>The allowed base is <em>never</em> caller-supplied: it is always derived from this repository's
 * own root ({@code settings.gradle.kts} + {@code build.gradle.kts} + {@code .git}) resolved to
 * {@code <repo>/build/native/issue50-host}. A lab home must be strictly beneath that base.</p>
 */
public final class LabHomeGuard {
    /** Marker file required at the lab home root; {@link #requireMarker} compares its exact bytes. */
    public static final String MARKER_NAME = "turboism-issue50-lab-marker.json";

    /** Exact marker content. Never place this file inside a real installation. */
    private static final String MARKER_CONTENT =
            "{\"schemaVersion\":1,\"purpose\":\"turboism-issue50-recovery-lab\",\"isolated\":true}\n";

    private static final long MAX_MARKER_BYTES = 256;

    private LabHomeGuard() {}

    /** Resolves {@code --home <path>} or the positional arg at {@code index}; never mutates args. */
    public static Path requireHomeArg(final String[] args, final int index) throws IOException {
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--home".equals(args[i])) return Path.of(args[i + 1]);
        }
        if (index >= 0 && index < args.length) return Path.of(args[index]);
        throw new IOException("missing --home <labHome>");
    }

    /** Returns the optional {@code --out <path>} argument, else null. */
    public static Path optionalOutArg(final String[] args) {
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--out".equals(args[i])) return Path.of(args[i + 1]);
        }
        return null;
    }

    /** Returns the optional {@code --plugins a,b,c} argument, else null (all plugins). */
    public static String optionalPluginsArg(final String[] args) {
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--plugins".equals(args[i])) return args[i + 1];
        }
        return null;
    }

    /** Locates this repository's root, validated by the presence of its known marker files. */
    public static Path repoRoot() throws IOException {
        final String property = System.getProperty("turboism.lab.repo.root");
        final Path candidate = (property == null || property.isBlank()
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(property)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(candidate.resolve("settings.gradle.kts"))
                || !Files.isRegularFile(candidate.resolve("build.gradle.kts"))) {
            throw new IOException("not the Turboism repo root (missing gradle build files): " + candidate);
        }
        final Path git = candidate.resolve(".git");
        if (!Files.isRegularFile(git, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not the Turboism repo root (missing .git): " + candidate);
        }
        return candidate;
    }

    /** The fixed lab base under this repo; it is not caller-supplied and must already exist. */
    public static Path base() throws IOException {
        final Path base = repoRoot().resolve("build").resolve("native").resolve("issue50-host")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("lab base does not exist: " + base);
        }
        if (isReparsePoint(base)) {
            throw new IOException("lab base is a reparse point: " + base);
        }
        return base;
    }

    /** Normalizes and confines a path strictly beneath the lab base, rejecting reparse points. */
    public static Path confineUnderBase(final Path target) throws IOException {
        final Path base = base();
        final Path resolved = target.toAbsolutePath().normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            throw new IOException("path must be strictly under " + base + ": " + resolved);
        }
        rejectLinksAlongPath(resolved);
        return resolved;
    }

    /**
     * Validates a requested lab home: strictly under the fixed base, an existing directory, no
     * reparse point along the path, not a Cubism application root, and carrying the exact marker.
     */
    public static Path validate(final Path requested) throws IOException {
        Objects.requireNonNull(requested, "requested home");
        final Path home = confineUnderBase(requested);
        if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a directory: " + home);
        }
        requireNotCubismInstall(home);
        requireMarker(home);
        return home;
    }

    /**
     * Creates a brand-new isolated lab home. Refuses to add the marker to any home that already
     * contains content, so an existing user directory can never be retroactively "isolated".
     */
    public static void createIsolatedHome(final Path requested) throws IOException {
        final Path home = confineUnderBase(requested);
        requireNotCubismInstall(home);
        if (Files.exists(home, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS) || isReparsePoint(home)) {
                throw new IOException("refusing non-directory/linked home: " + home);
            }
            try (var entries = Files.list(home)) {
                if (entries.findAny().isPresent()) {
                    throw new IOException("refusing to mark a non-empty home as isolated: " + home);
                }
            }
        } else {
            Files.createDirectories(home);
            if (isReparsePoint(home)) throw new IOException("new home is a reparse point: " + home);
        }
        createMarker(home);
    }

    private static void createMarker(final Path home) throws IOException {
        final Path marker = home.resolve(MARKER_NAME);
        Files.write(marker, MARKER_CONTENT.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public static void requireMarker(final Path home) throws IOException {
        final Path marker = home.resolve(MARKER_NAME);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(marker)) {
            throw new IOException("missing lab marker: " + marker);
        }
        final byte[] bytes;
        try (var input = Files.newInputStream(marker)) {
            bytes = input.readNBytes((int) MAX_MARKER_BYTES + 1);
        }
        if (bytes.length > MAX_MARKER_BYTES
                || !MessageDigest.isEqual(bytes, MARKER_CONTENT.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("marker content mismatch; refusing non-lab home: " + marker);
        }
    }

    /** Refuses a root that looks like a Cubism application installation. */
    public static void requireNotCubismInstall(final Path home) throws IOException {
        final List<String> artifacts = List.of(
                "CubismEditor5.bat",
                "CubismEditor5.exe",
                "CubismEditor5_d3d.bat",
                "app/lib/Live2D_Cubism.jar",
                "app/jre/bin/java.exe",
                "Live2D_Cubism.jar");
        for (final String relative : artifacts) {
            if (Files.exists(home.resolve(relative), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "refusing a Cubism installation root (" + relative + "): " + home);
            }
        }
    }

    /**
     * True for symlinks, junctions, and any other reparse-point/irregular entry. JDK 17 exposes no
     * {@code isReparsePoint()} on {@code DosFileAttributes}; reparse points are reported as
     * {@code isOther() == true} by {@code BasicFileAttributes} when read with {@code NOFOLLOW_LINKS}.
     * A path that does not exist yet is not a reparse point ({@code NoSuchFileException}); callers
     * re-verify the path after creating it. Real access failures fail closed.
     */
    public static boolean isReparsePoint(final Path path) {
        if (Files.isSymbolicLink(path)) return true;
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther();
        } catch (java.nio.file.NoSuchFileException absent) {
            return false; // not created yet; nothing to reject
        } catch (IOException failure) {
            return true; // fail closed
        }
    }

    /** Walks every component from the filesystem root to {@code absolute}, rejecting links. */
    public static void rejectLinksAlongPath(final Path absolute) throws IOException {
        final Path root = absolute.getRoot();
        if (root != null && isReparsePoint(root)) {
            throw new IOException("reparse point at filesystem root: " + root);
        }
        Path current = root;
        for (final Path component : absolute) {
            current = current == null ? component : current.resolve(component);
            if (isReparsePoint(current)) {
                throw new IOException("reparse point in lab path: " + current);
            }
        }
    }

    /** SHA-256 hex of a file. */
    public static String sha256Hex(final Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Sorted list of the regular (non-link) {@code *.jar} files directly under {@code home/plugins}. */
    public static List<Path> pluginJars(final Path home) throws IOException {
        final Path plugins = home.resolve("plugins");
        final List<Path> jars = new ArrayList<>();
        if (!Files.isDirectory(plugins, LinkOption.NOFOLLOW_LINKS)) return jars;
        try (var entries = Files.list(plugins)) {
            entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .forEach(jars::add);
        }
        return jars;
    }

    /**
     * Writes a plain-text, reviewable baseline. The output is confined under the lab base and must
     * not already exist (never overwrite a possible hardlink into user data).
     */
    public static void recordBaseline(final Path home, final Path requestedOut) throws IOException {
        final Path out = confineUnderBase(requestedOut);
        final StringBuilder report = new StringBuilder();
        report.append("lab-home=").append(home.toAbsolutePath().normalize()).append('\n');
        recordIfPresent(report, "config.json", home.resolve("config.json"));
        recordIfPresent(report, "README.txt", home.resolve("README.txt"));
        recordIfPresent(report, "installed-build.json", home.resolve("installed-build.json"));
        recordIfPresent(report, "turboism-agent.jar", home.resolve("turboism-agent.jar"));

        final List<Path> jars = pluginJars(home);
        report.append("plugin-count=").append(jars.size()).append('\n');
        for (final Path jar : jars) {
            report.append("plugin ").append(jar.getFileName()).append(' ')
                    .append(sha256Hex(jar)).append('\n');
        }

        final Path pending = home.resolve("state/runtime/plugin-management/pending.json");
        final Path failed = home.resolve("state/runtime/plugin-management/failed-pending.json");
        report.append("pending.json=").append(Files.exists(pending, LinkOption.NOFOLLOW_LINKS) ? "present" : "absent")
                .append('\n');
        report.append("failed-pending.json=")
                .append(Files.exists(failed, LinkOption.NOFOLLOW_LINKS) ? "present" : "absent").append('\n');

        final Path parent = out.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void recordIfPresent(final StringBuilder report, final String label, final Path path) {
        try {
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                report.append(label).append(' ').append(Files.size(path)).append(' ')
                        .append(sha256Hex(path)).append('\n');
            } else {
                report.append(label).append("=absent\n");
            }
        } catch (IOException failure) {
            report.append(label).append("=error:").append(failure.getClass().getSimpleName()).append('\n');
        }
    }

    /** Refuses seed/apply while a live JVM references this lab's agent JAR. Fails closed. */
    public static void assertNoLiveJvm(final Path home) throws IOException, InterruptedException {
        final Path agent = home.toAbsolutePath().normalize().resolve("turboism-agent.jar");
        final boolean busy;
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            busy = windowsJvmReferences(agent);
        } else {
            busy = ProcessHandle.allProcesses()
                    .filter(p -> p.pid() != ProcessHandle.current().pid() && p.isAlive())
                    .anyMatch(p -> p.info().commandLine()
                            .map(line -> line.contains(agent.toString()))
                            .orElse(false));
        }
        if (busy) {
            throw new IOException("a live JVM references this lab agent; refusing mutation: " + home);
        }
    }

    private static boolean windowsJvmReferences(final Path agent) throws IOException, InterruptedException {
        // Mirrors the reviewed production helper: ProcessHandle command lines are unreliable on
        // Windows JDK 17, so query CIM and never interpolate the path as PowerShell source.
        final String encodedAgent = Base64.getEncoder().encodeToString(
                agent.toString().getBytes(StandardCharsets.UTF_8));
        final String script = """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
                $agent = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s'))
                $busy = $false
                foreach ($candidate in @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")) {
                    if ($candidate.ProcessId -eq %d) { continue }
                    $live = Get-Process -Id $candidate.ProcessId -ErrorAction SilentlyContinue
                    if ($null -eq $live -or $live.HasExited) { continue }
                    if ([String]::IsNullOrWhiteSpace($candidate.CommandLine)) { throw 'JVM command line unavailable' }
                    if ($candidate.CommandLine.IndexOf($agent, [StringComparison]::OrdinalIgnoreCase) -ge 0) { $busy = $true }
                }
                if ($busy) { Write-Output 'BUSY' } else { Write-Output 'IDLE' }
                """.formatted(encodedAgent, ProcessHandle.current().pid());
        final String windowsRoot = System.getenv("SystemRoot");
        if (windowsRoot == null || !Path.of(windowsRoot).isAbsolute()) {
            throw new IOException("Windows system root unavailable");
        }
        final Path powershell = Path.of(windowsRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        final Path output = Files.createTempFile("turboism-lab-jvm-guard-", ".log");
        Process query = null;
        try {
            query = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive",
                    "-OutputFormat", "Text", "-EncodedCommand",
                    Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!query.waitFor(15, TimeUnit.SECONDS)) {
                throw new IOException("Windows process query timed out");
            }
            if (query.exitValue() != 0 || Files.size(output) > 1024) {
                throw new IOException("Windows process state could not be established");
            }
            final String status = Files.readString(output, StandardCharsets.UTF_8).trim().toUpperCase(Locale.ROOT);
            if (!status.equals("BUSY") && !status.equals("IDLE")) {
                throw new IOException("Invalid Windows process query response");
            }
            return status.equals("BUSY");
        } finally {
            if (query != null && query.isAlive()) {
                query.destroyForcibly();
                query.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(output);
        }
    }
}
