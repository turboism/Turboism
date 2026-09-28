package dev.turboism.adapter.cubism.optimization.modelupdate.incremental;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        HostArtifactDigest digest, String version, String updater, String updateContext, String contextParam) {

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
    public static final String ROTATION_FORM = "com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerForm";
    /** Concrete {@code ACDeformerForm} subclass owning warp interpolation. */
    public static final String WARP_FORM = "com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerForm";
    /** Obfuscated {@code CArtMeshForm} owner of the mesh interpolation body. */
    public static final String MESH_FORM = "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm";

    private static final IncrementalUpdateTarget CUBISM_5203 = new IncrementalUpdateTarget(
            ReviewedHostArtifacts.CUBISM_5_2_03,
            "5.2.03",
            "com/live2d/cubism/view/au",
            "com/live2d/cubism/doc/model/ay",
            "com/live2d/cubism/view/context/bL");

    private static final IncrementalUpdateTarget CUBISM_5302 = new IncrementalUpdateTarget(
            ReviewedHostArtifacts.CUBISM_5_3_02,
            "5.3.02",
            "com/live2d/cubism/view/ay",
            "com/live2d/cubism/doc/model/ax",
            "com/live2d/cubism/view/context/bK");

    private static final IncrementalUpdateTarget CUBISM_5303 = new IncrementalUpdateTarget(
            ReviewedHostArtifacts.CUBISM_5_3_03,
            "5.3.03",
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

    /**
     * The reviewed target for a declared reviewed version, independent of the
     * whole-artifact digest. Callers must still verify the pinned class digests in
     * {@link #reviewedClassSha256()} against the actual artifact.
     */
    public static Optional<IncrementalUpdateTarget> forReviewedVersion(final String version) {
        Objects.requireNonNull(version, "version");
        for (final IncrementalUpdateTarget target : all()) {
            if (target.version().equals(version)) return Optional.of(target);
        }
        return Optional.empty();
    }

    /**
     * SHA-256 of every host class the updater/form rewrites or the dependency
     * verification touches, keyed by internal name, per reviewed generation. A
     * repackaged or differently-declared artifact binds only when exactly one
     * generation's pin set matches every class entry.
     */
    public static Map<String, Map<String, String>> reviewedClassSha256() {
        return REVIEWED_CLASS_SHA256;
    }

    private static final Map<String, Map<String, String>> REVIEWED_CLASS_SHA256 = Map.of(
            "5.2.03",
                    Map.ofEntries(
                            Map.entry(
                                    "com/live2d/cubism/doc/model/CModel",
                                    "82391960da52933e7aaa45813c4144c7c9d7bb1622f19ff2283ec450dbb213cd"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/param/CParameterSet",
                                    "25af1041a5271526529d1906f7a724fbfd314025a1b5ff1ccc1e4bf6d152e4d5"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/param/CParameter",
                                    "a567f25f07dd46ab8e6a082a11ab4542e7d2581046a66da5f069e1da395f22cf"),
                            Map.entry(
                                    "com/live2d/cubism/doc/modeling/CModelingDocument",
                                    "34881d77cedadd3e1f21aa8023d24f920c72266e9b1fee206a2be8b313c3c365"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/CEViewContext",
                                    "096b41d6fb53fa540953a46a9b51ebe3efe91c3def555152c48d7c70eabf375a"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/deformer/ACDeformer",
                                    "02015a4f50afcee0e4d2c3880f12e6008981fdbca6a2ad9939ea49c443246df5"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/artMesh/CArtMesh",
                                    "6844027678b54808b1cf159d2d4f36c96c531f7557f8715922f0d846024942ae"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm",
                                    "51a72b256b7b56c7d18ca32701c494b71927aa1d26c55d71548828fdc5271dec"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/ACDrawable",
                                    "9e195973ed329f090469d4b13eeb2222068114bdbf5701ade65e22bb5e492669"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/artPath/CArtPathForm",
                                    "2f5e5bbfc488606cf730a660c2a6a85953083a233f2ca4d3e77bfe9640b7d46d"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/artPath/CArtPathPoint",
                                    "8c30e2575339958bafc753d870f201ebb0970f7b0d60cd3b36139b3c3b69181a"),
                            Map.entry(
                                    "com/live2d/graphics/splineCurve/CSplineCurvePoint",
                                    "b8974c17a3a967bb8e14f295a6246463d5f6a4ff1f502fede6197db0f8bf9700"),
                            Map.entry(
                                    "com/live2d/graphics3d/type/GVector2",
                                    "91d06613e29fe8d0b1b03a30594cbc28d9e2a49adcdcd544be60e2576e0aac31"),
                            Map.entry(
                                    "com/live2d/doc/selection/d",
                                    "4058e615a8825139af8ecd0b06d821a40d920911e33337d560e08939cca357d4"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/deformer/rotation/CRotationDeformerForm",
                                    "3ecf55e1320921761afdac966fdb63f154e7b6c09bc974ddb0c5acd757474f00"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/deformer/warp/CWarpDeformerForm",
                                    "8dfc6d6e42f2b73ca66fb52f75e8215016294e36bdc7454a5c95eb05a0af5778"),
                            Map.entry(
                                    "com/live2d/cubism/view/au",
                                    "e6fcfd5495e5f96deb33dca42233b5d7b94c92b29f966554f8cd5c772cf55bbd"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/ay",
                                    "6758d8dd687693a87f0dd6c23a0222e9fcc3b954a599674c43296e7a67907fd8"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/bL",
                                    "64a0235fa3ca6e1a223a51577d4a768fc3450884acda6425354e77b83f4fc99e")),
            "5.3.02",
                    Map.ofEntries(
                            Map.entry(
                                    "com/live2d/cubism/doc/model/CModel",
                                    "15a5b929203b340af0df396b7629ff4f60703e6fc8a7eb8ad4874d99886d96a8"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/param/CParameterSet",
                                    "25af1041a5271526529d1906f7a724fbfd314025a1b5ff1ccc1e4bf6d152e4d5"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/param/CParameter",
                                    "a567f25f07dd46ab8e6a082a11ab4542e7d2581046a66da5f069e1da395f22cf"),
                            Map.entry(
                                    "com/live2d/cubism/doc/modeling/CModelingDocument",
                                    "7ca4f69d9304e38d15adf0cce4750873b3d9325cac296a02e617d5c491d72188"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/CEViewContext",
                                    "5bd6c34d0b5a0213f2762396fb13eb81e78354156013210df4121a82494bfb8e"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/deformer/ACDeformer",
                                    "02015a4f50afcee0e4d2c3880f12e6008981fdbca6a2ad9939ea49c443246df5"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/artMesh/CArtMesh",
                                    "95bbb56cd5d7f9c6cf45a330c8a22154e4df5c552a86e7480039105feccc4ef0"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm",
                                    "513e4e42cef50b5978b0bbfb28e86d2d92ff9380323b2bf4df8cc24608d34e56"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/ACDrawable",
                                    "9e195973ed329f090469d4b13eeb2222068114bdbf5701ade65e22bb5e492669"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/artPath/CArtPathForm",
                                    "9c621d58e5bb0963928d9ed6a807f302185ef33b6d53e06606dd6e6ea8864743"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/artPath/CArtPathPoint",
                                    "8c30e2575339958bafc753d870f201ebb0970f7b0d60cd3b36139b3c3b69181a"),
                            Map.entry(
                                    "com/live2d/graphics/splineCurve/CSplineCurvePoint",
                                    "b8974c17a3a967bb8e14f295a6246463d5f6a4ff1f502fede6197db0f8bf9700"),
                            Map.entry(
                                    "com/live2d/graphics3d/type/GVector2",
                                    "91d06613e29fe8d0b1b03a30594cbc28d9e2a49adcdcd544be60e2576e0aac31"),
                            Map.entry(
                                    "com/live2d/doc/selection/d",
                                    "4058e615a8825139af8ecd0b06d821a40d920911e33337d560e08939cca357d4"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/deformer/rotation/CRotationDeformerForm",
                                    "8676ee43760375a5e81bf524651d7ab579b7c74df079737702270980de1c4102"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/deformer/warp/CWarpDeformerForm",
                                    "174959b444c2a5c13bbd05962b98824a67a7cb1a363f583e5d6e129bea12c117"),
                            Map.entry(
                                    "com/live2d/cubism/view/ay",
                                    "66847926ad5ee0be1258138ed9474a8f0118b63e8d8f241b808b1f8f1c7315f7"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/ax",
                                    "4d7ce924304bf0cab63b3470a2c969297b4ec691fdf23840099faad73ab00937"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/bK",
                                    "13601a1a586eeded08f5cfc5b034824cdc00f79d3f7d2a3e7a74c3498064d13c")),
            "5.3.03",
                    Map.ofEntries(
                            Map.entry(
                                    "com/live2d/cubism/doc/model/CModel",
                                    "492a4a12e24ae847a2c122e8db303dc1a6eb4516dfcd29b8b66c8c1d4937eb8c"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/param/CParameterSet",
                                    "25af1041a5271526529d1906f7a724fbfd314025a1b5ff1ccc1e4bf6d152e4d5"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/param/CParameter",
                                    "a567f25f07dd46ab8e6a082a11ab4542e7d2581046a66da5f069e1da395f22cf"),
                            Map.entry(
                                    "com/live2d/cubism/doc/modeling/CModelingDocument",
                                    "96a7784c6330a562fa3f4a9fb8b6a02a0fd55cc4d2465915a90435b0a822041e"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/CEViewContext",
                                    "6f888ce6f10a79a71f4409ecfc22e6f0cabe190f7eab13bf61279da7f033fd57"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/deformer/ACDeformer",
                                    "02015a4f50afcee0e4d2c3880f12e6008981fdbca6a2ad9939ea49c443246df5"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/artMesh/CArtMesh",
                                    "cf71aa89d84f41e559a8c1b6494e5a519b9b4d9943cb5a5b5b500cb1b3282365"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm",
                                    "513e4e42cef50b5978b0bbfb28e86d2d92ff9380323b2bf4df8cc24608d34e56"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/ACDrawable",
                                    "9e195973ed329f090469d4b13eeb2222068114bdbf5701ade65e22bb5e492669"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/artPath/CArtPathForm",
                                    "9c621d58e5bb0963928d9ed6a807f302185ef33b6d53e06606dd6e6ea8864743"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/artPath/CArtPathPoint",
                                    "8c30e2575339958bafc753d870f201ebb0970f7b0d60cd3b36139b3c3b69181a"),
                            Map.entry(
                                    "com/live2d/graphics/splineCurve/CSplineCurvePoint",
                                    "b8974c17a3a967bb8e14f295a6246463d5f6a4ff1f502fede6197db0f8bf9700"),
                            Map.entry(
                                    "com/live2d/graphics3d/type/GVector2",
                                    "91d06613e29fe8d0b1b03a30594cbc28d9e2a49adcdcd544be60e2576e0aac31"),
                            Map.entry(
                                    "com/live2d/doc/selection/d",
                                    "4058e615a8825139af8ecd0b06d821a40d920911e33337d560e08939cca357d4"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/deformer/rotation/CRotationDeformerForm",
                                    "8676ee43760375a5e81bf524651d7ab579b7c74df079737702270980de1c4102"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/deformer/warp/CWarpDeformerForm",
                                    "174959b444c2a5c13bbd05962b98824a67a7cb1a363f583e5d6e129bea12c117"),
                            Map.entry(
                                    "com/live2d/cubism/view/ay",
                                    "70b0e5350f54b0e8d34ac011f024d967adbbcc181f82175f208cc169a8a0c9ab"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/ax",
                                    "4d7ce924304bf0cab63b3470a2c969297b4ec691fdf23840099faad73ab00937"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/bL",
                                    "64a0235fa3ca6e1a223a51577d4a768fc3450884acda6425354e77b83f4fc99e")));

    private String t(final String dotted) {
        return "L" + dotted.replace('.', '/') + ";";
    }

    /** {@code a(CModel, List, UC, CEViewContext, bL|bK)V} — per-update frame boundary. */
    public String coreDescriptor() {
        return "(" + t(CM) + "Ljava/util/List;" + t(updateContext) + t(CX) + t(contextParam) + ")V";
    }

    /** {@code a(CModel, CParameterSet, List<ACDeformer>, UC, CEViewContext, bL|bK)V}. */
    public String deformerUpdateDescriptor() {
        return "(" + t(CM) + t(PS) + "Ljava/util/List;" + t(updateContext) + t(CX) + t(contextParam) + ")V";
    }

    /** {@code a(CModel, CParameterSet, CArtMesh, UC, CEViewContext, bL|bK)V}. */
    public String artMeshUpdateDescriptor() {
        return "(" + t(CM) + t(PS) + t(CAM) + t(updateContext) + t(CX) + t(contextParam) + ")V";
    }

    /** {@code ACDeformerForm.interpolate__testImpl(KeyformGridSource, CParameterSet, UC)V}. */
    public String deformerInterpolateDescriptor() {
        return "(Lcom/live2d/cubism/doc/model/interpolator/KeyformGridSource;" + t(PS) + t(updateContext) + ")V";
    }

    /** {@code CArtMeshForm.interpolate__testImpl(UC, KeyformGridSource, CParameterSet)V}. */
    public String meshInterpolateDescriptor() {
        return "(" + t(updateContext) + "Lcom/live2d/cubism/doc/model/interpolator/KeyformGridSource;" + t(PS) + ")V";
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
        deps.add(new Dep(
                CM,
                "getDeformer",
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
        deps.add(new Dep(ACD, "getInterpolatedForm", "()Lcom/live2d/cubism/doc/model/deformer/ACDeformerForm;"));
        deps.add(new Dep(ACD, "getLocalAnimatedForm", "()Lcom/live2d/cubism/doc/model/deformer/ACDeformerForm;"));
        deps.add(new Dep(ACD, "getTargetDeformerGuid", "()Lcom/live2d/type/CDeformerGuid;"));
        deps.add(new Dep(ACD, "getGuid", "()Lcom/live2d/type/CDeformerGuid;"));
        deps.add(new Dep(ACD, "getCreateLocalToCanvasTransform", "()Lcom/live2d/doc/selection/d;"));
        deps.add(new Dep(CAM, "getInterpolatedForm", "()Lcom/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm;"));
        deps.add(new Dep(CAM, "getLocalAnimatedForm", "()Lcom/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm;"));
        deps.add(new Dep(
                MF,
                "transform",
                "(Lcom/live2d/doc/selection/d;Lcom/live2d/cubism/doc/model/ACForm;)"
                        + "Lcom/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm;"));
        deps.add(new Dep(DR, "getDeformedForm", "()Lcom/live2d/cubism/doc/model/drawable/ACDrawableForm;"));
        deps.add(new Dep(DR, "getDrawOrder", "()I"));
        deps.add(new Dep(MF, "getPositions", "()[F"));
        deps.add(new Dep(PF, "getPositions", "()Lcom/live2d/type/CArrayList;"));
        deps.add(new Dep(PP, "getCurvePointPosition", "()Lcom/live2d/graphics/splineCurve/CSplineCurvePoint;"));
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
