package dev.turboism.adapter.cubism;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CubismTransactionStubTest {

    @Test
    void publicWriteMethodsThrowUnsupportedOperationException() {
        final CubismTransactionStub stub = new CubismTransactionStub();

        final UnsupportedOperationException parameterError = assertThrows(
            UnsupportedOperationException.class,
            () -> stub.setParameterValue("object-1", "parameter-1", 0.5)
        );
        assertEquals("M6 read-only boundary: Cubism transaction writes are disabled", parameterError.getMessage());

        final UnsupportedOperationException artMeshError = assertThrows(
            UnsupportedOperationException.class,
            () -> stub.mutateArtMesh("object-2")
        );
        assertEquals("M6 read-only boundary: Cubism transaction writes are disabled", artMeshError.getMessage());

        final UnsupportedOperationException deformerError = assertThrows(
            UnsupportedOperationException.class,
            () -> stub.mutateDeformer("object-3")
        );
        assertEquals("M6 read-only boundary: Cubism transaction writes are disabled", deformerError.getMessage());

        final UnsupportedOperationException projectPathError = assertThrows(
            UnsupportedOperationException.class,
            () -> stub.setProjectPath("projects/demo")
        );
        assertEquals("M6 read-only boundary: Cubism transaction writes are disabled", projectPathError.getMessage());

        final UnsupportedOperationException commitError = assertThrows(
            UnsupportedOperationException.class,
            stub::commit
        );
        assertEquals("M6 read-only boundary: Cubism transaction writes are disabled", commitError.getMessage());
    }
}
