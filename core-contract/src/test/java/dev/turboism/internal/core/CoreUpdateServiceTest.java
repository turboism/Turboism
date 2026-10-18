package dev.turboism.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.internal.core.CoreUpdateService.PreferenceSaveResult;
import dev.turboism.internal.core.CoreUpdateService.Preferences;
import dev.turboism.internal.core.CoreUpdateService.Snapshot;
import dev.turboism.internal.core.CoreUpdateService.Status;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class CoreUpdateServiceTest {

    private static Snapshot snapshot(
            final Status status, final Optional<String> version, final OptionalLong build, final boolean reminder) {
        return new Snapshot(status, "1.0", version, build, false, reminder);
    }

    @Test
    void idleSnapshotHasNoCandidate() {
        final Snapshot idle = Snapshot.idle("1.2.3");
        assertEquals(Status.IDLE, idle.status());
        assertEquals("1.2.3", idle.localVersion());
        assertEquals(Optional.empty(), idle.availableIdentity());
        assertEquals(Optional.empty(), idle.downloadUrl());
    }

    @Test
    void availableIdentityIncludesBuildWhenPresent() {
        assertEquals(
                Optional.of("0.43.11 (Build 5)"),
                snapshot(Status.UPDATE_AVAILABLE, Optional.of("0.43.11"), OptionalLong.of(5), false)
                        .availableIdentity());
        assertEquals(
                Optional.of("0.43.11"),
                snapshot(Status.UPDATE_AVAILABLE, Optional.of("0.43.11"), OptionalLong.empty(), false)
                        .availableIdentity());
    }

    @Test
    void snapshotRejectsInconsistentStates() {
        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(Status.UPDATE_AVAILABLE, Optional.of(" "), OptionalLong.empty(), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(Status.IDLE, Optional.of("2.0"), OptionalLong.empty(), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(Status.UP_TO_DATE, Optional.empty(), OptionalLong.of(3), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(Status.UPDATE_AVAILABLE, Optional.of("2.0"), OptionalLong.of(0), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(Status.IDLE, Optional.empty(), OptionalLong.empty(), true));
        assertNotNull(snapshot(Status.UPDATE_AVAILABLE, Optional.of("2.0"), OptionalLong.of(1), true));
    }

    @Test
    void snapshotRejectsNullComponents() {
        assertThrows(
                NullPointerException.class,
                () -> new Snapshot(null, "1", Optional.empty(), OptionalLong.empty(), false, false));
        assertThrows(
                NullPointerException.class,
                () -> new Snapshot(Status.IDLE, null, Optional.empty(), OptionalLong.empty(), false, false));
    }

    @Test
    void preferenceSaveResultFactories() {
        assertTrue(PreferenceSaveResult.success().saved());
        final PreferenceSaveResult failed = PreferenceSaveResult.failed("why");
        assertFalse(failed.saved());
        assertEquals("why", failed.message());
        assertThrows(NullPointerException.class, () -> PreferenceSaveResult.failed(null));
    }

    @Test
    void unavailableServiceIsInertAndRefusesSaves() throws Exception {
        final CoreUpdateService service = CoreUpdateService.unavailable();
        assertSame(service, CoreUpdateService.unavailable());
        assertFalse(service.available());
        assertEquals(Status.UNAVAILABLE, service.snapshot().status());
        assertTrue(service.automaticChecksEnabled());
        assertSame(
                service.snapshot(), service.manualCheck().toCompletableFuture().get());
        final PreferenceSaveResult result = service.saveAutomaticChecksEnabled(false);
        assertFalse(result.saved());
        assertThrows(NullPointerException.class, () -> service.savePreferences(null));
        assertThrows(NullPointerException.class, () -> service.subscribe(null));
        service.subscribe(snapshot -> {}).close();
        service.start();
        service.close();
    }

    @Test
    void convenienceDefaultsDelegateToCoreMethods() {
        final boolean[] saved = new boolean[1];
        final CoreUpdateService service = new CoreUpdateService() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public Snapshot snapshot() {
                return Snapshot.idle("x");
            }

            @Override
            public Preferences preferences() {
                return new Preferences(false);
            }

            @Override
            public void start() {}

            @Override
            public java.util.concurrent.CompletionStage<Snapshot> checkManual() {
                return java.util.concurrent.CompletableFuture.completedFuture(snapshot());
            }

            @Override
            public PreferenceSaveResult savePreferences(final Preferences preferences) {
                saved[0] = preferences.automaticChecksEnabled();
                return PreferenceSaveResult.success();
            }

            @Override
            public dev.turboism.sdk.plugin.Registration subscribe(
                    final java.util.function.Consumer<Snapshot> listener) {
                return () -> {};
            }

            @Override
            public void close() {}
        };
        assertFalse(service.automaticChecksEnabled());
        assertTrue(service.saveAutomaticChecksEnabled(true).saved());
        assertTrue(saved[0]);
        assertEquals(
                Status.IDLE, service.manualCheck().toCompletableFuture().join().status());
    }
}
