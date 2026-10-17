package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorPartStructureSelectorContract;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorPartStructureAccessTest {

    @Test
    void addsCopiesAndDeletesPartsThroughNativeUndo() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        final var parts = access.active().parts();

        final Part created = parts.add("NewPart");
        assertEquals(new PartId("NewPart"), created.id());
        assertEquals(2, fixture.root.children.size());
        assertEquals("NewPart", fixture.root.children.get(1).id.value);
        assertEquals(1, fixture.editMode.edits.size());
        assertTrue(fixture.document.dirty);
        assertEquals(1, fixture.source.updateCount);
        assertTrue(fixture.editMode.edits.get(0).captured);
        assertEquals(500, fixture.root.children.get(1).defaultOrder);

        final Part copied = parts.copy(new PartId("PartChild"));
        assertEquals(3, fixture.root.children.size());
        assertEquals(2, fixture.editMode.edits.size());
        assertEquals("PartChild_1", copied.id().value());

        parts.remove(new PartId("NewPart"));
        assertEquals(2, fixture.root.children.size());
        assertEquals(3, fixture.editMode.edits.size());
        assertEquals(1, fixture.handler.lastRemoved.size());
        assertEquals("NewPart", fixture.handler.lastRemoved.get(0).id.value);

        parts.add("NewPart");
        assertThrows(IllegalArgumentException.class, () -> parts.add("NewPart"));
    }

    @Test
    void missingCapabilityFailsClosedBeforeAnyWrite() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(resolver(false), "session-a");
        assertThrows(
                UnsupportedOperationException.class,
                () -> access.active().parts().add("NewPart"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> access.active().parts().copy(new PartId("PartChild")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> access.active().parts().remove(new PartId("PartChild")));
        assertEquals(0, fixture.editMode.edits.size());
        assertTrue(!fixture.document.dirty);
    }

    @Test
    void absentPartFailsClosed() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        final var parts = access.active().parts();
        assertThrows(java.util.NoSuchElementException.class, () -> parts.copy(new PartId("Missing")));
        assertThrows(java.util.NoSuchElementException.class, () -> parts.remove(new PartId("Missing")));
        assertThrows(java.util.NoSuchElementException.class, () -> parts.add("NewPart", new PartId("Missing")));
        assertEquals(0, fixture.editMode.edits.size());
    }

    private static VerifiedMemberResolver resolver(final boolean includeCapability) {
        return TestVerifiedResolvers.create(
                "5.3.02",
                "adapter.editor-model.readwrite",
                includeCapability
                        ? java.util.Set.of(
                                "cubism.editor-model.read",
                                EditorPartStructureSelectorContract.CAPABILITY_ID,
                                dev.turboism.mapping.verification.selector.EditorPartTreeSelectorContract.CAPABILITY_ID)
                        : java.util.Set.of("cubism.editor-model.read"),
                hierarchySelectors(),
                Host.class.getClassLoader());
    }

    private static final class Fixture {
        final ModelSource source;
        final PartSource childSource = new PartSource("PartChild");
        final Document document;
        final EditMode editMode;
        final CompletePack pack;
        final PartHandler partHandler;
        final ModelHandler handler;
        final PartSource root;

        Fixture() {
            source = new ModelSource();
            root = new PartSource("ROOT", source);
            source.root = root;
            source.rootPart = root;
            handler = source.handler;
            partHandler = root.handler;
            source.sources.add(childSource);
            childSource.parent = root;
            root.children.add(childSource);
            source.model.parts.add(new PartView("PartChild", childSource));
            document = new Document(source);
            editMode = document.editMode;
            pack = document.pack;
        }
    }
}
