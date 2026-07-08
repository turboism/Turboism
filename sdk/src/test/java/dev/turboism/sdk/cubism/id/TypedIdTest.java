package dev.turboism.sdk.cubism.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TypedIdTest {

    @Test
    void typedIdsExposeOnlyTypedStringValue_whenInspectingSourceContract() throws IOException {
        for (String typeName : typedIdTypeNames()) {
            String source = sourceFor(typeName);

            assertTrue(source.contains("public record " + typeName + "(String value)"));
            assertFalse(source.contains("public record " + typeName + "(String value,"));
        }
    }

    @Test
    void typedIdsCompareByConcreteTypeAndValue_whenCompared() {
        assertEquals(new ProjectId("project"), new ProjectId("project"));
        assertNotEquals(new ProjectId("project"), new ProjectId("other-project"));
        assertNotEquals(new ProjectId("same-value"), new DocumentId("same-value"));
        assertNotEquals(new ParameterId("same-value"), new ModelObjectId("same-value"));
    }

    @Test
    void typedIdsIncludeUnderlyingValue_whenRenderedAsString() {
        assertTrue(new ProjectId("project-1").toString().contains("project-1"));
        assertTrue(new DocumentId("document-1").toString().contains("document-1"));
        assertTrue(new ModelObjectId("object-1").toString().contains("object-1"));
        assertTrue(new ParameterId("parameter-1").toString().contains("parameter-1"));
        assertTrue(new ArtMeshId("mesh-1").toString().contains("mesh-1"));
        assertTrue(new DeformerId("deformer-1").toString().contains("deformer-1"));
    }

    @Test
    void typedIdsRequireStringConstructor_whenConstructed() {
        ProjectId projectId = new ProjectId("project");
        DocumentId documentId = new DocumentId("document");
        ModelObjectId objectId = new ModelObjectId("object");
        ParameterId parameterId = new ParameterId("parameter");
        ArtMeshId artMeshId = new ArtMeshId("mesh");
        DeformerId deformerId = new DeformerId("deformer");

        assertEquals("project", projectId.value());
        assertEquals("document", documentId.value());
        assertEquals("object", objectId.value());
        assertEquals("parameter", parameterId.value());
        assertEquals("mesh", artMeshId.value());
        assertEquals("deformer", deformerId.value());
    }

    @Test
    void constructorRejectsNullValue_whenCreatingTypedId() {
        assertThrows(NullPointerException.class, () -> new ProjectId(null));
        assertThrows(NullPointerException.class, () -> new DocumentId(null));
        assertThrows(NullPointerException.class, () -> new ModelObjectId(null));
        assertThrows(NullPointerException.class, () -> new ParameterId(null));
        assertThrows(NullPointerException.class, () -> new ArtMeshId(null));
        assertThrows(NullPointerException.class, () -> new DeformerId(null));
    }

    private static List<String> typedIdTypeNames() {
        return List.of("ProjectId", "DocumentId", "ModelObjectId", "ParameterId", "ArtMeshId", "DeformerId");
    }

    private static String sourceFor(String typeName) throws IOException {
        return Files.readString(Path.of("src/main/java/dev/turboism/sdk/cubism/id", typeName + ".java"));
    }
}
