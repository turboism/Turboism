package dev.turboism.tests.cubism;

import static dev.turboism.tests.cubism.CubismQueryIntegrationSupport.MODEL_READ_PERMISSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.permission.CubismPermissionException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CubismReadParametersIntegrationTest {

    @Test
    void findByIdReturnsParameterWhenExactPermissionIsGranted() {
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
                CubismQueryIntegrationSupport.sampleHost(), MODEL_READ_PERMISSION);

        final Optional<ParameterSnapshot> parameter = environment.context().cubismRead().parameters().stream()
                .filter(value -> value.id().equals("param-angle-x"))
                .findFirst();

        assertTrue(parameter.isPresent());
        assertEquals("Angle X", parameter.orElseThrow().name());
        assertEquals(-30.0, parameter.orElseThrow().minValue());
        assertEquals(30.0, parameter.orElseThrow().maxValue());
    }

    @Test
    void parametersReturnsEveryParameterWhenExactPermissionIsGranted() {
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
                CubismQueryIntegrationSupport.sampleHost(), MODEL_READ_PERMISSION);

        final List<ParameterSnapshot> parameters =
                environment.context().cubismRead().parameters();

        assertEquals(
                List.of("param-angle-x", "param-opacity"),
                parameters.stream().map(ParameterSnapshot::id).toList());
    }

    @Test
    void parametersReportsPresentAndMissingParametersWhenExactPermissionIsGranted() {
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
                CubismQueryIntegrationSupport.sampleHost(), MODEL_READ_PERMISSION);

        final boolean existingParameter = environment.context().cubismRead().parameters().stream()
                .anyMatch(value -> value.id().equals("param-opacity"));
        final boolean missingParameter = environment.context().cubismRead().parameters().stream()
                .anyMatch(value -> value.id().equals("param-missing"));

        assertTrue(existingParameter);
        assertEquals(false, missingParameter);
    }

    @Test
    void deniedParameterReadThrowsPermissionExceptionAndRecordsAuditEvent() {
        final CubismQueryIntegrationSupport.QueryEnvironment environment =
                CubismQueryIntegrationSupport.environment(CubismQueryIntegrationSupport.sampleHost());

        final CubismPermissionException error = assertThrows(
                CubismPermissionException.class,
                () -> environment.context().cubismRead().parameters());

        assertTrue(error.getMessage().contains(MODEL_READ_PERMISSION));
        assertEquals(1, environment.auditEvents().size());
        assertEquals(MODEL_READ_PERMISSION, environment.auditEvents().get(0).permissionId());
        assertEquals("cubismRead.parameters", environment.auditEvents().get(0).methodName());
    }
}
