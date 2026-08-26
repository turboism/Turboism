package dev.turboism.test.fake;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FakeSidecarProcessTest {

    @Test
    void enqueueResponseProvidesSuccessResponse() {
        // Given
        FakeSidecarProcess process = new FakeSidecarProcess();

        // When
        process.enqueueResponse("{\"ok\":true}");
        FakeSidecarProcess.Response response = process.nextResponse();

        // Then
        assertNotNull(response);
        assertEquals(FakeSidecarProcess.Behavior.SUCCESS, response.behavior());
        assertEquals("{\"ok\":true}", response.payload());
        assertNull(response.errorCode());
        assertNull(response.errorMessage());
    }

    @Test
    void simulateCrashProvidesErrorResponse() {
        // Given
        FakeSidecarProcess process = new FakeSidecarProcess();

        // When
        process.simulateCrash("SIDECAR_CRASH", "sidecar process crashed");
        FakeSidecarProcess.Response response = process.nextResponse();

        // Then
        assertNotNull(response);
        assertEquals(FakeSidecarProcess.Behavior.ERROR, response.behavior());
        assertEquals("SIDECAR_CRASH", response.errorCode());
        assertEquals("sidecar process crashed", response.errorMessage());
    }

    @Test
    void simulateTimeoutProvidesTimeoutResponse() {
        // Given
        FakeSidecarProcess process = new FakeSidecarProcess();

        // When
        process.simulateTimeout();
        FakeSidecarProcess.Response response = process.nextResponse();

        // Then
        assertNotNull(response);
        assertEquals(FakeSidecarProcess.Behavior.TIMEOUT, response.behavior());
    }

    @Test
    void responsesAreConsumedInOrder() {
        // Given
        FakeSidecarProcess process = new FakeSidecarProcess();
        process.enqueueResponse("first");
        process.simulateTimeout();

        // Then
        assertEquals(FakeSidecarProcess.Behavior.SUCCESS, process.nextResponse().behavior());
        assertEquals(FakeSidecarProcess.Behavior.TIMEOUT, process.nextResponse().behavior());
        assertNull(process.nextResponse());
    }
}
