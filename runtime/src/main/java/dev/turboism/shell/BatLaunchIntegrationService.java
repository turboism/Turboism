package dev.turboism.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Editor-side access to the installer-managed official Cubism BAT launch integration.
 *
 * <p>The editor never edits Cubism files and never parses or writes BAT content itself.
 * {@link #integrated()} mirrors the installer's managed state (read-only), and
 * {@link #setIntegrated(boolean)} delegates to the hash-guarded installer configurator
 * script ({@code configure_turboism.ps1}), which owns backups, elevation and restoration.
 * A non-zero exit, a timeout or a cancelled elevation is surfaced as a failure instead of
 * being silently treated as success. The change only takes effect on the next Editor
 * launch, because the integration injects agent options at BAT start-up.</p>
 */
final class BatLaunchIntegrationService {
    /** Bounds one Apply wait; a pending UAC prompt stays inside this budget. */
    static final long APPLY_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(5);
    /** The installer caps the state document far below this defensive read bound. */
    private static final int MAX_STATE_BYTES = 256 * 1024;
    /** Diagnostics kept from child output; the configurator's own log carries the rest. */
    private static final int MAX_OUTPUT_TAIL_CHARS = 2_000;
    private static final String STATE_FILE = "cubism-installations.json";
    private static final String CONFIGURATOR_SCRIPT = "configure_turboism.ps1";

    /** Exit code plus a bounded output tail from one configurator invocation. */
    record InvocationResult(int exitCode, String outputTail) {
        InvocationResult {
            Objects.requireNonNull(outputTail, "outputTail");
        }
    }

    /** Executes one configurator invocation; separated so tests can capture the command. */
    interface ScriptRunner {
        InvocationResult run(List<String> command) throws IOException, InterruptedException;
    }

    private final Path turboismHome;
    private final String osName;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScriptRunner runner;

    /** Detects the launch integration from the {@code turboism.home} system property. */
    static BatLaunchIntegrationService detect() {
        final String property = System.getProperty("turboism.home", "");
        if (property.isBlank()) {
            return null;
        }
        return new BatLaunchIntegrationService(Path.of(property));
    }

    BatLaunchIntegrationService(final Path turboismHome) {
        this(turboismHome, System.getProperty("os.name", ""), BatLaunchIntegrationService::runConfigurator);
    }

    BatLaunchIntegrationService(
        final Path turboismHome,
        final String osName,
        final ScriptRunner runner
    ) {
        this.turboismHome = Objects.requireNonNull(turboismHome, "turboismHome");
        this.osName = Objects.requireNonNull(osName, "osName");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /** True only where the integration exists: Windows with the installed configurator. */
    boolean supported() {
        if (!osName.toLowerCase(Locale.ROOT).contains("windows")) {
            return false;
        }
        return Files.isRegularFile(turboismHome.resolve(CONFIGURATOR_SCRIPT));
    }

    /**
     * Mirrors the installer state: at least one BAT integration record is active. An absent
     * or unreadable state file reports "not integrated"; the configurator remains
     * authoritative for conflicts and reports them on the next apply.
     */
    boolean integrated() {
        final Path state = turboismHome.resolve(STATE_FILE);
        if (!Files.isRegularFile(state)) {
            return false;
        }
        try {
            if (Files.size(state) > MAX_STATE_BYTES) {
                throw new IOException("managed installation state is too large");
            }
            final JsonNode root = mapper.readTree(Files.readAllBytes(state));
            final JsonNode records = root.get("batIntegrations");
            return records != null && records.isArray() && !records.isEmpty();
        } catch (IOException | RuntimeException broken) {
            return false;
        }
    }

    /** Applies the requested integration through the guarded configurator script. */
    void setIntegrated(final boolean enabled) {
        final InvocationResult result;
        try {
            result = runner.run(buildCommand(turboismHome, resolvePowershell(System.getenv("WINDIR")), enabled));
        } catch (IOException failure) {
            throw new IllegalStateException(
                "The BAT launch configurator could not be started: " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The BAT launch configurator was interrupted; the change was not applied.");
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException("The BAT launch configurator failed with exit code "
                + result.exitCode() + ": " + result.outputTail());
        }
    }

    /** Builds the argv for one configurator invocation; no shell interpolation is involved. */
    static List<String> buildCommand(final Path home, final String powershell, final boolean enabled) {
        final List<String> command = new ArrayList<>();
        command.add(powershell);
        command.add("-NoProfile");
        command.add("-NonInteractive");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(home.resolve(CONFIGURATOR_SCRIPT).toString());
        command.add("-Home");
        command.add(home.toString());
        command.add(enabled ? "-IntegrateBat" : "-DisableBat");
        return List.copyOf(command);
    }

    /** Prefers the absolute system PowerShell; falls back to PATH resolution. */
    static String resolvePowershell(final String windir) {
        if (windir != null && !windir.isBlank()) {
            final Path powershell = Path.of(windir, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (Files.isRegularFile(powershell)) {
                return powershell.toString();
            }
        }
        return "powershell.exe";
    }

    private static InvocationResult runConfigurator(final List<String> command)
        throws IOException, InterruptedException {
        final ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        final Process process = builder.start();
        final StringBuilder output = new StringBuilder();
        final Thread drainer = new Thread(
            () -> drain(process.getInputStream(), output),
            "turboism-bat-integration-drain"
        );
        drainer.setDaemon(true);
        drainer.start();
        if (!process.waitFor(APPLY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new IllegalStateException(
                "The BAT launch configurator did not finish within 5 minutes; the change was not applied.");
        }
        drainer.join(TimeUnit.SECONDS.toMillis(5));
        return new InvocationResult(process.exitValue(), tail(output));
    }

    private static void drain(final InputStream stream, final StringBuilder into) {
        final byte[] buffer = new byte[4096];
        try (InputStream source = stream) {
            int read;
            while ((read = source.read(buffer)) != -1) {
                if (into.length() < MAX_OUTPUT_TAIL_CHARS) {
                    into.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException ignored) {
            // The child process ended; partial output is enough for diagnostics.
        }
    }

    private static String tail(final StringBuilder output) {
        final String text = output.toString().strip();
        if (text.length() <= MAX_OUTPUT_TAIL_CHARS) {
            return text;
        }
        return text.substring(text.length() - MAX_OUTPUT_TAIL_CHARS);
    }
}
