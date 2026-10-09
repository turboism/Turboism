package dev.turboism.validation;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Session-mode hooks shared by host-validation probes.
 *
 * <p>Unless {@code -Dturboism.validation.session=true} is set every method here is inert and
 * probes keep their standalone behavior exactly. In session mode an auto-run probe waits for
 * its own go-flag under {@code state/session/go/<plugin-state-dir-name>.flag} before doing any
 * work, and its terminal path records a step-done marker under {@code state/session/steps/}
 * instead of exiting or halting the host process. The session coordinator plugin owns step
 * ordering, per-step timeouts, health checks and the final host shutdown.</p>
 *
 * <p>The go-flag file may carry {@code turboism.validation.*} key=value lines; they are applied
 * as system properties when the flag is observed so one plugin build can serve variant steps
 * (for example the PSD export observation profile of the texture probe).</p>
 */
public final class SessionGate {
    public static final String ENABLED_PROPERTY = "turboism.validation.session";
    public static final long POLL_MILLIS = 1_000L;
    private static final String VALIDATION_PREFIX = "turboism.validation.";

    private SessionGate() {}

    public static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    /** The {@code state/} root shared by all plugins: the parent of a per-plugin state dir. */
    public static Path stateRoot(final Path pluginStateDir) {
        final Path root = pluginStateDir.getParent();
        if (root == null) throw new IllegalStateException("plugin state directory has no state root");
        return root;
    }

    public static Path sessionDir(final Path pluginStateDir) {
        return stateRoot(pluginStateDir).resolve("session");
    }

    /** Written by the coordinator when the session terminates, so waiting probes return. */
    public static Path finishedFlag(final Path pluginStateDir) {
        return sessionDir(pluginStateDir).resolve("finished.flag");
    }

    /** The coordinator's per-step go-flag for this plugin (keyed by its state dir name). */
    public static Path goFlag(final Path pluginStateDir) {
        return sessionDir(pluginStateDir).resolve("go")
            .resolve(pluginStateDir.getFileName() + ".flag");
    }

    public static Path doneMarker(final Path pluginStateDir) {
        return sessionDir(pluginStateDir).resolve("steps")
            .resolve(pluginStateDir.getFileName() + ".done");
    }

    /**
     * Waits for this plugin's session go-flag. When observed, {@code turboism.validation.*}
     * properties carried in the flag file are applied and returned; {@code null} means the
     * session finished first and the caller must return without doing any work.
     */
    public static Properties awaitTurn(final Path pluginStateDir, final Consumer<String> log) {
        final Path flag = goFlag(pluginStateDir);
        final Path finished = finishedFlag(pluginStateDir);
        log.accept("SESSION_GATE_WAIT flag=" + flag);
        while (true) {
            if (Files.isRegularFile(flag)) {
                final Properties step = new Properties();
                try {
                    step.load(new StringReader(Files.readString(flag, StandardCharsets.UTF_8)));
                } catch (Exception failure) {
                    log.accept("SESSION_GATE_FLAG_UNREADABLE " + failure);
                    return null;
                }
                for (String name : step.stringPropertyNames()) {
                    if (name.startsWith(VALIDATION_PREFIX)) {
                        System.setProperty(name, step.getProperty(name));
                    }
                }
                log.accept("SESSION_GATE_GO flag=" + flag + " properties=" + step.stringPropertyNames());
                return step;
            }
            if (Files.isRegularFile(finished)) {
                log.accept("SESSION_GATE_FINISHED_WITHOUT_TURN flag=" + flag);
                return null;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** Records the step outcome; session probes call this instead of exit/halt/closeIfEligible. */
    public static void complete(final Path pluginStateDir, final String status) {
        final Path marker = doneMarker(pluginStateDir);
        try {
            Files.createDirectories(marker.getParent());
            final Path pending = marker.resolveSibling(marker.getFileName() + ".pending");
            Files.writeString(pending, "status=" + status + "\n", StandardCharsets.UTF_8);
            Files.move(pending, marker, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) {
            logFallback("SESSION_STEP_DONE_WRITE_FAILED " + marker + " " + failure);
        }
    }

    private static void logFallback(final String line) {
        System.err.println(line);
        System.err.flush();
    }
}
