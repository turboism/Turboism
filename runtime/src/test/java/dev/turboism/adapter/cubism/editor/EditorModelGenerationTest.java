package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.internal;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Staleness contract of the single Editor model generation owner: what a minted binding proves,
 * which switches it detects, and that the model-source guid stays off the repeated-check path.
 */
class EditorModelGenerationTest {

    @BeforeEach
    void installActiveModel() {
        Host.documentReads.set(0);
        Document.sourceReads.set(0);
        ModelSource.instanceReads.set(0);
        ModelSource.guidReads.set(0);
        ModelSource.guidValueReads.set(0);
        Host.document = new Document(new ModelSource("model-a", new Model()));
    }

    @Test
    void mintsTheActiveModelAndReusesOneBindingWhileTheReferencesAreUnchanged() {
        final EditorModelGeneration generation = new EditorModelGeneration(resolver(), "session-a");

        final BoundModel first = generation.mint();

        assertEquals("session-a:model-a:1", first.identity());
        assertEquals(1L, first.generation());
        assertEquals("model-a", first.modelId());
        assertSame(Host.document, first.document());
        assertSame(source(), first.source());
        assertSame(source().value, first.model());

        final BoundModel second = generation.mint();

        assertEquals(first.identity(), second.identity());
        assertEquals(1L, second.generation());
        assertEquals(1L, generation.generation());
        assertEquals(1, ModelSource.guidReads.get());
        assertEquals(1, ModelSource.guidValueReads.get());
    }

    @Test
    void requireCurrentRejectsADocumentSwitch() {
        final EditorModelGeneration generation = new EditorModelGeneration(resolver(), "session-a");
        final BoundModel bound = generation.mint();

        Host.document = new Document((ModelSource) bound.source());

        assertThrows(IllegalStateException.class, () -> generation.requireCurrent(bound.identity(), bound.model()));
        assertEquals(2L, generation.generation());
        assertNotEquals(bound.identity(), generation.mint().identity());
    }

    @Test
    void requireCurrentRejectsAModelSourceReplacement() {
        final EditorModelGeneration generation = new EditorModelGeneration(resolver(), "session-a");
        final BoundModel bound = generation.mint();

        Host.document.source = new ModelSource("model-b", new Model());

        assertThrows(IllegalStateException.class, () -> generation.requireCurrent(bound.identity(), bound.model()));
        assertEquals(2L, generation.generation());
        assertEquals("session-a:model-b:2", generation.mint().identity());
    }

    @Test
    void requireCurrentRejectsAModelInstanceReplacement() {
        final EditorModelGeneration generation = new EditorModelGeneration(resolver(), "session-a");
        final BoundModel bound = generation.mint();

        source().value = new Model();

        assertThrows(IllegalStateException.class, () -> generation.requireCurrent(bound.identity(), bound.model()));
        assertEquals(2L, generation.generation());
        assertEquals("session-a:model-a:2", generation.mint().identity());
    }

    @Test
    void requireCurrentRejectsAReusedIdentityWithAnotherModelReference() {
        final EditorModelGeneration generation = new EditorModelGeneration(resolver(), "session-a");
        final BoundModel bound = generation.mint();

        assertThrows(IllegalStateException.class, () -> generation.requireCurrent(bound.identity(), new Model()));
        assertEquals(1L, generation.generation());
    }

    @Test
    void invalidateMintsANewGenerationWhenTheSameReferencesComeBack() {
        final EditorModelGeneration generation = new EditorModelGeneration(resolver(), "session-a");
        final BoundModel bound = generation.mint();
        final Object document = bound.document();
        final Object source = bound.source();
        final Object model = bound.model();

        generation.invalidate("project-close");

        assertThrows(IllegalStateException.class, () -> generation.requireCurrent(bound.identity(), bound.model()));
        final BoundModel reminted = generation.mint();
        assertEquals(2L, reminted.generation());
        assertEquals("session-a:model-a:2", reminted.identity());
        assertSame(document, reminted.document());
        assertSame(source, reminted.source());
        assertSame(model, reminted.model());
        assertEquals(2, ModelSource.guidReads.get());
        generation.requireCurrent(reminted.identity(), reminted.model());
    }

    @Test
    void repeatedRequireCurrentNeverReadsTheModelSourceGuid() {
        final EditorModelGeneration generation = new EditorModelGeneration(resolver(), "session-a");
        final BoundModel bound = generation.mint();

        for (int check = 0; check < 5; check++) {
            final BoundModel current = generation.requireCurrent(bound.identity(), bound.model());
            assertEquals(bound.identity(), current.identity());
        }

        assertEquals(1, ModelSource.guidReads.get());
        assertEquals(1, ModelSource.guidValueReads.get());
        // Six walks (one mint + five checks), each resolving the document, source and instance.
        assertEquals(6, Host.documentReads.get());
        assertEquals(6, Document.sourceReads.get());
        assertEquals(6, ModelSource.instanceReads.get());
        assertEquals(1L, generation.generation());
    }

    @Test
    void generationReadsTheMintedGenerationWithoutWalking() {
        final EditorModelGeneration generation = new EditorModelGeneration(resolver(), "session-a");
        assertEquals(0L, generation.generation());
        assertEquals(0, Host.documentReads.get());

        generation.mint();

        assertEquals(1L, generation.generation());
        assertEquals(1, Host.documentReads.get());
    }

    @Test
    void failsClosedWithoutAVerifiedModelingDocumentOrModel() {
        final EditorModelGeneration generation = new EditorModelGeneration(resolver(), "session-a");
        Host.document = null;
        assertThrows(IllegalStateException.class, generation::mint);

        installActiveModel();
        source().value = null;
        assertThrows(IllegalStateException.class, generation::mint);
        assertEquals(0L, generation.generation());
    }

    @Test
    void invalidateRejectsABlankReason() {
        final EditorModelGeneration generation = new EditorModelGeneration(resolver(), "session-a");

        assertThrows(IllegalArgumentException.class, () -> generation.invalidate(null));
        assertThrows(IllegalArgumentException.class, () -> generation.invalidate(" "));
    }

    private static ModelSource source() {
        return Host.document.source;
    }

    private static VerifiedMemberResolver resolver() {
        return TestVerifiedResolvers.create(
                "cubism.editor-model-read",
                java.util.Set.of("editor-model-read"),
                selectors(),
                EditorModelGenerationTest.class.getClassLoader());
    }

    private static List<StaticSelector> selectors() {
        final List<StaticSelector> values = new ArrayList<>();
        values.add(StaticSelector.staticMethod(
                "cubism.editor-model.app-controller.instance",
                internal(Host.class),
                "instance",
                "()L" + internal(Host.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(method(
                "cubism.editor-model.app-controller.current-document",
                Host.class,
                "currentDocument",
                "()L" + internal(Document.class) + ";"));
        values.add(
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        values.add(method(
                "cubism.editor-model.modeling-document.model-source",
                Document.class,
                "modelSource",
                "()L" + internal(ModelSource.class) + ";"));
        values.add(method(
                "cubism.editor-model.model-source.current-instance",
                ModelSource.class,
                "currentInstance",
                "()L" + internal(Model.class) + ";"));
        values.add(method(
                "cubism.editor-model.model-source.guid", ModelSource.class, "guid", "()L" + internal(Id.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)));
        values.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        return List.copyOf(values);
    }

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static final AtomicInteger documentReads = new AtomicInteger();
        static Document document;

        public static Host instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            documentReads.incrementAndGet();
            return document;
        }
    }

    public static final class Document {
        static final AtomicInteger sourceReads = new AtomicInteger();
        ModelSource source;

        Document(final ModelSource source) {
            this.source = source;
        }

        public ModelSource modelSource() {
            sourceReads.incrementAndGet();
            return source;
        }
    }

    public static final class ModelSource {
        static final AtomicInteger instanceReads = new AtomicInteger();
        static final AtomicInteger guidReads = new AtomicInteger();
        static final AtomicInteger guidValueReads = new AtomicInteger();
        private final String id;
        Model value;

        ModelSource(final String id, final Model value) {
            this.id = id;
            this.value = value;
        }

        public Model currentInstance() {
            instanceReads.incrementAndGet();
            return value;
        }

        public Id guid() {
            guidReads.incrementAndGet();
            return new Id(id);
        }
    }

    public static final class Id {
        private final String value;

        Id(final String value) {
            this.value = value;
        }

        public String value() {
            ModelSource.guidValueReads.incrementAndGet();
            return value;
        }
    }

    public static final class Model {}
}
