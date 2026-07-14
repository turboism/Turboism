package dev.turboism.bootstrap;

import dev.turboism.preview.PreviewRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Java-agent entrypoint for the Turboism 0.1 Developer Preview. */
public final class TurboismAgent {

    private static final String VERIFICATION_RESOURCE =
        "/META-INF/turboism/verification/cubism-5.3.02-project-workspace.json";
    private static final AtomicBoolean START_REQUESTED = new AtomicBoolean(false);
    private static final AtomicReference<PreviewRuntime> RUNTIME = new AtomicReference<>();

    private TurboismAgent() {
    }

    public static void premain(final String options, final Instrumentation instrumentation) {
        requestStart(options, instrumentation);
    }

    public static void agentmain(final String options, final Instrumentation instrumentation) {
        requestStart(options, instrumentation);
    }

    private static void requestStart(final String rawOptions, final Instrumentation instrumentation) {
        if (!START_REQUESTED.compareAndSet(false, true)) {
            System.err.println("Turboism agent start ignored: runtime has already been requested");
            return;
        }

        final AgentOptions options;
        try {
            options = AgentOptions.parse(rawOptions, defaultHome());
        } catch (RuntimeException exception) {
            System.err.println("Turboism agent options rejected: " + exception.getMessage());
            return;
        }

        final Thread bootstrap = new Thread(
            () -> start(options, instrumentation),
            "turboism-bootstrap"
        );
        bootstrap.setDaemon(true);
        bootstrap.setContextClassLoader(TurboismAgent.class.getClassLoader());
        bootstrap.start();
    }

    private static void start(final AgentOptions options, final Instrumentation instrumentation) {
        try {
            System.err.println(
                "Turboism agent active; waiting for " + options.hostClassName()
                    + " for up to " + options.detectionTimeout().toSeconds() + " seconds"
            );
            final Optional<HostClassLocator.LocatedHost> located = new HostClassLocator().await(
                instrumentation,
                options.hostClassName(),
                options.detectionTimeout()
            );
            if (located.isEmpty()) {
                System.err.println("Turboism agent stopped: Cubism host class was not observed");
                return;
            }

            final HostClassLocator.LocatedHost host = located.orElseThrow();
            final Path verificationRecord = extractVerificationRecord(options.home());
            final PreviewRuntime runtime = PreviewRuntime.start(
                options.home(),
                verificationRecord,
                host.artifact(),
                host.classLoader()
            );
            if (!RUNTIME.compareAndSet(null, runtime)) {
                runtime.close();
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(TurboismAgent::shutdown, "turboism-shutdown"));
            System.err.println(
                "Turboism Developer Preview started: host=" + runtime.hostState()
                    + ", plugins=" + runtime.loadReport().loaded().size()
                    + ", failures=" + runtime.loadReport().failures().size()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("Turboism bootstrap interrupted");
        } catch (Throwable failure) {
            System.err.println(
                "Turboism bootstrap failed safely: " + failure.getClass().getName()
                    + ": " + failure.getMessage()
            );
        }
    }

    private static Path extractVerificationRecord(final Path home) throws IOException {
        final Path target = home.resolve("state")
            .resolve("verification")
            .resolve("cubism-5.3.02-project-workspace.json")
            .toAbsolutePath()
            .normalize();
        Files.createDirectories(target.getParent());
        try (InputStream source = TurboismAgent.class.getResourceAsStream(VERIFICATION_RESOURCE)) {
            if (source == null) {
                throw new IOException("Embedded Cubism verification record is missing");
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static Path defaultHome() {
        final String configured = System.getProperty("turboism.home");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        try {
            final Path location = Path.of(
                TurboismAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).toAbsolutePath().normalize();
            if (Files.isRegularFile(location)) {
                return location.getParent();
            }
            return location.resolve("turboism-preview");
        } catch (URISyntaxException | RuntimeException exception) {
            return Path.of("turboism-preview").toAbsolutePath().normalize();
        }
    }

    private static void shutdown() {
        final PreviewRuntime runtime = RUNTIME.getAndSet(null);
        if (runtime != null) {
            runtime.close();
        }
    }
}
