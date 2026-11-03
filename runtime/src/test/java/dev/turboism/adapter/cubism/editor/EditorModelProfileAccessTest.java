package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.*;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.desc;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.internal;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorModelProfileSelectorContract;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorModelProfileAccessTest {

    @Test
    void writesModelNameAndReadsProfileAndCanvas() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorModelProfileAccess access = new EditorModelProfileAccess(resolver(true), (identity, model) -> {});

        access.setName("session-a", fixture.source, fixture.model, "New Model Name");
        assertEquals("New Model Name", fixture.source.name);
        assertEquals(1, fixture.editMode.edits.size());
        assertTrue(fixture.document.dirty);
        assertEquals(1, fixture.source.updateCount);
        assertTrue(!fixture.editMode.aborted);

        final var profile = access.profile("session-a", fixture.source, fixture.model);
        assertEquals(10.0F, profile.pixelsPerUnit());
        assertEquals(4.0F, profile.originXPixels());
        assertEquals(5.0F, profile.originYPixels());

        final var canvas = access.canvas("session-a", fixture.source, fixture.model);
        assertEquals(1024, canvas.widthPixels());
        assertEquals(768, canvas.heightPixels());
        assertEquals(4.0F, canvas.originXPixels());
        assertEquals(5.0F, canvas.originYPixels());
        assertEquals(10.0F, canvas.pixelsPerUnit());
    }

    @Test
    void missingCapabilityFailsClosed() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorModelProfileAccess access = new EditorModelProfileAccess(resolver(false), (identity, model) -> {});
        assertThrows(
                UnsupportedOperationException.class,
                () -> access.setName("session-a", fixture.source, fixture.model, "X"));
        assertThrows(
                UnsupportedOperationException.class, () -> access.profile("session-a", fixture.source, fixture.model));
        assertThrows(
                UnsupportedOperationException.class, () -> access.canvas("session-a", fixture.source, fixture.model));
        assertEquals(0, fixture.editMode.edits.size());
    }

    @Test
    void blankNameRejectedAndNoChangeSkipsUndo() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorModelProfileAccess access = new EditorModelProfileAccess(resolver(true), (identity, model) -> {});
        assertThrows(
                IllegalArgumentException.class, () -> access.setName("session-a", fixture.source, fixture.model, "  "));
        access.setName("session-a", fixture.source, fixture.model, "original");
        assertEquals(0, fixture.editMode.edits.size());
        assertTrue(!fixture.document.dirty);
    }

    private static VerifiedMemberResolver resolver(final boolean includeCapability) {
        final List<StaticSelector> selectors = new ArrayList<>(EditorSelectorCatalog.standardWriteSelectors());
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.model-source.class", internal(ModelSource.class)));
        selectors.add(
                method("cubism.editor-model.model-source.name", ModelSource.class, "name", "()Ljava/lang/String;"));
        selectors.add(method(
                "cubism.editor-model.model-source.set-name", ModelSource.class, "setName", "(Ljava/lang/String;)V"));
        selectors.add(method(
                "cubism.editor-model.model-source.model-info", ModelSource.class, "modelInfo", desc(ModelInfo.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.canvas", ModelSource.class, "canvas", desc(ImageCanvas.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.model-info.class", internal(ModelInfo.class)));
        selectors.add(method("cubism.editor-model.model-info.origin", ModelInfo.class, "origin", desc(Point.class)));
        selectors.add(
                method("cubism.editor-model.model-info.pixels-per-unit", ModelInfo.class, "pixelsPerUnit", "()F"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.point.class", internal(Point.class)));
        selectors.add(method("cubism.editor-model.point.x", Point.class, "x", "()I"));
        selectors.add(method("cubism.editor-model.point.y", Point.class, "y", "()I"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.image-canvas.class", internal(ImageCanvas.class)));
        selectors.add(method("cubism.editor-model.image-canvas.width", ImageCanvas.class, "width", "()I"));
        selectors.add(method("cubism.editor-model.image-canvas.height", ImageCanvas.class, "height", "()I"));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.simple-undo.create",
                internal(Undo.class),
                "(Ljava/lang/String;L" + internal(Object.class) + ";Ljava/lang/Object;)V",
                StaticSelector.ACCESS_PUBLIC));
        return TestVerifiedResolvers.create(
                "5.3.02",
                "adapter.editor-model.readwrite",
                includeCapability
                        ? java.util.Set.of(
                                "cubism.editor-model.read",
                                EditorModelProfileSelectorContract.NAME_WRITE_CAPABILITY_ID,
                                EditorModelProfileSelectorContract.PROFILE_READ_CAPABILITY_ID)
                        : java.util.Set.of("cubism.editor-model.read"),
                selectors,
                Host.class.getClassLoader());
    }

    private static final class Fixture {
        final ModelSource source = new ModelSource();
        final Object model = new Object();
        final Document document;
        final EditMode editMode;

        Fixture() {
            source.name = "original";
            document = new Document(source);
            editMode = document.editMode;
        }
    }
}
