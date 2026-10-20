package dev.turboism.plugin.acp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Read-only discovery, version reporting, and protocol checks for installed harnesses. */
final class AgentAvailability {
    record Detection(Path executable, boolean available, String version, String reason) {}

    private record Check(long checkedAt, boolean available, String version, String reason) {}

    private static final Map<Path, Check> CACHE = new ConcurrentHashMap<>();
    private static final java.util.regex.Pattern VERSION =
            java.util.regex.Pattern.compile("\\b\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?\\b");

    private AgentAvailability() {}

    static void invalidate() {
        CACHE.clear();
    }

    static boolean available(final AgentProfile profile, final Path executable) {
        return checked(profile, executable).available();
    }

    static String version(final Path executable) {
        final Check check = CACHE.get(executable);
        return check == null ? "" : check.version();
    }

    private static Check checked(final AgentProfile profile, final Path executable) {
        final Check cached = CACHE.get(executable);
        if (cached != null && System.nanoTime() - cached.checkedAt() < TimeUnit.SECONDS.toNanos(30)) return cached;
        final Check result = check(profile, executable);
        CACHE.put(executable, result);
        return result;
    }

    static java.util.Optional<Path> locate(final AgentProfile profile) {
        return locate(profile, ignored -> {});
    }

    static java.util.Optional<Path> locate(
            final AgentProfile profile, final java.util.function.Consumer<String> diagnostics) {
        final Detection found = discover(profile, diagnostics);
        return found.available() ? java.util.Optional.of(found.executable()) : java.util.Optional.empty();
    }

    static Detection discover(final AgentProfile profile, final java.util.function.Consumer<String> diagnostics) {
        final List<Path> candidates = new ArrayList<>();
        for (String name : profile.executableCandidates()) candidates.addAll(AgentLocator.findExecutables(name));
        return discover(profile, candidates, diagnostics);
    }

    static java.util.Optional<Path> locate(
            final AgentProfile profile,
            final List<Path> candidates,
            final java.util.function.Consumer<String> diagnostics) {
        final Detection found = discover(profile, candidates, diagnostics);
        return found.available() ? java.util.Optional.of(found.executable()) : java.util.Optional.empty();
    }

    static Detection discover(
            final AgentProfile profile,
            final List<Path> candidates,
            final java.util.function.Consumer<String> diagnostics) {
        Detection firstFailure = null;
        for (Path candidate : candidates) {
            final Check check = checked(profile, candidate);
            diagnostics.accept(profile.id() + " path=" + candidate + " version="
                    + (check.version().isEmpty() ? "unknown" : check.version()) + " status=" + check.reason());
            final Detection detection = new Detection(candidate, check.available(), check.version(), check.reason());
            if (check.available()) return detection;
            if (firstFailure == null) firstFailure = detection;
        }
        if (candidates.isEmpty()) diagnostics.accept(profile.id() + " status=executable-not-found");
        return firstFailure == null ? new Detection(null, false, "", "executable-not-found") : firstFailure;
    }

    private static Check result(final boolean available, final String version, final String reason) {
        return new Check(System.nanoTime(), available, version, reason);
    }

    private static Check check(final AgentProfile profile, final Path executable) {
        String version = "";
        try {
            final var match = VERSION.matcher(probe(List.of(executable.toString(), "--version")));
            if (match.find()) version = match.group();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return result(false, version, "interrupted");
        } catch (Exception ignored) {
            // Some ACP adapters only provide initialize, not --version.
        }
        final AgentProtocol protocol = AgentProtocol.forExecutable(profile, executable);
        if (protocol == AgentProtocol.ACP) {
            try (AcpClient ignored = AcpClient.start(
                    new AgentLaunchSpec(command(profile, executable), Path.of(System.getProperty("user.home"))),
                    new AcpListener() {})) {
                return result(true, version, "available");
            } catch (IOException | AcpException | RuntimeException failure) {
                return result(
                        false,
                        version,
                        "initialize-failed-" + failure.getClass().getSimpleName());
            }
        }
        final var arguments = new ArrayList<String>();
        arguments.add(executable.toString());
        if (protocol == AgentProtocol.CODEX) arguments.add("app-server");
        arguments.add("--help");
        try {
            final String help = probe(arguments);
            final boolean supported =
                    switch (protocol) {
                        case CODEX -> help.contains("app-server");
                        case CLAUDE -> help.contains("stream-json") && help.contains("--input-format");
                        case PI -> help.contains("rpc") && help.contains("--extension");
                        default -> false;
                    };
            return result(supported, version, supported ? "available" : "native-mode-not-advertised");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return result(false, version, "interrupted");
        } catch (Exception failure) {
            return result(false, version, "probe-failed-" + failure.getClass().getSimpleName());
        }
    }

    /** Help/version output may be written to either stream by native CLIs and Windows shims. */
    private static String probe(final List<String> command) throws Exception {
        try (AcpProcessTransport process =
                AcpProcessTransport.start(new AgentLaunchSpec(command, Path.of(System.getProperty("user.home"))))) {
            final var stdout = read(process.stdout());
            final var stderr = read(process.stderr());
            try {
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                java.util.concurrent.CompletableFuture.allOf(stdout, stderr).get(15, TimeUnit.SECONDS);
                if (process.awaitExit(deadline - System.nanoTime()) != 0) {
                    throw new IOException("Harness probe exited unsuccessfully");
                }
                return stdout.join() + "\n" + stderr.join();
            } finally {
                process.terminate(Duration.ofMillis(100));
            }
        }
    }

    private static java.util.concurrent.CompletableFuture<String> read(final java.io.InputStream input) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return readProbeOutput(input);
            } catch (IOException failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        });
    }

    /** Windows JRE 17 FileInputStream.readNBytes tries to seek on non-seekable process pipes. */
    static String readProbeOutput(final java.io.InputStream input) throws IOException {
        final int limit = 128 * 1024;
        final var captured = new java.io.ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            final int keep = Math.min(count, limit - captured.size());
            if (keep > 0) captured.write(buffer, 0, keep);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static List<String> command(final AgentProfile profile, final Path executable) {
        final var command = new ArrayList<String>();
        command.add(executable.toString());
        command.addAll(profile.arguments());
        return command;
    }
}
