package dev.turboism.adapter.cubism.optimization.modelupdate;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One reviewed model-update entry point per admitted Cubism Editor artifact.
 *
 * <p>Each exact version names its own obfuscated owner of {@code CModelUpdateSystem}'s full
 * update entry — the overload that the repaint path ({@code view/context/K.a}, DrawImpl)
 * actually calls — together with the obfuscated helper types the skip predicate reads.
 * Method names are version-stable; owning and context class names are not. Compatible
 * artifacts bind through reviewed target-class evidence; the legacy digest lookup
 * remains an exact-sample helper.</p>
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
     * The reviewed target for a declared reviewed version, independent of the
     * whole-artifact digest. Callers must still verify the pinned class digests in
     * {@link #reviewedClassSha256()} against the actual artifact.
     */
    public static Optional<ModelUpdateSkipTarget> forReviewedVersion(final String version) {
        Objects.requireNonNull(version, "version");
        for (final ModelUpdateSkipTarget target : all()) {
            if (target.version().equals(version)) return Optional.of(target);
        }
        return Optional.empty();
    }

    /**
     * SHA-256 of every host class the entry rewrite or the dependency verification
     * touches, keyed by internal name, per reviewed generation. A repackaged or
     * differently-declared artifact binds only when exactly one generation's pin set
     * matches every class entry.
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
                                    "com/live2d/cubism/view/context/CEViewContext_ModelingView",
                                    "d8399c1633b7742e62a1951dca4c31ca86811ce9efb9574d5258271fa9fe837e"),
                            Map.entry(
                                    "com/live2d/cubism/setting/AppSetting",
                                    "3ebf26cfdf45e767c174c0e65f8c3adf01b02fc5a5518cd307dfc38d8c9a31bf"),
                            Map.entry(
                                    "com/live2d/cubism/setting/AppSetting$DrawSetting",
                                    "a9dd6fbbeff7ab576b963f4c54c0beb377b0d7a2cdc36753ebeb48f8ded9bf9b"),
                            Map.entry(
                                    "com/live2d/cubism/doc/animation/formAnimation/t",
                                    "ce32a19cd0b5f0d5201c6828ea3a44ff6c473fe1535cbfd33ed91e0b027135dd"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/CModelSource",
                                    "54e4323b70810b74778f881180eea05498e9447dafc1dc68af779941dadacbe4"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/ACDrawable",
                                    "9e195973ed329f090469d4b13eeb2222068114bdbf5701ade65e22bb5e492669"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm",
                                    "51a72b256b7b56c7d18ca32701c494b71927aa1d26c55d71548828fdc5271dec"),
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
                                    "com/live2d/cubism/view/au",
                                    "e6fcfd5495e5f96deb33dca42233b5d7b94c92b29f966554f8cd5c772cf55bbd"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/ay",
                                    "6758d8dd687693a87f0dd6c23a0222e9fcc3b954a599674c43296e7a67907fd8"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/bR$b",
                                    "d411768ee326c3c793aeff544699ef38001fa33c24de32ae4eb067061ca51b13"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/bR$a",
                                    "e7fb8ec2f9e82b8b80b20cd81534c831a7fcabea8d35381393d4347b41a580d6")),
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
                                    "com/live2d/cubism/view/context/CEViewContext_ModelingView",
                                    "f64e68c0b938efda943d992c7b6794d778622575ee1d85c4e43120d4d5669730"),
                            Map.entry(
                                    "com/live2d/cubism/setting/AppSetting",
                                    "37dfb2fe302de2b2433d144733049da28a98f0c43b229f40c75375c6e3ef1570"),
                            Map.entry(
                                    "com/live2d/cubism/setting/AppSetting$DrawSetting",
                                    "a9dd6fbbeff7ab576b963f4c54c0beb377b0d7a2cdc36753ebeb48f8ded9bf9b"),
                            Map.entry(
                                    "com/live2d/cubism/doc/animation/formAnimation/t",
                                    "927ae5530cb0afa6583715023efd8f236d28cc5837c14ecca2db89a03e10eedc"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/CModelSource",
                                    "55692c69382655a14142a6fc3dab1da78862d67180c6e7cbbe0c22e8253085d1"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/ACDrawable",
                                    "9e195973ed329f090469d4b13eeb2222068114bdbf5701ade65e22bb5e492669"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm",
                                    "513e4e42cef50b5978b0bbfb28e86d2d92ff9380323b2bf4df8cc24608d34e56"),
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
                                    "com/live2d/cubism/view/ay",
                                    "66847926ad5ee0be1258138ed9474a8f0118b63e8d8f241b808b1f8f1c7315f7"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/ax",
                                    "4d7ce924304bf0cab63b3470a2c969297b4ec691fdf23840099faad73ab00937"),
                            Map.entry(
                                    "com/live2d/cubism/setting/AppSetting$GuiSetting",
                                    "0487f959f56ab96aaceba31371ef1d9ac81e07f7b73be3ad6b8b62489502bdee"),
                            Map.entry(
                                    "com/live2d/cubism/setting/AppSetting$Warning",
                                    "fe6e39917ea749beea49e745bf58014616870df6e5f5cceaf42bf028620894da"),
                            Map.entry(
                                    "com/live2d/cubism/setting/AppSetting$CanvasSetting",
                                    "0ff57f07c63b1bedfe872b8e09392b28952ef80b836e1cc11fa1fa6b500e5bdf"),
                            Map.entry(
                                    "com/live2d/cubism/setting/AppSetting$DeveloperSetting",
                                    "2a36fc9da808f46545a472be0f5632ef0ce0f2ff4efa22c3912379cc5c535600"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/bR$b",
                                    "95c9eb49cd7fa06ec8a6bab4585136c39b08d8e640de2d285a7f1f86ca2c644d"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/bR$a",
                                    "e7fb8ec2f9e82b8b80b20cd81534c831a7fcabea8d35381393d4347b41a580d6")),
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
                                    "com/live2d/cubism/view/context/CEViewContext_ModelingView",
                                    "5ef29b7339e979b5421d796433efa96ec01ba34eff35673829fdf7b6cd6fcab3"),
                            Map.entry(
                                    "com/live2d/cubism/setting/AppSetting",
                                    "1c836ea95ae3aa7982f794ab0b675606f239c8258f9d892590d59c17651bad9b"),
                            Map.entry(
                                    "com/live2d/cubism/setting/AppSetting$DrawSetting",
                                    "a9dd6fbbeff7ab576b963f4c54c0beb377b0d7a2cdc36753ebeb48f8ded9bf9b"),
                            Map.entry(
                                    "com/live2d/cubism/doc/animation/formAnimation/t",
                                    "927ae5530cb0afa6583715023efd8f236d28cc5837c14ecca2db89a03e10eedc"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/CModelSource",
                                    "55692c69382655a14142a6fc3dab1da78862d67180c6e7cbbe0c22e8253085d1"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/ACDrawable",
                                    "9e195973ed329f090469d4b13eeb2222068114bdbf5701ade65e22bb5e492669"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm",
                                    "513e4e42cef50b5978b0bbfb28e86d2d92ff9380323b2bf4df8cc24608d34e56"),
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
                                    "com/live2d/cubism/view/ay",
                                    "70b0e5350f54b0e8d34ac011f024d967adbbcc181f82175f208cc169a8a0c9ab"),
                            Map.entry(
                                    "com/live2d/cubism/doc/model/ax",
                                    "4d7ce924304bf0cab63b3470a2c969297b4ec691fdf23840099faad73ab00937"),
                            Map.entry(
                                    "com/live2d/cubism/setting/AppSetting$GuiSetting",
                                    "0487f959f56ab96aaceba31371ef1d9ac81e07f7b73be3ad6b8b62489502bdee"),
                            Map.entry(
                                    "com/live2d/cubism/setting/AppSetting$Warning",
                                    "c7d7740e36d939b50e6383fd81b3e2464370099f38464f542b8bd7cf7b7245ec"),
                            Map.entry(
                                    "com/live2d/cubism/setting/AppSetting$CanvasSetting",
                                    "0ff57f07c63b1bedfe872b8e09392b28952ef80b836e1cc11fa1fa6b500e5bdf"),
                            Map.entry(
                                    "com/live2d/cubism/setting/AppSetting$DeveloperSetting",
                                    "2a36fc9da808f46545a472be0f5632ef0ce0f2ff4efa22c3912379cc5c535600"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/bS$b",
                                    "0d448a201b9aa0115ea971b6772111ef8ff99e7e1e2564e5dca60e20683204cd"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/bS$a",
                                    "5422642a085280a5f2cf4a4ec23814281ed1551181963615c8aa2633efb11643")));

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
        deps.add(new Dep(MV, "getCurrentViewMode", "()Lcom/live2d/cubism/view/context/CEViewContext_ModelingView$c;"));
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
