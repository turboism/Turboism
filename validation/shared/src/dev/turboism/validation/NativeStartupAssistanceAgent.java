package dev.turboism.validation;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/** Explicit task-only UI assistance; does not observe SDK behavior or terminate the host. */
public final class NativeStartupAssistanceAgent {
    private NativeStartupAssistanceAgent() {}

    static void validateTaskHome(final Path home, final String runId) {
        if (!runId.matches("queue-[0-9a-f]{32}") || home.getFileName() == null
                || !"turboism-home".equals(home.getFileName().toString()) || home.getParent() == null
                || !runId.equals(home.getParent().getFileName().toString())) {
            throw new IllegalArgumentException("Startup assistance requires a bound queue task home");
        }
    }

    public static void premain(final String ignored, final Instrumentation instrumentation) {
        if (!Boolean.getBoolean("turboism.validation.startupAssistance")) return;
        final String runId = System.getProperty("turboism.validation.runId", "");
        final Path home = Path.of(System.getProperty("turboism.home", ""));
        validateTaskHome(home, runId);
        final Thread helper = new Thread(() -> assist(home, runId), "task-native-startup-assistance");
        helper.setDaemon(true);
        helper.start();
    }

    private static void assist(final Path home, final String runId) {
        final long deadline = System.nanoTime() + java.time.Duration.ofMinutes(15).toNanos();
        final Properties result = new Properties();
        result.setProperty("schemaVersion", "1");
        result.setProperty("runId", runId);
        result.setProperty("status", "OBSERVATION_ONLY");
        int actions = 0;
        String last = "";
        try {
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                final AtomicReference<String> observation = new AtomicReference<>();
                final AtomicReference<String> action = new AtomicReference<>();
                SwingUtilities.invokeAndWait(() -> {
                    observation.set(NativeFreeStartup.describeDialogs());
                    action.set(NativeFreeStartup.select());
                });
                if (!observation.get().equals(last)) {
                    last = observation.get();
                    System.out.println("TASK_STARTUP_DIALOG " + last);
                }
                if (action.get() != null) {
                    result.setProperty("action." + ++actions, action.get());
                    result.setProperty("observedLicenseMode",
                        System.getProperty("turboism.validation.observedLicenseMode", "NOT_OBSERVED"));
                    final Path state = home.resolve("state");
                    Files.createDirectories(state);
                    final Path pending = state.resolve("native-startup-assistance.pending");
                    try (var output = Files.newOutputStream(pending)) {
                        result.store(output, "Task-local prompt observations; not capability acceptance");
                    }
                    Files.move(pending, state.resolve("native-startup-assistance.properties"),
                        StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("TASK_STARTUP_ACTION " + action.get());
                }
                Thread.sleep(500L);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            System.err.println("TASK_STARTUP_ASSISTANCE_FAILED " + failure);
        }
    }
}
