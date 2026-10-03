package dev.turboism.recentfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.RecentFileAdapter;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class RuntimeRecentFileServiceTest {
    @Test
    void delegatesToConnectedRuntimeAdapter() {
        final RecentFileSummary file = new RecentFileSummary(
                new RecentFileId("one"),
                "One.cmo3",
                Optional.of(Instant.parse("2026-08-05T00:00:00Z")),
                Optional.of("C:/models/One.cmo3"));
        final RuntimeRecentFileService service =
                new RuntimeRecentFileService(adapter(List.of(file)), PermissionChecker.allowAll());

        assertEquals(List.of(file), service.list());
    }

    @Test
    void safeModeReturnsNoFabricatedFiles() {
        assertEquals(
                List.of(),
                new RuntimeRecentFileService(RecentFileAdapter.safeMode(), PermissionChecker.allowAll()).list());
    }

    @Test
    void reportsUnavailableForSafeModeAdapter() {
        assertFalse(
                new RuntimeRecentFileService(RecentFileAdapter.safeMode(), PermissionChecker.allowAll()).isAvailable());
    }

    @Test
    void reportsAvailableForConnectedAdapter() {
        assertTrue(new RuntimeRecentFileService(adapter(List.of()), PermissionChecker.allowAll()).isAvailable());
    }

    @Test
    void availabilityFollowsTheHostOperationsProbe() {
        final boolean[] attached = {false};
        final RecentFileAdapter adapter = RecentFileAdapter.connected(new RecentFileAdapter.HostOperations() {
            @Override
            public List<RecentFileSummary> list() {
                return List.of();
            }

            @Override
            public Optional<RecentFileId> current() {
                return Optional.empty();
            }

            @Override
            public boolean available() {
                return attached[0];
            }
        });
        final RuntimeRecentFileService service = new RuntimeRecentFileService(adapter, PermissionChecker.allowAll());

        assertFalse(service.isAvailable());
        attached[0] = true;
        assertTrue(service.isAvailable(), "the probe must answer the current delegate state, not a snapshot");
    }

    @Test
    void aThrowingAvailabilityProbeReportsUnavailableInsteadOfThrowing() {
        final RuntimeRecentFileService service = new RuntimeRecentFileService(
                RecentFileAdapter.connected(new RecentFileAdapter.HostOperations() {
                    @Override
                    public List<RecentFileSummary> list() {
                        return List.of();
                    }

                    @Override
                    public Optional<RecentFileId> current() {
                        return Optional.empty();
                    }

                    @Override
                    public boolean available() {
                        // Mirrors DynamicRuntimeHostAdapters.call: an outermost
                        // adapter call may run session teardown on this thread.
                        throw new IllegalStateException("host session teardown on the calling thread");
                    }
                }),
                PermissionChecker.allowAll());
        assertFalse(service.isAvailable(), "a throwing probe is an unavailable host, never an escaping failure");
    }

    @Test
    void checksRecentFileReadPermissionBeforeCallingAdapter() {
        final int[] calls = {0};
        final RuntimeRecentFileService service = new RuntimeRecentFileService(
                RecentFileAdapter.connected(new RecentFileAdapter.HostOperations() {
                    @Override
                    public List<RecentFileSummary> list() {
                        calls[0]++;
                        return List.of();
                    }

                    @Override
                    public Optional<RecentFileId> current() {
                        return Optional.empty();
                    }
                }),
                (permission, operation) -> {
                    throw new dev.turboism.sdk.permission.CubismPermissionException("denied");
                });

        assertThrows(dev.turboism.sdk.permission.CubismPermissionException.class, service::list);
        assertEquals(0, calls[0]);
    }

    @Test
    void usesTheRecentFileReadPermissionId() {
        assertEquals(
                dev.turboism.sdk.permission.PermissionIds.TURBOISM_CUBISM_RECENT_FILE_READ,
                RuntimeRecentFileService.PERMISSION);
    }

    private static RecentFileAdapter adapter(final List<RecentFileSummary> files) {
        return RecentFileAdapter.connected(new RecentFileAdapter.HostOperations() {
            @Override
            public List<RecentFileSummary> list() {
                return files;
            }

            @Override
            public Optional<RecentFileId> current() {
                return Optional.empty();
            }
        });
    }
}
