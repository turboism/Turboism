package dev.turboism.core.runtime.psd;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level replace contract with a fake host: no native call, no host thread and no Cubism
 * process is involved. Only authority, revision consumption and outcome classification are asserted.
 */
class RuntimePsdReplaceServiceTest {
    private static final RawImageId TARGET = new RawImageId("raw-target");
    private static final RawImageId OBSERVED = new RawImageId("raw-observed");
    private static final PsdEditRegistry.Binding BINDING =
        new PsdEditRegistry.Binding("session-a", 4L);
    private static final List<String> REQUIRED_PERMISSIONS = List.of(
        PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
        PermissionIds.TURBOISM_FILE_READ,
        PermissionIds.TURBOISM_FILE_WRITE
    );

    @TempDir Path root;

    @Test
    void observedApplicationAppliesAndConsumesTheRevisionExactlyOnce() throws Exception {
        final Fixture fixture = fixture(allowAll(), new AtomicBoolean(true));
        final AtomicInteger nativeCalls = new AtomicInteger();
        final PsdReplaceHost host = (target, stage, admission) -> {
            admission.run();
            nativeCalls.incrementAndGet();
            assertEquals(TARGET, target);
            assertTrue(Files.exists(stage));
            return applied();
        };

        final PsdReplaceResult result = await(fixture.service.replaceRawImagePsd(
            host, TARGET, fixture.file, fixture.revision));

        assertEquals(PsdReplaceResult.Status.APPLIED, result.status());
        assertEquals(TARGET, result.before());
        assertEquals(Optional.of(OBSERVED), result.after());
        assertEquals(Optional.of(fixture.revision), result.consumedRevision());
        assertEquals(1, nativeCalls.get());

        // The consumed token cannot be replayed.
        final PsdReplaceResult replay = await(fixture.service.replaceRawImagePsd(
            host, TARGET, fixture.file, fixture.revision));
        assertEquals(PsdReplaceResult.Status.REJECTED, replay.status());
        assertEquals(1, nativeCalls.get());
        fixture.close();
    }

    @Test
    void aSaveDuringReplacementIsSerializedAndConsumesItsOwnRevision() throws Exception {
        final Fixture fixture = fixture(allowAll(), new AtomicBoolean(true));
        final java.util.concurrent.CountDownLatch firstNativeEntered =
            new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseFirst = new java.util.concurrent.CountDownLatch(1);
        final AtomicInteger nativeCalls = new AtomicInteger();
        final List<String> stageContents = new ArrayList<>();
        final PsdReplaceHost host = (target, stage, admission) -> {
            admission.run();
            try {
                stageContents.add(Files.readString(stage));
            } catch (IOException unreadable) {
                throw new IllegalStateException(unreadable);
            }
            if (nativeCalls.incrementAndGet() == 1) {
                firstNativeEntered.countDown();
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return applied();
        };

        final CompletionStage<PsdReplaceResult> first = fixture.service.replaceRawImagePsd(
            host, TARGET, fixture.file, fixture.revision);
        assertTrue(firstNativeEntered.await(3, TimeUnit.SECONDS),
            "the first replacement must hold the lane");

        // A second stable save arrives while the first replacement is still in-flight.
        final List<PsdFileRevision> delivered = new ArrayList<>();
        fixture.file.observeSaves(delivered::add);
        Files.writeString(fixture.allocation.validatedPath(), "second save content");
        fixture.file.publishStableSave(PsdStableSnapshot.capture(fixture.allocation));
        assertEquals(1, delivered.size());
        final PsdFileRevision second = delivered.get(0);

        final CompletionStage<PsdReplaceResult> secondResult = fixture.service.replaceRawImagePsd(
            host, TARGET, fixture.file, second);
        releaseFirst.countDown();

        assertEquals(PsdReplaceResult.Status.APPLIED, await(first).status());
        assertEquals(PsdReplaceResult.Status.APPLIED, await(secondResult).status());
        assertEquals(2, nativeCalls.get(), "each save must reach the native lane exactly once");
        assertEquals(
            List.of("runtime PSD replace fixture", "second save content"),
            stageContents,
            "the single lane must preserve save order"
        );
        fixture.close();
    }

    @Test
    void stageFailureDetailsStaySanitizedAndLeaveTheRevisionUsable() throws Exception {
        final Fixture fixture = fixture(allowAll(), new AtomicBoolean(true));
        try {
            for (final var detail : List.of(new PsdReplaceHost.Failure("CONSTRUCT", "OUT_OF_MEMORY"),
                new PsdReplaceHost.Failure("/private/path", "native.Type: private-value"))) {
                final PsdReplaceHost host = (target, stage, admission) -> {
                    admission.run();
                    return new PsdReplaceHost.Replacement("STAGE_UNREADABLE", true, false, false, false,
                        false, Optional.empty(), "safe", Optional.of(detail));
                };
                final var result = await(fixture.service.replaceRawImagePsd(
                    host, TARGET, fixture.file, fixture.revision));
                assertEquals(PsdReplaceResult.Status.FAILED, result.status());
                assertTrue(result.consumedRevision().isEmpty());
                final boolean unknown = detail.phase().startsWith("/");
                assertEquals("PSD_NATIVE_REPLACE;status=STAGE_UNREADABLE;phase="
                    + (unknown ? "UNKNOWN" : "CONSTRUCT") + ";category="
                    + (unknown ? "UNKNOWN" : "OUT_OF_MEMORY"), result.diagnostic());
            }
            assertEquals(PsdReplaceResult.Status.APPLIED, await(fixture.service.replaceRawImagePsd(
                (target, stage, admission) -> { admission.run(); return applied(); },
                TARGET, fixture.file, fixture.revision)).status());
        } finally {
            fixture.close();
        }
    }

    @Test
    void aStagedRevisionSurvivesExternalDeletionOfTheLiveFile() throws Exception {
        final Fixture fixture = fixture(allowAll(), new AtomicBoolean(true));
        final AtomicBoolean stageExisted = new AtomicBoolean();
        final PsdReplaceHost host = (target, stage, admission) -> {
            admission.run();
            stageExisted.set(Files.exists(stage));
            return applied();
        };

        // The external application or the OS may remove the live temporary file after the
        // runtime already staged the revision: replacement must still apply the retained stage.
        Files.delete(fixture.allocation.validatedPath());
        final PsdReplaceResult result = await(fixture.service.replaceRawImagePsd(
            host, TARGET, fixture.file, fixture.revision));

        assertEquals(PsdReplaceResult.Status.APPLIED, result.status());
        assertTrue(stageExisted.get(), "the staged copy must remain readable for replacement");
        fixture.close();
    }

    @Test
    void foreignHandleAndForgedRevisionAreRejectedBeforeAnyNativeCall() throws Exception {
        final Fixture fixture = fixture(allowAll(), new AtomicBoolean(true));
        final AtomicInteger nativeCalls = new AtomicInteger();
        final PsdReplaceHost host = (target, stage, admission) -> {
            nativeCalls.incrementAndGet();
            return applied();
        };
        final PsdEditFile forged = new RuntimePsdEditFileTestHandle();

        assertEquals(
            PsdReplaceResult.Status.REJECTED,
            await(fixture.service.replaceRawImagePsd(host, TARGET, forged, fixture.revision)).status()
        );
        assertEquals(
            PsdReplaceResult.Status.REJECTED,
            await(fixture.service.replaceRawImagePsd(
                host, TARGET, fixture.file, new PsdFileRevision() { })).status()
        );
        assertEquals(0, nativeCalls.get());
        fixture.close();
    }

    @Test
    void permissionDenialAndInactiveScopeStopBeforeNative() throws Exception {
        final Fixture denied = fixture(
            (permission, operation) -> {
                throw new CubismPermissionException("permission denied");
            },
            new AtomicBoolean(true));
        final AtomicInteger nativeCalls = new AtomicInteger();
        final PsdReplaceHost host = (target, stage, admission) -> {
            nativeCalls.incrementAndGet();
            return applied();
        };
        assertEquals(
            PsdReplaceResult.Status.REJECTED,
            await(denied.service.replaceRawImagePsd(host, TARGET, denied.file, denied.revision)).status()
        );
        denied.close();

        final AtomicBoolean active = new AtomicBoolean(true);
        final Fixture inactive = fixture(allowAll(), active);
        active.set(false);
        assertEquals(
            PsdReplaceResult.Status.UNAVAILABLE,
            await(inactive.service.replaceRawImagePsd(host, TARGET, inactive.file, inactive.revision))
                .status()
        );
        assertEquals(0, nativeCalls.get());
        inactive.close();
    }

    @Test
    void hostEditingRejectionLeavesTheRevisionAvailableForRetry() throws Exception {
        final Fixture fixture = fixture(allowAll(), new AtomicBoolean(true));
        final AtomicBoolean editing = new AtomicBoolean(true);
        final PsdReplaceHost host = (target, stage, admission) -> {
            admission.run();
            if (editing.get()) {
                return new PsdReplaceHost.Replacement(
                    "HOST_EDIT_IN_PROGRESS", true, false, false, true, false, Optional.empty(),
                    "Cubism is applying another edit.");
            }
            return applied();
        };

        final PsdReplaceResult rejected = await(fixture.service.replaceRawImagePsd(
            host, TARGET, fixture.file, fixture.revision));
        assertEquals(PsdReplaceResult.Status.REJECTED, rejected.status());
        assertTrue(rejected.consumedRevision().isEmpty());

        editing.set(false);
        final PsdReplaceResult applied = await(fixture.service.replaceRawImagePsd(
            host, TARGET, fixture.file, fixture.revision));
        assertEquals(PsdReplaceResult.Status.APPLIED, applied.status());
        fixture.close();
    }

    @Test
    void unobservableOrFailedNativeResultsArePartialFailuresAndNeverConsumeTheRevision()
        throws Exception {
        final Fixture fixture = fixture(allowAll(), new AtomicBoolean(true));
        final List<PsdReplaceHost.Replacement> outcomes = List.of(
            new PsdReplaceHost.Replacement(
                "NATIVE_RETURNED_UNOBSERVED", true, true, false, false, false, Optional.empty(),
                "Current state could not be re-read."),
            new PsdReplaceHost.Replacement(
                "NATIVE_OUTCOME_UNKNOWN", false, false, true, false, false, Optional.empty(),
                "The native call did not report a usable outcome."),
            new PsdReplaceHost.Replacement(
                "NATIVE_RETURNED", false, true, false, false, true, Optional.of(OBSERVED),
                "The session changed across the native call.")
        );

        for (final PsdReplaceHost.Replacement outcome : outcomes) {
            final PsdReplaceResult result = await(fixture.service.replaceRawImagePsd(
                (target, stage, admission) -> outcome,
                TARGET,
                fixture.file,
                fixture.revision
            ));
            assertEquals(PsdReplaceResult.Status.PARTIAL_FAILURE, result.status());
            assertTrue(result.after().isEmpty());
            assertTrue(result.consumedRevision().isEmpty());
            assertTrue(result.diagnostic().contains("pause=automatic-import"));
        }
        fixture.close();
    }

    @Test
    void nativeReturnWithoutVerifiedIncomingRawLeavesRevisionAvailable() throws Exception {
        final Fixture fixture = fixture(allowAll(), new AtomicBoolean(true));
        final AtomicInteger nativeCalls = new AtomicInteger();
        final PsdReplaceHost host = (target, stage, admission) -> {
            admission.run();
            nativeCalls.incrementAndGet();
            return new PsdReplaceHost.Replacement(
                "NATIVE_RETURNED", true, true, false, false, true, Optional.empty(),
                "Native returned but the affected model images did not prove the incoming raw."
            );
        };

        final PsdReplaceResult first = await(fixture.service.replaceRawImagePsd(
            host, TARGET, fixture.file, fixture.revision));
        assertEquals(PsdReplaceResult.Status.PARTIAL_FAILURE, first.status());
        assertTrue(first.consumedRevision().isEmpty());
        assertTrue(first.diagnostic().contains("pause=automatic-import"));

        // The same revision remains available for an explicitly controlled retry; the service
        // must not turn a native return without incoming identity evidence into APPLIED.
        final PsdReplaceResult retry = await(fixture.service.replaceRawImagePsd(
            host, TARGET, fixture.file, fixture.revision));
        assertEquals(PsdReplaceResult.Status.PARTIAL_FAILURE, retry.status());
        assertTrue(retry.consumedRevision().isEmpty());
        assertEquals(2, nativeCalls.get());
        fixture.close();
    }

    @Test
    void aThrowingHostIsReportedAsPartialFailureNotAsSuccess() throws Exception {
        final Fixture fixture = fixture(allowAll(), new AtomicBoolean(true));
        final PsdReplaceResult result = await(fixture.service.replaceRawImagePsd(
            (target, stage, admission) -> {
                throw new IllegalStateException("native exploded");
            },
            TARGET,
            fixture.file,
            fixture.revision
        ));

        assertEquals(PsdReplaceResult.Status.PARTIAL_FAILURE, result.status());
        assertFalse(result.diagnostic().contains("native exploded"));
        fixture.close();
    }

    @Test
    void unavailableProjectionIsReportedWithoutConsumingTheRevision() throws Exception {
        final Fixture fixture = fixture(allowAll(), new AtomicBoolean(true));
        final PsdReplaceResult result = await(fixture.service.replaceRawImagePsd(
            (target, stage, admission) -> PsdReplaceHost.Replacement.unavailable(),
            TARGET,
            fixture.file,
            fixture.revision
        ));

        assertEquals(PsdReplaceResult.Status.UNAVAILABLE, result.status());
        assertTrue(result.consumedRevision().isEmpty());
        fixture.close();
    }

    @Test
    void nativeAdmissionRechecksPermissionBeforeNativeEntry() throws Exception {
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final List<String> calls = new ArrayList<>();
        final Fixture fixture = fixture(permissionChecker(allowed, calls), new AtomicBoolean(true));
        final PsdReplaceHost host = (target, stage, admission) -> {
            allowed.set(false);
            admission.run();
            return applied();
        };

        final PsdReplaceResult result = await(fixture.service.replaceRawImagePsd(
            host, TARGET, fixture.file, fixture.revision));

        assertEquals(PsdReplaceResult.Status.REJECTED, result.status());
        for (final String permission : REQUIRED_PERMISSIONS) {
            assertTrue(calls.stream().filter(permission::equals).count() >= 2);
        }
        fixture.close();
    }

    private static PsdReplaceHost.Replacement applied() {
        return new PsdReplaceHost.Replacement(
            "NATIVE_RETURNED", true, true, false, false, true, Optional.of(OBSERVED),
            "The native replacement returned and current state was re-read.");
    }

    private Fixture fixture(
        final PermissionChecker permissionChecker,
        final AtomicBoolean active
    ) throws IOException {
        final PsdEditRegistry registry = new PsdEditRegistry();
        final PsdTemporaryFile allocation = PsdTemporaryFile.createIn(root);
        Files.writeString(allocation.validatedPath(), "runtime PSD replace fixture");
        final PsdStableSnapshot.Snapshot baseline = PsdStableSnapshot.capture(allocation);
        final RuntimePsdEditFile file = new RuntimePsdEditFile(
            "test.plugin",
            BINDING,
            allocation,
            registry,
            permissionChecker,
            active::get,
            Runnable::run,
            Runnable::run,
            path -> { },
            (task, delayMillis) -> () -> { },
            System::nanoTime,
            baseline.sha256()
        );
        registry.register(BINDING, file, allocation);
        final PsdFileRevision revision = registry.issueRevision(BINDING, file, baseline);
        file.beginWatching(revision);
        return new Fixture(
            new RuntimePsdReplaceService(
                "test.plugin", permissionChecker, active::get, Runnable::run, registry),
            file,
            revision,
            registry,
            allocation
        );
    }

    private static PermissionChecker allowAll() {
        return (permission, operation) -> { };
    }

    private static PermissionChecker permissionChecker(
        final AtomicBoolean allowed,
        final List<String> calls
    ) {
        return (permission, operation) -> {
            calls.add(permission);
            if (!allowed.get()) throw new CubismPermissionException("permission denied");
        };
    }

    private static PsdReplaceResult await(final CompletionStage<PsdReplaceResult> stage)
        throws Exception {
        return stage.toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private record Fixture(
        RuntimePsdReplaceService service,
        RuntimePsdEditFile file,
        PsdFileRevision revision,
        PsdEditRegistry registry,
        PsdTemporaryFile allocation
    ) {
        private void close() {
            service.close();
            registry.close();
            file.revokeInternal();
        }
    }

    /** A handle-shaped value that the registry never issued. */
    private static final class RuntimePsdEditFileTestHandle implements PsdEditFile {
        @Override
        public CompletionStage<dev.turboism.sdk.cubism.psd.PsdFileOperationResult>
            openInDefaultApplication() {
            throw new AssertionError("open must not run");
        }

        @Override
        public dev.turboism.sdk.plugin.Registration observeSaves(
            final java.util.function.Consumer<PsdFileRevision> listener
        ) {
            throw new AssertionError("observe must not run");
        }

        @Override
        public CompletionStage<dev.turboism.sdk.cubism.psd.PsdFileOperationResult> stop() {
            throw new AssertionError("stop must not run");
        }
    }
}
