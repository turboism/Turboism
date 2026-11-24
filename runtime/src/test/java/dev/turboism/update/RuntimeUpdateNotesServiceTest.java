package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.internal.core.GuidePreferences;
import dev.turboism.internal.core.UpdateNotesService.ReleaseNotes;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeUpdateNotesServiceTest {

    @Test
    void freshOnboardingInstallRecordsBaselineQuietlyWithoutShowingNotes(@TempDir final Path home) throws Exception {
        final AtomicBoolean onboardingPending = new AtomicBoolean(true);
        final GuidePreferences guide = new GuidePreferences() {
            @Override
            public boolean shouldShowGuide() {
                return onboardingPending.get();
            }

            @Override
            public void markGuideSeen() {
                onboardingPending.set(false);
            }
        };
        final AtomicReference<String> version = new AtomicReference<>("0.70.2");
        final RuntimeUpdateNotesService service = new RuntimeUpdateNotesService(
                home, guide, () -> buildInfo(version.get()), new BundledReleaseNotes(), ignored -> {});

        assertTrue(service.pendingReleaseNotes().isEmpty());
        final Path stateFile = home.resolve(RuntimeUpdateNotesService.RELATIVE_PATH);
        assertTrue(Files.exists(stateFile));
        assertTrue(Files.readString(stateFile).contains("\"0.70.2\""));

        guide.markGuideSeen();
        assertTrue(service.pendingReleaseNotes().isEmpty());

        version.set("0.70.3");
        final ReleaseNotes upgraded = service.pendingReleaseNotes().orElseThrow();
        assertEquals("0.70.3", upgraded.version());
        assertEquals(Optional.of("0.70.2"), upgraded.previousVersion());
        assertFalse(upgraded.localizedNotes().isEmpty());

        service.markReleaseNotesSeen("0.70.3");
        assertTrue(service.pendingReleaseNotes().isEmpty());
    }

    @Test
    void preTrackingUpgradeShowsCurrentNotesOnceAndIgnoresDowngrade(@TempDir final Path home) {
        final GuidePreferences seenGuide = new GuidePreferences() {
            @Override
            public boolean shouldShowGuide() {
                return false;
            }

            @Override
            public void markGuideSeen() {}
        };
        final AtomicReference<String> version = new AtomicReference<>("0.70.3");
        final RuntimeUpdateNotesService service = new RuntimeUpdateNotesService(
                home, seenGuide, () -> buildInfo(version.get()), new BundledReleaseNotes(), ignored -> {});

        final ReleaseNotes pending = service.pendingReleaseNotes().orElseThrow();
        assertEquals("0.70.3", pending.version());
        assertEquals(Optional.empty(), pending.previousVersion());
        assertTrue(service.pendingReleaseNotes().isPresent(), "unacknowledged notes stay pending across restarts");

        service.markReleaseNotesSeen("0.70.3");
        assertTrue(service.pendingReleaseNotes().isEmpty());

        version.set("0.70.2");
        assertTrue(service.pendingReleaseNotes().isEmpty(), "downgrades never reopen older release notes");
    }

    @Test
    void preservesCorruptStateBackupBeforeRecordingValidBaseline(@TempDir final Path home) throws Exception {
        final Path stateFile = home.resolve(RuntimeUpdateNotesService.RELATIVE_PATH);
        Files.createDirectories(stateFile.getParent());
        final byte[] corruptBytes = "{corrupt-json".getBytes(StandardCharsets.UTF_8);
        Files.write(stateFile, corruptBytes);

        final List<String> diagnostics = new ArrayList<>();
        final GuidePreferences seenGuide = new GuidePreferences() {
            @Override
            public boolean shouldShowGuide() {
                return false;
            }

            @Override
            public void markGuideSeen() {}
        };
        final RuntimeUpdateNotesService service = new RuntimeUpdateNotesService(
                home,
                seenGuide,
                () -> buildInfo("0.70.3"),
                new BundledReleaseNotes(ignored -> new ByteArrayInputStream(new byte[0])),
                diagnostics::add);

        final ReleaseNotes recovered = service.pendingReleaseNotes().orElseThrow();
        assertEquals("0.70.3", recovered.version());
        assertTrue(diagnostics.stream().anyMatch(entry -> entry.contains("UPDATE_NOTES_STATE_CORRUPT")));

        service.markReleaseNotesSeen("0.70.3");
        final Path backup = stateFile.resolveSibling("update-notes.json.corrupt.bak");
        assertTrue(Files.exists(backup));
        assertArrayEquals(corruptBytes, Files.readAllBytes(backup));
        assertTrue(service.pendingReleaseNotes().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.markReleaseNotesSeen("not-semver"));
    }

    private static FrameworkBuildInfo buildInfo(final String version) {
        return new FrameworkBuildInfo(
                version, FrameworkBuildInfo.Channel.STABLE, OptionalLong.of(10L), "deadbeef", "release", false);
    }
}
