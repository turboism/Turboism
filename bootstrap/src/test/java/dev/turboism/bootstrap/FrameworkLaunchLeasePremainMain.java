package dev.turboism.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Real built-agent premain evidence for the framework launch lease (issue #50).
 *
 * <p>{@code FrameworkUpdateLaunchLeaseProcessTest} spawns runtime JVMs that call the health seam directly; this
 * probe instead starts an independent JVM with {@code -javaagent:<built agent JAR>} so the production
 * {@link TurboismAgent#premain} entry point performs the handshake itself. Every child JVM runs only the packaged
 * artifact: the host process carries this probe's fixture classes on its class path and takes all framework code
 * from the agent JAR, while every helper process runs {@code dev.turboism.update.FrameworkUpdateHelper} straight
 * from an independent copy of that same JAR — never from the test/runtime compile outputs.</p>
 *
 * <p>Scenarios, each asserted through real child processes:</p>
 *
 * <ol>
 *   <li>a real helper JVM ({@code FrameworkUpdateHelper --prepare-launch}) publishes {@code LAUNCHING} for a live
 *       launcher; the real premain converts that same entry to {@code USING} under the host JVM's own pid, the
 *       post-handshake startup path runs, a helper {@code --recover} is refused while the host JVM is alive, and
 *       the stale entry is reaped only after the host process really exits;
 *   <li>a well-formed token with no {@code LAUNCHING} entry is rejected by premain, the framework startup path
 *       never runs and no lease entry is registered;
 *   <li>a token that does not own the existing {@code LAUNCHING} entry is rejected the same way and cannot steal
 *       or convert the owner's entry.
 * </ol>
 *
 * <p>The probe never rewrites the artifact identity it exercises: whatever the built JAR embeds (a local dirty
 * worktree build or a legitimately numbered CI build) is validated for internal consistency and recorded. No Cubism
 * host, no installed Turboism home, no signed update and no trust key are involved. Every child process is bounded by
 * an explicit deadline and {@code finally} only destroys processes this probe created.</p>
 */
public final class FrameworkLaunchLeasePremainMain {

    /** Production helper entry point, launched as its own JVM; kept as a name so no internals are linked. */
    private static final String HELPER_MAIN = "dev.turboism.update.FrameworkUpdateHelper";

    private static final int EXIT_NOTHING = 10;
    private static final int EXIT_BUSY = 75;

    /** Printed by the built agent once the premain handshake passed and startup ran as far as host detection. */
    private static final String STARTUP_ATTEMPTED = "Turboism agent stopped: Cubism host class was not observed";
    /** Printed by the built agent when the premain handshake rejected startup. */
    private static final String HANDSHAKE_REJECTED = "Turboism agent launch lease rejected; framework not started";
    /** Lease admission refusal text, which the CIM process scan never produces. */
    private static final String LEASE_USING_REASON = "A runtime instance is using this installation";

    private static final String NO_MATCHING_LAUNCHING = "no matching LAUNCHING entry";

    private static final String HOST_READY = "HOST_MAIN_READY";
    private static final String LAUNCHER_PID = "LAUNCHER_PID ";
    private static final String LAUNCHER_PREPARE = "LAUNCHER_PREPARE ";

    private static final long WAIT_SECONDS = 90;
    private static final long ABSENCE_SECONDS = 8;

    private final Path builtAgentJar;
    private final String fixtureClasspath;
    private final Path scratchRoot;
    private final List<Process> spawned = new ArrayList<>();
    private Path runDirectory;
    private Path helperJar;

    private FrameworkLaunchLeasePremainMain(
            final Path builtAgentJar, final String fixtureClasspath, final Path scratchRoot) {
        this.builtAgentJar = builtAgentJar;
        this.fixtureClasspath = fixtureClasspath;
        this.scratchRoot = scratchRoot;
    }

    public static void main(final String[] args) throws Exception {
        final Path builtAgentJar = Path.of(required("turboism.test.builtAgentJar"))
                .toAbsolutePath()
                .normalize();
        final String fixtureClasspath = required("turboism.test.premainFixtureClasspath");
        final Path scratchRoot = Path.of(required("turboism.test.premainScratch"))
                .toAbsolutePath()
                .normalize();
        new FrameworkLaunchLeasePremainMain(builtAgentJar, fixtureClasspath, scratchRoot).run();
    }

    private void run() throws Exception {
        Files.createDirectories(scratchRoot);
        // One fresh run directory per invocation: earlier runs keep their logs and homes untouched, and nothing is
        // ever deleted except paths this run created itself.
        runDirectory = Files.createTempDirectory(scratchRoot, "run-");
        helperJar = stageBuiltAgent();
        try {
            convertingLaunchingBlocksLiveRecoveryThenReaps();
            missingLaunchingRejectsFrameworkStartup();
            foreignTokenRejectsFrameworkStartup();
        } finally {
            destroySpawnedProcesses();
        }
        System.out.println("launch lease premain probe passed; evidence kept in " + runDirectory);
    }

    /**
     * Copies the built agent into this run's tool directory: helper JVMs then execute the packaged helper class from
     * an independent artifact copy, so no child process can reach the test/runtime compile outputs and no child holds
     * a home path that an update may replace.
     */
    private Path stageBuiltAgent() throws IOException {
        final Path tools = runDirectory.resolve("tools");
        Files.createDirectories(tools);
        final Path copy = tools.resolve("turboism-agent.jar");
        Files.copy(builtAgentJar, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        require(
                Files.mismatch(builtAgentJar, copy) == -1L,
                "the staged agent copy must be byte-identical to the built agent");
        verifyBuiltAgentIdentity(copy);
        return copy;
    }

    // ------------------------------------------------------------------ scenarios

    /**
     * Happy path: real helper prepare -> LAUNCHING, real premain -> USING for the host pid, recovery refused while
     * the host JVM is alive, entry reaped only after the process really exits.
     */
    private void convertingLaunchingBlocksLiveRecoveryThenReaps() throws Exception {
        final Path home = freshHome("happy-path");
        final String token = newToken();

        // A live launcher publishes its own LAUNCHING entry through the packaged production helper.
        final Path launcherLog = log("happy-path-launcher.log");
        final Process launcher = spawn(
                launcherLog, fixtureClasspath, Launcher.class.getName(), home.toString(), token, helperJar.toString());
        final String launcherPid = awaitLine(launcherLog, LAUNCHER_PID, WAIT_SECONDS);
        final String prepared = awaitLine(launcherLog, LAUNCHER_PREPARE, WAIT_SECONDS);
        require(
                Integer.toString(EXIT_NOTHING).equals(prepared),
                "helper --prepare-launch must publish nothing to do (10), got '" + prepared + "'\n"
                        + read(launcherLog));
        require(
                ProcessHandle.of(Long.parseLong(launcherPid))
                        .filter(ProcessHandle::isAlive)
                        .isPresent(),
                "the launcher publishing LAUNCHING must be alive during the launch window");
        final Lease launching = onlyLease(home);
        require("LAUNCHING".equals(launching.kind), "helper prepare must publish LAUNCHING: " + launching);
        require(launching.pid == Long.parseLong(launcherPid), "LAUNCHING must belong to the launcher: " + launching);
        require(token.equals(launching.token), "LAUNCHING must carry the launch token: " + launching);

        // A real JVM whose only framework source is the staged agent JAR runs the production premain handshake.
        final Path hostLog = log("happy-path-host.log");
        final Process host = spawnAgentHost(hostLog, home, token);
        awaitText(hostLog, STARTUP_ATTEMPTED, WAIT_SECONDS);
        final Lease using = onlyLease(home);
        require("USING".equals(using.kind), "real premain must convert LAUNCHING to USING: " + using);
        require(using.pid == host.pid(), "USING must name the host JVM's own pid " + host.pid() + ": " + using);
        require(token.equals(using.token), "USING must keep the launch token: " + using);
        require(
                home.toString().equals(using.root),
                "USING must be bound to this experimental home " + home + ": " + using);

        // While the host JVM lives, a real helper --recover is refused by the lease (not by the CIM scan).
        final Path busyLog = log("happy-path-recover-busy.log");
        final int busy = runRecover(home, busyLog);
        require(
                busy == EXIT_BUSY,
                "helper --recover must be refused (75) while the runtime lives, got " + busy + "\n" + read(busyLog));
        require(
                read(busyLog).contains(LEASE_USING_REASON),
                "refusal must come from the launch lease, not the process scan:\n" + read(busyLog));
        final Lease stillUsing = onlyLease(home);
        require(
                "USING".equals(stillUsing.kind) && stillUsing.pid == host.pid(),
                "a refused recovery must not remove the live USING entry: " + stillUsing);

        // Only a real process exit may free the installation.
        terminate(host);
        require(!host.isAlive(), "the host JVM must have exited before recovery is retried");
        final Path reapLog = log("happy-path-recover-after-exit.log");
        final int reap = runRecover(home, reapLog);
        require(
                reap == EXIT_NOTHING,
                "helper --recover must proceed (10) after the runtime exited, got " + reap + "\n" + read(reapLog));
        require(
                leases(home).isEmpty(),
                "the stale USING entry must be reaped after the runtime exited: " + leases(home));

        terminate(launcher);
    }

    /** A well-formed token that no launcher ever published must fail closed before framework startup. */
    private void missingLaunchingRejectsFrameworkStartup() throws Exception {
        final Path home = freshHome("missing-launching");
        final String token = newToken();
        final Path hostLog = log("missing-launching-host.log");
        final Process host = spawnAgentHost(hostLog, home, token);
        awaitText(hostLog, HOST_READY, WAIT_SECONDS);
        awaitText(hostLog, HANDSHAKE_REJECTED, WAIT_SECONDS);
        require(
                read(hostLog).contains(NO_MATCHING_LAUNCHING),
                "the rejection must name the missing LAUNCHING entry:\n" + read(hostLog));
        // The JVM reached its own main class, so premain returned; the startup path must never have run.
        assertAbsentWithin(hostLog, STARTUP_ATTEMPTED);
        require(leases(home).isEmpty(), "a rejected premain must not register a lease entry: " + leases(home));
        terminate(host);
    }

    /** A token that does not own the existing LAUNCHING entry must be rejected and must leave that entry intact. */
    private void foreignTokenRejectsFrameworkStartup() throws Exception {
        final Path home = freshHome("foreign-token");
        final String ownerToken = newToken();
        final String foreignToken = newToken();
        final Path launcherLog = log("foreign-token-launcher.log");
        final Process launcher = spawn(
                launcherLog,
                fixtureClasspath,
                Launcher.class.getName(),
                home.toString(),
                ownerToken,
                helperJar.toString());
        final String launcherPid = awaitLine(launcherLog, LAUNCHER_PID, WAIT_SECONDS);
        final String prepared = awaitLine(launcherLog, LAUNCHER_PREPARE, WAIT_SECONDS);
        require(
                Integer.toString(EXIT_NOTHING).equals(prepared),
                "helper --prepare-launch must publish nothing to do (10), got '" + prepared + "'\n"
                        + read(launcherLog));

        final Path hostLog = log("foreign-token-host.log");
        final Process host = spawnAgentHost(hostLog, home, foreignToken);
        awaitText(hostLog, HOST_READY, WAIT_SECONDS);
        awaitText(hostLog, HANDSHAKE_REJECTED, WAIT_SECONDS);
        require(
                read(hostLog).contains(NO_MATCHING_LAUNCHING),
                "a foreign token must be rejected for the missing LAUNCHING entry:\n" + read(hostLog));
        assertAbsentWithin(hostLog, STARTUP_ATTEMPTED);

        final Lease untouched = onlyLease(home);
        require("LAUNCHING".equals(untouched.kind), "the owner's LAUNCHING entry must survive: " + untouched);
        require(
                untouched.pid == Long.parseLong(launcherPid),
                "the owner's LAUNCHING entry must keep the launcher pid: " + untouched);
        require(ownerToken.equals(untouched.token), "the owner's LAUNCHING entry must keep its token: " + untouched);
        require(untouched.pid != host.pid(), "a foreign token must never claim the host JVM's pid: " + untouched);

        terminate(host);
        terminate(launcher);
    }

    // ------------------------------------------------------------------ built agent identity

    /**
     * Proves the probe exercises the real packaged agent and records that artifact's own identity. Nothing is
     * rewritten: a local worktree build stays local/dirty and a legitimately numbered CI build stays numbered. Only
     * the structure and the internal consistency of identity (manifest vs embedded resource) are asserted, so the
     * probe is usable in any real build context instead of demanding one particular one.
     */
    private void verifyBuiltAgentIdentity(final Path agent) throws IOException {
        require(Files.isRegularFile(agent), "the built agent JAR is missing: " + agent);
        try (JarFile jar = new JarFile(agent.toFile())) {
            final Manifest manifest = jar.getManifest();
            require(manifest != null, "the built agent JAR has no manifest: " + agent);
            final Attributes attributes = manifest.getMainAttributes();
            final String premain = attributes.getValue("Premain-Class");
            require(
                    TurboismAgent.class.getName().equals(premain),
                    "the built agent must declare Premain-Class=" + TurboismAgent.class.getName() + ", found "
                            + premain);
            require(
                    jar.getJarEntry("dev/turboism/bootstrap/TurboismAgent.class") != null,
                    "the built agent JAR must contain the premain entry point");
            require(
                    jar.getJarEntry("dev/turboism/update/FrameworkUpdateHealth.class") != null,
                    "the built agent JAR must contain the launch-lease handshake it runs in premain");
            require(
                    jar.getJarEntry("dev/turboism/update/FrameworkUpdateHelper.class") != null,
                    "the built agent JAR must contain the update helper executed as a helper JVM here");
            final var versionEntry = jar.getJarEntry("META-INF/turboism/framework-version.properties");
            require(versionEntry != null, "the built agent JAR is missing its framework version resource");
            final Properties version = new Properties();
            try (InputStream input = jar.getInputStream(versionEntry)) {
                version.load(input);
            }
            final String frameworkVersion = version.getProperty("version", "");
            require(!frameworkVersion.isBlank(), "the built agent must embed its framework version");
            final String buildKind = version.getProperty("buildKind", "");
            final String channel = version.getProperty("channel", "");
            final String buildNumber = version.getProperty("buildNumber", "");
            final String dirty = version.getProperty("dirty", "");
            if (!buildKind.isBlank()) {
                require(
                        buildKind.equals("local") || buildKind.equals("ci"),
                        "the built agent reports an unknown buildKind=" + buildKind);
            }
            // The manifest carries the numbered CI identity; the embedded resource must agree with it exactly, so a
            // probe that records one never contradicts the other.
            final String manifestNumber = attributes.getValue("Turboism-Build-Number");
            final String manifestChannel = attributes.getValue("Turboism-Channel");
            if (manifestNumber != null) {
                require(
                        manifestNumber.equals(buildNumber),
                        "manifest build number " + manifestNumber + " disagrees with embedded buildNumber="
                                + buildNumber);
            }
            if (manifestChannel != null) {
                require(
                        manifestChannel.equals(channel),
                        "manifest channel " + manifestChannel + " disagrees with embedded channel=" + channel);
            }
            System.out.println("built agent under test: " + builtAgentJar);
            System.out.println("staged agent copy: " + agent);
            System.out.println("built agent identity: version=" + frameworkVersion + ", buildKind=" + buildKind
                    + ", dirty=" + dirty + ", channel=" + channel + ", buildNumber='" + buildNumber + "'");
        }
    }

    // ------------------------------------------------------------------ process plumbing

    private static String required(final String property) {
        final String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required system property is missing: " + property);
        }
        return value;
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private Path freshHome(final String name) throws IOException {
        final Path home = runDirectory.resolve("homes").resolve(name);
        Files.createDirectories(home);
        // Safe mode: no Cubism hooks, no menu work, no native UI; the handshake and its startup decision stay real.
        Files.writeString(
                home.resolve("config.json"),
                "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1,"
                        + "\"worktreeId\":\"launch-lease-premain\",\"safeMode\":true}\n",
                StandardCharsets.UTF_8);
        return home;
    }

    private Path log(final String name) {
        return runDirectory.resolve("logs").resolve(name);
    }

    /** A real JVM running the staged agent JAR: the only framework code on its class path is that JAR. */
    private Process spawnAgentHost(final Path output, final Path home, final String token) throws IOException {
        final List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Djava.awt.headless=true");
        command.add("-javaagent:" + helperJar + "=home=" + home + ";launchToken=" + token
                + ";hostClass=missing.Host;timeoutSeconds=1");
        command.add("-cp");
        command.add(fixtureClasspath);
        command.add(Host.class.getName());
        return launch(output, command);
    }

    private Process spawn(final Path output, final String classpath, final String mainClass, final String... arguments)
            throws IOException {
        final List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass);
        command.addAll(List.of(arguments));
        return launch(output, command);
    }

    private Process launch(final Path output, final List<String> command) throws IOException {
        Files.createDirectories(output.getParent());
        final Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        spawned.add(process);
        return process;
    }

    private static void terminate(final Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            throw new AssertionError("child JVM " + process.pid() + " did not exit within 30s");
        }
    }

    private void destroySpawnedProcesses() {
        for (final Process process : spawned) {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        for (final Process process : spawned) {
            try {
                process.waitFor(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private int runRecover(final Path home, final Path output) throws Exception {
        final Process helper = spawn(
                output,
                helperJar.toString(),
                HELPER_MAIN,
                "--recover",
                "--root",
                home.toString(),
                "--work",
                work(home).toString());
        if (!helper.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)) {
            terminate(helper);
            throw new AssertionError("helper --recover did not exit within " + WAIT_SECONDS + "s\n" + read(output));
        }
        return helper.exitValue();
    }

    // ------------------------------------------------------------------ lease inspection (black box on purpose)

    private static Path work(final Path home) {
        return home.resolve("state/runtime/update/helper");
    }

    private static Path leaseDirectory(final Path home) {
        return work(home).resolve("lease");
    }

    private static List<Path> leases(final Path home) throws IOException {
        final Path directory = leaseDirectory(home);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(directory)) {
            return children.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }

    private static Lease onlyLease(final Path home) throws IOException {
        final List<Path> entries = leases(home);
        require(entries.size() == 1, "expected exactly one launch lease entry, found " + entries);
        return readLease(entries.get(0));
    }

    private static Lease readLease(final Path file) throws IOException {
        final String json = Files.readString(file, StandardCharsets.UTF_8);
        require(
                json.contains("\"format\":\"turboism-launch-lease-v1\""),
                "unexpected launch lease format in " + file + ": " + json);
        return new Lease(
                file,
                stringField(json, "kind"),
                longField(json, "pid"),
                stringField(json, "token"),
                stringField(json, "root"));
    }

    private static String stringField(final String json, final String key) {
        final Matcher matcher =
                Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("launch lease entry has no " + key + " field: " + json);
        }
        // The entries are written by Jackson, so a Windows root arrives with escaped separators.
        return matcher.group(1).replace("\\\\", "\\");
    }

    private static long longField(final String json, final String key) {
        final Matcher matcher =
                Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+)").matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("launch lease entry has no " + key + " field: " + json);
        }
        return Long.parseLong(matcher.group(1));
    }

    private record Lease(Path file, String kind, long pid, String token, String root) {
        @Override
        public String toString() {
            return kind + "{pid=" + pid + ", token=" + token + ", root=" + root + ", file=" + file.getFileName() + "}";
        }
    }

    // ------------------------------------------------------------------ bounded waits

    private static String awaitLine(final Path output, final String prefix, final long seconds) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(output)) {
                for (final String line : Files.readAllLines(output, StandardCharsets.UTF_8)) {
                    if (line.startsWith(prefix)) {
                        return line.substring(prefix.length()).trim();
                    }
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError(
                "timed out after " + seconds + "s waiting for '" + prefix + "' in " + output + "\n" + read(output));
    }

    private static String awaitText(final Path output, final String marker, final long seconds) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            final String text = read(output);
            if (text.contains(marker)) {
                return text;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(
                "timed out after " + seconds + "s waiting for '" + marker + "' in " + output + "\n" + read(output));
    }

    private static void assertAbsentWithin(final Path output, final String marker) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ABSENCE_SECONDS);
        while (System.nanoTime() < deadline) {
            if (read(output).contains(marker)) {
                throw new AssertionError(
                        "rejected premain must never reach '" + marker + "' in " + output + "\n" + read(output));
            }
            Thread.sleep(200);
        }
    }

    private static String read(final Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException unreadable) {
            return "<unreadable: " + unreadable.getMessage() + ">";
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String newToken() {
        final byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    // ------------------------------------------------------------------ child fixtures

    /**
     * Stand-in launcher process: publishes the real {@code LAUNCHING} lease by executing the packaged update helper
     * from the staged agent copy, then stays alive for the whole launch window so the lease holder is a live process.
     */
    public static final class Launcher {
        public static void main(final String[] args) throws Exception {
            final Path home = Path.of(args[0]);
            final String token = args[1];
            final Path packagedHelper = Path.of(args[2]);
            System.out.println(LAUNCHER_PID + ProcessHandle.current().pid());
            System.out.flush();
            final Process helper = new ProcessBuilder(
                            javaExecutable(),
                            "-cp",
                            packagedHelper.toString(),
                            HELPER_MAIN,
                            "--root",
                            home.toString(),
                            "--work",
                            work(home).toString(),
                            "--prepare-launch",
                            Long.toString(ProcessHandle.current().pid()),
                            "--launch-token",
                            token)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .start();
            String prepared = "TIMEOUT";
            if (helper.waitFor(60, TimeUnit.SECONDS)) {
                prepared = Integer.toString(helper.exitValue());
            } else {
                // Only this fixture's own helper process is cleaned up; the driver owns everything else.
                helper.destroyForcibly();
                helper.waitFor(10, TimeUnit.SECONDS);
            }
            System.out.println(LAUNCHER_PREPARE + prepared);
            System.out.flush();
            Thread.sleep(TimeUnit.SECONDS.toMillis(300));
        }
    }

    /** Host JVM body: reached only when premain returned; stays alive so its USING entry can be observed. */
    public static final class Host {
        public static void main(final String[] args) throws Exception {
            System.out.println(HOST_READY);
            System.out.flush();
            Thread.sleep(TimeUnit.SECONDS.toMillis(300));
        }
    }
}
