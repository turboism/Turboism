package dev.turboism.sdk.cubism.id;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TypedIdTest {

    @Test
    void constructorRejectsNullValue_whenCreatingTypedId() {
        assertThrows(NullPointerException.class, () -> new ProjectId(null));
        assertThrows(NullPointerException.class, () -> new DocumentId(null));
        assertThrows(NullPointerException.class, () -> new ModelObjectId(null));
        assertThrows(NullPointerException.class, () -> new ParameterId(null));
        assertThrows(NullPointerException.class, () -> new ArtMeshId(null));
        assertThrows(NullPointerException.class, () -> new DeformerId(null));
    }
}
