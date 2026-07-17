package dev.turboism.adapter.cubism.service.query;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.service.query.ParameterSummary;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterQueryServiceImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-08T01:00:00Z"), ZoneOffset.UTC);
    private static final String PARAMETER_READ_PERMISSION = "turboism.cubism.parameter.read";

    @Test
    void findByIdListAllAndExistsReturnParameterSummariesWhenPermissionGranted() throws CubismServiceException {
        final VersionedSource source = VersionedSource.withModel();
        final ParameterQueryServiceImpl service = serviceWith(source, new ArrayList<>(), List.of(
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
            permission(PARAMETER_READ_PERMISSION)
        ));

        final Optional<ParameterSummary> found = service.findById(new ParameterId("param-angle-x"));
        final List<ParameterSummary> allParameters = service.listAll();
        final boolean existingParameter = service.exists(new ParameterId("param-opacity"));
        final boolean missingParameter = service.exists(new ParameterId("param-missing"));

        assertTrue(found.isPresent());
        assertEquals("Angle X", found.orElseThrow().name());
        assertEquals(-30.0, found.orElseThrow().minValue());
        assertEquals(2, allParameters.size());
        assertTrue(existingParameter);
        assertFalse(missingParameter);
    }

    @Test
    void listAllUsesCachedIndexUntilSnapshotVersionChanges() throws CubismServiceException {
        final VersionedSource source = VersionedSource.withModel();
        final ParameterQueryServiceImpl service = serviceWith(source, new ArrayList<>(), List.of(
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
            permission(PARAMETER_READ_PERMISSION)
        ));

        final List<ParameterSummary> first = service.listAll();
        source.replaceParametersWithoutInvalidation(List.of(new HostSnapshotSource.HostParameter(
            "param-changed",
            "Changed",
            0.0,
            0.0,
            0.0,
            1.0,
            true,
            true
        )));
        final List<ParameterSummary> cached = service.listAll();
        source.advanceInvalidationToken();
        final List<ParameterSummary> refreshed = service.listAll();

        assertEquals(List.of(new ParameterId("param-angle-x"), new ParameterId("param-opacity")), first.stream().map(ParameterSummary::id).toList());
        assertEquals(first, cached);
        assertEquals(List.of(new ParameterId("param-changed")), refreshed.stream().map(ParameterSummary::id).toList());
    }

    @Test
    void deniedParameterReadThrowsAndRecordsAuditEvent() {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final ParameterQueryServiceImpl service = serviceWith(VersionedSource.withModel(), auditEvents, List.of(
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION)
        ));

        final CubismPermissionException error = assertThrows(
            CubismPermissionException.class,
            service::listAll
        );

        assertTrue(error.getMessage().contains(PARAMETER_READ_PERMISSION));
        assertEquals(1, auditEvents.size());
        assertEquals(PARAMETER_READ_PERMISSION, auditEvents.get(0).permissionId());
        assertEquals("parameterQuery.listAll", auditEvents.get(0).operationId());
        assertEquals("cubism.parameter.read", auditEvents.get(0).capabilityId());
        assertEquals("parameterQuery.listAll", auditEvents.get(0).methodName());
        assertEquals(FIXED_CLOCK.instant(), auditEvents.get(0).timestamp());
    }

    @Test
    void corruptSnapshotIsReportedAsCubismServiceException() {
        final ParameterQueryServiceImpl service = serviceWith(VersionedSource.withCorruptParameter(), new ArrayList<>(), List.of(
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
            permission(PARAMETER_READ_PERMISSION)
        ));

        final CubismServiceException error = assertThrows(CubismServiceException.class, service::listAll);

        assertEquals("cubism.query.snapshot.invalid", error.code());
        assertTrue(error.getMessage().contains("parameter"));
    }

    private static ParameterQueryServiceImpl serviceWith(
        final HostSnapshotSource source,
        final List<CubismFacadeAuditEvent> auditEvents,
        final List<PluginPermission> permissions
    ) {
        final CubismPermissionGate permissionGate = new CubismPermissionGate(
            "plugin.demo",
            permissions,
            auditEvents::add,
            FIXED_CLOCK
        );
        return new ParameterQueryServiceImpl(new CubismFacadeImpl(source, permissionGate), permissionGate);
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

    private static final class VersionedSource implements HostSnapshotSource {

        private static final HostArtMesh MESH = new HostArtMesh("mesh-face", "Face Mesh", Optional.of("texture-1"), true, true);
        private static final HostDeformer DEFORMER = new HostDeformer("deformer-root", "Root", DeformerType.ROOT, Optional.empty(), List.of("mesh-face"));

        private List<HostParameter> parameters;
        private long invalidationToken;

        private VersionedSource(final List<HostParameter> parameters) {
            this.parameters = List.copyOf(parameters);
        }

        static VersionedSource withModel() {
            return new VersionedSource(List.of(
                new HostParameter("param-angle-x", "Angle X", 3.0, 0.0, -30.0, 30.0, true, true),
                new HostParameter("param-opacity", "Opacity", 1.0, 1.0, 0.0, 1.0, true, false)
            ));
        }

        static VersionedSource withCorruptParameter() {
            return new VersionedSource(List.of(new HostParameter("param-invalid", "Invalid", 2.0, 0.0, 10.0, -10.0, true, true)));
        }

        void replaceParametersWithoutInvalidation(final List<HostParameter> nextParameters) {
            parameters = List.copyOf(nextParameters);
        }

        void advanceInvalidationToken() {
            invalidationToken++;
        }

        @Override
        public Optional<HostProject> activeProject() {
            return Optional.of(new HostProject(
                "project-1",
                "Project",
                Optional.of(Path.of("projects/demo")),
                List.of(document())
            ));
        }

        @Override
        public Optional<HostDocument> activeDocument() {
            return Optional.of(document());
        }

        @Override
        public Optional<HostModel> activeModel() {
            return Optional.of(model());
        }

        @Override
        public HostSelection selection() {
            return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        @Override
        public boolean isHostPresent() {
            return true;
        }

        @Override
        public long invalidationToken() {
            return invalidationToken;
        }

        private HostDocument document() {
            return new HostDocument("document-1", "Document", "models/demo/model.cdi3.json", Optional.empty(), Optional.of(model()));
        }

        private HostModel model() {
            return new HostModel("model-1", "Model", parameters, List.of(MESH), List.of(DEFORMER));
        }
    }
}
