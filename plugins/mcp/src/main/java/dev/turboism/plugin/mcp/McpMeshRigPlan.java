package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.*;
import java.util.*;

/** Native snapshot adapter for the shared stored-key landmark planner. */
final class McpMeshRigPlan {
    static final int MAX_POINTS = McpLandmarkRigPlan.MAX_POINTS;

    static List<Map<String, Object>> edits(ArtMeshAuthoringSnapshot snapshot, List<McpLandmarkRigPlan.Axis> input) {
        return edits(snapshot, input, List.of());
    }

    static List<Map<String, Object>> edits(
            ArtMeshAuthoringSnapshot snapshot,
            List<McpLandmarkRigPlan.Axis> input,
            List<McpLandmarkRigPlan.Corner> corners) {
        var axes = snapshot.axes().stream()
                .map(a -> new McpLandmarkRigPlan.NativeAxis(
                        a.parameterGuid(), a.minimum(), a.defaultValue(), a.maximum(), a.keys()))
                .toList();
        var cells = snapshot.cells().stream()
                .map(c -> new McpLandmarkRigPlan.NativeCell(
                        c.formGuid(),
                        c.coordinates().stream()
                                .map(v -> new McpLandmarkRigPlan.NativeCoordinate(v.parameterGuid(), v.value()))
                                .toList()))
                .toList();
        var forms = new HashMap<String, List<Point2>>();
        snapshot.forms().forEach(f -> forms.put(f.formGuid(), f.positions()));
        var edits = McpLandmarkRigPlan.edits(axes, cells, forms, input, corners, "positions");
        McpKeyformWritePlan.prepare(snapshot, edits);
        return edits;
    }
}
