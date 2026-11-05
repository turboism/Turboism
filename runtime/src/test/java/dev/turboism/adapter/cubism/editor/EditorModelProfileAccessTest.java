package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.*;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.desc;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.internal;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.editor.history.EditorHistoryPort;
import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.VerifiedEditorAuthoringTransactionHost;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorModelProfileSelectorContract;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EditorModelProfileAccessTest {

    private static final String IDENTITY = "session-a:model-a:1";

    @Test
    void writesModelNameAndReadsProfileAndCanvas() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final VerifiedMemberResolver resolver = resolver(true);
        final EditorModelProfileAccess access =
                new EditorModelProfileAccess(resolver, (identity, model) -> {}, writer(resolver, fixture));

        access.setName("session-a", fixture.source, fixture.model, "New Model Name");
        assertEquals("New Model Name", fixture.source.name);
        assertEquals(1, fixture.document.undoManager().entries().size(), "one native Undo entry per write");
        assertEquals(
                "Turboism: Set Model Name",
                ((GroupUndo) fixture.document.undoManager().entries().get(0)).presentationName(),
                "the frozen Undo label is the write label");
        assertTrue(fixture.document.dirty);
        assertEquals(1, fixture.source.updateCount, "the coalesced commit updates the model instances once");
        assertEquals(1, fixture.document.pack.partRefreshCount, "the coalesced commit refreshes the part palette once");
        assertEquals(1, fixture.document.pack.repaintCalls, "the coalesced commit repaints the canvas once");
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
        final VerifiedMemberResolver resolver = resolver(false);
        final EditorModelProfileAccess access =
                new EditorModelProfileAccess(resolver, (identity, model) -> {}, writer(resolver, fixture));
        assertThrows(
                UnsupportedOperationException.class,
                () -> access.setName("session-a", fixture.source, fixture.model, "X"));
        assertThrows(
                UnsupportedOperationException.class, () -> access.profile("session-a", fixture.source, fixture.model));
        assertThrows(
                UnsupportedOperationException.class, () -> access.canvas("session-a", fixture.source, fixture.model));
        assertEquals(0, fixture.document.undoManager().entries().size());
    }

    @Test
    void blankNameRejectedAndNoChangeSkipsUndo() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final VerifiedMemberResolver resolver = resolver(true);
        final EditorModelProfileAccess access =
                new EditorModelProfileAccess(resolver, (identity, model) -> {}, writer(resolver, fixture));
        assertThrows(
                IllegalArgumentException.class, () -> access.setName("session-a", fixture.source, fixture.model, "  "));
        access.setName("session-a", fixture.source, fixture.model, "original");
        assertEquals(0, fixture.document.undoManager().entries().size());
        assertTrue(!fixture.document.dirty);
    }

    /**
     * Builds the production authoring envelope over the fixture's native document, model source and
     * model, so the write exercises real Undo admission, history verification and refresh instead
     * of a hand-rolled edit bracket.
     */
    private static CoordinatorAuthoringWriter writer(final VerifiedMemberResolver resolver, final Fixture fixture) {
        final VerifiedEditorAuthoringTransactionHost host = new VerifiedEditorAuthoringTransactionHost(
                resolver,
                () -> new VerifiedEditorAuthoringTransactionHost.NativeBinding(
                        IDENTITY, 1L, fixture.document, fixture.source, fixture.model),
                () -> 1L,
                new EditorHistoryPort(() -> Optional.of(resolver), () -> 1L));
        final EditorAuthoringTransactionCoordinator coordinator = new EditorAuthoringTransactionCoordinator(host);
        return new CoordinatorAuthoringWriter(
                coordinator,
                resolver,
                () -> new EditorAuthoringTransactionCoordinator.Binding(
                        "model-profile-test", IDENTITY, 1L, IDENTITY, 1L, Thread.currentThread()));
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
        selectors.add(method(
                "cubism.editor-history.document.undo-manager", Document.class, "undoManager", desc(UndoManager.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-history.manager.class", internal(UndoManager.class)));
        selectors.add(
                method("cubism.editor-history.manager.entries", UndoManager.class, "entries", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-history.manager.position", UndoManager.class, "position", "()I"));
        selectors.add(method(
                "cubism.editor-history.manager.can-undo", UndoManager.class, "canUndo", "()Ljava/lang/Boolean;"));
        selectors.add(method(
                "cubism.editor-history.manager.can-redo", UndoManager.class, "canRedo", "()Ljava/lang/Boolean;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-history.entry.class", internal(GroupUndo.class)));
        selectors.add(method(
                "cubism.editor-history.entry.presentation-name",
                GroupUndo.class,
                "presentationName",
                "()Ljava/lang/String;"));
        selectors.add(method(
                "cubism.editor-history.entry.significant", GroupUndo.class, "significant", "()Ljava/lang/Boolean;"));
        return TestVerifiedResolvers.create(
                "5.3.02",
                "adapter.editor-model.readwrite",
                includeCapability
                        ? java.util.Set.of(
                                "cubism.editor-model.read",
                                "cubism.editor-history.read",
                                EditorModelProfileSelectorContract.NAME_WRITE_CAPABILITY_ID,
                                EditorModelProfileSelectorContract.PROFILE_READ_CAPABILITY_ID)
                        : java.util.Set.of("cubism.editor-model.read", "cubism.editor-history.read"),
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
