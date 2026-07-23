package dev.turboism.preview;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import dev.turboism.mapping.verification.VerifiedAccessException;

/** Small preview-owned logger that writes both stderr and a relocatable log file. */
public final class PreviewLog implements AutoCloseable {

    private final Clock clock;
    private final BufferedWriter writer;

    public PreviewLog(final Path logFile) throws IOException {
        this(logFile, Clock.systemUTC());
    }

    PreviewLog(final Path logFile, final Clock clock) throws IOException {
        final Path normalized = Objects.requireNonNull(logFile, "logFile").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        Files.createDirectories(normalized.getParent());
        writer = Files.newBufferedWriter(
            normalized,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE
        );
    }

    public void debug(final String component, final String message) {
        write("DEBUG", component, message, null);
    }

    public void info(final String component, final String message) {
        write("INFO", component, message, null);
    }

    public void warn(final String component, final String message) {
        write("WARN", component, message, null);
    }

    public void error(final String component, final String message, final Throwable failure) {
        write("ERROR", component, message, failure);
    }

    private synchronized void write(
        final String level,
        final String component,
        final String message,
        final Throwable failure
    ) {
        final String line = Instant.now(clock) + " [" + level + "] [" + safe(component) + "] " + safe(message);
        System.err.println(line);
        try {
            writer.write(line);
            writer.newLine();
            if (failure != null) {
                writeFailure(failure);
            }
            writer.flush();
        } catch (IOException exception) {
            System.err.println("Turboism preview log write failed: " + exception.getMessage());
        }
    }

    private void writeFailure(final Throwable failure) throws IOException {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < 8) {
            if (depth > 0) {
                writer.write("caused by ");
            }
            writer.write(current.getClass().getName() + ": " + safe(current.getMessage()));
            if (current instanceof VerifiedAccessException verified) {
                writer.write(
                    " [alias=" + safe(verified.alias())
                        + ", failureKind=" + verified.failureKind() + ']'
                );
            }
            writer.newLine();
            current = current.getCause();
            depth++;
        }
    }

    private static String safe(final String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }
}
