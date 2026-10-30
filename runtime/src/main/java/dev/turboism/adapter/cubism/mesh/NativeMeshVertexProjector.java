package dev.turboism.adapter.cubism.mesh;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;

/** Native mesh-edit vertex coordinates, projected into the brush overlay component. */
final class NativeMeshVertexProjector {
    private NativeMeshVertexProjector() {}

    static List<Point2> project(final VerifiedMemberResolver resolver, final MeshEditSessionIdentity identity) {
        final float[] positions = positions(
                resolver, MeshToolSessionSelectorContract.EDITABLE_MESH_GL_POSITIONS, identity.editableMesh());
        final Object count =
                resolver.invoke(MeshToolSessionSelectorContract.EDITABLE_MESH_POINT_COUNT, identity.editableMesh());
        if (!(count instanceof Number number) || number.intValue() < 0) {
            throw new IllegalStateException("native editable-mesh point count is unavailable");
        }
        MeshVertexProjector.validate(positions, number.intValue());
        return projectPositions(resolver, identity, positions);
    }

    static List<Point2> projectPositions(
            final VerifiedMemberResolver resolver,
            final MeshEditSessionIdentity identity,
            final float[] meshPositions) {
        MeshVertexProjector.validate(meshPositions, meshPositions.length / 2);
        return MeshVertexProjector.project(
                canvasPositions(resolver, identity, meshPositions),
                meshPositions.length / 2,
                (x, y) -> projectCamera(resolver, identity.camera(), x, y));
    }

    static Point2 unprojectToMesh(
            final VerifiedMemberResolver resolver,
            final MeshEditSessionIdentity identity,
            final float componentX,
            final float componentY) {
        final Point2 document = unprojectCamera(resolver, identity.camera(), componentX, componentY);
        final float[] raw = new float[] {document.x(), document.y()};
        final float[] local = localPositions(resolver, identity, raw);
        return new Point2(local[0], local[1]);
    }

    static Point2 defaultMeshCenter(final VerifiedMemberResolver resolver, final MeshEditSessionIdentity identity) {
        final float[] positions = positions(
                resolver, MeshToolSessionSelectorContract.EDITABLE_MESH_GL_POSITIONS, identity.editableMesh());
        final Object count =
                resolver.invoke(MeshToolSessionSelectorContract.EDITABLE_MESH_POINT_COUNT, identity.editableMesh());
        if (count instanceof Number number && number.intValue() > 0 && positions.length >= 2) {
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            final int limit = Math.min(positions.length, number.intValue() * 2);
            for (int index = 0; index + 1 < limit; index += 2) {
                final float x = positions[index];
                final float y = positions[index + 1];
                if (Float.isFinite(x) && Float.isFinite(y)) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
            if (Float.isFinite(minX) && Float.isFinite(minY)) {
                return new Point2((minX + maxX) * 0.5f, (minY + maxY) * 0.5f);
            }
        }
        if (identity.component() instanceof javax.swing.JComponent component
                && component.getWidth() > 0
                && component.getHeight() > 0) {
            return unprojectToMesh(resolver, identity, component.getWidth() * 0.5f, component.getHeight() * 0.5f);
        }
        return new Point2(0.0f, 0.0f);
    }

    private static float[] canvasPositions(
            final VerifiedMemberResolver resolver, final MeshEditSessionIdentity identity, final float[] raw) {
        // Native lasso (Z$c.c / ac) hit-tests a Canvas copy of the staging mesh. Its converter
        // maps source triangles to the calculated form, except in source/image view modes.
        final Object mode =
                resolver.invoke(MeshToolSessionSelectorContract.MODELING_VIEW_MODE, identity.modelingView());
        if (mode != resolver.readStaticField(MeshToolSessionSelectorContract.VIEW_MODE_CURRENT_FORM)) {
            if (mode == resolver.readStaticField(MeshToolSessionSelectorContract.VIEW_MODE_SOURCE)
                    || mode == resolver.readStaticField(MeshToolSessionSelectorContract.VIEW_MODE_IMAGE)) {
                return raw;
            }
            throw new IllegalStateException("native mesh-edit view mode is unsupported");
        }
        final Object model =
                resolver.invoke(MeshToolSessionSelectorContract.MODELING_VIEW_MODEL, identity.modelingView());
        final Object id = resolver.invoke(MeshToolSessionSelectorContract.ARTMESH_SOURCE_ID, identity.artMesh());
        final Object artMesh = resolver.invoke(MeshToolSessionSelectorContract.MODEL_GET_OBJECT, model, id);
        if (!resolver.isInstance(MeshToolSessionSelectorContract.ARTMESH_CLASS, artMesh)
                || resolver.invoke(MeshToolSessionSelectorContract.ARTMESH_SOURCE, artMesh) != identity.artMesh()) {
            throw new IllegalStateException("native displayed ArtMesh does not match the mesh-edit source");
        }
        final float[] source =
                positions(resolver, MeshToolSessionSelectorContract.ARTMESH_SOURCE_POSITIONS, identity.artMesh());
        final Object form = resolver.invoke(MeshToolSessionSelectorContract.ARTMESH_CALCULATED_FORM, artMesh);
        final float[] current = positions(resolver, MeshToolSessionSelectorContract.ARTMESH_FORM_POSITIONS, form);
        MeshVertexProjector.validate(current, source.length / 2);
        final Object rawIndices =
                resolver.invoke(MeshToolSessionSelectorContract.ARTMESH_SOURCE_INDICES, identity.artMesh());
        if (!(rawIndices instanceof int[] indices) || indices.length % 3 != 0) {
            throw new IllegalStateException("native ArtMesh source triangles are unavailable");
        }
        for (int index : indices) {
            if (index < 0 || index >= source.length / 2) {
                throw new IllegalStateException("native ArtMesh source triangle index is out of range");
            }
        }
        final Object converter = resolver.construct(
                MeshToolSessionSelectorContract.MESH_COORDINATE_CONVERTER_CREATE, source, current, indices, 2);
        // The direct transform overload requires an output buffer (Kotlin's default helper
        // allocates one). Preserve staging-mesh order/count, including newly added vertices.
        return positions(resolver.invoke(
                MeshToolSessionSelectorContract.MESH_COORDINATE_CONVERTER_TRANSFORM,
                converter,
                raw,
                new float[raw.length]));
    }

    private static float[] positions(final VerifiedMemberResolver resolver, final String alias, final Object target) {
        return positions(resolver.invoke(alias, target));
    }

    private static float[] positions(final Object value) {
        if (!(value instanceof float[] positions)) {
            throw new IllegalStateException("native mesh vertex positions are unavailable");
        }
        MeshVertexProjector.validate(positions, positions.length / 2);
        return positions;
    }

    private static Point2 projectCamera(
            final VerifiedMemberResolver resolver, final Object camera, final float x, final float y) {
        final Object documentPoint = resolver.construct(MeshToolSessionSelectorContract.VECTOR_CREATE, x, y);
        final Object componentPoint =
                resolver.invoke(MeshToolSessionSelectorContract.CAMERA_DOCUMENT_TO_COMPONENT, camera, documentPoint);
        final Object rawX = resolver.invoke(MeshToolSessionSelectorContract.VECTOR_X, componentPoint);
        final Object rawY = resolver.invoke(MeshToolSessionSelectorContract.VECTOR_Y, componentPoint);
        if (!(rawX instanceof Number componentX) || !(rawY instanceof Number componentY)) {
            throw new IllegalStateException("camera projection coordinates are unavailable");
        }
        return new Point2(componentX.floatValue(), componentY.floatValue());
    }

    static Point2 unprojectCamera(
            final VerifiedMemberResolver resolver,
            final Object camera,
            final float componentX,
            final float componentY) {
        final Point2 origin = projectCamera(resolver, camera, 0.0f, 0.0f);
        final float step = 1024.0f;
        final Point2 unitX = projectCamera(resolver, camera, step, 0.0f);
        final Point2 unitY = projectCamera(resolver, camera, 0.0f, step);
        final float a11 = (unitX.x() - origin.x()) / step;
        final float a21 = (unitX.y() - origin.y()) / step;
        final float a12 = (unitY.x() - origin.x()) / step;
        final float a22 = (unitY.y() - origin.y()) / step;
        final float det = a11 * a22 - a12 * a21;
        if (!Float.isFinite(det) || Math.abs(det) < 1.0e-8f) {
            throw new IllegalStateException("camera projection matrix is singular");
        }
        final float dx = componentX - origin.x();
        final float dy = componentY - origin.y();
        final float docX = (dx * a22 - a12 * dy) / det;
        final float docY = (a11 * dy - dx * a21) / det;
        if (!Float.isFinite(docX) || !Float.isFinite(docY)) {
            throw new IllegalStateException("camera unprojection coordinates are not finite");
        }
        return new Point2(docX, docY);
    }

    private static float[] localPositions(
            final VerifiedMemberResolver resolver, final MeshEditSessionIdentity identity, final float[] canvas) {
        final Object mode =
                resolver.invoke(MeshToolSessionSelectorContract.MODELING_VIEW_MODE, identity.modelingView());
        if (mode != resolver.readStaticField(MeshToolSessionSelectorContract.VIEW_MODE_CURRENT_FORM)) {
            if (mode == resolver.readStaticField(MeshToolSessionSelectorContract.VIEW_MODE_SOURCE)
                    || mode == resolver.readStaticField(MeshToolSessionSelectorContract.VIEW_MODE_IMAGE)) {
                return canvas;
            }
            throw new IllegalStateException("native mesh-edit view mode is unsupported");
        }
        final Object model =
                resolver.invoke(MeshToolSessionSelectorContract.MODELING_VIEW_MODEL, identity.modelingView());
        final Object id = resolver.invoke(MeshToolSessionSelectorContract.ARTMESH_SOURCE_ID, identity.artMesh());
        final Object artMesh = resolver.invoke(MeshToolSessionSelectorContract.MODEL_GET_OBJECT, model, id);
        if (!resolver.isInstance(MeshToolSessionSelectorContract.ARTMESH_CLASS, artMesh)
                || resolver.invoke(MeshToolSessionSelectorContract.ARTMESH_SOURCE, artMesh) != identity.artMesh()) {
            throw new IllegalStateException("native displayed ArtMesh does not match the mesh-edit source");
        }
        final float[] source =
                positions(resolver, MeshToolSessionSelectorContract.ARTMESH_SOURCE_POSITIONS, identity.artMesh());
        final Object form = resolver.invoke(MeshToolSessionSelectorContract.ARTMESH_CALCULATED_FORM, artMesh);
        final float[] current = positions(resolver, MeshToolSessionSelectorContract.ARTMESH_FORM_POSITIONS, form);
        MeshVertexProjector.validate(current, source.length / 2);
        if (java.util.Arrays.equals(source, current)) {
            return canvas;
        }
        final Object rawIndices =
                resolver.invoke(MeshToolSessionSelectorContract.ARTMESH_SOURCE_INDICES, identity.artMesh());
        if (!(rawIndices instanceof int[] indices) || indices.length < 3 || indices.length % 3 != 0) {
            return canvas;
        }
        for (int index : indices) {
            if (index < 0 || index >= source.length / 2) {
                return canvas;
            }
        }
        try {
            final Object converter = resolver.construct(
                    MeshToolSessionSelectorContract.MESH_COORDINATE_CONVERTER_CREATE, current, source, indices, 2);
            final Object transformed = resolver.invoke(
                    MeshToolSessionSelectorContract.MESH_COORDINATE_CONVERTER_TRANSFORM,
                    converter,
                    canvas,
                    new float[canvas.length]);
            if (transformed instanceof float[] values && values.length == canvas.length) {
                boolean finite = true;
                for (float v : values) {
                    if (!Float.isFinite(v)) {
                        finite = false;
                        break;
                    }
                }
                if (finite) return values;
            }
        } catch (RuntimeException ignored) {
            // Fallback to identity when the point lies outside the deformed triangulation.
        }
        return canvas;
    }
}
