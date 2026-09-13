package com.live2d.graphics3d.editableMesh;
import com.live2d.util.j.a;
/** Host-named stub: triangulation pass. */
public class GEditableMesh2 {
    public int calls;
    private final void updateMesh(a context, boolean full) { calls++; }
    public final void driveMesh(a context) { updateMesh(context, true); }
}
