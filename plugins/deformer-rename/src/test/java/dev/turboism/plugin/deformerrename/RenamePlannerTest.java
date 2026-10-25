package dev.turboism.plugin.deformerrename;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
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
    void recursivelyUsesLeafNamesAndOwnBindingsWithoutWriting() {
        var parent = deformer(WarpDeformer.class, "parent", "头 变形", null, List.of("px", "py"));
        var child = deformer(RotationDeformer.class, "child", "旧旋转", "parent", List.of());
        var empty = deformer(WarpDeformer.class, "empty", "空变形", null, List.of("px"));
        var bindings = Map.of("px", parameter("头部X"), "py", parameter("头部Y"));
        var model =
                model(List.of(parent, child, empty), List.of(mesh("头发1", "child"), mesh("头发1 阴影", "child")), bindings);
        var rows = RenamePlanner.preview(model);
        assertEquals(3, rows.size());
        assertEquals("头发1 头部XY", rows.get(0).target());
        assertEquals("头发1", rows.get(1).target());
        assertTrue(rows.get(1).rotation());
        assertEquals("空变形", rows.get(2).target());
        assertFalse(rows.get(2).changed());
        assertEquals(rows, RenamePlanner.preview(model));
    }

    @Test
    void detectsHierarchyCycles() {
        var a = deformer(WarpDeformer.class, "a", "a", "b", List.of());
        var b = deformer(WarpDeformer.class, "b", "b", "a", List.of());
        assertThrows(
                IllegalStateException.class, () -> RenamePlanner.preview(model(List.of(a, b), List.of(), Map.of())));
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

    private static <T extends Deformer> T deformer(
            Class<T> type, String id, String name, String parent, List<String> parameters) {
        return stub(type, (method, args) -> switch (method) {
            case "id" -> new DeformerId(id);
            case "name" -> name;
            case "parentDeformerId" -> Optional.ofNullable(parent).map(DeformerId::new);
            case "getParameterBindings" ->
                parameters.stream()
                        .map(p -> new ParameterBinding(
                                ParameterBindingTarget.warpDeformer(new DeformerId(id)),
                                new ParameterId(p),
                                ParameterBindingFamily.KEYFORM_GRID,
                                List.of()))
                        .toList();
            default -> throw new AssertionError("Unexpected deformer access: " + method);
        });
    }

    private static Drawable mesh(String name, String parent) {
        return stub(Drawable.class, (method, args) -> switch (method) {
            case "name" -> name;
            case "parentDeformerId" -> Optional.of(new DeformerId(parent));
            default -> throw new AssertionError(method);
        });
    }

    private static Parameter parameter(String name) {
        return stub(Parameter.class, (method, args) -> Optional.of(name));
    }

    private static CubismModel model(
            List<Deformer> deformers, List<Drawable> meshes, Map<String, Parameter> parameters) {
        return stub(CubismModel.class, (method, args) -> switch (method) {
            case "deformers" ->
                stub(
                        Deformers.class,
                        (m, a) -> deformers.stream()
                                .map(d -> stub(Deformer.class, (dm, da) -> switch (dm) {
                                    case "id" -> d.id();
                                    case "name" -> d.name();
                                    case "parentDeformerId" -> d.parentDeformerId();
                                    case "getParameterBindings" -> d.getParameterBindings();
                                    default -> throw new AssertionError(dm);
                                }))
                                .toList());
            case "warpDeformers" ->
                stub(
                        WarpDeformers.class,
                        (m, a) -> deformers.stream()
                                .filter(WarpDeformer.class::isInstance)
                                .map(WarpDeformer.class::cast)
                                .toList());
            case "rotationDeformers" ->
                stub(
                        RotationDeformers.class,
                        (m, a) -> deformers.stream()
                                .filter(RotationDeformer.class::isInstance)
                                .map(RotationDeformer.class::cast)
                                .toList());
            case "drawables" -> stub(Drawables.class, (m, a) -> meshes);
            case "parameters" -> stub(Parameters.class, (m, a) -> parameters.get(((ParameterId) a[0]).value()));
            default -> throw new AssertionError(method);
        });
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
