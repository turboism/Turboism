package dev.turboism.graalhost;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GraalHostMainTest {

    @Test
    void oversizedUnterminatedProtocolInputReportsFailureThenExits() throws Exception {
        final byte[] input = "x".repeat(4 * 1024 * 1024 + 1).getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final GraalHostMain host = new GraalHostMain(new ByteArrayInputStream(input), output);

        final Thread thread = new Thread(() -> {
            try {
                host.run();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }, "graal-host-main-test");
        thread.start();
        thread.join(TimeUnit.SECONDS.toMillis(2));

        assertTrue(!thread.isAlive(), "Graal host did not exit after oversized stdin");
        final String messages = output.toString(StandardCharsets.UTF_8);
        assertTrue(messages.contains("\"type\":\"READY\""), messages);
        assertTrue(messages.contains("\"type\":\"PROTOCOL_ERROR\""), messages);
        assertTrue(messages.contains("\"code\":\"MESSAGE_TOO_LARGE\""), messages);
        assertEquals(2, messages.lines().count(), messages);
    }

    @Test
    void crLfProtocolInputReachesThePublicPingResponse() throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final GraalHostMain host = new GraalHostMain(
            new ByteArrayInputStream("{\"type\":\"PING\"}\r\n{\"type\":\"SHUTDOWN\"}\r\n"
                .getBytes(StandardCharsets.UTF_8)),
            output
        );

        host.run();

        final String messages = output.toString(StandardCharsets.UTF_8);
        assertTrue(messages.contains("\"type\":\"PONG\""), messages);
    }

    @Test
    void cancelDuringWorkerHandoffEmitsOneTerminalCancellation() throws Exception {
        final PipedOutputStream commands = new PipedOutputStream();
        final PipedInputStream input = new PipedInputStream(commands);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final CountDownLatch workerStarted = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        final GraalHostMain host = new GraalHostMain(input, output, () -> {
            workerStarted.countDown();
            try {
                assertTrue(releaseWorker.await(5L, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        });
        final Thread hostThread = new Thread(() -> {
            try {
                host.run();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }, "graal-host-main-cancel-test");
        hostThread.start();
        write(commands, "{\"type\":\"RUN\",\"executionId\":\"queued\","
            + "\"scriptId\":\"queued\",\"source\":\"\",\"arguments\":{}}");
        assertTrue(workerStarted.await(5L, TimeUnit.SECONDS));

        write(commands, "{\"type\":\"CANCEL\",\"executionId\":\"queued\"}");
        awaitOutput(output, "\"status\":\"CANCELLED\"");
        releaseWorker.countDown();
        write(commands, "{\"type\":\"SHUTDOWN\"}");
        commands.close();
        hostThread.join(TimeUnit.SECONDS.toMillis(5));

        assertTrue(!hostThread.isAlive(), "Graal host did not shut down");
        final String messages = output.toString(StandardCharsets.UTF_8);
        assertTrue(messages.contains("\"executionId\":\"queued\""), messages);
        assertTrue(messages.contains("\"status\":\"CANCELLED\""), messages);
        assertEquals(
            1,
            messages.lines().filter(line -> line.contains("\"executionId\":\"queued\"")).count(),
            messages
        );
    }

    private static void awaitOutput(
        final ByteArrayOutputStream output,
        final String marker
    ) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            if (output.toString(StandardCharsets.UTF_8).contains(marker)) {
                return;
            }
            Thread.sleep(10L);
        }
        assertTrue(false, output.toString(StandardCharsets.UTF_8));
    }

    private static void write(final PipedOutputStream output, final String line)
        throws Exception {
        output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
