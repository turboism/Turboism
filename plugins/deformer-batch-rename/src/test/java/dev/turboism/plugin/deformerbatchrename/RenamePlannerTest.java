package dev.turboism.plugin.deformerbatchrename;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.ModelNamingIndex;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;

class RenamePlannerTest {
    @Test
    void commonPrefixAndShortestFallback() {
        assertEquals("头发1", RenamePlanner.baseName(List.of("头发1", "头发1 阴影")));
        assertEquals("头发", RenamePlanner.baseName(List.of("头发1", "头发2")));
        assertEquals("左眼", RenamePlanner.baseName(List.of("左眼", "右眼")));
        assertEquals("眼", RenamePlanner.baseName(List.of("头发", "眼")));
        assertEquals("", RenamePlanner.baseName(List.of("  ", "")));
        assertEquals("😀", RenamePlanner.baseName(List.of("😀", "😁x")));
    }

    @Test
    void combinesOnlyMatchingAxisGroups() {
        assertEquals("头部XY", RenamePlanner.parameterSuffix(List.of("头部X", "头部Y", "头部X")));
        assertEquals("头部XY 呼吸 身体Z", RenamePlanner.parameterSuffix(List.of("头部X", "呼吸", "头部Y", "身体Z")));
        assertEquals("ParamAngleXY", RenamePlanner.parameterSuffix(List.of("ParamAngleX", "ParamAngleY")));
        assertEquals("", RenamePlanner.parameterSuffix(List.of()));
    }

    @Test
    void recursivelyUsesLeafNamesAndOwnBindings() {
        var index = index(
                List.of(parameter("px", "头部X"), parameter("py", "头部Y")),
                List.of(),
                List.of(
                        deformer("parent", "头 变形", false, null, List.of("px", "py")),
                        deformer("child", "旧旋转", true, "parent", List.of()),
                        deformer("empty", "空变形", false, null, List.of("px"))),
                List.of(mesh("头发1", "child", null), mesh("头发1 阴影", "child", null)));
        var rows = RenamePlanner.rows(index, RenamePlanner.BaseNameMode.MINIMAL_COMMON);
        assertEquals(3, rows.size());
        assertEquals("头发1 头部XY", rows.get(0).target());
        assertEquals("头发1", rows.get(1).target());
        assertTrue(rows.get(1).rotation());
        assertEquals("空变形", rows.get(2).target());
        assertFalse(rows.get(2).changed());
        assertEquals(rows, RenamePlanner.rows(index, RenamePlanner.BaseNameMode.MINIMAL_COMMON));
    }

    @Test
    void partModeUsesThePartThatOwnsMostLeafMeshes() {
        var index = index(
                List.of(),
                List.of(part("p-face", "脸"), part("p-hair", "头发")),
                List.of(deformer("d1", "旧", false, null, List.of())),
                List.of(mesh("a", "d1", "p-face"), mesh("b", "d1", "p-face"), mesh("c", "d1", "p-hair")));
        var rows = RenamePlanner.rows(index, RenamePlanner.BaseNameMode.PART_NAME);
        assertEquals("脸", rows.get(0).target());
    }

    @Test
    void partModeFallsBackToLeafNamesWhenNoLeafCarriesAPart() {
        var index = index(
                List.of(),
                List.of(part("p-face", "脸")),
                List.of(deformer("d1", "旧", false, null, List.of())),
                List.of(mesh("头发1", "d1", null), mesh("头发2", "d1", null)));
        assertEquals(
                "头发",
                RenamePlanner.rows(index, RenamePlanner.BaseNameMode.PART_NAME)
                        .get(0)
                        .target());
    }

    @Test
    void previewDerivesBothModesFromOneIndex() {
        var index = index(
                List.of(),
                List.of(part("p-face", "脸")),
                List.of(deformer("d1", "旧", false, null, List.of())),
                List.of(mesh("头发1", "d1", "p-face")));
        var preview = RenamePlanner.preview(index);
        assertSame(index, preview.index());
        assertEquals(
                "脸", preview.rows(RenamePlanner.BaseNameMode.PART_NAME).get(0).target());
        assertEquals(
                "头发1",
                preview.rows(RenamePlanner.BaseNameMode.MINIMAL_COMMON).get(0).target());
    }

    @Test
    void fallsBackToParameterIdWhenBoundParameterIsMissingFromCatalog() {
        var index = index(
                List.of(),
                List.of(),
                List.of(deformer("d1", "旧变形", false, null, List.of("ParamAngleX", "ParamAngleY"))),
                List.of(mesh("头发", "d1", null)));
        var rows = RenamePlanner.rows(index, RenamePlanner.BaseNameMode.MINIMAL_COMMON);
        assertEquals(1, rows.size());
        assertEquals("头发 ParamAngleXY", rows.get(0).target());
    }

    @Test
    void detectsHierarchyCycles() {
        var index = index(
                List.of(),
                List.of(),
                List.of(deformer("a", "a", false, "b", List.of()), deformer("b", "b", false, "a", List.of())),
                List.of());
        assertThrows(
                IllegalStateException.class,
                () -> RenamePlanner.rows(index, RenamePlanner.BaseNameMode.MINIMAL_COMMON));
    }

    @Test
    void previewUsesTypedNativeIconsAndDisablesUnchangedRows() {
        var changed = new RenamePlanner.Row(new DeformerId("a"), false, "old", "new");
        var unchanged = new RenamePlanner.Row(new DeformerId("b"), true, "same", "same");
        PluginLocalization text =
                stub(PluginLocalization.class, (method, args) -> method.equals("text") ? args[0] : null);
        var request = RenameDialog.request(text, List.of(changed, unchanged));
        assertEquals(
                dev.turboism.sdk.ui.resource.CubismIcon.WARP_DEFORMER,
                request.rows().get(0).icon().icon());
        assertEquals(
                dev.turboism.sdk.ui.resource.CubismIcon.ROTATION_DEFORMER,
                request.rows().get(1).icon().icon());
        assertTrue(request.rows().get(0).selected());
        assertFalse(request.rows().get(1).selectable());
        assertFalse(request.rows().get(1).selected());
    }

    private static ModelNamingIndex index(
            List<ModelNamingIndex.ParameterEntry> parameters,
            List<ModelNamingIndex.PartEntry> parts,
            List<ModelNamingIndex.DeformerEntry> deformers,
            List<ModelNamingIndex.ArtMeshEntry> meshes) {
        return new ModelNamingIndex(parameters, parts, deformers, meshes);
    }

    private static ModelNamingIndex.ParameterEntry parameter(String id, String name) {
        return new ModelNamingIndex.ParameterEntry(new ParameterId(id), name);
    }

    private static ModelNamingIndex.PartEntry part(String id, String name) {
        return new ModelNamingIndex.PartEntry(new PartId(id), name);
    }

    private static ModelNamingIndex.DeformerEntry deformer(
            String id, String name, boolean rotation, String parent, List<String> parameters) {
        return new ModelNamingIndex.DeformerEntry(
                new DeformerId(id),
                name,
                rotation,
                Optional.ofNullable(parent).map(DeformerId::new),
                parameters.stream().map(ParameterId::new).toList());
    }

    private static ModelNamingIndex.ArtMeshEntry mesh(String name, String parentDeformer, String parentPart) {
        return new ModelNamingIndex.ArtMeshEntry(
                new ArtMeshId(name),
                name,
                Optional.ofNullable(parentDeformer).map(DeformerId::new),
                Optional.ofNullable(parentPart).map(PartId::new));
    }

    private interface Call {
        Object invoke(String method, Object[] args);
    }

    private static <T> T stub(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (p, method, args) -> call.invoke(method.getName(), args)));
    }
}
