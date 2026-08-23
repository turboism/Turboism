package dev.turboism.graalhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReflectiveGraalJsRuntimeTest {

    @Test
    void executeAfterCloseReturnsStructuredClosedFailure() {
        final ReflectiveGraalJsRuntime runtime = new ReflectiveGraalJsRuntime(new ObjectMapper());
        runtime.close();
        runtime.close();

        final ReflectiveGraalJsRuntime.ExecutionResult result = runtime.execute(
            "console.log('never');",
            Map.of(),
            (operation, payload) -> "{}",
            new ReflectiveGraalJsRuntime.ExecutionControl()
        );

        assertEquals(ReflectiveGraalJsRuntime.Status.FAILED, result.status());
        assertEquals("GRAAL_RUNTIME_CLOSED", result.code());
    }

    @Test
    void preservesHostErrorCodeFromGuestHostCall() {
        try (ReflectiveGraalJsRuntime runtime = new ReflectiveGraalJsRuntime(new ObjectMapper())) {
            if (!runtime.availability().available()) {
                return;
            }

            final ReflectiveGraalJsRuntime.ExecutionResult result = runtime.execute(
                "turboism.cubism.status();",
                Map.of(),
                (operation, payload) -> {
                    throw new GraalHostMain.HostCallException(
                        "SCRIPT_PERMISSION_DENIED", "Cubism read permission was not declared."
                    );
                },
                new ReflectiveGraalJsRuntime.ExecutionControl()
            );

            assertEquals(ReflectiveGraalJsRuntime.Status.FAILED, result.status());
            assertEquals("SCRIPT_PERMISSION_DENIED", result.code());
        }
    }

    @Test
    void rejectsNonNumericParameterValuesBeforeCallingTheHost() {
        try (ReflectiveGraalJsRuntime runtime = new ReflectiveGraalJsRuntime(new ObjectMapper())) {
            if (!runtime.availability().available()) {
                return;
            }
            final int[] hostCalls = {0};

            final ReflectiveGraalJsRuntime.ExecutionResult result = runtime.execute(
                "turboism.cubism.parameters.set('ParamAngleX', null);",
                Map.of(),
                (operation, payload) -> {
                    hostCalls[0]++;
                    return "{}";
                },
                new ReflectiveGraalJsRuntime.ExecutionControl()
            );

            assertEquals(ReflectiveGraalJsRuntime.Status.FAILED, result.status());
            assertEquals("SCRIPT_EVALUATION_FAILED", result.code());
            assertEquals(0, hostCalls[0]);
        }
    }

    @Test
    void reusesOneEngineWhileEachExecutionGetsAFreshContext() {
        try (ReflectiveGraalJsRuntime runtime = new ReflectiveGraalJsRuntime(new ObjectMapper())) {
            if (!runtime.availability().available()) {
                assertTrue(runtime.availability().detail().contains("unavailable"));
                return;
            }
            final Object engine = runtime.engineForTest();
            final int contextsBefore = runtime.contextsCreatedForTest();

            final ReflectiveGraalJsRuntime.ExecutionResult first = runtime.execute(
                "globalThis.executionMarker = 'first'; console.log(executionMarker);",
                Map.of(),
                (operation, payload) -> "{}",
                new ReflectiveGraalJsRuntime.ExecutionControl()
            );
            final ReflectiveGraalJsRuntime.ExecutionResult second = runtime.execute(
                "console.log(typeof executionMarker);",
                Map.of(),
                (operation, payload) -> "{}",
                new ReflectiveGraalJsRuntime.ExecutionControl()
            );

            assertEquals(ReflectiveGraalJsRuntime.Status.SUCCEEDED, first.status());
            assertEquals(ReflectiveGraalJsRuntime.Status.SUCCEEDED, second.status());
            assertEquals("first\n", first.output());
            assertEquals("undefined\n", second.output());
            assertSame(engine, runtime.engineForTest());
            assertEquals(contextsBefore + 2, runtime.contextsCreatedForTest());
        }
    }
}
