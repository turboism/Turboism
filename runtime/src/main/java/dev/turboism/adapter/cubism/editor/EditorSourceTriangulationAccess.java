package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorSourceTriangulationSelectorContract;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.ArtMeshSourceTriangulation;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/** No source, document, selection, evaluator or Undo object is passed to this engine. */
final class EditorSourceTriangulationAccess {
    private final VerifiedMemberResolver resolver;

    EditorSourceTriangulationAccess(VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver);
    }

    ArtMeshSourceTriangulation preview(ArtMeshGeometry geometry) {
        Objects.requireNonNull(geometry, "geometry");
        if (!EditorReviewedGeneration.admits(resolver)
                || !resolver.authorizesFeature(
                        EditorSourceTriangulationSelectorContract.ADAPTER_SLICE_ID,
                        EditorSourceTriangulationSelectorContract.CAPABILITY_ID,
                        EditorSourceTriangulationSelectorContract.REQUIRED_ALIASES)) {
            throw new UnsupportedOperationException("Verified source triangulation is unavailable.");
        }
        final float[] xy = new float[Math.multiplyExact(geometry.positions().size(), 2)];
        for (int i = 0; i < geometry.positions().size(); i++) {
            xy[i * 2] = geometry.positions().get(i).x();
            xy[i * 2 + 1] = geometry.positions().get(i).y();
        }
        final int[] indices =
                geometry.triangleIndices().stream().mapToInt(Integer::intValue).toArray();
        final Object mesh = resolver.construct("cubism.editor-model.editable-mesh.create");
        if (!resolver.isInstance("cubism.editor-model.editable-mesh.class", mesh)) {
            throw new IllegalStateException("Native source triangulator construction failed.");
        }
        resolver.invoke(
                "cubism.editor-model.editable-mesh.init-by-indices",
                mesh,
                xy,
                indices,
                resolver.invokeStatic("cubism.editor-model.coord-type.canvas"));
        final Object result = resolver.invokeStatic(
                "cubism.editor-model.editable-mesh.gl-indices-default",
                mesh,
                null,
                Boolean.FALSE,
                Integer.valueOf(3),
                null);
        final Object positions = resolver.invoke("cubism.editor-model.editable-mesh.gl-positions", mesh);
        if (!(result instanceof int[] output) || !(positions instanceof float[] points) || points.length % 2 != 0) {
            throw new IllegalStateException("Native source triangulator returned invalid arrays.");
        }
        final var owned = new ArrayList<Point2>(points.length / 2);
        for (int i = 0; i < points.length; i += 2) owned.add(new Point2(points[i], points[i + 1]));
        return new ArtMeshSourceTriangulation(
                owned, Arrays.stream(output).boxed().toList());
    }
}
