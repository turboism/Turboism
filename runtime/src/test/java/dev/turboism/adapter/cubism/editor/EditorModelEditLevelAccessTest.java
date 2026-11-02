package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.*;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorModelEditLevelReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorModelEditLevelWriteSelectorContract;
import dev.turboism.sdk.cubism.model.ModelEditLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EditorModelEditLevelAccessTest {

    @AfterEach
    void clearHost() {
        Host.currentDocument = null;
        Host.editLevel = 1;
        Host.commandCalls = 0;
        Host.readOnEdt = false;
        Host.writeOnEdt = false;
    }

    @Test
    void readsAndSwitchesTheCurrentModelEditLevelThroughTheHostCommand() {
        Host.install(new Fixture("model-a"));
        final var model = new EditorBackedCubismModelAccess(resolver(true), "session-a").active();

        assertEquals(ModelEditLevel.LEVEL_1, model.editLevel());
        model.setEditLevel(ModelEditLevel.LEVEL_3);

        assertEquals(ModelEditLevel.LEVEL_3, model.editLevel());
        assertEquals(1, Host.commandCalls);
        model.setEditLevel(ModelEditLevel.LEVEL_3);
        assertEquals(1, Host.commandCalls, "unchanged level must not invoke the host command");
    }

    @Test
    void offEdtReadsAndWritesRunHostEditLevelMethodsOnEdt() throws Exception {
        Host.install(new Fixture("model-a"));
        final var model = new EditorBackedCubismModelAccess(resolver(true), "session-a").active();
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            assertEquals(
                    ModelEditLevel.LEVEL_1,
                    worker.submit(() -> {
                                assertFalse(SwingUtilities.isEventDispatchThread());
                                return model.editLevel();
                            })
                            .get());
            worker.submit(() -> {
                        assertFalse(SwingUtilities.isEventDispatchThread());
                        model.setEditLevel(ModelEditLevel.LEVEL_2);
                        return null;
                    })
                    .get();
        } finally {
            worker.shutdownNow();
        }
        assertTrue(Host.readOnEdt);
        assertTrue(Host.writeOnEdt);
    }

    @Test
    void switchingFailsClosedWithoutSeparateWriteEvidence() {
        Host.install(new Fixture("model-a"));
        final var model = new EditorBackedCubismModelAccess(resolver(false), "session-a").active();

        assertEquals(ModelEditLevel.LEVEL_1, model.editLevel());
        assertThrows(UnsupportedOperationException.class, () -> model.setEditLevel(ModelEditLevel.LEVEL_2));
        assertEquals(0, Host.commandCalls);
    }

    @Test
    void unknownHostLevelFailsClosed() {
        Host.install(new Fixture("model-a"));
        Host.editLevel = 0;
        final var model = new EditorBackedCubismModelAccess(resolver(true), "session-a").active();

        assertThrows(IllegalStateException.class, model::editLevel);
    }

    private static VerifiedMemberResolver resolver(final boolean writeAuthorized) {
        final Set<String> capabilities = writeAuthorized
                ? Set.of(
                        "cubism.editor-model.read",
                        EditorModelEditLevelReadSelectorContract.CAPABILITY_ID,
                        EditorModelEditLevelWriteSelectorContract.CAPABILITY_ID)
                : Set.of("cubism.editor-model.read", EditorModelEditLevelReadSelectorContract.CAPABILITY_ID);
        final List<StaticSelector> selectors = new ArrayList<>(EditorSelectorCatalog.standardModelReadSelectors());
        selectors.add(method("cubism.editor-model.app-controller.edit-level", Host.class, "editLevel", "()I"));
        selectors.add(
                method("cubism.editor-model.app-controller.set-edit-level", Host.class, "commandSetEditLevel", "(I)V"));
        return TestVerifiedResolvers.create(
                "5.3.02", "adapter.editor-model.readwrite", capabilities, selectors, Host.class.getClassLoader());
    }

    static final class Fixture {
        final Document document;

        Fixture(final String id) {
            document = new Document(new ModelSource(id));
        }
    }
}
