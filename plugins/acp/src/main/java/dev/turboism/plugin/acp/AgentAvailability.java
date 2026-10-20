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

/** Read-only capability checks: a matching executable name alone is not a usable harness. */
final class AgentAvailability {
    private record Check(long checkedAt, boolean available) {}

    private static final Map<Path, Check> CACHE = new ConcurrentHashMap<>();

    private AgentAvailability() {}

    static boolean available(final AgentProfile profile, final Path executable) {
        final Check cached = CACHE.get(executable);
        if (cached != null && System.nanoTime() - cached.checkedAt() < TimeUnit.SECONDS.toNanos(30))
            return cached.available();
        final boolean available = check(profile, executable);
        CACHE.put(executable, new Check(System.nanoTime(), available));
        return available;
    }

    static java.util.Optional<Path> locate(final AgentProfile profile) {
        for (String candidate : profile.executableCandidates()) {
            final var found = AgentLocator.findExecutable(candidate);
            if (found.isPresent() && available(profile, found.get())) return found;
        }
        return java.util.Optional.empty();
    }

    private static boolean check(final AgentProfile profile, final Path executable) {
        final AgentProtocol protocol = AgentProtocol.forExecutable(profile, executable);
        if (protocol == AgentProtocol.ACP) {
            try (AcpClient ignored = AcpClient.start(
                    new AgentLaunchSpec(command(profile, executable), Path.of(System.getProperty("user.home"))),
                    new AcpListener() {})) {
                return true;
            } catch (IOException | AcpException | RuntimeException failure) {
                return false;
            }
        }
        final var arguments = new ArrayList<String>();
        arguments.add(executable.toString());
        if (protocol == AgentProtocol.CODEX) arguments.add("app-server");
        arguments.add("--help");
        AcpTransport process = null;
        try {
            process =
                    AcpProcessTransport.start(new AgentLaunchSpec(arguments, Path.of(System.getProperty("user.home"))));
            final AcpTransport running = process;
            final var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(running.stdout().readNBytes(128 * 1024), StandardCharsets.UTF_8);
                } catch (IOException failure) {
                    return "";
                }
            });
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    running.stderr().transferTo(java.io.OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                }
            });
            final String help = output.get(5, TimeUnit.SECONDS);
            return switch (protocol) {
                case CODEX -> help.contains("app-server");
                case CLAUDE -> help.contains("stream-json") && help.contains("--input-format");
                case PI -> help.contains("rpc") && help.contains("--extension");
                default -> false;
            };
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception failure) {
            return false;
        } finally {
            if (process != null) {
                process.terminate(Duration.ofMillis(100));
                process.close();
            }
        }
    }

    private static List<String> command(final AgentProfile profile, final Path executable) {
        final var command = new ArrayList<String>();
        command.add(executable.toString());
        command.addAll(profile.arguments());
        return command;
    }
}
