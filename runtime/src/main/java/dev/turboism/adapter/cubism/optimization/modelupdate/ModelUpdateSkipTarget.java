package dev.turboism.adapter.cubism.optimization.modelupdate;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One reviewed model-update entry point per admitted Cubism Editor artifact.
 *
 * <p>Each exact version names its own obfuscated owner of {@code CModelUpdateSystem}'s full
 * update entry — the overload that the repaint path ({@code view/context/K.a}, DrawImpl)
 * actually calls — together with the obfuscated helper types the skip predicate reads.
 * Method names are version-stable; owning and context class names are not. A digest outside
 * {@link ReviewedHostArtifacts} is not a target and fails closed.</p>
 */
public record ModelUpdateSkipTarget(
        HostArtifactDigest digest,
        String version,
        String owner,
        String methodDescriptor,
        String updateContext,
        String developSetting,
        String appearanceSetting,
        boolean conflictPolygonFlag,
        boolean extendedUpdateContext,
        boolean appearanceAuxSettings) {

    /** The reviewed entry method name, identical across versions. */
    public static final String METHOD = "a";

    /** A reviewed dependency method used by the predicate or the probe digest. */
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
    private static final String MV = "com.live2d.cubism.view.context.CEViewContext_ModelingView";
    private static final String AS = "com.live2d.cubism.setting.AppSetting";
    private static final String DS = "com.live2d.cubism.setting.AppSetting$DrawSetting";
    private static final String GS = "com.live2d.cubism.setting.AppSetting$GuiSetting";
    private static final String WARN = "com.live2d.cubism.setting.AppSetting$Warning";
    private static final String CS = "com.live2d.cubism.setting.AppSetting$CanvasSetting";
    private static final String DEVSET = "com.live2d.cubism.setting.AppSetting$DeveloperSetting";
    private static final String FT = "com.live2d.cubism.doc.animation.formAnimation.t";
    private static final String MS = "com.live2d.cubism.doc.model.CModelSource";
    private static final String DR = "com.live2d.cubism.doc.model.drawable.ACDrawable";
    private static final String MF = "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm";
    private static final String PF = "com.live2d.cubism.doc.model.drawable.artPath.CArtPathForm";
    private static final String PP = "com.live2d.cubism.doc.model.drawable.artPath.CArtPathPoint";
    private static final String SP = "com.live2d.graphics.splineCurve.CSplineCurvePoint";
    private static final String GV = "com.live2d.graphics3d.type.GVector2";
    private static final String UC_53X = "com.live2d.cubism.doc.model.ax";
    private static final String UC_5203 = "com.live2d.cubism.doc.model.ay";

    private static final ModelUpdateSkipTarget CUBISM_5203 = new ModelUpdateSkipTarget(
        ReviewedHostArtifacts.CUBISM_5_2_03,
        "5.2.03",
        "com/live2d/cubism/view/au",
        "(Lcom/live2d/cubism/view/context/CEViewContext;Lcom/live2d/cubism/doc/model/CModel;Z"
            + "Lcom/live2d/cubism/doc/model/ay;ZLcom/live2d/cubism/view/context/bL;)V",
        UC_5203,
        "com.live2d.cubism.view.context.bR$b",
        "com.live2d.cubism.view.context.bR$a",
        false,
        false,
        false);

    private static final ModelUpdateSkipTarget CUBISM_5302 = new ModelUpdateSkipTarget(
        ReviewedHostArtifacts.CUBISM_5_3_02,
        "5.3.02",
        "com/live2d/cubism/view/ay",
        "(Lcom/live2d/cubism/view/context/CEViewContext;Lcom/live2d/cubism/doc/model/CModel;Z"
            + "Lcom/live2d/cubism/doc/model/ax;ZLcom/live2d/cubism/view/context/bK;Z)V",
        UC_53X,
        "com.live2d.cubism.view.context.bR$b",
        "com.live2d.cubism.view.context.bR$a",
        true,
        true,
        true);

    private static final ModelUpdateSkipTarget CUBISM_5303 = new ModelUpdateSkipTarget(
        ReviewedHostArtifacts.CUBISM_5_3_03,
        "5.3.03",
        "com/live2d/cubism/view/ay",
        "(Lcom/live2d/cubism/view/context/CEViewContext;Lcom/live2d/cubism/doc/model/CModel;Z"
            + "Lcom/live2d/cubism/doc/model/ax;ZLcom/live2d/cubism/view/context/bL;Z)V",
        UC_53X,
        "com.live2d.cubism.view.context.bS$b",
        "com.live2d.cubism.view.context.bS$a",
        true,
        true,
        true);

    /** The reviewed target for a host artifact digest, or empty when unsupported. */
    public static Optional<ModelUpdateSkipTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        if (CUBISM_5203.digest().equals(digest)) return Optional.of(CUBISM_5203);
        if (CUBISM_5302.digest().equals(digest)) return Optional.of(CUBISM_5302);
        if (CUBISM_5303.digest().equals(digest)) return Optional.of(CUBISM_5303);
        return Optional.empty();
    }

    /** Every reviewed target, oldest supported version first. */
    public static List<ModelUpdateSkipTarget> all() {
        return List.of(CUBISM_5203, CUBISM_5302, CUBISM_5303);
    }

    /**
     * The dependency methods the bridge resolves and the installer verifies against the
     * official artifact. Owners are binary class names; every entry must exist with this
     * exact name and descriptor on the admitted host or installation fails closed.
     */
    public List<Dep> dependencies() {
        final List<Dep> deps = new ArrayList<>(64);
        deps.add(new Dep(CM, "getParameterSet", "()Lcom/live2d/cubism/doc/model/param/CParameterSet;"));
        deps.add(new Dep(CM, "getLastUpdatedParameterSet", "()Lcom/live2d/cubism/doc/model/param/CParameterSet;"));
        deps.add(new Dep(CM, "getAllDrawables", "()Ljava/util/List;"));
        deps.add(new Dep(CM, "getSource", "()Lcom/live2d/cubism/doc/model/CModelSource;"));
        deps.add(new Dep(PS, "getParameters", "()Ljava/util/List;"));
        deps.add(new Dep(PS, "getUpdateVersion", "()I"));
        deps.add(new Dep(CP, "getValue", "()F"));
        deps.add(new Dep(CP, "getId", "()Lcom/live2d/cubism/doc/model/id/CParameterId;"));
        deps.add(new Dep(DOC, "getLastModifiedTime", "()J"));
        deps.add(new Dep(CX, "getDoc", "()Lcom/live2d/cubism/doc/IDocument;"));
        deps.add(new Dep(CX, "getDevelopSetting", "()L" + developSetting().replace('.', '/') + ";"));
        deps.add(new Dep(CX, "getAppearanceSetting", "()L" + appearanceSetting().replace('.', '/') + ";"));
        deps.add(new Dep(CX, "getCurrentEditMode", "()Lcom/live2d/doc/IEditMode;"));
        deps.add(new Dep(MV, "isRandomPoseAnimation", "()Z"));
        deps.add(new Dep(MV, "isExternalAppAnimation", "()Z"));
        deps.add(new Dep(MV, "isRecording", "()Z"));
        deps.add(new Dep(MV, "getCurrentViewMode",
            "()Lcom/live2d/cubism/view/context/CEViewContext_ModelingView$c;"));
        deps.add(new Dep(developSetting(), "h", "()Z"));
        deps.add(new Dep(developSetting(), "k", "()Z"));
        deps.add(new Dep(appearanceSetting(), "d", "()F"));
        deps.add(new Dep(AS, "getDraw", "()Lcom/live2d/cubism/setting/AppSetting$DrawSetting;"));
        deps.add(new Dep(DS, "getOptimizeArtMesh", "()Z"));
        deps.add(new Dep(DS, "getOptimizeDeformer", "()Z"));
        deps.add(new Dep(DS, "getOptimizeDrawOrder", "()Z"));
        deps.add(new Dep(DS, "getOptimizeHierarchy", "()Z"));
        deps.add(new Dep(FT, "a", "(Lcom/live2d/cubism/view/context/CEViewContext;)Z"));
        deps.add(new Dep(MS, "isModelEditing", "()Z"));
        deps.add(new Dep(updateContext(), "a", "()Z"));
        deps.add(new Dep(updateContext(), "b", "()Z"));
        deps.add(new Dep(updateContext(), "c", "()F"));
        deps.add(new Dep(updateContext(), "d", "()Z"));
        deps.add(new Dep(updateContext(), "e", "()Z"));
        deps.add(new Dep(updateContext(), "f", "()Z"));
        deps.add(new Dep(updateContext(), "g", "()Lcom/live2d/cubism/view/context/CEViewContext_ModelingView;"));
        deps.add(new Dep(updateContext(), "h", "()Lcom/live2d/cubism/doc/modeling/CModelingEditMode_Main;"));
        deps.add(new Dep(updateContext(), "i", "()Ljava/util/List;"));
        if (extendedUpdateContext()) {
            deps.add(new Dep(updateContext(), "l", "()Ljava/util/ArrayList;"));
            deps.add(new Dep(updateContext(), "m", "()Ljava/lang/Integer;"));
            deps.add(new Dep(updateContext(), "n", "()Ljava/util/ArrayList;"));
        }
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
        deps.add(new Dep(owner().replace('/', '.'), "a", "()Z"));
        deps.add(new Dep(owner().replace('/', '.'), "b", "()Z"));
        if (appearanceAuxSettings()) {
            deps.add(new Dep(AS, "getGui", "()Lcom/live2d/cubism/setting/AppSetting$GuiSetting;"));
            deps.add(new Dep(GS, "getWarning", "()Lcom/live2d/cubism/setting/AppSetting$Warning;"));
            deps.add(new Dep(WARN, "isVisibleMaskWarning", "()Z"));
            deps.add(new Dep(WARN, "isBlendModeAppearanceWarning", "()Z"));
            deps.add(new Dep(AS, "getCanvas", "()Lcom/live2d/cubism/setting/AppSetting$CanvasSetting;"));
            deps.add(new Dep(CS, "getHideSelectedState", "()Z"));
            deps.add(new Dep(AS, "getDeveloper", "()Lcom/live2d/cubism/setting/AppSetting$DeveloperSetting;"));
            deps.add(new Dep(DEVSET, "getHighLightDeformerChild", "()Z"));
        }
        return List.copyOf(deps);
    }
}
