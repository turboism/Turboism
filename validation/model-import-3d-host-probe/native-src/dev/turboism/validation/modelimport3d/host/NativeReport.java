package dev.turboism.validation.modelimport3d.host;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Task-local observations only. Runner/Supervisor separately decide exact-host lifecycle. */
final class NativeReport {
    static final List<String> DEFERRED = List.of("morphCreation", "basicIdentity",
        "absoluteVsDelta", "targetWrite", "atomicImport", "dirty", "saveReopen", "atlasRepack",
        "pluginDisable", "hostLifecycle");
    final Map<String, Object> data = new LinkedHashMap<>();
    private final List<Map<String, Object>> checks = new ArrayList<>();
    final Map<String, Object> ordinaryWrite = new LinkedHashMap<>();
    final Map<String, Object> evaluation = new LinkedHashMap<>();
    final Map<String, Object> order = new LinkedHashMap<>();
    final Map<String, Object> morph = new LinkedHashMap<>();
    final Map<String, Object> morphOrder = new LinkedHashMap<>();
    final Map<String, Object> textureBinding = new LinkedHashMap<>();
    final Map<String, Object> pixelSampling = new LinkedHashMap<>();
    final Map<String, Object> shapeImport = new LinkedHashMap<>();
    final Map<String, Object> wholeImport = new LinkedHashMap<>();
    final Map<String, Object> cubeRender = new LinkedHashMap<>();
    final Map<String, Object> cubeGeometry = new LinkedHashMap<>();
    final Map<String, Object> jointPose = new LinkedHashMap<>();
    final Map<String, Object> cubeExport = new LinkedHashMap<>();
    final Map<String, Object> cubeAtlas = new LinkedHashMap<>();
    final Map<String, Object> cubePersistence = new LinkedHashMap<>();
    final Map<String, Object> persistenceSave = new LinkedHashMap<>();
    final Map<String, Object> persistenceReopen = new LinkedHashMap<>();

    NativeReport(String runId) {
        data.put("schemaVersion", "mi3d-native-n2-v1"); data.put("runId", runId);
        data.put("scope", "N2_BOOTSTRAP_CREATE_GRID_READ");
        data.put("stageAStatus", "NOT_EVALUATED"); data.put("requestedVersion", "5302");
        data.put("hostIdentity", null); // Never promoted from a supplied JVM property.
        data.put("checks", checks);
        data.put("deferred", DEFERRED.stream().map(id -> Map.of("id", id, "status", "NOT_RUN",
            "reason", "Outside N2; stage A or external supervisor evidence is still required")).toList());
        ordinaryWrite.put("schemaVersion", "mi3d-native-n3a-v1");
        ordinaryWrite.put("status", "NOT_RUN"); ordinaryWrite.put("failure", null);
        ordinaryWrite.put("evaluationStatus", "NOT_RUN");
        ordinaryWrite.put("coordinateSpace", "EDITOR_STORED_FORM"); ordinaryWrite.put("checks", List.of());
        data.put("ordinaryWrite", ordinaryWrite);
        evaluation.put("schemaVersion", "mi3d-native-n3b-v1");
        evaluation.put("status", "NOT_RUN"); evaluation.put("failure", null);
        evaluation.put("coordinateSpace", "EDITOR_CALCULATED_ROOT_PIXELS");
        evaluation.put("checks", List.of()); evaluation.put("samples", List.of());
        data.put("evaluation", evaluation);
        order.put("schemaVersion", "mi3d-native-n3c-v1");
        order.put("status", "NOT_RUN"); order.put("failure", null);
        order.put("coordinateSpace", "EDITOR_CALCULATED_ROOT_PIXELS");
        order.put("checks", List.of()); order.put("samples", List.of());
        data.put("order", order);
        morph.put("schemaVersion", "mi3d-native-n4a-v1");
        morph.put("status", "NOT_RUN"); morph.put("failure", null);
        morph.put("coordinateSpace", "EDITOR_CALCULATED_ROOT_PIXELS");
        morph.put("checks", List.of()); morph.put("samples", List.of());
        data.put("morph", morph);
        morphOrder.put("schemaVersion", "mi3d-native-n4b-v2");
        morphOrder.put("status", "NOT_RUN"); morphOrder.put("failure", null);
        morphOrder.put("coordinateSpace", "EDITOR_CALCULATED_ROOT_PIXELS");
        morphOrder.put("checks", List.of()); morphOrder.put("samples", List.of());
        data.put("morphOrder", morphOrder);
        textureBinding.put("schemaVersion", "mi3d-native-n5a-v1");
        textureBinding.put("status", "NOT_RUN"); textureBinding.put("failure", null);
        textureBinding.put("checks", List.of()); textureBinding.put("samples", List.of());
        data.put("textureBinding", textureBinding);
        pixelSampling.put("schemaVersion", "mi3d-native-t008-v1");
        pixelSampling.put("status", "NOT_RUN"); pixelSampling.put("failure", null);
        pixelSampling.put("coordinateSpace", "NATIVE_1024_OUTPUT_ARGB");
        pixelSampling.put("checks", List.of()); pixelSampling.put("frames", List.of());
        data.put("pixelSampling", pixelSampling);
        shapeImport.put("schemaVersion", "mi3d-native-b0-v1");
        shapeImport.put("status", "NOT_RUN"); shapeImport.put("failure", null);
        shapeImport.put("coordinateSpace", "EDITOR_STORED_FORM");
        shapeImport.put("checks", List.of());
        data.put("shapeImport", shapeImport);
        cubeGeometry.put("schemaVersion", "mi3d-cube-geometry-v1");
        cubeGeometry.put("status", "NOT_RUN"); cubeGeometry.put("failure", null);
        data.put("cubeGeometry", cubeGeometry);
        wholeImport.put("schemaVersion", "mi3d-whole-import-v1");
        wholeImport.put("status", "NOT_RUN"); wholeImport.put("failure", null);
        data.put("wholeImport", wholeImport);
        cubeRender.put("schemaVersion", "mi3d-cube-render-v1");
        cubeRender.put("status", "NOT_RUN"); cubeRender.put("failure", null);
        data.put("cubeRender", cubeRender);
        jointPose.put("schemaVersion", "mi3d-native-joint-v1");
        jointPose.put("status", "NOT_RUN"); jointPose.put("failure", null);
        data.put("jointPose", jointPose);
        cubePersistence.put("schemaVersion", "mi3d-cube-persistence-v1");
        cubePersistence.put("status", "NOT_RUN"); cubePersistence.put("failure", null);
        data.put("cubePersistence", cubePersistence);
        cubeAtlas.put("status", "NOT_RUN");data.put("cubeAtlas",cubeAtlas);
        cubeExport.put("status", "NOT_RUN");data.put("cubeExport",cubeExport);
        persistenceSave.put("schemaVersion", "mi3d-t014-save-v1");
        persistenceSave.put("status", "NOT_RUN"); persistenceSave.put("failure", null);
        persistenceSave.put("checks", List.of());
        data.put("persistenceSave", persistenceSave);
        persistenceReopen.put("schemaVersion", "mi3d-t014-reopen-v1");
        persistenceReopen.put("status", "NOT_RUN"); persistenceReopen.put("failure", null);
        persistenceReopen.put("checks", List.of()); persistenceReopen.put("frames", List.of());
        data.put("persistenceReopen", persistenceReopen);
    }

    void check(String id, boolean actual, String expected) {
        if (checks.stream().anyMatch(c -> id.equals(c.get("id")))) throw new IllegalArgumentException("duplicate check " + id);
        checks.add(Map.of("id", id, "expected", expected, "actual", actual,
            "status", actual ? "PASS" : "FAIL", "evidenceKind", "EXACT_HOST"));
        NativeAssertions.require(actual, id + ": " + expected);
    }

    void write(Path state, String status, Throwable failure) throws Exception {
        data.put("status", status);
        data.put("failure", failure == null ? null : failure.getClass().getName() + ": " + failure.getMessage());
        for (Path p = state.toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IllegalArgumentException("symlink state path");
        }
        Files.createDirectories(state);
        Path json = state.resolve("report.json"), terminal = state.resolve("result.properties");
        if (Files.exists(json, LinkOption.NOFOLLOW_LINKS) || Files.exists(terminal, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("refusing to replace task evidence");
        }
        Files.writeString(json, json(data) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(terminal, "status=" + status + "\nrunId=" + data.get("runId") + "\nstageAStatus=NOT_EVALUATED\n",
            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) {
            var out = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') out.append('\\').append(c);
                else if (c < 32) out.append(String.format("\\u%04x", (int)c));
                else out.append(c);
            }
            return out.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) return value.toString();
        if (value instanceof Float f && Float.isFinite(f)) return f.toString();
        if (value instanceof Double d && Double.isFinite(d)) return d.toString();
        if (value instanceof Map<?, ?> map) {
            return "{" + String.join(",", map.entrySet().stream().map(e -> json((String)e.getKey()) + ":" + json(e.getValue())).toList()) + "}";
        }
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(NativeReport::json).toList()) + "]";
        throw new IllegalArgumentException("unsupported report value " + value.getClass().getName());
    }
}
