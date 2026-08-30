package dev.turboism.graal;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Installer bridge for the pinned Turboism-managed GraalVM runtime. */
public final class ManagedGraalRuntimeCli {

    private static final String INSTALL = "install";
    private static final long POLL_MILLIS = 250L;

    private ManagedGraalRuntimeCli() {
    }

    /**
     * Installs the one runtime pinned by {@link ManagedGraalRuntimeService}.
     *
     * <p>The command deliberately accepts no URI, archive name, size, version,
     * or digest arguments. Installers may select whether to invoke it and the
     * exact Turboism home; the service remains the only download authority.</p>
     *
     * @param args {@code install <existing-turboism-home>}
     */
    public static void main(final String[] args) {
        System.exit(run(args, System.in, System.out, System.err));
    }

    static int run(
        final String[] args,
        final PrintStream output,
        final PrintStream error
    ) {
        return run(args, InputStream.nullInputStream(), output, error);
    }

    static int run(
        final String[] args,
        final InputStream input,
        final PrintStream output,
        final PrintStream error
    ) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        if (args.length != 2 || !INSTALL.equals(args[0])) {
            error.println("Usage: ManagedGraalRuntimeCli install <turboism-home>");
            return 2;
        }

        final Path home;
        try {
            home = Path.of(args[1]).toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            error.println("GRAAL_RUNTIME_HOME_INVALID: Turboism home is not a valid path.");
            return 2;
        }
        if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(home)) {
            error.println("GRAAL_RUNTIME_HOME_INVALID: Turboism home must be an existing ordinary directory.");
            return 2;
        }

        try (ManagedGraalRuntimeService service = new ManagedGraalRuntimeService(
            home,
            code -> error.println("DIAGNOSTIC " + bounded(code))
        )) {
            final ManagedGraalRuntimeService.Operation operation = service.install();
            final Thread cancelReader = cancelReader(input, operation);
            cancelReader.start();
            ManagedGraalRuntimeService.Status last = null;
            while (!operation.completion().toCompletableFuture().isDone()) {
                final ManagedGraalRuntimeService.Status current = operation.status();
                if (!sameProgress(last, current)) {
                    output.println(progress(current));
                    last = current;
                }
                TimeUnit.MILLISECONDS.sleep(POLL_MILLIS);
            }
            final ManagedGraalRuntimeService.Status terminal = operation.completion()
                .toCompletableFuture().get();
            if (!sameProgress(last, terminal)) output.println(progress(terminal));
            if (terminalExitCode(terminal) == 0) {
                output.println(
                    "GRAAL_RUNTIME_READY "
                        + terminal.javaExecutable().orElseThrow().toAbsolutePath().normalize()
                );
                return 0;
            }
            error.println(
                bounded(terminal.code().isBlank() ? "GRAAL_RUNTIME_INSTALL_FAILED" : terminal.code())
                    + ": " + bounded(terminal.message())
            );
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            error.println("GRAAL_RUNTIME_CANCELLED: Installer process was interrupted.");
            return 1;
        } catch (Exception failure) {
            error.println(
                "GRAAL_RUNTIME_INSTALL_FAILED: "
                    + bounded(Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()))
            );
            return 1;
        }
    }

    private static Thread cancelReader(
        final InputStream input,
        final ManagedGraalRuntimeService.Operation operation
    ) {
        final Thread reader = new Thread(() -> {
            try {
                final BufferedReader commands = new BufferedReader(
                    new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)
                );
                for (String command; (command = commands.readLine()) != null;) {
                    if ("cancel".equalsIgnoreCase(command.trim())) {
                        operation.cancel();
                        return;
                    }
                }
            } catch (java.io.IOException ignored) {
                // Cancellation input is optional; install progress remains authoritative.
            }
        }, "turboism-graal-runtime-cancel-reader");
        reader.setDaemon(true);
        return reader;
    }

    static int terminalExitCode(final ManagedGraalRuntimeService.Status status) {
        return status.state() == ManagedGraalRuntimeService.State.READY ? 0 : 1;
    }

    private static boolean sameProgress(
        final ManagedGraalRuntimeService.Status left,
        final ManagedGraalRuntimeService.Status right
    ) {
        return left != null
            && left.state() == right.state()
            && left.completedBytes() == right.completedBytes()
            && left.totalBytes() == right.totalBytes();
    }

    static String progress(final ManagedGraalRuntimeService.Status status) {
        return "GRAAL_RUNTIME_PROGRESS " + status.state()
            + " " + status.completedBytes() + "/" + status.totalBytes()
            + " " + bounded(status.message());
    }

    private static String bounded(final String value) {
        final String normalized = Objects.requireNonNullElse(value, "")
            .replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }
}
