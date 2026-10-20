package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WindowsHarnessDiscoveryTest {
    @TempDir
    Path home;

    @Test
    void discoversReportedWingetAndNpmLayoutsWithoutInheritedPath() throws Exception {
        Path roaming = Files.createDirectories(home.resolve("AppData/Roaming/npm"));
        Path local = Files.createDirectories(home.resolve("AppData/Local"));
        Path claude = Files.createDirectories(local.resolve(
                        "Microsoft/WinGet/Packages/Anthropic.ClaudeCode_Microsoft.Winget.Source_8wekyb3d8bbwe"))
                .resolve("claude.exe");
        Files.createFile(claude);
        for (String name : List.of("pi", "codex")) {
            Files.createFile(roaming.resolve(name));
            Files.createFile(roaming.resolve(name + ".cmd"));
            Files.createFile(roaming.resolve(name + ".ps1"));
        }
        List<Path> directories = AgentLocator.commonInstallDirectories(
                home.toString(),
                Map.of("APPDATA", roaming.getParent().toString(), "LOCALAPPDATA", local.toString()),
                true);
        assertEquals(List.of(claude), AgentLocator.findExecutables("claude", directories, true));
        assertEquals(List.of(roaming.resolve("pi.cmd")), AgentLocator.findExecutables("pi", directories, true));
        assertEquals(List.of(roaming.resolve("codex.cmd")), AgentLocator.findExecutables("codex", directories, true));
    }

    @Test
    void discoversNativeLaunchersAndConfiguredPrefixes() throws Exception {
        Path bin = Files.createDirectories(home.resolve(".local/bin"));
        Path managedPi = Files.createDirectories(home.resolve(".pi/agent/bin"));
        Path customPi = Files.createDirectories(home.resolve("custom-pi/bin"));
        Path npm = Files.createDirectories(home.resolve("custom-npm"));
        Files.createFile(bin.resolve("claude.exe"));
        Files.createFile(managedPi.resolve("pi.cmd"));
        Files.createFile(customPi.resolve("pi.cmd"));
        Files.createFile(npm.resolve("codex.cmd"));
        List<Path> directories = AgentLocator.commonInstallDirectories(
                home.toString(),
                Map.of("PI_CODING_AGENT_DIR", customPi.getParent().toString(), "NPM_CONFIG_PREFIX", npm.toString()),
                true);
        assertEquals(List.of(bin.resolve("claude.exe")), AgentLocator.findExecutables("claude", directories, true));
        assertTrue(AgentLocator.findExecutables("pi", directories, true)
                .containsAll(List.of(managedPi.resolve("pi.cmd"), customPi.resolve("pi.cmd"))));
        assertEquals(List.of(npm.resolve("codex.cmd")), AgentLocator.findExecutables("codex", directories, true));
    }

    @Test
    void augmentsOnlyChildPathAndPreservesExistingEntriesAndKeyCase() throws Exception {
        Path node = Files.createDirectories(home.resolve("nodejs"));
        Map<String, String> environment = new HashMap<>(Map.of("Path", "C:\\Windows;C:\\Tools", "KEEP", "value"));
        AgentLocator.augmentWindowsPath(environment, List.of(node, node, home.resolve("missing")));
        assertEquals("C:\\Windows;C:\\Tools;" + node, environment.get("Path"));
        assertFalse(environment.containsKey("PATH"));
        assertEquals("value", environment.get("KEEP"));
    }

    @Test
    void checksStderrAndVersionAndTriesNextInstalledCandidate() throws Exception {
        assumeFalse(AgentLocator.isWindows());
        Path old = script("old", "echo 'pi 0.1.0' >&2\n");
        Path current = script(
                "current",
                "if [ \"$1\" = '--version' ]; then echo 'pi 1.2.3' >&2; else echo 'rpc --extension' >&2; fi\n");
        List<String> diagnostic = new ArrayList<>();
        AgentProfile profile = AgentCatalog.profile("pi").orElseThrow();
        assertEquals(
                current,
                AgentAvailability.locate(profile, List.of(old, current), diagnostic::add)
                        .orElseThrow());
        assertEquals("1.2.3", AgentAvailability.version(current));
        assertTrue(diagnostic.get(0).contains("native-mode-not-advertised"));
        assertTrue(diagnostic.get(1).contains("version=1.2.3 status=available"));
    }

    @Test
    void explicitRefreshRechecksPreviouslyUnsupportedCandidate() throws Exception {
        assumeFalse(AgentLocator.isWindows());
        Path candidate = script("refresh", "echo 'pi 0.1.0'\n");
        AgentProfile profile = AgentCatalog.profile("pi").orElseThrow();
        assertFalse(AgentAvailability.available(profile, candidate));
        Files.writeString(candidate, "#!/bin/sh\necho 'pi 1.2.3 rpc --extension'\n");
        assertFalse(AgentAvailability.available(profile, candidate));
        AgentAvailability.invalidate();
        assertTrue(AgentAvailability.available(profile, candidate));
        assertEquals("1.2.3", AgentAvailability.version(candidate));
    }

    @Test
    void doesNotAcceptErrorOutputAsSupportedProtocol() throws Exception {
        assumeFalse(AgentLocator.isWindows());
        Path candidate = script("broken", "echo 'rpc --extension' >&2\nexit 1\n");
        List<String> diagnostics = new ArrayList<>();
        assertTrue(
                AgentAvailability.locate(AgentCatalog.profile("pi").orElseThrow(), List.of(candidate), diagnostics::add)
                        .isEmpty());
        assertTrue(diagnostics.get(0).contains("probe-failed-IOException"));
    }

    @Test
    void readsNonSeekablePipeWithoutReadNBytesAndDrainsPastOutputLimit() throws Exception {
        byte[] bytes = new byte[140 * 1024];
        java.util.Arrays.fill(bytes, (byte) 'x');
        var source = new java.io.ByteArrayInputStream(bytes);
        var pipe = new java.io.FilterInputStream(source) {
            @Override
            public byte[] readNBytes(int length) throws java.io.IOException {
                throw new java.io.IOException("Windows process pipe cannot seek");
            }

            @Override
            public long transferTo(java.io.OutputStream target) throws java.io.IOException {
                throw new java.io.IOException("Use ordinary reads for process pipes");
            }
        };
        assertEquals(128 * 1024, AgentAvailability.readProbeOutput(pipe).length());
        assertEquals(0, source.available());
    }

    private Path script(String directory, String body) throws Exception {
        Path executable = Files.createDirectories(home.resolve(directory)).resolve("pi");
        Files.writeString(executable, "#!/bin/sh\n" + body);
        assertTrue(executable.toFile().setExecutable(true));
        return executable;
    }
}
