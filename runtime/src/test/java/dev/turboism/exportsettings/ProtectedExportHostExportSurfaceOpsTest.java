package dev.turboism.exportsettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.mapping.verification.VerifiedAccessException;
import java.io.File;
import org.junit.jupiter.api.Test;

/**
 * Behavioural pins for {@link ProtectedExportHostExportSurfaceOps}.
 *
 * <p>The native export half re-drives the host's own export entry point and force-releases a
 * locked file handle, so the guards it keeps are what stand between a wrong host object and a
 * native call: a driver that is not the verified type must be rejected before any resolution, and
 * an alias the verified plan does not carry must fail closed instead of reading as "no handles
 * held".</p>
 */
class ProtectedExportHostExportSurfaceOpsTest {

    private static final String FILE_CACHE_INSTANCE = "cubism.protected-export.file-cache.instance";

    @Test
    void theNativeReDriveRejectsANullDriverBeforeTouchingThePlan() {
        final ProtectedExportHostExportSurfaceOps ops = ops();

        final IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ops.invokeNativeExport(null, new Object(), new Object(), new Object()));
        assertEquals("export driver is not the verified driver type", failure.getMessage());
    }

    @Test
    void theNativeReDriveFailsClosedForANonDriverTypeOnceTheFrameIsPresent() {
        final ProtectedExportHostExportSurfaceOps ops = ops();

        assertThrows(
                VerifiedAccessException.class,
                () -> ops.invokeNativeExport(new Object(), new Object(), new Object(), new Object()));
    }

    @Test
    void theFileCacheProbeFailsClosedInsteadOfReportingNoHandles() {
        final ProtectedExportHostExportSurfaceOps ops = ops();

        final VerifiedAccessException release =
                assertThrows(VerifiedAccessException.class, () -> ops.releaseFileHandleFor(new File("model.cmo3")));
        assertEquals(FILE_CACHE_INSTANCE, release.alias());

        final VerifiedAccessException diagnostics =
                assertThrows(VerifiedAccessException.class, () -> ops.fileHandleDiagnostics(new File("model.cmo3")));
        assertEquals(FILE_CACHE_INSTANCE, diagnostics.alias());
    }

    @Test
    void theExportDialogProbeFailsClosedForANonDialogOwner() {
        final ProtectedExportHostExportSurfaceOps ops = ops();

        assertThrows(VerifiedAccessException.class, () -> ops.isExportDialog(new Object()));
        assertThrows(VerifiedAccessException.class, () -> ops.dialogModelSource(new Object()));
    }

    private static ProtectedExportHostExportSurfaceOps ops() {
        return new ProtectedExportHostExportSurfaceOps(
                ProtectedExportHostOpsTestSupport.resolverWithoutProtectedExportAliases());
    }
}
