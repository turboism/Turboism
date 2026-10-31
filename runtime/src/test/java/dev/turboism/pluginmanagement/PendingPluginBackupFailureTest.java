package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PendingPluginBackupFailureTest {
    @TempDir
    Path home;

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void incompleteBackupNeverRestoresOverLivePlugins(final int failAt) throws Exception {
        final Path plugins = Files.createDirectories(home.resolve("plugins"));
        final byte[] originalA = PluginManagementPackageFixture.pluginJarBytes("example.a", "1.0.0");
        final byte[] originalZ = PluginManagementPackageFixture.pluginJarBytes("example.z", "1.0.0");
        final Path a = Files.write(plugins.resolve("aa.jar"), originalA);
        final Path z = Files.write(plugins.resolve("zz.jar"), originalZ);
        final Path note = Files.writeString(plugins.resolve("user-note.txt"), "preserved");
        final Path update = Files.write(
                home.resolve("update.jar"), PluginManagementPackageFixture.pluginJarBytes("example.a", "2.0.0"));
        final AtomicInteger copied = new AtomicInteger();
        final PendingPluginOperations pending = new PendingPluginOperations(
                home,
                (source, target, parent) -> { throw new AssertionError("No install may start before backup completes"); },
                (source, target, parent) -> {
                    if (copied.incrementAndGet() == failAt) {
                        Files.write(target, new byte[] {0});
                        throw new IOException("Injected partial backup write failure");
                    }
                    Files.copy(source, target);
                });
        assertTrue(pending.stage(update).accepted());
        final Path journal = home.resolve("state/runtime/plugin-management/pending.json");
        final byte[] consentedJournal = Files.readAllBytes(journal);
        final var result = pending.apply();

        assertArrayEquals(originalA, Files.readAllBytes(a), "An incomplete backup must not replace live A");
        assertArrayEquals(originalZ, Files.readAllBytes(z), "An incomplete backup must not replace live Z");
        assertEquals("preserved", Files.readString(note));
        assertArrayEquals(consentedJournal, Files.readAllBytes(journal));
        assertEquals(1, pending.operations().size());
        assertEquals(PendingPluginOperations.Status.RECOVERY_REQUIRED, result.status());
        assertEquals("PLUGIN_PENDING_BACKUP_FAILED", result.code());
    }
}
