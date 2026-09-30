package dev.turboism.runtime.env;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The detection contract: the managed marker is authoritative, Wine-only
 * filesystem facts are admissible, and ambient user-settable variables like
 * {@code WINEPREFIX} or {@code STEAM_COMPAT_*} never decide alone.
 */
class ProtonEnvironmentTest {

    @TempDir
    Path tempDir;

    @Test
    void managedMarkerAloneIsAuthoritative() {
        assertTrue(ProtonEnvironment.underWineOrProton(
                Map.of(ProtonEnvironment.MANAGED_MARKER, "1", "SystemRoot", tempDir.toString())));
    }

    @Test
    void emptyMarkerDoesNotCount() {
        // An empty marker must not claim Proton; with no filesystem facts the
        // answer is native.
        assertFalse(ProtonEnvironment.underWineOrProton(
                Map.of(ProtonEnvironment.MANAGED_MARKER, "", "SystemRoot", tempDir.toString())));
    }

    @Test
    void ambientWineVariablesAreNotEvidence() {
        // Review regression: a user exporting WINEPREFIX/STEAM_COMPAT_* on a
        // native Windows box must not flip the Proton default.
        final Map<String, String> fakeProton = Map.of(
                "WINEPREFIX", tempDir.resolve("prefix").toString(),
                "WINELOADER", "/usr/bin/wine",
                "WINEDLLPATH", "/usr/lib/wine",
                "WINEESYNC", "1",
                "WINEFSYNC", "1",
                "STEAM_COMPAT_DATA_PATH", "/games/compat",
                "STEAM_COMPAT_CLIENT_INSTALL_PATH", "/steam",
                "PROTON_LOG", "1",
                "PROTON_LOG_DIR", tempDir.toString(),
                "SystemRoot", tempDir.toString());
        assertFalse(
                ProtonEnvironment.underWineOrProton(fakeProton),
                "ambient variables alone must not enable Proton defaults");
    }

    @Test
    void falseManagedMarkerAndInvalidSystemRootFailClosed() {
        for (String marker : java.util.List.of("0", "false", "true", " ")) {
            assertFalse(ProtonEnvironment.underWineOrProton(
                    Map.of(ProtonEnvironment.MANAGED_MARKER, marker, "SystemRoot", "invalid\0root")));
        }
    }

    @Test
    void winecfgInSystem32ProvesWine() throws Exception {
        final Path system32 = Files.createDirectories(tempDir.resolve("system32"));
        Files.writeString(system32.resolve("winecfg.exe"), "stub");
        assertTrue(ProtonEnvironment.wineFilesystemFacts(tempDir, tempDir.resolve("no-proc")));
    }

    @Test
    void zDriveProcSelfProvesLinuxKernel() throws Exception {
        final Path procSelf = Files.createDirectories(tempDir.resolve("proc-self"));
        assertFalse(
                ProtonEnvironment.wineFilesystemFacts(tempDir.resolve("no-root"), procSelf),
                "an arbitrary Z directory is not proc evidence");
        Files.writeString(procSelf.resolve("stat"), "test proc stat");
        Files.writeString(procSelf.resolve("maps"), "test proc maps");
        assertTrue(ProtonEnvironment.wineFilesystemFacts(tempDir.resolve("no-root"), procSelf));
    }

    @Test
    void nativeWindowsLayoutIsNotProton() {
        // A plain system32 without winecfg and no Z:\proc\self is native
        // Windows — the filesystem facts are absent.
        assertFalse(ProtonEnvironment.wineFilesystemFacts(tempDir, tempDir.resolve("no-proc")));
    }
}
