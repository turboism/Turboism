package com.live2d.graphics3d.editableMesh;
import com.live2d.util.j.a;
/** Host-named stub: triangulation pass. */
public class GEditableMesh2 {
    public int calls;
    private final void updateVertices() { calls++; }
    private final void updateIndices(a context) {
        calls++;
        final b helper = new b();
        helper.a(this, helper.b(this), true, context);
        new com.live2d.graphics3d.editableMesh.triangulation.g().a(this, context);
    }
    private final void updateMesh(a context, boolean full) {
        calls++;
        updateVertices();
        updateIndices(context);
    }
    public final void driveMesh(a context) { updateMesh(context, true); }
}
