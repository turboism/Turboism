package dev.turboism.adapter.cubism.core;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.cubism.model.Parameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeCoreModelBackendTest {

    @BeforeEach
    void resetVersion() {
        TestCoreApiFixture.resetVersion();
    }

    @Test
    void admitsPublishesClearsAndClosesOneRuntimeOwnedBackend() {
        final RuntimeCoreModelBackend backend = RuntimeCoreModelBackend.admit(
            TestCoreApiFixture.resolver("5.3.02"),
            CoreVersionExpectation.exact(11, 12, 13)
        ).value().orElseThrow();
        final AtomicInteger hostCloseCalls = new AtomicInteger();
        final BorrowedModel model = new BorrowedModel(
            coreModel(new float[]{10.0F}),
            hostCloseCalls
        );

        backend.publishBorrowedModel(model.coreModel, "model-a");
        final CubismFacadeImpl facade = new CubismFacadeImpl(
            emptyHostSource(),
            new CubismPermissionGate(
                "plugin.demo",
                List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                ignored -> { },
                Clock.systemUTC()
            ),
            backend.modelAccess()
        );
        final Parameter parameter = facade.model().active()
            .parameters()
            .find(new ParameterId("ParamAngleX"));
        assertEquals(10.0F, parameter.getValue());

        backend.clearBorrowedModel();
        final IllegalStateException stale = assertThrows(
            IllegalStateException.class,
            parameter::getValue
        );
        assertTrue(stale.getMessage().contains("No verified active")
            || stale.getMessage().contains("stale"));

        backend.close();
        backend.close();
        assertEquals(0, hostCloseCalls.get());
        assertThrows(IllegalStateException.class, () -> backend.modelAccess().active());
        assertThrows(
            IllegalStateException.class,
            () -> backend.publishBorrowedModel(model.coreModel, "model-b")
        );
    }

    @Test
    void rejectedProviderDoesNotPublishPartialBackend() {
        final CoreProviderResult<RuntimeCoreModelBackend> result =
            RuntimeCoreModelBackend.admit(
                TestCoreApiFixture.resolver(
                    "5.3.02",
                    dev.turboism.mapping.verification.CorePublicApiSelectorContract.PARAMETERS_GET_REPEATS
                ),
                CoreVersionExpectation.exact(11, 12, 13)
            );

        assertTrue(result.value().isEmpty());
        assertEquals(
            CoreProviderFailure.Code.EVIDENCE_REJECTED,
            result.failure().orElseThrow().code()
        );
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String scope() {
                return "read";
            }

            @Override
            public String reason() {
                return "test";
            }
        };
    }

    private static HostSnapshotSource emptyHostSource() {
        return new HostSnapshotSource() {
            @Override
            public Optional<HostProject> activeProject() {
                return Optional.empty();
            }

            @Override
            public Optional<HostDocument> activeDocument() {
                return Optional.empty();
            }

            @Override
            public Optional<HostModel> activeModel() {
                return Optional.empty();
            }

            @Override
            public HostSelection selection() {
                return new HostSelection(
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
                );
            }

            @Override
            public boolean isHostPresent() {
                return true;
            }

            @Override
            public long invalidationToken() {
                return 0L;
            }
        };
    }

    private static TestCoreApiFixture.Model coreModel(final float[] values) {
        return new TestCoreApiFixture.Model(
            new TestCoreApiFixture.CanvasInfo(
                new float[]{1000.0F, 500.0F},
                new float[]{500.0F, 250.0F},
                100.0F
            ),
            new TestCoreApiFixture.Parameters(
                new String[]{"ParamAngleX"},
                new TestCoreApiFixture.ParameterType[]{
                    new TestCoreApiFixture.ParameterType(0)
                },
                new float[]{-30.0F},
                new float[]{30.0F},
                new float[]{0.0F},
                values,
                new int[]{0},
                new float[][]{new float[0]},
                new boolean[]{false}
            )
        );
    }

    private record BorrowedModel(
        TestCoreApiFixture.Model coreModel,
        AtomicInteger closeCalls
    ) {
        @SuppressWarnings("unused")
        void close() {
            closeCalls.incrementAndGet();
        }
    }
}
