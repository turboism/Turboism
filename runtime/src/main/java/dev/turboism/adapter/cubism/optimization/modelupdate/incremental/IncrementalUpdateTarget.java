package dev.turboism.adapter.cubism.optimization.modelupdate.incremental;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One reviewed incremental-update target per admitted Cubism Editor artifact.
 *
 * <p>Slice B narrows the full update instead of skipping it: the updater singleton's
 * {@code optimize_skipInterpolationIfParameterNotUpdated} flag (obfuscated static field
 * {@code e}, written by {@code b(Z)}) gates each object's interpolation on the host's own
 * per-keyform parameter diff, while two rewritten call sites make deformer dirty marking
 * conditional and let unchanged ArtMeshes keep their previous {@code deformedForm}. The
 * method and call-site shapes below were reviewed against the official artifacts; a digest
 * outside {@link ReviewedHostArtifacts} is not a target and fails closed.</p>
 */
public record IncrementalUpdateTarget(
        HostArtifactDigest digest,
        String version,
        String updater,
        String updateContext,
        String contextParam) {

    /** Reviewed dependency method identity, identical to the slice-A dep record. */
    public record Dep(String owner, String name, String descriptor) {
        /** Canonical dependency method identity. */
        public Dep {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    private static final String CM = "com.live2d.cubism.doc.model.CModel";
    private static final String PS = "com.live2d.cubism.doc.model.param.CParameterSet";
    private static final String CP = "com.live2d.cubism.doc.model.param.CParameter";
    private static final String DOC = "com.live2d.cubism.doc.modeling.CModelingDocument";
    private static final String CX = "com.live2d.cubism.view.context.CEViewContext";
    private static final String ACD = "com.live2d.cubism.doc.model.deformer.ACDeformer";
    private static final String CAM = "com.live2d.cubism.doc.model.drawable.artMesh.CArtMesh";
    private static final String MF = "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm";
    private static final String DR = "com.live2d.cubism.doc.model.drawable.ACDrawable";
    private static final String PF = "com.live2d.cubism.doc.model.drawable.artPath.CArtPathForm";
    private static final String PP = "com.live2d.cubism.doc.model.drawable.artPath.CArtPathPoint";
    private static final String SP = "com.live2d.graphics.splineCurve.CSplineCurvePoint";
    private static final String GV = "com.live2d.graphics3d.type.GVector2";
    private static final String TR = "com.live2d.doc.selection.d";

    /** Concrete {@code ACDeformerForm} subclass owning rotation interpolation. */
    public static final String ROTATION_FORM =
        "com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerForm";
    /** Concrete {@code ACDeformerForm} subclass owning warp interpolation. */
    public static final String WARP_FORM =
        "com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerForm";
    /** Obfuscated {@code CArtMeshForm} owner of the mesh interpolation body. */
    public static final String MESH_FORM =
        "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm";

    private static final IncrementalUpdateTarget CUBISM_5203 = new IncrementalUpdateTarget(
        ReviewedHostArtifacts.CUBISM_5_2_03, "5.2.03",
        "com/live2d/cubism/view/au",
        "com/live2d/cubism/doc/model/ay",
        "com/live2d/cubism/view/context/bL");

    private static final IncrementalUpdateTarget CUBISM_5302 = new IncrementalUpdateTarget(
        ReviewedHostArtifacts.CUBISM_5_3_02, "5.3.02",
        "com/live2d/cubism/view/ay",
        "com/live2d/cubism/doc/model/ax",
        "com/live2d/cubism/view/context/bK");

    private static final IncrementalUpdateTarget CUBISM_5303 = new IncrementalUpdateTarget(
        ReviewedHostArtifacts.CUBISM_5_3_03, "5.3.03",
        "com/live2d/cubism/view/ay",
        "com/live2d/cubism/doc/model/ax",
        "com/live2d/cubism/view/context/bL");

    /** The reviewed target for a host artifact digest, or empty when unsupported. */
    public static Optional<IncrementalUpdateTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        if (CUBISM_5203.digest().equals(digest)) return Optional.of(CUBISM_5203);
        if (CUBISM_5302.digest().equals(digest)) return Optional.of(CUBISM_5302);
        if (CUBISM_5303.digest().equals(digest)) return Optional.of(CUBISM_5303);
        return Optional.empty();
    }

    /** Every reviewed target, oldest supported version first. */
    public static List<IncrementalUpdateTarget> all() {
        return List.of(CUBISM_5203, CUBISM_5302, CUBISM_5303);
    }

    private String t(final String dotted) {
        return "L" + dotted.replace('.', '/') + ";";
    }

    /** {@code a(CModel, List, UC, CEViewContext, bL|bK)V} — per-update frame boundary. */
    public String coreDescriptor() {
        return "(" + t(CM) + "Ljava/util/List;" + t(updateContext) + t(CX)
            + t(contextParam) + ")V";
    }

    /** {@code a(CModel, CParameterSet, List<ACDeformer>, UC, CEViewContext, bL|bK)V}. */
    public String deformerUpdateDescriptor() {
        return "(" + t(CM) + t(PS) + "Ljava/util/List;" + t(updateContext) + t(CX)
            + t(contextParam) + ")V";
    }

    /** {@code a(CModel, CParameterSet, CArtMesh, UC, CEViewContext, bL|bK)V}. */
    public String artMeshUpdateDescriptor() {
        return "(" + t(CM) + t(PS) + t(CAM) + t(updateContext) + t(CX)
            + t(contextParam) + ")V";
    }

    /** {@code ACDeformerForm.interpolate__testImpl(KeyformGridSource, CParameterSet, UC)V}. */
    public String deformerInterpolateDescriptor() {
        return "(Lcom/live2d/cubism/doc/model/interpolator/KeyformGridSource;"
            + t(PS) + t(updateContext) + ")V";
    }

    /** {@code CArtMeshForm.interpolate__testImpl(UC, KeyformGridSource, CParameterSet)V}. */
    public String meshInterpolateDescriptor() {
        return "(" + t(updateContext)
            + "Lcom/live2d/cubism/doc/model/interpolator/KeyformGridSource;"
            + t(PS) + ")V";
    }

    /** Binary {@code ACDeformer} name used to recognise the dirty-mark call site. */
    public String deformerBinary() {
        return ACD.replace('.', '/');
    }

    /** Binary {@code CArtMeshForm} name used to recognise the deform call site. */
    public String meshFormBinary() {
        return MF.replace('.', '/');
    }

    /**
     * The dependency methods the bridge resolves and the installer verifies against the
     * official artifact. Every entry must exist with this exact name and descriptor on the
     * admitted host or installation fails closed.
     */
    public List<Dep> dependencies() {
        final String up = updater().replace('/', '.');
        final List<Dep> deps = new ArrayList<>(48);
        deps.add(new Dep(up, "b", "()Z"));
        deps.add(new Dep(up, "b", "(Z)V"));
        deps.add(new Dep(CM, "getAllDeformers", "()Ljava/util/List;"));
        deps.add(new Dep(CM, "getAllArtPaths", "()Ljava/util/List;"));
        deps.add(new Dep(CM, "getAllAffecters", "()Ljava/util/List;"));
        deps.add(new Dep(CM, "getDeformer",
            "(Lcom/live2d/type/CDeformerGuid;)Lcom/live2d/cubism/doc/model/deformer/ACDeformer;"));
        deps.add(new Dep(CM, "getParameterSet", "()Lcom/live2d/cubism/doc/model/param/CParameterSet;"));
        deps.add(new Dep(CM, "getAllDrawables", "()Ljava/util/List;"));
        deps.add(new Dep(PS, "getParameters", "()Ljava/util/List;"));
        deps.add(new Dep(PS, "getUpdateVersion", "()I"));
        deps.add(new Dep(CP, "getValue", "()F"));
        deps.add(new Dep(DOC, "getLastModifiedTime", "()J"));
        deps.add(new Dep(CX, "getDoc", "()Lcom/live2d/cubism/doc/IDocument;"));
        deps.add(new Dep(ACD, "getDirtyDeformedForm", "()Z"));
        deps.add(new Dep(ACD, "setDirtyDeformedForm", "(Z)V"));
        deps.add(new Dep(ACD, "getInterpolatedForm",
            "()Lcom/live2d/cubism/doc/model/deformer/ACDeformerForm;"));
        deps.add(new Dep(ACD, "getLocalAnimatedForm",
            "()Lcom/live2d/cubism/doc/model/deformer/ACDeformerForm;"));
        deps.add(new Dep(ACD, "getTargetDeformerGuid", "()Lcom/live2d/type/CDeformerGuid;"));
        deps.add(new Dep(ACD, "getGuid", "()Lcom/live2d/type/CDeformerGuid;"));
        deps.add(new Dep(ACD, "getCreateLocalToCanvasTransform",
            "()Lcom/live2d/doc/selection/d;"));
        deps.add(new Dep(CAM, "getInterpolatedForm",
            "()Lcom/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm;"));
        deps.add(new Dep(CAM, "getLocalAnimatedForm",
            "()Lcom/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm;"));
        deps.add(new Dep(MF, "transform",
            "(Lcom/live2d/doc/selection/d;Lcom/live2d/cubism/doc/model/ACForm;)"
                + "Lcom/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm;"));
        deps.add(new Dep(DR, "getDeformedForm",
            "()Lcom/live2d/cubism/doc/model/drawable/ACDrawableForm;"));
        deps.add(new Dep(DR, "getDrawOrder", "()I"));
        deps.add(new Dep(MF, "getPositions", "()[F"));
        deps.add(new Dep(PF, "getPositions", "()Lcom/live2d/type/CArrayList;"));
        deps.add(new Dep(PP, "getCurvePointPosition",
            "()Lcom/live2d/graphics/splineCurve/CSplineCurvePoint;"));
        deps.add(new Dep(PP, "getWidth", "()F"));
        deps.add(new Dep(PP, "getOpacity", "()F"));
        deps.add(new Dep(SP, "getPoint", "()Lcom/live2d/graphics3d/type/GVector2;"));
        deps.add(new Dep(SP, "getStartVelocity", "()Lcom/live2d/graphics3d/type/GVector2;"));
        deps.add(new Dep(SP, "getEndVelocity", "()Lcom/live2d/graphics3d/type/GVector2;"));
        deps.add(new Dep(GV, "getX", "()F"));
        deps.add(new Dep(GV, "getY", "()F"));
        return List.copyOf(deps);
    }
}
