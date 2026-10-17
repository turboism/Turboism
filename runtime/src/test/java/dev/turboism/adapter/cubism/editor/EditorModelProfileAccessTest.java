package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.*;
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
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        selectors.add(StaticSelector.staticMethod(
                "cubism.editor-model.app-controller.instance",
                internal(Host.class),
                "instance",
                desc(Host.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method(
                "cubism.editor-model.app-controller.current-document",
                Host.class,
                "currentDocument",
                desc(Document.class)));
        selectors.add(method(
                "cubism.editor-model.app-controller.complete-pack",
                Host.class,
                "completePack",
                desc(CompletePack.class)));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        selectors.add(method(
                "cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)));
        selectors.add(method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"));
        selectors.add(method(
                "cubism.editor-model.edit-mode.begin",
                EditMode.class,
                "begin",
                "(Ljava/lang/String;)" + type(GroupUndo.class)));
        selectors.add(method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"));
        selectors.add(method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"));
        selectors.add(method(
                "cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(" + type(Listener.class) + ")Z"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", "()V"));
        selectors.add(method(
                "cubism.editor-model.complete-pack.update-part-palette", CompletePack.class, "updateParts", "(Z)V"));
        selectors.add(
                method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaint", "(Z)V"));
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

    private static StaticSelector method(
            final String alias, final Class<?> owner, final String name, final String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String type(final Class<?> type) {
        return "L" + internal(type) + ";";
    }

    private static String desc(final Class<?> type) {
        return "()" + type(type);
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
