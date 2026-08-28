package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorDefaultKeyformLockReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterBindingReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterGroupsReadSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.ParameterBindingTargetType;
import dev.turboism.sdk.cubism.model.ParameterType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorBackedCubismModelAccessTest {

    @Test
    void joinsAuthoringMetadataToTheActiveModelGenerationAndFailsClosedOnReplacement() {
        Fixture host = new Fixture("model-a", 12.0F);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );
        Host.install(host);

        var model = access.active();
        var parameter = model.parameters().find(new ParameterId("ParamAngleX"));

        assertEquals("model-a", model.id().value());
        assertTrue(model.defaultKeyformLocked());
        assertEquals(List.of("ParamAngleX"), model.parameters().all().stream()
            .map(value -> value.id().value()).toList());
        assertEquals(12.0F, parameter.getValue());
        assertEquals(-30.0F, parameter.getMinimumValue());
        assertEquals(30.0F, parameter.getMaximumValue());
        assertEquals(0.0F, parameter.getDefaultValue());
        assertEquals(Optional.of("Angle X"), parameter.name());
        assertEquals(
            List.of("ParamAngleX"),
            model.parameters().findByName("Angle X").stream()
                .map(value -> value.id().value()).toList()
        );
        assertEquals(
            List.of("ParamAngleX"),
            model.parameters().search("angle").stream()
                .map(value -> value.id().value()).toList()
        );
        assertEquals(ParameterType.BLEND_SHAPE, parameter.type());
        assertEquals(Optional.of(true), parameter.repeat());
        assertEquals(Optional.of(true), parameter.combined());
        assertEquals(
            List.of("ParamAngleX"),
            model.parameters().filter(value ->
                value.isBlendShape()
                    && value.repeat().orElse(false)
                    && value.combined().orElse(false)
            ).stream().map(value -> value.id().value()).toList()
        );

        host.source.currentInstance.parameterSet.parameters.get(0).source.name = null;
        assertEquals(Optional.empty(), parameter.name());
        assertEquals(List.of(), model.parameters().findByName("Angle X"));

        host = new Fixture("model-b", -5.0F);
        Host.install(host);
        assertThrows(IllegalStateException.class, parameter::getValue);
        assertEquals("model-b", access.active().id().value());
        assertThrows(NoSuchElementException.class, () ->
            access.active().parameters().find(new ParameterId("Missing"))
        );
    }


    @Test
    void documentReplacementWithTheSameSourceAndModelInvalidatesOldReferences() {
        final Fixture host = new Fixture("model-a", 12.0F);
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );
        Host.install(host);
        final var parameter = access.active().parameters().find(new ParameterId("ParamAngleX"));

        Host.currentDocument = new Document(host.source);

        assertThrows(IllegalStateException.class, parameter::getValue);
    }

    @Test
    void exposesParameterDefinitionsAndIndexWhileEditorKeyValuesFailClosed() {
        Fixture host = new Fixture("model-a", 12.0F);
        Host.install(host);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );

        var model = access.active();
        var parameter = model.parameters().find(new ParameterId("ParamAngleX"));

        assertEquals(0, parameter.index());
        assertThrows(UnsupportedOperationException.class, parameter::keyValues);
        assertEquals(
            List.of(new ParameterId("ParamAngleX")),
            model.parameterDefinitions().all().stream()
                .map(value -> value.id())
                .toList()
        );
        assertEquals(
            new dev.turboism.sdk.cubism.model.ParameterDefinition(
                new ParameterId("ParamAngleX"),
                "Angle X",
                -30.0F,
                0.0F,
                30.0F,
                ParameterType.BLEND_SHAPE,
                true
            ),
            model.parameterDefinitions().find(new ParameterId("ParamAngleX"))
        );

        Host.install(new Fixture("model-b", -5.0F));
        assertThrows(IllegalStateException.class, parameter::keyValues);
    }

    @Test
    void duplicateParameterIdsArePreservedInStableOrder() {
        // Verified host evidence: CParameterSet stores CParameter entries in a plain CArrayList
        // without any id-uniqueness constraint and real Editor models can contain duplicate ids.
        // all() must include every entry, find() returns the first match, and nothing throws.
        final ParameterSet set = new ParameterSet(new java.util.ArrayList<>(List.of(
            new Parameter("ParamAngleX", 12.0F), new Parameter("ParamAngleX", 5.0F))));
        final Model model = new Model(set);
        final ModelSource source = new ModelSource("model-a", model);
        model.source = source;
        Host.currentDocument = new Document(source);
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a");
        final var active = access.active();

        assertEquals(
            List.of("ParamAngleX", "ParamAngleX"),
            active.parameters().all().stream().map(value -> value.id().value()).toList()
        );
        assertEquals(
            new ParameterId("ParamAngleX"),
            active.parameters().find(new ParameterId("ParamAngleX")).id()
        );
        assertTrue(active.parameters().findById(new ParameterId("ParamAngleX")).isPresent());
        assertEquals(2, active.parameterDefinitions().all().size());
        assertEquals(
            new ParameterId("ParamAngleX"),
            active.parameterDefinitions().find(new ParameterId("ParamAngleX")).id()
        );
    }

    @Test
    void parameterBindingProjectionCollectsAllThreeObjectFamiliesAndGoesStaleWithTheModel() {
        Fixture host = new Fixture("model-a", 12.0F);
        Host.install(host);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );

        final var parameter = access.active().parameters().find(new ParameterId("ParamAngleX"));
        assertEquals(
            List.of(
                ParameterBindingTargetType.ART_MESH,
                ParameterBindingTargetType.WARP_DEFORMER,
                ParameterBindingTargetType.ROTATION_DEFORMER
            ),
            parameter.getParameterBindings().stream().map(binding -> binding.target().type()).toList()
        );

        Host.install(new Fixture("model-b", -5.0F));
        assertThrows(IllegalStateException.class, parameter::getParameterBindings);
    }

    @Test
    void exposesTheParameterGroupHierarchyInStableEditorOrder() {
        Fixture host = new Fixture("model-a", 12.0F);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );
        Host.install(host);

        var groups = access.active().parameterGroups();
        var root = groups.root();
        var face = groups.find(new dev.turboism.sdk.cubism.id.ParameterGroupId("GroupFace"));

        assertEquals(Optional.of("Root"), root.name());
        assertEquals(Optional.empty(), root.parentId());
        assertEquals(
            List.of(new dev.turboism.sdk.cubism.id.ParameterGroupId("GroupFace")),
            root.childGroupIds()
        );
        assertEquals(List.of(), root.parameterIds());
        assertEquals(Optional.of("Face"), face.name());
        assertEquals(Optional.of(root.id()), face.parentId());
        assertEquals(List.of(), face.childGroupIds());
        assertEquals(List.of(new ParameterId("ParamAngleX")), face.parameterIds());
        assertEquals(List.of(root.id(), face.id()), groups.all().stream()
            .map(dev.turboism.sdk.cubism.model.ParameterGroup::id)
            .toList());

        Host.install(new Fixture("model-b", -5.0F));
        assertThrows(IllegalStateException.class, root::name);
        assertThrows(IllegalStateException.class, groups::all);
    }

    @Test
    void findTraversesEachParameterGroupOnlyOnceWhileValidatingTheEntireHierarchy() {
        final Fixture host = new Fixture("model-a", 12.0F);
        final ParameterGroup jaw = new ParameterGroup("GroupJaw", "Jaw", host.source.rootGroup);
        final ParameterGroup brow = new ParameterGroup("GroupBrow", "Brow", host.source.rootGroup);
        final ParameterGroup eye = new ParameterGroup("GroupEye", "Eye", host.source.rootGroup);
        host.source.rootGroup.children.addAll(List.of(jaw, brow, eye));
        Host.install(host);
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );
        final var groups = access.active().parameterGroups();
        ParameterGroup.resetIdReads();

        final var found = groups.find(new dev.turboism.sdk.cubism.id.ParameterGroupId("GroupEye"));

        assertEquals(5, ParameterGroup.idReads());
        assertEquals(new dev.turboism.sdk.cubism.id.ParameterGroupId("GroupEye"), found.id());
    }

    @Test
    void findContinuesValidationAfterItFindsTheRequestedParameterGroup() {
        final Fixture host = new Fixture("model-a", 12.0F);
        final ParameterGroup face = (ParameterGroup) host.source.rootGroup.children.get(0);
        face.children.add(host.source.rootGroup);
        Host.install(host);
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );

        assertThrows(IllegalStateException.class, () -> access.active().parameterGroups().find(
            new dev.turboism.sdk.cubism.id.ParameterGroupId("GroupFace")
        ));
    }

    @Test
    void detachedParameterGroupReferencesFailClosedWithinTheSameModelGeneration() {
        Fixture host = new Fixture("model-a", 12.0F);
        Host.install(host);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );
        var face = access.active().parameterGroups().find(
            new dev.turboism.sdk.cubism.id.ParameterGroupId("GroupFace")
        );

        host.source.rootGroup.children.clear();

        assertThrows(IllegalStateException.class, face::name);
    }

    @Test
    void parameterGroupsRequireTheirSeparateVerifiedCapability() {
        Fixture host = new Fixture("model-a", 12.0F);
        Host.install(host);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolverWithoutParameterGroups(), "session-a"
        );

        assertThrows(
            UnsupportedOperationException.class,
            () -> access.active().parameterGroups().root()
        );
    }

    @Test
    void defaultKeyformLockReadsRequireTheirSeparateVerifiedCapability() {
        Host.install(new Fixture("model-a", 12.0F));
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolverWithoutDefaultKeyformLock(), "session-a"
        );

        assertEquals("model-a", access.active().id().value());
        assertThrows(
            UnsupportedOperationException.class,
            () -> access.active().defaultKeyformLocked()
        );
    }

    @Test
    void malformedParameterGroupTreesFailClosed() {
        Fixture host = new Fixture("model-a", 12.0F);
        ParameterGroup face = (ParameterGroup) host.source.rootGroup.children.get(0);
        face.children.add(host.source.rootGroup);
        Host.install(host);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );

        assertThrows(IllegalStateException.class, () -> access.active().parameterGroups().all());
    }

    @Test
    void nonModelingDocumentsAndMissingActiveInstancesRemainUnavailable() {
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );
        Host.currentDocument = null;
        assertTrue(assertThrows(IllegalStateException.class, access::active)
            .getMessage().contains("modeling document"));

        Fixture fixture = new Fixture("model-a", 0.0F);
        fixture.source.currentInstance = null;
        Host.install(fixture);
        assertTrue(assertThrows(IllegalStateException.class, access::active)
            .getMessage().contains("active model"));
    }

    private static VerifiedMemberResolver resolverWithoutDefaultKeyformLock() {
        return resolver(java.util.Set.of(
            "cubism.editor-model.read",
            dev.turboism.mapping.verification.selector.EditorParameterGroupsReadSelectorContract.CAPABILITY_ID,
            dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract.CAPABILITY_ID,
            dev.turboism.mapping.verification.selector.EditorParameterBindingReadSelectorContract.CAPABILITY_ID
        ));
    }

    private static VerifiedMemberResolver resolver() {
        return resolver(java.util.Set.of(
            "cubism.editor-model.read",
            dev.turboism.mapping.verification.selector.EditorParameterGroupsReadSelectorContract.CAPABILITY_ID,
            dev.turboism.mapping.verification.selector.EditorDefaultKeyformLockReadSelectorContract.CAPABILITY_ID,
            dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract.CAPABILITY_ID,
            dev.turboism.mapping.verification.selector.EditorParameterBindingReadSelectorContract.CAPABILITY_ID
        ));
    }

    private static VerifiedMemberResolver resolver(final java.util.Set<String> capabilities) {
        String host = internal(Host.class);
        String document = internal(Document.class);
        String source = internal(ModelSource.class);
        String model = internal(Model.class);
        String set = internal(ParameterSet.class);
        String parameter = internal(Parameter.class);
        String metadata = internal(ParameterSource.class);
        String group = internal(ParameterGroup.class);
        String labelColor = internal(LabelColor.class);
        String color = internal(HostColor.class);
        String id = internal(Id.class);
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            capabilities,
            List.of(
                StaticSelector.classSelector("cubism.editor-model.app-controller.class", host),
                StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", host, "instance", desc(Host.class), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)),
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", document),
                method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", desc(ModelSource.class)),
                StaticSelector.classSelector("cubism.editor-model.modeling-view.class", internal(Object.class)),
                StaticSelector.classSelector("cubism.editor-model.model-source.class", source),
                method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)),
                method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", desc(Model.class)),
                method("cubism.editor-model.model-source.default-keyform-locked", ModelSource.class, "defaultKeyformLocked", "()Z"),
                method("cubism.editor-model.model-source.root-parameter-group", ModelSource.class, "rootParameterGroup", desc(ParameterGroup.class)),
                method("cubism.editor-model.model-source.all-art-meshes", ModelSource.class, "allArtMeshes", "()Ljava/util/List;"),
                method("cubism.editor-model.model-source.all-deformers", ModelSource.class, "allDeformers", "()Ljava/util/List;"),
                StaticSelector.classSelector("cubism.editor-model.model.class", model),
                method("cubism.editor-model.model.parameter-set", Model.class, "parameterSet", desc(ParameterSet.class)),
                method("cubism.editor-model.model.all-art-meshes", Model.class, "allArtMeshes", "()Ljava/util/List;"),
                method("cubism.editor-model.model.all-deformers", Model.class, "allDeformers", "()Ljava/util/List;"),
                StaticSelector.classSelector("cubism.editor-model.parameter-set.class", set),
                method("cubism.editor-model.parameter-set.parameters", ParameterSet.class, "parameters", "()Ljava/util/List;"),
                StaticSelector.classSelector("cubism.editor-model.parameter.class", parameter),
                method("cubism.editor-model.parameter.id", Parameter.class, "id", desc(Id.class)),
                method("cubism.editor-model.parameter.value", Parameter.class, "value", "()F"),
                method("cubism.editor-model.parameter.source", Parameter.class, "source", desc(ParameterSource.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-source.class", metadata),
                method("cubism.editor-model.parameter-source.minimum", ParameterSource.class, "minimum", "()F"),
                method("cubism.editor-model.parameter-source.maximum", ParameterSource.class, "maximum", "()F"),
                method("cubism.editor-model.parameter-source.default", ParameterSource.class, "defaultValue", "()F"),
                method("cubism.editor-model.parameter-source.name", ParameterSource.class, "name", "()Ljava/lang/String;"),
                method("cubism.editor-model.parameter-source.repeat", ParameterSource.class, "repeat", "()Z"),
                method("cubism.editor-model.parameter-source.morph-target", ParameterSource.class, "morphTarget", "()Z"),
                method("cubism.editor-model.parameter-source.combined", ParameterSource.class, "combined", "()Z"),
                method("cubism.editor-model.parameter-source.id", ParameterSource.class, "id", desc(Id.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-group.class", group),
                method("cubism.editor-model.parameter-group.id", ParameterGroup.class, "id", desc(Id.class)),
                method("cubism.editor-model.parameter-group.name", ParameterGroup.class, "name", "()Ljava/lang/String;"),
                method("cubism.editor-model.parameter-group.parent", ParameterGroup.class, "parent", desc(ParameterGroup.class)),
                method("cubism.editor-model.parameter-group.children", ParameterGroup.class, "children", "()Ljava/util/List;"),
                method("cubism.editor-model.parameter-group.label-color", ParameterGroup.class, "labelColor", desc(LabelColor.class)),
                StaticSelector.classSelector("cubism.editor-model.label-color.class", labelColor),
                method("cubism.editor-model.label-color.color", LabelColor.class, "color", desc(HostColor.class)),
                StaticSelector.classSelector("cubism.editor-model.color.class", color),
                method("cubism.editor-model.color.red", HostColor.class, "red", "()F"),
                method("cubism.editor-model.color.green", HostColor.class, "green", "()F"),
                method("cubism.editor-model.color.blue", HostColor.class, "blue", "()F"),
                method("cubism.editor-model.color.alpha", HostColor.class, "alpha", "()F"),
                StaticSelector.classSelector("cubism.editor-model.id.class", id),
                method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.guid.class", id),
                method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.art-mesh-source.class", internal(ArtMeshSource.class)),
                StaticSelector.classSelector("cubism.editor-model.art-mesh.class", internal(ArtMesh.class)),
                StaticSelector.classSelector("cubism.editor-model.warp-source.class", internal(WarpSource.class)),
                StaticSelector.classSelector("cubism.editor-model.warp.class", internal(Warp.class)),
                StaticSelector.classSelector("cubism.editor-model.rotation-source.class", internal(RotationSource.class)),
                StaticSelector.classSelector("cubism.editor-model.rotation.class", internal(Rotation.class)),
                method("cubism.editor-model.parameter-controllable-source.id", ObjectSource.class, "id", desc(Id.class)),
                method("cubism.editor-model.parameter-controllable-source.local-name", ObjectSource.class, "localName", "()Ljava/lang/String;"),
                method("cubism.editor-model.parameter-controllable-source.visible", ObjectSource.class, "visible", "()Z"),
                method("cubism.editor-model.parameter-controllable-source.locked", ObjectSource.class, "locked", "()Z"),
                method("cubism.editor-model.parameter-controllable-source.visible-in-hierarchy", ObjectSource.class, "visibleInHierarchy", "()Z"),
                method("cubism.editor-model.parameter-controllable-source.locked-in-hierarchy", ObjectSource.class, "lockedInHierarchy", "()Z"),
                method("cubism.editor-model.parameter-controllable.keyform-grid", ObjectSource.class, "keyformGrid", desc(KeyformGrid.class)),
                method("cubism.editor-model.art-mesh.source", ArtMesh.class, "source", desc(ArtMeshSource.class)),
                method("cubism.editor-model.art-mesh.current-keyform", ArtMesh.class, "currentForm", desc(Form.class)),
                method("cubism.editor-model.deformer.source", Deformer.class, "source", desc(ObjectSource.class)),
                method("cubism.editor-model.deformer.current-keyform", Deformer.class, "currentForm", desc(Form.class)),
                StaticSelector.classSelector("cubism.editor-model.keyform-grid.class", internal(KeyformGrid.class)),
                method("cubism.editor-model.keyform-grid.bindings", KeyformGrid.class, "bindings", "()Ljava/util/List;"),
                StaticSelector.classSelector("cubism.editor-model.keyform-binding.class", internal(KeyformBinding.class)),
                method("cubism.editor-model.keyform-binding.parameter-id", KeyformBinding.class, "parameterId", desc(Id.class)),
                method("cubism.editor-model.keyform-binding.keys", KeyformBinding.class, "keys", "()Ljava/util/List;")
            ),
            Host.class.getClassLoader()
        );
    }

    private static VerifiedMemberResolver resolverWithoutParameterGroups() {
        final VerifiedMemberResolver full = resolver();
        final java.util.Set<String> groupAliases =
            dev.turboism.mapping.verification.selector.EditorParameterGroupsReadSelectorContract.REQUIRED_ALIASES;
        final java.util.List<StaticSelector> selectors = new java.util.ArrayList<>();
        for (String alias : java.util.List.of(
            "cubism.editor-model.app-controller.class",
            "cubism.editor-model.app-controller.instance",
            "cubism.editor-model.app-controller.current-document",
            "cubism.editor-model.modeling-document.class",
            "cubism.editor-model.modeling-document.model-source",
            "cubism.editor-model.modeling-view.class",
            "cubism.editor-model.model-source.class",
            "cubism.editor-model.model-source.guid",
            "cubism.editor-model.model-source.current-instance",
            "cubism.editor-model.model.class",
            "cubism.editor-model.model.parameter-set",
            "cubism.editor-model.parameter-set.class",
            "cubism.editor-model.parameter-set.parameters",
            "cubism.editor-model.parameter.class",
            "cubism.editor-model.parameter.id",
            "cubism.editor-model.parameter.value",
            "cubism.editor-model.parameter.source",
            "cubism.editor-model.parameter-source.class",
            "cubism.editor-model.parameter-source.minimum",
            "cubism.editor-model.parameter-source.maximum",
            "cubism.editor-model.parameter-source.default",
            "cubism.editor-model.parameter-source.name",
            "cubism.editor-model.parameter-source.repeat",
            "cubism.editor-model.parameter-source.morph-target",
            "cubism.editor-model.parameter-source.combined",
            "cubism.editor-model.parameter-source.id",
            "cubism.editor-model.id.class",
            "cubism.editor-model.id.value",
            "cubism.editor-model.guid.class",
            "cubism.editor-model.guid.value"
        )) {
            if (!groupAliases.contains(alias)) selectors.add(full.verifiedSelector(alias));
        }
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            java.util.Set.of("cubism.editor-model.read"),
            selectors,
            Host.class.getClassLoader()
        );
    }

    private static StaticSelector method(String alias, Class<?> owner, String name, String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }
    private static String internal(Class<?> type) { return type.getName().replace('.', '/'); }
    private static String desc(Class<?> type) { return "()L" + internal(type) + ";"; }

    private static List<Float> sequence(final dev.turboism.sdk.cubism.model.FloatSequence values) {
        final java.util.ArrayList<Float> result = new java.util.ArrayList<>();
        for (int index = 0; index < values.size(); index++) result.add(values.get(index));
        return result;
    }

    public static final class Host {
        static final Host INSTANCE = new Host();
        static Object currentDocument;
        public static Host instance() { return INSTANCE; }
        public Document currentDocument() { return (Document) currentDocument; }
        static void install(Fixture fixture) { currentDocument = fixture.document; }
    }
    public static final class Document {
        final ModelSource source;
        Document(ModelSource source) { this.source = source; }
        public ModelSource modelSource() { return source; }
        public Object lastActiveView() { return null; }
    }
    public static final class ModelSource {
        final Id guid;
        final ParameterGroup rootGroup;
        final java.util.List<ArtMeshSource> artMeshes = new java.util.ArrayList<>();
        final java.util.List<ObjectSource> deformers = new java.util.ArrayList<>();
        Model currentInstance;
        ModelSource(String id, Model model) {
            guid = new Id(id);
            currentInstance = model;
            rootGroup = new ParameterGroup("GroupRoot", "Root", null);
            ParameterGroup face = new ParameterGroup("GroupFace", "Face", rootGroup);
            rootGroup.children.add(face);
            face.children.add(model.parameterSet.parameters.get(0).source());
            ArtMeshSource artMesh = new ArtMeshSource("ArtMeshFace");
            WarpSource warp = new WarpSource("WarpFace");
            RotationSource rotation = new RotationSource("RotationHead");
            artMeshes.add(artMesh);
            deformers.add(warp);
            deformers.add(rotation);
            model.artMeshes.add(new ArtMesh(artMesh));
            model.deformers.add(new Warp(warp));
            model.deformers.add(new Rotation(rotation));
        }
        public Id guid() { return guid; }
        public Model currentInstance() { return currentInstance; }
        public boolean defaultKeyformLocked() { return true; }
        public List<ParameterSource> allParameters() { return currentInstance.parameterSet.parameters.stream().map(Parameter::source).toList(); }
        public ParameterGroup rootParameterGroup() { return rootGroup; }
        public List<ArtMeshSource> allArtMeshes() { return artMeshes; }
        public List<ObjectSource> allDeformers() { return deformers; }
    }
    public static final class Model {
        final ParameterSet parameterSet;
        final java.util.List<ArtMesh> artMeshes = new java.util.ArrayList<>();
        final java.util.List<Deformer> deformers = new java.util.ArrayList<>();
        ModelSource source;
        Model(ParameterSet parameterSet) { this.parameterSet = parameterSet; }
        public ModelSource source() { return source; }
        public ParameterSet parameterSet() { return parameterSet; }
        public List<ArtMesh> allArtMeshes() { return artMeshes; }
        public List<Deformer> allDeformers() { return deformers; }
    }
    public static final class ParameterSet {
        final List<Parameter> parameters;
        ParameterSet(List<Parameter> parameters) { this.parameters = parameters; }
        public List<Parameter> parameters() { return parameters; }
    }
    public static final class Parameter {
        final Id id; final ParameterSource source; float value;
        Parameter(String id, float value) { this.id = new Id(id); this.source = new ParameterSource(); this.value = value; }
        public Id id() { return id; }
        public float value() { return value; }
        public ParameterSource source() { return source; }
    }
    public static final class ParameterSource extends ObjectSource {
        String name = "Angle X";
        ParameterSource() { super("ParamAngleX"); }
        @Override public Id id() { return super.id(); }
        public float minimum() { return -30.0F; }
        public float maximum() { return 30.0F; }
        public float defaultValue() { return 0.0F; }
        public String name() { return name; }
        public boolean repeat() { return true; }
        public boolean morphTarget() { return true; }
        public boolean combined() { return true; }
    }
    public static final class ParameterGroup {
        private static int idReads;
        final Id id;
        final String name;
        final ParameterGroup parent;
        final LabelColor labelColor = new LabelColor(new HostColor(0.25F, 0.5F, 0.75F, 1.0F));
        final java.util.ArrayList<Object> children = new java.util.ArrayList<>();
        ParameterGroup(String id, String name, ParameterGroup parent) {
            this.id = new Id(id);
            this.name = name;
            this.parent = parent;
        }
        static void resetIdReads() { idReads = 0; }
        static int idReads() { return idReads; }
        public Id id() { idReads++; return id; }
        public String name() { return name; }
        public ParameterGroup parent() { return parent; }
        public LabelColor labelColor() { return labelColor; }
        public List<Object> children() { return children; }
    }
    public record LabelColor(HostColor color) { public HostColor color() { return color; } }
    public record HostColor(float red, float green, float blue, float alpha) {
        public float red() { return red; }
        public float green() { return green; }
        public float blue() { return blue; }
        public float alpha() { return alpha; }
    }
    public record Id(String value) { public String value() { return value; } }
    static final class Fixture {
        final ModelSource source;
        final Document document;
        Fixture(String id, float value) {
            Model model = new Model(new ParameterSet(List.of(new Parameter("ParamAngleX", value))));
            source = new ModelSource(id, model);
            model.source = source;
            document = new Document(source);
        }
    }
    public static final class KeyformBinding {
        final Id parameterId = new Id("ParamAngleX");
        public Id parameterId() { return parameterId; }
        public List<Float> keys() { return List.of(-30.0F, 0.0F, 30.0F); }
    }
    public static final class KeyformGrid {
        public List<KeyformBinding> bindings() { return List.of(new KeyformBinding()); }
    }
    public static class ObjectSource {
        final Id id;
        final KeyformGrid keyformGrid = new KeyformGrid();
        ObjectSource(final String id) { this.id = new Id(id); }
        public Id id() { return id; }
        public String localName() { return id.value(); }
        public boolean visible() { return true; }
        public boolean locked() { return false; }
        public boolean visibleInHierarchy() { return true; }
        public boolean lockedInHierarchy() { return false; }
        public KeyformGrid keyformGrid() { return keyformGrid; }
    }
    public static final class ArtMeshSource extends ObjectSource { ArtMeshSource(String id) { super(id); } }
    public static final class WarpSource extends ObjectSource { WarpSource(String id) { super(id); } }
    public static final class RotationSource extends ObjectSource { RotationSource(String id) { super(id); } }
    public static class Form { public float opacity() { return 1.0F; } }
    public static final class ArtMesh {
        final ArtMeshSource source;
        ArtMesh(ArtMeshSource source) { this.source = source; }
        public ArtMeshSource source() { return source; }
        public Form currentForm() { return new Form(); }
    }
    public static class Deformer {
        final ObjectSource source;
        Deformer(ObjectSource source) { this.source = source; }
        public ObjectSource source() { return source; }
        public Form currentForm() { return new Form(); }
    }
    public static final class Warp extends Deformer { Warp(WarpSource source) { super(source); } }
    public static final class Rotation extends Deformer { Rotation(RotationSource source) { super(source); } }
}
