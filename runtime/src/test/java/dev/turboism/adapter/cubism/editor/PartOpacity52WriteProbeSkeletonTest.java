package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorPartOpacity52SelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.PartId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5.2 Part opacity write probe (focused skeleton, W2).
 *
 * <p>Decompiled 5.2.03 evidence (see
 * {@code validation/part-opacity52-write-probe/} and the task artifact):</p>
 * <ul>
 *   <li>{@code CPart.setPartsOpacity(F)V} — plain field setter
 *       ({@code this.partsOpacity = var1;}), no Undo, not an authoring path.</li>
 *   <li>Callers are animation/viewer runtime paths only:
 *       {@code doc/animation/formAnimation/t}, {@code CMvTrack_Live2DModel_Instance},
 *       {@code viewer/motion/o}, {@code viewer/m}, {@code ViewerUI_Main}.</li>
 *   <li>{@code OWData_ModelSDK.setPartsOpacity(String,float)} routes to
 *       {@code CubismPartView.setOpacity} — a Core write, forbidden.</li>
 * </ul>
 *
 * <p>The SDK write therefore stays fail-closed on 5.2; this test pins the exact
 * behavior the W3 host probe will observe, and the probe agent records the same
 * outcome on the real host.</p>
 */
class PartOpacity52WriteProbeSkeletonTest {

    @Test
    void exact5203PartOpacityWriteFailsClosedWithoutMutationOrUndo() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(
            resolver("5.2.0"), "session-a"
        );
        final var part = access.active().parts().find(new PartId("PartClip"));

        final UnsupportedOperationException failure = assertThrows(
            UnsupportedOperationException.class,
            () -> part.setOpacity(0.625F)
        );
        assertTrue(
            failure.getMessage().contains("unavailable on this exact Cubism version"),
            failure.getMessage()
        );
        assertEquals(1.0F, part.getOpacity(), "evaluation opacity read stays available");
        assertEquals(1.0F, fixture.document.model.parts.get(0).partsOpacity, "host Part opacity must not change");
        assertEquals(0, fixture.document.editMode.edits.size(), "no Undo entry may be opened");
        assertTrue(!fixture.document.dirty, "document must stay clean");
    }

    @Test
    void decompiledEvidencePinsTheNonAuthoringSetterShape() {
        // Exact 5.2.03 public member (decompiled CPart.java):
        //   public final void setPartsOpacity(float var1) { this.partsOpacity = var1; }
        assertEquals("setPartsOpacity", CPart.SET_PARTS_OPACITY_NAME);
        assertEquals("(F)V", CPart.SET_PARTS_OPACITY_DESCRIPTOR);
        // Runtime-only callers; no authoring/Undo path exists in 5.2.03.
        assertEquals(
            List.of(
                "com.live2d.cubism.doc.animation.formAnimation.t",
                "com.live2d.cubism.doc.animation.movie.track.CMvTrack_Live2DModel_Instance",
                "com.live2d.cubism.doc.modeling.ui.viewer.motion.o",
                "com.live2d.cubism.doc.modeling.ui.viewer.m",
                "com.live2d.cubism.doc.modeling.ui.viewer.ViewerUI_Main"
            ),
            CPart.RUNTIME_ONLY_CALLERS
        );
    }

    /** Decompiled-evidence constants for the probe record. */
    static final class CPart {
        static final String SET_PARTS_OPACITY_NAME = "setPartsOpacity";
        static final String SET_PARTS_OPACITY_DESCRIPTOR = "(F)V";
        static final List<String> RUNTIME_ONLY_CALLERS = List.of(
            "com.live2d.cubism.doc.animation.formAnimation.t",
            "com.live2d.cubism.doc.animation.movie.track.CMvTrack_Live2DModel_Instance",
            "com.live2d.cubism.doc.modeling.ui.viewer.motion.o",
            "com.live2d.cubism.doc.modeling.ui.viewer.m",
            "com.live2d.cubism.doc.modeling.ui.viewer.ViewerUI_Main"
        );

        private CPart() {
        }
    }

    private static VerifiedMemberResolver resolver(final String version) {
        final java.util.HashSet<String> capabilities = new java.util.HashSet<>();
        capabilities.add(dev.turboism.mapping.verification.selector.EditorPartOpacity52SelectorContract.CAPABILITY_ID);
        return TestVerifiedResolvers.create(
            version,
            dev.turboism.mapping.verification.selector.EditorPartOpacity52SelectorContract.ADAPTER_SLICE_ID,
            capabilities,
            selectors(),
            Host.class.getClassLoader()
        );
    }

    private static List<StaticSelector> selectors() {
        final List<StaticSelector> values = new ArrayList<>();
        values.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        values.add(StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", internal(Host.class), "instance", "()L" + internal(Host.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", "()L" + internal(Document.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        values.add(method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", "()L" + internal(ModelSource.class) + ";"));
        values.add(method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", "()L" + internal(Model.class) + ";"));
        values.add(method("cubism.editor-model.model-source.parts", ModelSource.class, "allParts", "()Ljava/util/List;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)));
        values.add(method("cubism.editor-model.model.parts", Model.class, "allParts", "()Ljava/util/List;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.part-source.class", internal(PartSource.class)));
        values.add(method("cubism.editor-model.part-source.id", PartSource.class, "id", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.part-source.parent", PartSource.class, "parent", "()L" + internal(PartSource.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.part-id.class", internal(Id.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.part.class", internal(Part.class)));
        values.add(method("cubism.editor-model.part.source", Part.class, "source", "()L" + internal(PartSource.class) + ";"));
        values.add(method("cubism.editor-model.part.parts-opacity", Part.class, "getPartsOpacity", "()F"));
        values.add(method("cubism.editor-model.part-id.value", Id.class, "value", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", "()L" + internal(EditMode.class) + ";"));
        return List.copyOf(values);
    }

    private static StaticSelector method(
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(
            alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC
        );
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static final class Fixture {
        final Document document = new Document();

        Fixture() {
            Host.document = document;
            document.source.parts.add(new PartSource("PartClip"));
            document.model.parts.add(new Part(document.source.parts.get(0)));
        }
    }

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static Document document;

        public static Host instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            return document;
        }
    }

    public static final class Document {
        final ModelSource source = new ModelSource();
        final EditMode editMode = new EditMode();
        final Model model = new Model();
        boolean dirty;

        public ModelSource modelSource() {
            return source;
        }

        public EditMode editMode() {
            return editMode;
        }

        public void markDirty() {
            dirty = true;
        }
    }

    public static final class EditMode {
        final List<Object> edits = new ArrayList<>();

        public Object begin(final String label) {
            final Object undo = new Object();
            edits.add(undo);
            return undo;
        }

        public void end(final boolean abort, final Object ignored) {
        }
    }

    public static final class ModelSource {
        final List<PartSource> parts = new ArrayList<>();

        public Id guid() {
            return new Id("model-a");
        }

        public Model currentInstance() {
            return Host.document.model;
        }

        public List<PartSource> allParts() {
            return parts;
        }
    }

    public static final class Model {
        final List<Part> parts = new ArrayList<>();

        public List<Part> allParts() {
            return parts;
        }
    }

    public static final class Id {
        private final String value;

        Id(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public static final class PartSource {
        private final Id id;

        PartSource(final String id) {
            this.id = new Id(id);
        }

        public Id id() {
            return id;
        }

        public PartSource parent() {
            return null;
        }
    }

    public static final class Part {
        private final PartSource source;
        float partsOpacity = 1.0F;

        Part(final PartSource source) {
            this.source = source;
        }

        public PartSource source() {
            return source;
        }

        public float getPartsOpacity() {
            return partsOpacity;
        }
    }
}
