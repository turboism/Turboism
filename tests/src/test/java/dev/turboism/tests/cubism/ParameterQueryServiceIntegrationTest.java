package dev.turboism.tests.cubism;

import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.service.query.ParameterSummary;
import dev.turboism.sdk.permission.CubismPermissionException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static dev.turboism.tests.cubism.CubismQueryIntegrationSupport.MODEL_READ_PERMISSION;
import static dev.turboism.tests.cubism.CubismQueryIntegrationSupport.PARAMETER_READ_PERMISSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterQueryServiceIntegrationTest {

    @Test
    void findByIdReturnsParameterWhenExactPermissionIsGranted() throws CubismServiceException {
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
            CubismQueryIntegrationSupport.sampleHost(),
            MODEL_READ_PERMISSION,
            PARAMETER_READ_PERMISSION
        );

        final Optional<ParameterSummary> parameter = environment.context().parameterQuery().findById(new ParameterId("param-angle-x"));

        assertTrue(parameter.isPresent());
        assertEquals("Angle X", parameter.orElseThrow().name());
        assertEquals(-30.0, parameter.orElseThrow().minValue());
        assertEquals(30.0, parameter.orElseThrow().maxValue());
    }

    @Test
    void listAllReturnsEveryParameterWhenExactPermissionIsGranted() throws CubismServiceException {
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
            CubismQueryIntegrationSupport.sampleHost(),
            MODEL_READ_PERMISSION,
            PARAMETER_READ_PERMISSION
        );

        final List<ParameterSummary> parameters = environment.context().parameterQuery().listAll();

        assertEquals(List.of(new ParameterId("param-angle-x"), new ParameterId("param-opacity")), parameters.stream().map(ParameterSummary::id).toList());
    }

    @Test
    void existsReportsPresentAndMissingParametersWhenExactPermissionIsGranted() throws CubismServiceException {
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
            CubismQueryIntegrationSupport.sampleHost(),
            MODEL_READ_PERMISSION,
            PARAMETER_READ_PERMISSION
        );

        final boolean existingParameter = environment.context().parameterQuery().exists(new ParameterId("param-opacity"));
        final boolean missingParameter = environment.context().parameterQuery().exists(new ParameterId("param-missing"));

        assertTrue(existingParameter);
        assertEquals(false, missingParameter);
    }

    @Test
    void deniedParameterReadThrowsPermissionExceptionAndRecordsAuditEvent() {
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
            CubismQueryIntegrationSupport.sampleHost(),
            MODEL_READ_PERMISSION
        );

        final CubismPermissionException error = assertThrows(CubismPermissionException.class, () -> environment.context().parameterQuery().listAll());

        assertTrue(error.getMessage().contains(PARAMETER_READ_PERMISSION));
        assertEquals(1, environment.auditEvents().size());
        assertEquals(PARAMETER_READ_PERMISSION, environment.auditEvents().get(0).permissionId());
        assertEquals("parameterQuery.listAll", environment.auditEvents().get(0).methodName());
    }

    @Test
    void corruptSnapshotReturnsStructuredServiceException() {
        final CubismQueryIntegrationSupport.VersionedSource source = CubismQueryIntegrationSupport.versionedSource(List.of(
            new dev.turboism.adapter.cubism.HostSnapshotSource.HostParameter("param-invalid", "Invalid", 2.0, 0.0, 10.0, -10.0, true, true)
        ));
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
            source,
            MODEL_READ_PERMISSION,
            PARAMETER_READ_PERMISSION
        );

        final CubismServiceException error = assertThrows(CubismServiceException.class, () -> environment.context().parameterQuery().listAll());

        assertEquals("cubism.query.snapshot.invalid", error.code());
        assertTrue(error.message().contains("parameter"));
    }
}
