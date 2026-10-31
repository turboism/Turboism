package dev.turboism.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Read-only process inspection. Failure to establish Windows process state is never permission to apply. */
final class FrameworkUpdateProcessGuard {
    private FrameworkUpdateProcessGuard() {}

    static boolean inUse(final Path root) throws IOException, InterruptedException {
        final Path agent = root.toAbsolutePath().normalize().resolve("turboism-agent.jar");
        if (System.getProperty("os.name", "").startsWith("Windows")) return windows(agent);
        try (var processes = ProcessHandle.allProcesses()) {
            return processes.filter(p -> p.pid() != ProcessHandle.current().pid() && p.isAlive())
                    .anyMatch(p -> p.info().commandLine().map(line -> line.contains(agent.toString())).orElse(false));
        }
    }

    private static boolean windows(final Path agent) throws IOException, InterruptedException {
        // ProcessHandle.Info.commandLine is not implemented reliably on Windows JDK 17. Use the OS CIM query;
        // never interpolate paths as PowerShell source. The path is a Base64 UTF-8 value inside an encoded script.
        final String encodedAgent = Base64.getEncoder().encodeToString(agent.toString().getBytes(StandardCharsets.UTF_8));
        final String script = """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
                $agent = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s'))
                $busy = $false
                foreach ($candidate in @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")) {
                    if ($candidate.ProcessId -eq %d) { continue }
                    $live = Get-Process -Id $candidate.ProcessId -ErrorAction SilentlyContinue
                    if ($null -eq $live -or $live.HasExited) { continue }
                    if ([String]::IsNullOrWhiteSpace($candidate.CommandLine)) { throw 'JVM command line unavailable' }
                    if ($candidate.CommandLine.IndexOf($agent, [StringComparison]::OrdinalIgnoreCase) -ge 0) { $busy = $true }
                }
                if ($busy) { Write-Output 'BUSY' } else { Write-Output 'IDLE' }
                """.formatted(encodedAgent, ProcessHandle.current().pid());
        final String windowsRoot = System.getenv("SystemRoot");
        if (windowsRoot == null || !Path.of(windowsRoot).isAbsolute()) throw new IOException("Windows system root unavailable");
        final Path powershell = Path.of(windowsRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        final Path output = Files.createTempFile("turboism-process-guard-", ".log");
        Process query = null;
        try {
            query = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-OutputFormat", "Text", "-EncodedCommand",
                    Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!query.waitFor(15, TimeUnit.SECONDS)) throw new IOException("Windows process query timed out");
            if (query.exitValue() != 0 || Files.size(output) > 1024) {
                throw new IOException("Windows process state could not be established");
            }
            final String status = Files.readString(output, StandardCharsets.UTF_8).trim().toUpperCase(Locale.ROOT);
            if (!status.equals("BUSY") && !status.equals("IDLE")) throw new IOException("Invalid Windows process query response");
            return status.equals("BUSY");
        } finally {
            if (query != null && query.isAlive()) {
                query.destroyForcibly();
                query.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(output);
        }
    }
}
