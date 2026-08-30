package dev.turboism.graal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedGraalRuntimeCliTest {

    @TempDir
    Path home;

    @Test
    void rejectsMissingAndExtraArgumentsBeforeStartingAnInstall() {
        final Captured missing = run();
        assertEquals(2, missing.exitCode());
        assertTrue(missing.error().contains("Usage:"));

        final Captured arbitraryUri = run(
            "install", home.toString(), "https://example.invalid/runtime.zip"
        );
        assertEquals(2, arbitraryUri.exitCode());
        assertTrue(arbitraryUri.error().contains("Usage:"));
    }

    @Test
    void rejectsUnknownCommandsAndMissingHomes() {
        final Captured unknown = run("verify", home.toString());
        assertEquals(2, unknown.exitCode());
        assertTrue(unknown.error().contains("Usage:"));

        final Captured missingHome = run("install", home.resolve("missing").toString());
        assertEquals(2, missingHome.exitCode());
        assertTrue(missingHome.error().contains("GRAAL_RUNTIME_HOME_INVALID"));
    }

    @Test
    void onlyReadyIsASuccessfulTerminalState() {
        for (ManagedGraalRuntimeService.State state : ManagedGraalRuntimeService.State.values()) {
            final Path java = home.resolve("graal/runtime/bin/java.exe");
            final ManagedGraalRuntimeService.Status status = new ManagedGraalRuntimeService.Status(
                state,
                ManagedGraalRuntimeService.GRAAL_VERSION,
                ManagedGraalRuntimeService.JAVA_VERSION,
                state == ManagedGraalRuntimeService.State.READY ? Optional.of(java) : Optional.empty(),
                0L,
                0L,
                "CODE",
                "message"
            );
            assertEquals(
                state == ManagedGraalRuntimeService.State.READY ? 0 : 1,
                ManagedGraalRuntimeCli.terminalExitCode(status),
                state.name()
            );
        }
    }

    @Test
    void progressProtocolCarriesExactByteCountsForInstallerSpeedCalculation() {
        final ManagedGraalRuntimeService.Status status = new ManagedGraalRuntimeService.Status(
            ManagedGraalRuntimeService.State.DOWNLOADING,
            ManagedGraalRuntimeService.GRAAL_VERSION,
            ManagedGraalRuntimeService.JAVA_VERSION,
            Optional.empty(),
            20L * 1024L * 1024L,
            341_299_924L,
            "",
            "Downloading."
        );

        assertTrue(ManagedGraalRuntimeCli.progress(status).contains(
            "20971520/341299924"
        ));
    }

    @Test
    void unsupportedPlatformFailsWithoutNetworkAccess() {
        final String originalOs = System.getProperty("os.name");
        final String originalArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Linux");
            System.setProperty("os.arch", "amd64");

            final Captured result = run("install", home.toString());

            assertEquals(1, result.exitCode());
            assertTrue(result.output().contains("GRAAL_RUNTIME_PROGRESS UNSUPPORTED"));
            assertTrue(result.error().contains("GRAAL_RUNTIME_PLATFORM_UNSUPPORTED"));
        } finally {
            restore("os.name", originalOs);
            restore("os.arch", originalArch);
        }
    }

    private static Captured run(final String... args) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final ByteArrayOutputStream error = new ByteArrayOutputStream();
        final int exitCode = ManagedGraalRuntimeCli.run(
            args,
            new PrintStream(output, true, StandardCharsets.UTF_8),
            new PrintStream(error, true, StandardCharsets.UTF_8)
        );
        return new Captured(
            exitCode,
            output.toString(StandardCharsets.UTF_8),
            error.toString(StandardCharsets.UTF_8)
        );
    }

    private static void restore(final String name, final String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private record Captured(int exitCode, String output, String error) {
    }
}
