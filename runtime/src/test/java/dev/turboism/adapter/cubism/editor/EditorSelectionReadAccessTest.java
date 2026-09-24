package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.HostSnapshotSource.HostSelection;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorSelectionReadSelectorContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live Editor selection reads: GUID list projected onto SDK object ids in host order,
 * with honest empty results only when the host has nothing to project.
 */
class EditorSelectionReadAccessTest {

    @AfterEach
    void resetHost() {
        Host.document = null;
        Host.updateManager = new UpdateManager();
        Host.updateManagerFails = false;
    }

    @Test
    void projectsSingleWarpSelection() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        Host.updateManager.selection = List.of(fixture.warp.guid);

        final HostSelection selection = access(true).currentSelection();

        assertEquals(List.of("WarpA"), selection.selectedObjectIds());
        assertTrue(selection.activeParameterId().isEmpty());
        assertTrue(selection.activeArtMeshId().isEmpty());
        assertTrue(selection.activeDeformerId().isEmpty());
    }

    @Test
    void projectsMixedMultiSelectionInHostOrder() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        Host.updateManager.selection = List.of(
            fixture.mesh.guid, fixture.warp.guid, fixture.part.guid
        );

        final HostSelection selection = access(true).currentSelection();

        assertEquals(List.of("MeshA", "WarpA", "PartA"), selection.selectedObjectIds());
    }

    @Test
    void resolvesParameterSelectionThroughLazyParameterMap() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        Host.updateManager.selection = List.of(fixture.warp.guid, fixture.parameter.guid);

        final HostSelection selection = access(true).currentSelection();

        assertEquals(List.of("WarpA", "ParamA"), selection.selectedObjectIds());
    }

    @Test
    void dropsUnknownGuidsInsteadOfMisattributingThem() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        Host.updateManager.selection = List.of(
            fixture.warp.guid, new Id("guid-deleted-object")
        );

        final HostSelection selection = access(true).currentSelection();

        assertEquals(List.of("WarpA"), selection.selectedObjectIds());
    }

    @Test
    void emptyHostSelectionIsHonestEmpty() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        Host.updateManager.selection = List.of();

        assertEquals(HostSelection.empty(), access(true).currentSelection());
    }

    @Test
    void absentDocumentWithEmptySelectionIsHonestEmpty() {
        Host.document = null;
        Host.updateManager.selection = List.of();

        assertEquals(HostSelection.empty(), access(true).currentSelection());
    }

    @Test
    void nonEmptySelectionWithoutModelingDocumentSurfaces() {
        Host.document = null;
        Host.updateManager.selection = List.of(new Id("guid-anything"));

        assertThrows(IllegalStateException.class, () -> access(true).currentSelection());
    }

    @Test
    void liveReadFailurePropagatesInsteadOfMaskingAsEmpty() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        Host.updateManagerFails = true;

        assertThrows(RuntimeException.class, () -> access(true).currentSelection());
    }

    @Test
    void unauthorizedEvidenceKeepsTheSeamClosed() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        Host.updateManager.selection = List.of(fixture.warp.guid);
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(false), "session-a"
        );

        assertFalse(access.selectionReadAuthorized());
        assertThrows(UnsupportedOperationException.class, access::readHostSelection);
    }

    @Test
    void readHostSelectionProjectsThroughEditorAccess() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        Host.updateManager.selection = List.of(fixture.mesh.guid);
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );

        assertTrue(access.selectionReadAuthorized());
        assertEquals(List.of("MeshA"), access.readHostSelection().selectedObjectIds());
    }

    private static EditorSelectionReadAccess access(final boolean authorized) {
        return new EditorSelectionReadAccess(resolver(authorized));
    }

    private static VerifiedMemberResolver resolver(final boolean includeSelectionRead) {
        final java.util.HashSet<String> capabilities = new java.util.HashSet<>();
        if (includeSelectionRead) {
            capabilities.add(EditorSelectionReadSelectorContract.CAPABILITY_ID);
        } else {
            capabilities.add(EditorObjectReadSelectorContract.CAPABILITY_ID);
        }
        return TestVerifiedResolvers.create(
            "5.3.03",
            EditorSelectionReadSelectorContract.ADAPTER_SLICE_ID,
            capabilities,
            selectors(),
            Host.class.getClassLoader()
        );
    }

    private static List<StaticSelector> selectors() {
        final List<StaticSelector> values = new ArrayList<>();
        values.add(StaticSelector.staticMethod(
            "cubism.editor-model.app-controller.instance", internal(Host.class),
            "instance", "()L" + internal(Host.class) + ";",
            StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC
        ));
        values.add(method(
            "cubism.editor-model.app-controller.current-document",
            Host.class, "currentDocument", "()L" + internal(Document.class) + ";"
        ));
        values.add(method(
            "cubism.editor-model.app-controller.update-manager",
            Host.class, "updateManager", "()L" + internal(UpdateManager.class) + ";"
        ));
        values.add(method(
            "cubism.editor-model.update-manager.selection-guid-list",
            UpdateManager.class, "selectionGuidList", "()Ljava/util/List;"
        ));
        values.add(StaticSelector.classSelector(
            "cubism.editor-model.modeling-document.class", internal(Document.class)
        ));
        values.add(method(
            "cubism.editor-model.modeling-document.model-source",
            Document.class, "modelSource", "()L" + internal(ModelSource.class) + ";"
        ));
        values.add(method(
            "cubism.editor-model.model-source.all-objects",
            ModelSource.class, "allObjects", "()Ljava/util/List;"
        ));
        values.add(method(
            "cubism.editor-model.model-source.all-parameters",
            ModelSource.class, "allParameters", "()Ljava/util/List;"
        ));
        values.add(method(
            "cubism.editor-model.parameter-controllable-source.guid",
            ObjectSource.class, "guid", "()L" + internal(Id.class) + ";"
        ));
        values.add(method(
            "cubism.editor-model.parameter-controllable-source.id",
            ObjectSource.class, "id", "()L" + internal(Id.class) + ";"
        ));
        values.add(method(
            "cubism.editor-model.parameter-source.guid",
            ParameterSource.class, "guid", "()L" + internal(Id.class) + ";"
        ));
        values.add(method(
            "cubism.editor-model.parameter-source.id",
            ParameterSource.class, "id", "()L" + internal(Id.class) + ";"
        ));
        values.add(method(
            "cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"
        ));
        values.add(method(
            "cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"
        ));
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
        final ModelSource source = document.source;
        final ObjectSource warp = new ObjectSource(new Id("guid-warp-a"), new Id("WarpA"));
        final ObjectSource mesh = new ObjectSource(new Id("guid-mesh-a"), new Id("MeshA"));
        final ObjectSource part = new ObjectSource(new Id("guid-part-a"), new Id("PartA"));
        final ParameterSource parameter =
            new ParameterSource(new Id("guid-param-a"), new Id("ParamA"));

        Fixture() {
            source.objects.addAll(List.of(warp, mesh, part));
            source.parameters.add(parameter);
        }
    }

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static Document document;
        static UpdateManager updateManager = new UpdateManager();
        static boolean updateManagerFails;

        public static Host instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            return document;
        }

        public UpdateManager updateManager() {
            if (updateManagerFails) {
                throw new IllegalStateException("simulated update manager failure");
            }
            return updateManager;
        }
    }

    public static final class UpdateManager {
        List<Id> selection = List.of();

        public List<Id> selectionGuidList() {
            return selection;
        }
    }

    public static final class Document {
        final ModelSource source = new ModelSource();

        public ModelSource modelSource() {
            return source;
        }
    }

    public static final class ModelSource {
        final List<ObjectSource> objects = new ArrayList<>();
        final List<ParameterSource> parameters = new ArrayList<>();

        public List<ObjectSource> allObjects() {
            return objects;
        }

        public List<ParameterSource> allParameters() {
            return parameters;
        }
    }

    public static class ObjectSource {
        private final Id guid;
        private final Id id;

        ObjectSource(final Id guid, final Id id) {
            this.guid = guid;
            this.id = id;
        }

        public Id guid() {
            return guid;
        }

        public Id id() {
            return id;
        }
    }

    public static final class ParameterSource {
        private final Id guid;
        private final Id id;

        ParameterSource(final Id guid, final Id id) {
            this.guid = guid;
            this.id = id;
        }

        public Id guid() {
            return guid;
        }

        public Id id() {
            return id;
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
}
