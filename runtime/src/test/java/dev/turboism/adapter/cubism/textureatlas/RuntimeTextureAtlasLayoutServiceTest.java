package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyStatus;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import dev.turboism.sdk.permission.PluginPermission;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTextureAtlasLayoutServiceTest {

    private static final TextureAtlasLayoutConstraints CONSTRAINTS =
        new TextureAtlasLayoutConstraints(16, 8, 1, 1, 1, false, false);
    private static final List<TextureAtlasLayoutItem> ITEMS = List.of(
        new TextureAtlasLayoutItem("texture-a", 4, 3),
        new TextureAtlasLayoutItem("texture-b", 2, 2)
    );
    private static final TextureAtlasLayoutPlan CURRENT = plan(1, 6);

    @Test
    void currentIssuesOwnerBoundTargetAndApplyChecksWritePermissionBeforeProviderMutation() {
        final RecordingProvider provider = new RecordingProvider(state(7));
        final TextureAtlasLayoutCoordinator coordinator = new TextureAtlasLayoutCoordinator();
        coordinator.connect(provider);
        final List<CubismFacadeAuditEvent> audit = new ArrayList<>();
        final RuntimeTextureAtlasLayoutService readOnly = service(coordinator, List.of(readPermission()), audit);

        final TextureAtlasLayoutSnapshot snapshot = readOnly.current().orElseThrow();
        final var denied = readOnly.apply(snapshot.target(), plan(1, 7));

        assertEquals(Optional.of(TextureAtlasLayoutFailureCode.PERMISSION_DENIED), denied.failureCode());
        assertEquals(0, provider.applyCount.get());
        assertEquals(1, audit.size());
        assertEquals("textureAtlasLayouts.apply", audit.get(0).operationId());
    }

    @Test
    void completeValidPlanAppliesOnceAndNoChangeIsStructured() {
        final RecordingProvider provider = new RecordingProvider(state(7));
        final TextureAtlasLayoutCoordinator coordinator = new TextureAtlasLayoutCoordinator();
        coordinator.connect(provider);
        final RuntimeTextureAtlasLayoutService service = service(coordinator, permissions(), new ArrayList<>());
        final TextureAtlasLayoutSnapshot snapshot = service.current().orElseThrow();

        final var changed = service.apply(snapshot.target(), plan(1, 7));
        provider.state = state(8, plan(1, 7));
        final TextureAtlasLayoutSnapshot refreshed = service.current().orElseThrow();
        final var unchanged = service.apply(refreshed.target(), plan(1, 7));

        assertEquals(Optional.of(TextureAtlasLayoutApplyStatus.APPLIED), changed.status());
        assertEquals(Optional.of(TextureAtlasLayoutApplyStatus.NO_CHANGE), unchanged.status());
        assertEquals(2, provider.applyCount.get());
    }

    @Test
    void rejectsIncompleteScaledMarginAndPaddingViolationsBeforeProviderMutation() {
        final RecordingProvider provider = new RecordingProvider(state(7));
        final TextureAtlasLayoutCoordinator coordinator = new TextureAtlasLayoutCoordinator();
        coordinator.connect(provider);
        final RuntimeTextureAtlasLayoutService service = service(coordinator, permissions(), new ArrayList<>());
        final TextureAtlasLayoutSnapshot snapshot = service.current().orElseThrow();

        final List<TextureAtlasLayoutPlan> invalid = List.of(
            new TextureAtlasLayoutPlan(16, 8, 1, List.of(
                new TextureAtlasPlacement("texture-a", 0, 1, 1, 4, 3, false)
            )),
            new TextureAtlasLayoutPlan(16, 8, 1, List.of(
                new TextureAtlasPlacement("texture-a", 0, 1, 1, 3, 3, false),
                new TextureAtlasPlacement("texture-b", 0, 6, 1, 2, 2, false)
            )),
            new TextureAtlasLayoutPlan(16, 8, 1, List.of(
                new TextureAtlasPlacement("texture-a", 0, 0, 1, 4, 3, false),
                new TextureAtlasPlacement("texture-b", 0, 6, 1, 2, 2, false)
            )),
            new TextureAtlasLayoutPlan(16, 8, 1, List.of(
                new TextureAtlasPlacement("texture-a", 0, 1, 1, 4, 3, false),
                new TextureAtlasPlacement("texture-b", 0, 5, 1, 2, 2, false)
            ))
        );

        for (TextureAtlasLayoutPlan plan : invalid) {
            assertEquals(
                Optional.of(TextureAtlasLayoutFailureCode.PLAN_INVALID),
                service.apply(snapshot.target(), plan).failureCode()
            );
        }
        assertEquals(0, provider.applyCount.get());
    }

    @Test
    void rejectsTrailingSparsePagesAndAcceptsEveryRepresentedDeclaredPage() {
        final TextureAtlasLayoutConstraints twoPages =
            new TextureAtlasLayoutConstraints(8, 8, 0, 0, 3, false, false);
        final List<TextureAtlasLayoutItem> items = List.of(
            new TextureAtlasLayoutItem("texture-a", 4, 3),
            new TextureAtlasLayoutItem("texture-b", 2, 2),
            new TextureAtlasLayoutItem("texture-c", 1, 1)
        );
        final RecordingProvider provider = new RecordingProvider(new TextureAtlasAuthoringState(
            "document-a", "model-a", "atlas-a", 7, twoPages, items,
            new TextureAtlasLayoutPlan(8, 8, 1, List.of(
                new TextureAtlasPlacement("texture-a", 0, 0, 0, 4, 3, false),
                new TextureAtlasPlacement("texture-b", 0, 4, 0, 2, 2, false),
                new TextureAtlasPlacement("texture-c", 0, 6, 0, 1, 1, false)
            ))
        ));
        final TextureAtlasLayoutCoordinator coordinator = new TextureAtlasLayoutCoordinator();
        coordinator.connect(provider);
        final RuntimeTextureAtlasLayoutService service = service(coordinator, permissions(), new ArrayList<>());
        final TextureAtlasLayoutSnapshot snapshot = service.current().orElseThrow();

        final TextureAtlasLayoutPlan trailing = new TextureAtlasLayoutPlan(8, 8, 3, List.of(
            new TextureAtlasPlacement("texture-a", 0, 0, 0, 4, 3, false),
            new TextureAtlasPlacement("texture-b", 0, 4, 0, 2, 2, false),
            new TextureAtlasPlacement("texture-c", 0, 6, 0, 1, 1, false)
        ));
        final TextureAtlasLayoutPlan sparse = new TextureAtlasLayoutPlan(8, 8, 3, List.of(
            new TextureAtlasPlacement("texture-a", 0, 0, 0, 4, 3, false),
            new TextureAtlasPlacement("texture-b", 2, 0, 0, 2, 2, false),
            new TextureAtlasPlacement("texture-c", 2, 2, 0, 1, 1, false)
        ));
        final TextureAtlasLayoutPlan complete = new TextureAtlasLayoutPlan(8, 8, 3, List.of(
            new TextureAtlasPlacement("texture-a", 0, 0, 0, 4, 3, false),
            new TextureAtlasPlacement("texture-b", 1, 0, 0, 2, 2, false),
            new TextureAtlasPlacement("texture-c", 2, 0, 0, 1, 1, false)
        ));

        assertEquals(Optional.of(TextureAtlasLayoutFailureCode.PLAN_INVALID), service.apply(snapshot.target(), trailing).failureCode());
        assertEquals(Optional.of(TextureAtlasLayoutFailureCode.PLAN_INVALID), service.apply(snapshot.target(), sparse).failureCode());
        assertEquals(Optional.of(TextureAtlasLayoutApplyStatus.APPLIED), service.apply(snapshot.target(), complete).status());
        assertEquals(1, provider.applyCount.get());
    }

    @Test
    void replacementAndCrossServiceTargetsFailClosedAsStale() {
        final RecordingProvider first = new RecordingProvider(state(7));
        final RecordingProvider second = new RecordingProvider(state(8));
        final TextureAtlasLayoutCoordinator coordinator = new TextureAtlasLayoutCoordinator();
        coordinator.connect(first);
        final RuntimeTextureAtlasLayoutService owner = service(coordinator, permissions(), new ArrayList<>());
        final RuntimeTextureAtlasLayoutService other = service(coordinator, permissions(), new ArrayList<>());
        final TextureAtlasLayoutSnapshot old = owner.current().orElseThrow();

        assertEquals(
            Optional.of(TextureAtlasLayoutFailureCode.TARGET_STALE),
            other.apply(old.target(), plan(1, 7)).failureCode()
        );
        coordinator.connect(second);
        assertEquals(
            Optional.of(TextureAtlasLayoutFailureCode.TARGET_STALE),
            owner.apply(old.target(), plan(1, 7)).failureCode()
        );
        assertEquals(0, first.applyCount.get());
        assertEquals(0, second.applyCount.get());
        assertNotEquals(old.target(), owner.current().orElseThrow().target());
    }

    @Test
    void planningStateChangeWithUnchangedRevisionFailsClosed() {
        final RecordingProvider provider = new RecordingProvider(state(7));
        final TextureAtlasLayoutCoordinator coordinator = new TextureAtlasLayoutCoordinator();
        coordinator.connect(provider);
        final RuntimeTextureAtlasLayoutService service = service(coordinator, permissions(), new ArrayList<>());
        final TextureAtlasLayoutSnapshot snapshot = service.current().orElseThrow();
        provider.state = new TextureAtlasAuthoringState(
            "document-a", "model-a", "atlas-a", 7,
            new TextureAtlasLayoutConstraints(16, 8, 2, 1, 1, false, false), ITEMS, CURRENT
        );

        assertEquals(
            Optional.of(TextureAtlasLayoutFailureCode.TARGET_STALE),
            service.apply(snapshot.target(), plan(1, 7)).failureCode()
        );
        assertEquals(0, provider.applyCount.get());
    }

    @Test
    void providerFailuresAreSanitizedAndErrorsDeactivateBeforeRethrow() {
        final ThrowingProvider runtimeFailure = new ThrowingProvider(new IllegalStateException("host-secret"));
        final TextureAtlasLayoutCoordinator first = new TextureAtlasLayoutCoordinator();
        first.connect(runtimeFailure);
        final RuntimeTextureAtlasLayoutService readService = service(first, permissions(), new ArrayList<>());
        assertTrue(readService.current().isEmpty());

        final ThrowingProvider errorProvider = new ThrowingProvider(new AssertionError("host-error"));
        final TextureAtlasLayoutCoordinator second = new TextureAtlasLayoutCoordinator();
        second.connect(errorProvider);
        final RuntimeTextureAtlasLayoutService errorService = service(second, permissions(), new ArrayList<>());
        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, errorService::current);
        assertTrue(errorService.current().isEmpty());

        final ApplyThrowingProvider applyRuntime = new ApplyThrowingProvider(
            state(7), new IllegalStateException("apply-secret")
        );
        final TextureAtlasLayoutCoordinator third = new TextureAtlasLayoutCoordinator();
        third.connect(applyRuntime);
        final RuntimeTextureAtlasLayoutService applyService = service(third, permissions(), new ArrayList<>());
        final TextureAtlasLayoutSnapshot target = applyService.current().orElseThrow();
        assertEquals(
            Optional.of(TextureAtlasLayoutFailureCode.PROVIDER_FAILED),
            applyService.apply(target.target(), plan(1, 7)).failureCode()
        );

        final ApplyThrowingProvider applyError = new ApplyThrowingProvider(
            state(7), new AssertionError("apply-error")
        );
        final TextureAtlasLayoutCoordinator fourth = new TextureAtlasLayoutCoordinator();
        fourth.connect(applyError);
        final RuntimeTextureAtlasLayoutService applyErrorService = service(fourth, permissions(), new ArrayList<>());
        final TextureAtlasLayoutSnapshot errorTarget = applyErrorService.current().orElseThrow();
        org.junit.jupiter.api.Assertions.assertThrows(
            AssertionError.class,
            () -> applyErrorService.apply(errorTarget.target(), plan(1, 7))
        );
        assertTrue(applyErrorService.current().isEmpty());
    }

    @Test
    void coordinatorSerializesProviderMutationAndFailsClosedAfterDeactivateOrClose() throws Exception {
        final BlockingProvider provider = new BlockingProvider(state(7));
        final TextureAtlasLayoutCoordinator coordinator = new TextureAtlasLayoutCoordinator();
        coordinator.connect(provider);
        final RuntimeTextureAtlasLayoutService service = service(coordinator, permissions(), new ArrayList<>());
        final TextureAtlasLayoutSnapshot first = service.current().orElseThrow();
        final TextureAtlasLayoutSnapshot second = service.current().orElseThrow();

        final Thread one = new Thread(() -> service.apply(first.target(), plan(1, 7)));
        final Thread two = new Thread(() -> service.apply(second.target(), plan(1, 7)));
        one.start();
        assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
        two.start();
        Thread.sleep(100);
        assertEquals(1, provider.active.get());
        assertEquals(1, provider.maxActive.get());
        provider.release.countDown();
        one.join(5000);
        two.join(5000);
        assertEquals(1, provider.maxActive.get());

        final TextureAtlasLayoutSnapshot stale = service.current().orElseThrow();
        coordinator.deactivate();
        assertFalse(service.current().isPresent());
        assertEquals(
            Optional.of(TextureAtlasLayoutFailureCode.CAPABILITY_UNAVAILABLE),
            service.apply(stale.target(), plan(1, 7)).failureCode()
        );
        coordinator.close();
        assertEquals(
            Optional.of(TextureAtlasLayoutFailureCode.RUNTIME_CLOSED),
            service.apply(stale.target(), plan(1, 7)).failureCode()
        );
    }

    private static RuntimeTextureAtlasLayoutService service(
        final TextureAtlasLayoutCoordinator coordinator,
        final List<PluginPermission> permissions,
        final List<CubismFacadeAuditEvent> audit
    ) {
        return new RuntimeTextureAtlasLayoutService(
            coordinator,
            new CubismPermissionGate("plugin.texture-atlas", permissions, audit::add, Clock.systemUTC())
        );
    }

    private static List<PluginPermission> permissions() {
        return List.of(readPermission(), permission(RuntimeTextureAtlasLayoutService.WRITE_PERMISSION));
    }

    private static PluginPermission readPermission() {
        return permission(RuntimeTextureAtlasLayoutService.READ_PERMISSION);
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override public String id() { return id; }
            @Override public String scope() { return "test"; }
            @Override public String reason() { return "test"; }
        };
    }

    private static TextureAtlasAuthoringState state(final long revision) {
        return state(revision, CURRENT);
    }

    private static TextureAtlasAuthoringState state(final long revision, final TextureAtlasLayoutPlan current) {
        return new TextureAtlasAuthoringState(
            "document-a",
            "model-a",
            "atlas-a",
            revision,
            CONSTRAINTS,
            ITEMS,
            current
        );
    }

    private static TextureAtlasLayoutPlan plan(final int firstX, final int secondX) {
        return new TextureAtlasLayoutPlan(
            16,
            8,
            1,
            List.of(
                new TextureAtlasPlacement("texture-a", 0, firstX, 1, 4, 3, false),
                new TextureAtlasPlacement("texture-b", 0, secondX, 1, 2, 2, false)
            )
        );
    }

    private static class RecordingProvider implements TextureAtlasLayoutProvider {
        volatile TextureAtlasAuthoringState state;
        final AtomicInteger applyCount = new AtomicInteger();

        RecordingProvider(final TextureAtlasAuthoringState state) { this.state = state; }
        @Override public Optional<TextureAtlasAuthoringState> current() { return Optional.ofNullable(state); }
        @Override public ApplyOutcome apply(
            final TextureAtlasAuthoringState expected,
            final TextureAtlasLayoutPlan plan
        ) {
            applyCount.incrementAndGet();
            return plan.equals(state.currentPlan()) ? ApplyOutcome.NO_CHANGE : ApplyOutcome.APPLIED;
        }
    }

    private static final class ThrowingProvider implements TextureAtlasLayoutProvider {
        private final Throwable failure;
        ThrowingProvider(final Throwable failure) { this.failure = failure; }
        @Override public Optional<TextureAtlasAuthoringState> current() {
            if (failure instanceof Error error) throw error;
            throw (RuntimeException) failure;
        }
        @Override public ApplyOutcome apply(
            final TextureAtlasAuthoringState expected,
            final TextureAtlasLayoutPlan plan
        ) {
            return ApplyOutcome.REJECTED;
        }
    }

    private static final class ApplyThrowingProvider extends RecordingProvider {
        private final Throwable failure;
        ApplyThrowingProvider(final TextureAtlasAuthoringState state, final Throwable failure) {
            super(state);
            this.failure = failure;
        }
        @Override public ApplyOutcome apply(
            final TextureAtlasAuthoringState expected,
            final TextureAtlasLayoutPlan plan
        ) {
            if (failure instanceof Error error) throw error;
            throw (RuntimeException) failure;
        }
    }

    private static final class BlockingProvider extends RecordingProvider {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();

        BlockingProvider(final TextureAtlasAuthoringState state) { super(state); }
        @Override public ApplyOutcome apply(
            final TextureAtlasAuthoringState expected,
            final TextureAtlasLayoutPlan plan
        ) {
            final int count = active.incrementAndGet();
            maxActive.accumulateAndGet(count, Math::max);
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                return ApplyOutcome.APPLIED;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return ApplyOutcome.REJECTED;
            } finally {
                active.decrementAndGet();
            }
        }
    }
}
