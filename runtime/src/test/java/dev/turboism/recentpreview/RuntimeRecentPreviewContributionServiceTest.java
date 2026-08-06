package dev.turboism.recentpreview;

import dev.turboism.adapter.cubism.RecentPreviewContributionAdapter;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewRenderer;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RuntimeRecentPreviewContributionServiceTest {
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    );

    @Test
    void delegatesContributionAndRefreshToConnectedAdapter() {
        final AtomicInteger contributed = new AtomicInteger();
        final AtomicInteger refreshed = new AtomicInteger();
        final RecentPreviewContributionAdapter adapter = RecentPreviewContributionAdapter.connected(
            new RecentPreviewContributionAdapter.HostOperations() {
                @Override
                public Registration contribute(final RecentPreviewRenderer renderer) {
                    contributed.incrementAndGet();
                    return () -> {
                    };
                }

                @Override
                public void refresh() {
                    refreshed.incrementAndGet();
                }
            }
        );
        final RuntimeRecentPreviewContributionService service =
            new RuntimeRecentPreviewContributionService(adapter, PermissionChecker.allowAll());

        service.contribute(summary -> Optional.empty());
        service.refresh();
        assertEquals(1, contributed.get());
        assertEquals(1, refreshed.get());
    }

    @Test
    void safeModeRefusesContributionAndToleratesRefresh() {
        final RuntimeRecentPreviewContributionService service = new RuntimeRecentPreviewContributionService(
            RecentPreviewContributionAdapter.safeMode(), PermissionChecker.allowAll()
        );
        assertThrows(UnsupportedOperationException.class,
            () -> service.contribute(summary -> Optional.empty()));
        service.refresh();
    }

    @Test
    void checksContributePermissionBeforeCallingAdapter() {
        final int[] calls = {0};
        final RuntimeRecentPreviewContributionService service = new RuntimeRecentPreviewContributionService(
            RecentPreviewContributionAdapter.connected(new RecentPreviewContributionAdapter.HostOperations() {
                @Override
                public Registration contribute(final RecentPreviewRenderer renderer) {
                    calls[0]++;
                    return () -> {
                    };
                }

                @Override
                public void refresh() {
                    calls[0]++;
                }
            }),
            (permission, operation) -> {
                throw new dev.turboism.sdk.permission.CubismPermissionException("denied");
            }
        );

        assertThrows(dev.turboism.sdk.permission.CubismPermissionException.class,
            () -> service.contribute(summary -> Optional.empty()));
        assertThrows(dev.turboism.sdk.permission.CubismPermissionException.class, service::refresh);
        assertEquals(0, calls[0]);
    }

    @Test
    void usesTheRecentPreviewContributePermissionId() {
        assertEquals(
            dev.turboism.sdk.permission.PermissionIds.TURBOISM_UI_RECENT_PREVIEW_CONTRIBUTE,
            RuntimeRecentPreviewContributionService.PERMISSION
        );
    }

    private static RecentFileSummary summary() {
        return new RecentFileSummary(
            new RecentFileId("recent-1"),
            "model.cmo3",
            Optional.of(Instant.parse("2026-08-05T00:00:00Z")),
            Optional.of("C:/models/model.cmo3")
        );
    }

}
