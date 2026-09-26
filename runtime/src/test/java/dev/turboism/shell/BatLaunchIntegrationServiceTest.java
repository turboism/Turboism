package dev.turboism.shell;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for the editor-side official BAT launch integration service. */
class BatLaunchIntegrationServiceTest {

    @TempDir
    Path home;

    @Test
    void missingStateFileMirrorsNotIntegrated() {
        final BatLaunchIntegrationService service = new BatLaunchIntegrationService(
            home, "Windows 11", command -> failRun());
        assertFalse(service.integrated(), "no state file means not integrated");
    }

    @Test
    void managedRecordsMirrorIntegrated() throws IOException {
        final String originalSha256 = "A".repeat(64);
        final String managedSha256 = "B".repeat(64);
        writeState("{\"format\":\"turboism.cubism.installation-state\",\"schemaVersion\":1,"
            + "\"installations\":[],\"managedShortcuts\":[],\"launchMode\":\"independent\","
            + "\"batIntegrations\":[{"
            + "\"path\":\"C:\\\\Program Files\\\\Live2D Cubism 5.3\\\\CubismEditor5.bat\","
            + "\"backupPath\":\"C:\\\\Program Files\\\\Live2D Cubism 5.3\\\\CubismEditor5.bat.turboism-original.bak\","
            + "\"originalSha256\":\"" + originalSha256 + "\","
            + "\"managedSha256\":\"" + managedSha256 + "\"}]}");
        final BatLaunchIntegrationService service = new BatLaunchIntegrationService(
            home, "Windows 11", command -> failRun());
        assertTrue(service.integrated(), "one managed record means integrated");
    }

    @Test
    void emptyRecordsAndBrokenStateMirrorNotIntegrated() throws IOException {
        writeState("{\"format\":\"turboism.cubism.installation-state\",\"schemaVersion\":1,"
            + "\"installations\":[],\"managedShortcuts\":[],\"batIntegrations\":[]}");
        assertFalse(service().integrated(), "an empty record list means not integrated");

        writeState("{\"batIntegrations\": [");
        assertFalse(service().integrated(), "a truncated state document fails closed to not integrated");

        writeState("{\"batIntegrations\": 12}");
        assertFalse(service().integrated(), "a non-array batIntegrations field means not integrated");
    }

    @Test
    void oversizedStateDocumentIsRejectedBeforeParsing() throws IOException {
        final String pad = "x".repeat(300 * 1024);
        writeState("{\"batIntegrations\":[],\"pad\":\"" + pad + "\"}");
        assertFalse(service().integrated(), "an oversized state document is not parsed");
    }

    @Test
    void supportedRequiresWindowsAndTheConfiguratorScript() throws IOException {
        Files.writeString(home.resolve("configure_turboism.ps1"), "# configurator", StandardCharsets.UTF_8);
        assertFalse(new BatLaunchIntegrationService(home, "Linux", command -> failRun()).supported(),
            "non-Windows hosts never offer BAT integration");
        assertTrue(new BatLaunchIntegrationService(home, "Windows 11", command -> failRun()).supported(),
            "Windows with the configurator script is supported");
        assertFalse(new BatLaunchIntegrationService(home.resolve("missing"), "Windows 11", command -> failRun())
                .supported(),
            "Windows without the configurator script is unsupported");
    }

    @Test
    void commandTargetsTheConfiguratorWithTheHomeSwitch() {
        final List<String> integrate = BatLaunchIntegrationService.buildCommand(
            home, "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", true);
        final List<String> disable = BatLaunchIntegrationService.buildCommand(
            home, "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", false);

        assertEquals("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", integrate.get(0));
        assertEquals(home.resolve("configure_turboism.ps1").toString(), integrate.get(integrate.indexOf("-File") + 1));
        assertEquals(home.toString(), integrate.get(integrate.indexOf("-Home") + 1));
        assertEquals("-IntegrateBat", integrate.get(integrate.size() - 1));
        assertEquals("-DisableBat", disable.get(disable.size() - 1));
    }

    @Test
    void applyDelegatesToTheRunnerAndSurfacesFailures() {
        final AtomicReference<List<String>> seen = new AtomicReference<>();
        final BatLaunchIntegrationService ok = new BatLaunchIntegrationService(home, "Windows 11", command -> {
            seen.set(command);
            return new BatLaunchIntegrationService.InvocationResult(0, "");
        });
        ok.setIntegrated(true);
        assertEquals("-IntegrateBat", seen.get().get(seen.get().size() - 1));

        final BatLaunchIntegrationService failed = new BatLaunchIntegrationService(home, "Windows 11", command ->
            new BatLaunchIntegrationService.InvocationResult(2, "Cubism BAT was edited after Turboism integration"));
        final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> failed.setIntegrated(false));
        assertTrue(failure.getMessage().contains("exit code 2"), "the dialog must surface the exit code");
        assertTrue(failure.getMessage().contains("edited after Turboism integration"),
            "the dialog must surface configurator diagnostics");

        final BatLaunchIntegrationService broken = new BatLaunchIntegrationService(home, "Windows 11", command -> {
            throw new IOException("powershell.exe not found");
        });
        final IllegalStateException spawn = assertThrows(IllegalStateException.class, () -> broken.setIntegrated(true));
        assertTrue(spawn.getMessage().contains("could not be started"));
    }

    private BatLaunchIntegrationService service() {
        return new BatLaunchIntegrationService(home, "Windows 11", command -> failRun());
    }

    private static BatLaunchIntegrationService.InvocationResult failRun() {
        throw new AssertionError("mirroring the state must never run the configurator");
    }

    private void writeState(final String json) throws IOException {
        Files.writeString(home.resolve("cubism-installations.json"), json, StandardCharsets.UTF_8);
    }
}
