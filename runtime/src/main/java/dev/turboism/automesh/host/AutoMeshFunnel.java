package dev.turboism.automesh.host;

/**
 * The two native Auto Mesh Generate entry points a dispatch guard protects.
 *
 * <p>Every popup of the Editor funnels into exactly one of these two methods, so one guard per
 * funnel covers all three popups. The identity travels to the runtime as a value instead of a
 * class reference, because transformed host bytecode may name only JDK types.</p>
 */
public enum AutoMeshFunnel {

    /** {@code AutoMeshUtil.updateMeshFromInspector_inModelingMode}: the modeling-mode popups. */
    MODELING_MODE("modeling-mode"),

    /** {@code AutoMeshUtil_InMeshEditor.updateMeshFromInspector_inMeshEditorMode}: mesh editor. */
    MESH_EDITOR_MODE("mesh-editor-mode");

    private final String token;

    AutoMeshFunnel(final String token) {
        this.token = token;
    }

    /** @return the stable log and diagnostic token of this funnel */
    public String token() {
        return token;
    }
}
