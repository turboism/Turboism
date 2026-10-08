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
        return MeshVertexProjector.project(
                canvasPositions(resolver, identity, positions),
                number.intValue(),
                (x, y) -> projectCamera(resolver, identity.camera(), x, y));
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
}
