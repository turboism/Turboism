package dev.turboism.validation.modelimport3d.host;

import static dev.turboism.validation.modelimport3d.host.SurfaceWarpAuthoringTest.proxy;
import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SurfaceRotationAuthoringTest {
    @Test
    void createsCompleteNativeScalarCircleAtProjectedPivotAndPreservesNeutralForm() {
        var state = new State();
        var request = request();
        var result = SurfaceRotationAuthoring.create(state.model(), null, request);
        assertSame(state.rotation, result);
        assertEquals(1, state.batches);
        assertEquals(
                List.of(new ParameterDefinition(
                        new ParameterId("Roll"), "Apple Z 旋转", -180, 0, 180, ParameterType.NORMAL, false)),
                state.definitions);
        var snapshot = result.authoringSnapshot();
        assertEquals(5, snapshot.forms().size());
        for (var cell : snapshot.cells()) {
            float key = cell.coordinates().get(0).value();
            var form = snapshot.forms().stream()
                    .filter(f -> f.formGuid().equals(cell.formGuid()))
                    .findFirst()
                    .orElseThrow()
                    .value();
            assertEquals(key == 0 ? 0 : -key, form.angle());
            assertEquals(16, form.originX());
            assertEquals(11, form.originY());
            assertEquals(1, form.scale());
        }
        assertEquals(
                new RotationDeformerForm(0, 16, 11, 1, false, false),
                snapshot.forms().get(2).value());
        assertEquals(4, state.lastEdits.size());
        assertTrue(
                state.lastEdits.stream().noneMatch(e -> e.coordinates().get(0).value() == 0));
    }

    @Test
    void existingRollAndDuplicateAxisIdsRefuseBeforeRotationCreation() {
        var state = new State();
        state.definitions.add(
                new ParameterDefinition(new ParameterId("Roll"), "Existing", -1, 0, 1, ParameterType.NORMAL, false));
        assertThrows(
                IllegalArgumentException.class, () -> SurfaceRotationAuthoring.create(state.model(), null, request()));
        assertEquals(0, state.creations);
        assertEquals(0, state.batches);
        assertThrows(
                IllegalArgumentException.class,
                () -> new SurfaceImport.Request(
                        "Apple",
                        new ParameterId("Yaw"),
                        new ParameterId("Pitch"),
                        request().camera(),
                        180,
                        180,
                        20000000,
                        Optional.of(new ParameterId("Yaw"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SurfaceImport.Request(
                        "Apple",
                        new ParameterId("Yaw"),
                        new ParameterId("Pitch"),
                        request().camera(),
                        181,
                        180,
                        20000000));
    }

    @Test
    void localizesRotationDeformerAndParameterNamesWithoutChangingIdsOrValues() throws Exception {
        var base = request();
        String[] locales = {"", "en", "ja", "ko", "zh_Hans", "zh_Hant"};
        for (String locale : locales) {
            var properties = new java.util.Properties();
            try (var in = getClass()
                    .getResourceAsStream("/META-INF/turboism/i18n/messages" + (locale.isEmpty() ? "" : "_" + locale)
                            + ".properties")) {
                assertNotNull(in);
                properties.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            }
            var labels = new java.util.HashMap<String, String>();
            for (String key : List.of(
                    "object.rotation.x",
                    "object.rotation.y",
                    "object.rotation.z",
                    "object.placement",
                    "object.warp",
                    "object.local-action")) {
                assertNotNull(properties.getProperty(key));
                labels.put(key, properties.getProperty(key));
            }
            var request = new SurfaceImport.Request(
                    base.name(),
                    base.yaw(),
                    base.pitch(),
                    base.camera(),
                    base.yawLimit(),
                    base.pitchLimit(),
                    base.maxProjectedVertices(),
                    base.roll(),
                    labels);
            var state = new State();
            SurfaceRotationAuthoring.create(state.model(), null, request);
            assertEquals("Apple " + labels.get("object.rotation.z"), state.rotationName);
            assertEquals(state.rotationName, state.definitions.get(0).name());
            assertEquals(base.roll().orElseThrow(), state.definitions.get(0).id());
            var file = ModelImportPlugin.requestForFile(request, "模型.glb", "scope");
            assertEquals("模型 " + labels.get("object.placement"), file.placementName());
            assertEquals(labels, file.labels());
            assertEquals(request.localActionName(2), file.localActionName(2));
            labels.put("object.rotation.z", "CHANGED");
            assertNotEquals("Apple CHANGED", request.rotationName("Z"));
        }
    }

    static SurfaceImport.Request request() {
        return new SurfaceImport.Request(
                "Apple",
                new ParameterId("Yaw"),
                new ParameterId("Pitch"),
                new SurfaceProjection.Camera(10, 20, 3, new SurfaceProjection.Vertex(2, 3, 4)),
                180,
                180,
                20000000,
                Optional.of(new ParameterId("Roll")));
    }

    static final class State {
        final List<ParameterDefinition> definitions = new ArrayList<>();
        final Map<String, RotationDeformerForm> values = new HashMap<>();
        RotationDeformerForm neutral = new RotationDeformerForm(0, 0, 0, 1, false, false);
        List<ParameterBindingPoint> keys = List.of();
        List<RotationKeyformForm> lastEdits = List.of();
        int batches, creations;
        String rotationName;
        final RotationDeformer rotation = proxy(RotationDeformer.class, (method, args) -> switch (method.getName()) {
            case "id" -> new DeformerId("roll");
            case "replaceForm" -> {
                neutral = (RotationDeformerForm) args[0];
                yield null;
            }
            case "authoringSnapshot" -> snapshot();
            case "setOrdinaryKeyformForms" -> {
                assertEquals(snapshot(), args[0]);
                lastEdits = List.copyOf((List<RotationKeyformForm>) args[1]);
                batches++;
                for (var edit : lastEdits) values.put(edit.formGuid(), edit.value());
                yield null;
            }
            default -> throw new AssertionError(method.toString());
        });

        RotationAuthoringSnapshot snapshot() {
            var axis = new RotationAuthoringSnapshot.Axis(
                    "axis",
                    new ParameterId("Roll"),
                    -180,
                    0,
                    180,
                    keys.stream().map(ParameterBindingPoint::value).toList());
            var cells = new ArrayList<RotationAuthoringSnapshot.Cell>();
            var forms = new ArrayList<RotationAuthoringSnapshot.Form>();
            for (int i = 0; i < keys.size(); i++) {
                String guid = "f" + i;
                cells.add(new RotationAuthoringSnapshot.Cell(
                        guid,
                        List.of(new RotationAuthoringSnapshot.Coordinate(
                                "axis", keys.get(i).value()))));
                forms.add(new RotationAuthoringSnapshot.Form(guid, values.getOrDefault(guid, neutral)));
            }
            return new RotationAuthoringSnapshot(new DeformerId("roll"), "source", 0, List.of(axis), cells, forms);
        }

        CubismModel model() {
            return proxy(CubismModel.class, (method, args) -> switch (method.getName()) {
                case "parameters" ->
                    proxy(Parameters.class, (m, a) -> switch (m.getName()) {
                        case "all" ->
                            definitions.stream()
                                    .map(d -> proxy(Parameter.class, (pm, pa) -> d.id()))
                                    .toList();
                        case "createMany" -> {
                            definitions.addAll((List<ParameterDefinition>) a[0]);
                            yield List.of();
                        }
                        default -> throw new AssertionError(m.toString());
                    });
                case "deformers" ->
                    proxy(Deformers.class, (m, a) -> {
                        assertEquals("createRotation", m.getName());
                        creations++;
                        rotationName = (String) a[0];
                        return rotation;
                    });
                case "parameterBindings" ->
                    proxy(ParameterBindingOperations.class, (m, a) -> {
                        assertEquals("bind", m.getName());
                        assertEquals(ParameterBindingTarget.rotationDeformer(new DeformerId("roll")), a[0]);
                        keys = List.copyOf((List<ParameterBindingPoint>) a[1]);
                        return null;
                    });
                default -> throw new AssertionError(method.toString());
            });
        }
    }
}
