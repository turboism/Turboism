package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorModelEditLevelReadSelectorContract;
import dev.turboism.mapping.verification.EditorModelEditLevelWriteSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.ModelEditLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                }).get()
            );
            worker.submit(() -> {
                assertFalse(SwingUtilities.isEventDispatchThread());
                model.setEditLevel(ModelEditLevel.LEVEL_2);
                return null;
            }).get();
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
        assertThrows(
            UnsupportedOperationException.class,
            () -> model.setEditLevel(ModelEditLevel.LEVEL_2)
        );
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
                EditorModelEditLevelWriteSelectorContract.CAPABILITY_ID
            )
            : Set.of(
                "cubism.editor-model.read",
                EditorModelEditLevelReadSelectorContract.CAPABILITY_ID
            );
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            capabilities,
            List.of(
                StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)),
                StaticSelector.staticMethod(
                    "cubism.editor-model.app-controller.instance",
                    internal(Host.class),
                    "instance",
                    desc(Host.class),
                    StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC
                ),
                method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)),
                method("cubism.editor-model.app-controller.edit-level", Host.class, "editLevel", "()I"),
                method("cubism.editor-model.app-controller.set-edit-level", Host.class, "commandSetEditLevel", "(I)V"),
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)),
                method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", desc(ModelSource.class)),
                StaticSelector.classSelector("cubism.editor-model.model-source.class", internal(ModelSource.class)),
                method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)),
                method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", desc(Model.class)),
                StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)),
                StaticSelector.classSelector("cubism.editor-model.guid.class", internal(Id.class)),
                method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;")
            ),
            Host.class.getClassLoader()
        );
    }

    private static StaticSelector method(
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(
            alias,
            internal(owner),
            name,
            descriptor,
            StaticSelector.ACCESS_PUBLIC
        );
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String desc(final Class<?> type) {
        return "()L" + internal(type) + ";";
    }

    record Id(String value) {
    }

    static final class Model {
    }

    static final class ModelSource {
        private final Id guid;
        private final Model model = new Model();

        ModelSource(final String id) {
            guid = new Id(id);
        }

        public Id guid() {
            return guid;
        }

        public Model currentInstance() {
            return model;
        }
    }

    static final class Document {
        private final ModelSource source;

        Document(final ModelSource source) {
            this.source = source;
        }

        public ModelSource modelSource() {
            return source;
        }
    }

    static final class Host {
        private static final Host INSTANCE = new Host();
        static Document currentDocument;
        static int editLevel = 1;
        static int commandCalls;
        static volatile boolean readOnEdt;
        static volatile boolean writeOnEdt;

        public static Host instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            return currentDocument;
        }

        public int editLevel() {
            readOnEdt = SwingUtilities.isEventDispatchThread();
            return editLevel;
        }

        public void commandSetEditLevel(final int level) {
            writeOnEdt = SwingUtilities.isEventDispatchThread();
            editLevel = level;
            commandCalls++;
        }

        static void install(final Fixture fixture) {
            currentDocument = fixture.document;
        }
    }

    static final class Fixture {
        final Document document;

        Fixture(final String id) {
            document = new Document(new ModelSource(id));
        }
    }
}
