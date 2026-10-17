package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorPartOpacitySelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartTreeSelectorContract;
import dev.turboism.sdk.cubism.model.PartId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorPartOpacityAccessTest {

    @Test
    void writes5302AuthoringOpacityWithNativeUndoDirtyAndRefresh() {
        final Fixture fixture = new Fixture();
        final PartSource childSource = new PartSource("PartChild");
        childSource.parent = fixture.partSource;
        fixture.source.sources.add(childSource);
        fixture.source.model.parts.add(new HostPart("PartChild", childSource));
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access =
                new EditorBackedCubismModelAccess(resolver(true, "5.3.02"), "session-a");
        final var part = access.active().parts().find(new PartId("PartClip"));
        final var child = access.active().parts().find(new PartId("PartChild"));

        assertEquals(1.0F, part.getOpacity());
        assertEquals(-1, part.parentIndex());
        assertEquals(0, part.index());
        assertTrue(part.parentId().isEmpty());
        assertEquals(List.of(new PartId("PartChild")), part.childIds());
        assertEquals(1, child.index());
        assertEquals(new PartId("PartClip"), child.parentId().orElseThrow());
        assertTrue(child.childIds().isEmpty());
        part.setOpacity(0.625F);

        assertEquals(0.625F, fixture.part.form.opacity);
        assertEquals(1, fixture.editMode.edits.size());
        assertTrue(fixture.document.dirty);
        assertEquals(1, fixture.source.updateCount);
        assertEquals(1, fixture.pack.partRefreshCount);
        assertEquals(1, fixture.pack.repaintCount);
        assertFalse(fixture.editMode.aborted);
    }

    @Test
    void exact5203ExposesEvaluationOpacityReadButRejectsAuthoringWrite() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access =
                new EditorBackedCubismModelAccess(resolver(true, "5.2.03"), "session-a");
        final var part = access.active().parts().find(new PartId("PartClip"));

        assertEquals(1.0F, part.getOpacity());
        assertThrows(UnsupportedOperationException.class, () -> part.setOpacity(0.625F));
        assertEquals(1.0F, fixture.part.partsOpacity);
        assertEquals(0, fixture.editMode.edits.size());
        assertFalse(fixture.document.dirty);
    }

    @Test
    void noChangeSkipsUndoAndDetachedSameIdReferenceFailsClosed() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        final var part = access.active().parts().find(new PartId("PartClip"));

        part.setOpacity(1.0F);
        assertEquals(0, fixture.editMode.edits.size());
        assertFalse(fixture.document.dirty);

        fixture.replacePartWithSameId();
        assertThrows(IllegalStateException.class, part::getOpacity);
        assertThrows(IllegalStateException.class, () -> part.setOpacity(0.5F));
        assertEquals(0, fixture.editMode.edits.size());
    }

    @Test
    void retainedPartsFindChecksCurrentDocumentBeforeReadingSavedSource() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        final var parts = access.active().parts();
        Host.document = new Fixture().document;

        assertThrows(IllegalStateException.class, () -> parts.find(new PartId("PartClip")));
    }

    @Test
    void exactResolverWithoutPartCapabilityFailsClosedBeforeSelectorUse() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(resolver(false), "session-a");

        assertThrows(UnsupportedOperationException.class, () -> access.active().parts());
    }

    private static VerifiedMemberResolver resolver(final boolean includePartCapability) {
        return resolver(includePartCapability, "5.3.02");
    }

    private static VerifiedMemberResolver resolver(final boolean includePartCapability, final String cubismVersion) {
        return TestVerifiedResolvers.create(
                cubismVersion,
                "adapter.editor-model.readwrite",
                includePartCapability
                        ? java.util.Set.of(
                                "cubism.editor-model.read",
                                opacityCapability(cubismVersion),
                                EditorPartTreeSelectorContract.CAPABILITY_ID)
                        : java.util.Set.of("cubism.editor-model.read"),
                hierarchySelectors(),
                Host.class.getClassLoader());
    }

    private static String opacityCapability(final String cubismVersion) {
        return "5.2.03".equals(cubismVersion)
                ? dev.turboism.mapping.verification.selector.EditorPartOpacityReadSelectorContract.CAPABILITY_ID
                : EditorPartOpacitySelectorContract.CAPABILITY_ID;
    }

    private static final class Fixture {
        final ModelSource source = new ModelSource();
        final PartSource partSource = new PartSource("PartClip");
        HostPart part = new HostPart("PartClip", partSource);
        final Document document;
        final EditMode editMode;
        final CompletePack pack;

        Fixture() {
            source.sources.add(partSource);
            source.model = new Model();
            source.model.parts.add(part);
            document = new Document(source);
            editMode = document.editMode;
            pack = document.pack;
        }

        void replacePartWithSameId() {
            final PartSource replacementSource = new PartSource("PartClip");
            final HostPart replacement = new HostPart("PartClip", replacementSource);
            source.sources.clear();
            source.sources.add(replacementSource);
            source.model.parts.clear();
            source.model.parts.add(replacement);
        }
    }
}
