package dev.turboism.core.runtime.sidecar;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidecarEnvelopeValidatorTest {

    private final SidecarEnvelopeValidator validator = new SidecarEnvelopeValidator();

    @Test
    void validEnvelopeWithPrimitivesIsAccepted() {
        SidecarEnvelope envelope = new SidecarEnvelope(
            "dev.turboism.plugin.demo",
            "task-001",
            SidecarWorkAction.QUERY.name(),
            "{\"message\":\"hello\",\"count\":42,\"enabled\":true}",
            "sidecar",
            Instant.now().toString()
        );

        SidecarEnvelopeValidator.ValidationResult result = validator.validate(envelope);

        assertTrue(result.valid(), "Expected envelope to be valid: " + result);
        assertEquals(SidecarEnvelopeValidator.PROBLEM_CODE_VALID, result.problemCode());
    }

    @Test
    void envelopeWithLive2dHostObjectIsRejected() {
        SidecarEnvelope envelope = new SidecarEnvelope(
            "dev.turboism.plugin.demo",
            "task-002",
            SidecarWorkAction.EXECUTE.name(),
            "{\"target\":{\"@class\":\"com.live2d.cubism.model.CubismModel\"}}",
            "sidecar",
            Instant.now().toString()
        );

        SidecarEnvelopeValidator.ValidationResult result = validator.validate(envelope);

        assertFalse(result.valid());
        assertEquals(SidecarEnvelopeValidator.PROBLEM_CODE_HOST_OBJECT, result.problemCode());
    }

    @Test
    void envelopeWithPathTraversalIsRejected() {
        SidecarEnvelope envelope = new SidecarEnvelope(
            "dev.turboism.plugin.demo",
            "task-003",
            SidecarWorkAction.EXECUTE.name(),
            "{\"outputPath\":\"../../etc/passwd\"}",
            "sidecar",
            Instant.now().toString()
        );

        SidecarEnvelopeValidator.ValidationResult result = validator.validate(envelope);

        assertFalse(result.valid());
        assertEquals(SidecarEnvelopeValidator.PROBLEM_CODE_PATH_TRAVERSAL, result.problemCode());
    }

    @Test
    void envelopeWithReflectionHandleIsRejected() {
        SidecarEnvelope envelope = new SidecarEnvelope(
            "dev.turboism.plugin.demo",
            "task-004",
            SidecarWorkAction.EXECUTE.name(),
            "{\"method\":\"java.lang.reflect.Method\"}",
            "sidecar",
            Instant.now().toString()
        );

        SidecarEnvelopeValidator.ValidationResult result = validator.validate(envelope);

        assertFalse(result.valid());
        assertEquals(SidecarEnvelopeValidator.PROBLEM_CODE_REFLECTION_HANDLE, result.problemCode());
    }
}
