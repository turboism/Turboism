package dev.turboism.adapter.cubism.optimization.warpdeform;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.ModelUpdateTargetTable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Resolved {@link MethodHandle} and host type registry for edit-mode subtree updates and overlay elision.
 *
 * <p>Resolves version-specific updater and context descriptors against {@link ModelUpdateTargetTable}
 * and fails closed with {@link ReflectiveOperationException} if any required contract is absent.</p>
 */
final class EditSubtreeHandles {

    private static final String CMODEL_OWNER = "com/live2d/cubism/doc/model/CModel";
    private static final String CMODEL_SOURCE_OWNER = "com/live2d/cubism/doc/model/CModelSource";
    private static final String CPARAM_SET_OWNER = "com/live2d/cubism/doc/model/param/CParameterSet";
    private static final String CPARAM_OWNER = "com/live2d/cubism/doc/model/param/CParameter";
    private static final String CPARAM_SRC_OWNER = "com/live2d/cubism/doc/model/param/CParameterSource";
    private static final String CPARAM_GUID_OWNER = "com/live2d/type/CParameterGuid";
    private static final String CE_VIEW_OWNER = "com/live2d/cubism/view/context/CEViewContext";
    private static final String CE_MODELING_VIEW_OWNER = "com/live2d/cubism/view/context/CEViewContext_ModelingView";
    private static final String CE_FORM_ANIM_VIEW_OWNER =
            "com/live2d/cubism/view/context/CEViewContext_FormAnimationView";
    private static final String COMPLETE_PACK_OWNER = "com/live2d/cubism/pack/CECompletePack";
    private static final String PANEL_WRAPPER_OWNER = "com/live2d/graphics3d/view/glWrapper/GLJPanelWrapper";
    private static final String CMODELING_DOC_OWNER = "com/live2d/cubism/doc/modeling/CModelingDocument";
    private static final String AC_EDIT_MODE_OWNER = "com/live2d/cubism/doc/ACEditMode";
    private static final String EDIT_MODE_MAIN_OWNER = "com/live2d/cubism/doc/modeling/CModelingEditMode_Main";
    private static final String SELECTOR_MAIN_OWNER = "com/live2d/cubism/doc/modeling/CModelingSelector_Main";
    private static final String GUID_SELECTOR_OWNER = "com/live2d/cubism/doc/selection/ACGuidSelector";
    private static final String ACTION_PACK_OWNER = "com/live2d/cubism/view/context/actionManager/aG";
    private static final String ACTION_PACK_BASE_OWNER = "com/live2d/cubism/view/context/actionManager/b";
    private static final String AC_DEFORMER_OWNER = "com/live2d/cubism/doc/model/deformer/ACDeformer";
    private static final String AC_DEFORMER_SOURCE_OWNER = "com/live2d/cubism/doc/model/deformer/ACDeformerSource";
    private static final String AC_DEFORMER_FORM_OWNER = "com/live2d/cubism/doc/model/deformer/ACDeformerForm";
    private static final String AC_CONTROLLABLE_OWNER = "com/live2d/cubism/doc/model/ACParameterControllable";
    private static final String AC_CONTROLLABLE_SOURCE_OWNER =
            "com/live2d/cubism/doc/model/ACParameterControllableSource";
    private static final String AC_FORM_OWNER = "com/live2d/cubism/doc/model/ACForm";
    private static final String ROT_DEFORMER_OWNER = "com/live2d/cubism/doc/model/deformer/rotation/CRotationDeformer";
    private static final String ROT_FORM_OWNER = "com/live2d/cubism/doc/model/deformer/rotation/CRotationDeformerForm";
    private static final String ROT_SOURCE_OWNER =
            "com/live2d/cubism/doc/model/deformer/rotation/CRotationDeformerSource";
    private static final String ART_MESH_OWNER = "com/live2d/cubism/doc/model/drawable/artMesh/CArtMesh";
    private static final String ART_MESH_FORM_OWNER = "com/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm";
    private static final String ART_MESH_SOURCE_OWNER = "com/live2d/cubism/doc/model/drawable/artMesh/CArtMeshSource";
    private static final String ART_MESH_SELECTION_OWNER =
            "com/live2d/cubism/doc/model/drawable/artMesh/ArtMeshSelection";
    private static final String SELECTION_DRAW_MESH_OWNER =
            "com/live2d/cubism/doc/model/drawable/artMesh/DrawEntity_ArtMesh_Selection";
    private static final String AC_DRAWABLE_OWNER = "com/live2d/cubism/doc/model/drawable/ACDrawable";
    private static final String ART_PATH_OWNER = "com/live2d/cubism/doc/model/drawable/artPath/CArtPath";
    private static final String CPART_OWNER = "com/live2d/cubism/doc/model/parts/CPart";
    private static final String CPART_SOURCE_OWNER = "com/live2d/cubism/doc/model/parts/CPartSource";
    private static final String CALIAS_OWNER = "com/live2d/cubism/doc/model/alias/CAlias";
    private static final String GLUE_OWNER = "com/live2d/cubism/doc/model/affecter/glue/CGlue";
    private static final String GLUE_SOURCE_OWNER = "com/live2d/cubism/doc/model/affecter/glue/CGlueSource";
    private static final String GLUE_FORM_OWNER = "com/live2d/cubism/doc/model/affecter/glue/CGlueForm";
    private static final String AC_AFFECTER_OWNER = "com/live2d/cubism/doc/model/affecter/ACAffecter";
    private static final String DEFORMER_GUID_OWNER = "com/live2d/type/CDeformerGuid";
    private static final String DEFORMER_GUID_COMPANION_OWNER = "com/live2d/type/CDeformerGuid$a";
    private static final String DRAWABLE_GUID_OWNER = "com/live2d/type/CDrawableGuid";
    private static final String COORD_TYPE_OWNER = "com/live2d/doc/CoordType";
    private static final String COORD_TYPE_COMPANION_OWNER = "com/live2d/doc/CoordType$a";
    private static final String CFLOAT_COLOR_OWNER = "com/live2d/type/CFloatColor";
    private static final String CCOLOR_OWNER = WarpDeformEditTarget.CCOLOR_OWNER;
    private static final String KEYFORM_GRID_SOURCE_OWNER =
            "com/live2d/cubism/doc/model/interpolator/KeyformGridSource";
    private static final String MORPH_SET_OWNER = "com/live2d/cubism/doc/model/morphTarget/KeyFormMorphTargetSet";
    private static final String EXT_MORPH_SET_OWNER =
            "com/live2d/cubism/doc/model/morphTarget/extendedInterpolation/ExtendedKeyFormMorphTargetSet";
    private static final String I_MORPH_SET_OWNER = "com/live2d/cubism/doc/model/morphTarget/IKeyFormMorphTargetSet";
    private static final String I_MORPH_ITEM_OWNER = "com/live2d/cubism/doc/model/morphTarget/IKeyFormMorphTarget";
    private static final String KEYFORM_MORPH_COMPANION_OWNER =
            "com/live2d/cubism/doc/model/morphTarget/KeyFormMorphTarget$a";
    private static final String KEYFORM_MORPH_OWNER = "com/live2d/cubism/doc/model/morphTarget/KeyFormMorphTarget";
    private static final String MORPH_UTILS_OWNER = "com/live2d/cubism/doc/model/morphTarget/MorphTargetUtils";
    private static final String MORPH_CONSTRAINT_SET_OWNER =
            "com/live2d/cubism/doc/model/morphTarget/MorphTargetBlendWeightConstraintSet";
    private static final String COPY_INFO_OWNER = "com/live2d/core/a";
    private static final String COPY_MODE_OWNER = "com/live2d/core/a$c";
    private static final String ICOPYABLE_OWNER = "com/live2d/core/ICopyable";
    private static final String IBLENDABLE_OWNER = "com/live2d/core/IBlendable";
    private static final String UTIL_B_OWNER = "com/live2d/util/B";
    private static final String CPOINT_OWNER = "com/live2d/type/CPoint";
    private static final String CAMERA_WRAPPER_OWNER = "com/live2d/graphics3d/sceneGraph/GCameraManager$GCameraWrapper";
    private static final String TRANSFORM_INTERFACE_OWNER = "com/live2d/doc/selection/d";
    private static final String SCENE_GRAPH_OWNER = "com/live2d/graphics3d/sceneGraph/GSceneGraph";
    private static final String CAMERA_MGR_OWNER = "com/live2d/cubism/view/context/CECameraManager";
    private static final String APP_SETTING_OWNER = "com/live2d/cubism/setting/AppSetting";
    private static final String DRAW_SETTING_OWNER = "com/live2d/cubism/setting/AppSetting$DrawSetting";
    private static final String CANVAS_SETTING_OWNER = "com/live2d/cubism/setting/AppSetting$CanvasSetting";
    private static final String ARTMESH_SETTING_OWNER = "com/live2d/cubism/setting/AppSetting$ArtMesh";
    private static final String DEFORMER_SETTING_OWNER = "com/live2d/cubism/setting/AppSetting$Deformer";
    private static final String ROT_SETTING_OWNER = "com/live2d/cubism/setting/AppSetting$RotationDeformer";
    private static final String ROT_MODE_OWNER = "com/live2d/cubism/setting/AppSetting$RotationDeformer$Mode";
    private static final String DEV_SETTING_OWNER = "com/live2d/cubism/setting/AppSetting$DeveloperSetting";
    private static final String WARP_O_OWNER = "com/live2d/cubism/doc/model/deformer/warp/o";
    private static final String FORM_ANIM_T_OWNER = "com/live2d/cubism/doc/animation/formAnimation/t";
    private static final String MORPH_C_OWNER = "com/live2d/cubism/doc/model/morphTarget/C";
    private static final String MORPH_Y_OWNER = "com/live2d/cubism/doc/model/morphTarget/y";
    private static final String MORPH_X_OWNER = "com/live2d/cubism/doc/model/morphTarget/x";
    private static final String ROT_DRAW_NORMAL_OWNER =
            "com/live2d/cubism/doc/model/deformer/rotation/RotationDeformer_DrawNormal";
    private static final String ROT_DRAW_BONE_OWNER =
            "com/live2d/cubism/doc/model/deformer/rotation/RotationDeformer_DrawBone";
    private static final String CONFLICT_POLY_OWNER = "com/live2d/cubism/doc/model/drawable/ConflictPolygon/h";
    private static final String GUID_OWNER = "com/live2d/type/Guid";
    private static final String AC_GUID_SELECTION_OWNER = "com/live2d/cubism/doc/selection/ACGuidSelection";

    private static final String SINGLETON_FIELD = "a";
    private static final String INSTANCE_FIELD = "INSTANCE";
    private static final String COMPANION_FIELD = "Companion";
    private static final String LEGACY_VERSION_PREFIX = "5.0.";
    private static final String PRE_SUBSET_AFFECTER_PREFIX = "5.1.";
    private static final long COLOR_HASH_MIX_A = 0x9E3779B97F4A7C15L;
    private static final long COLOR_HASH_MIX_B = 0xBF58476D1CE4E5B9L;
    private static final long COLOR_HASH_MIX_C = 0x94D049BB133111EBL;

    final Class<?> modelingDocClass;
    final Class<?> modelingViewClass;
    final Class<?> editModeClass;
    final Class<?> editModeMainClass;
    final Class<?> warpDeformerClass;
    final Class<?> rotDeformerClass;
    final Class<?> artMeshClass;
    final Class<?> formAnimViewClass;
    final Class<?> deformerGuidClass;
    final Class<?> drawableGuidClass;
    final Class<?> glueClass;
    final Class<?> actionPackClass;
    final Class<?> warpDrawClass;
    final Class<?> rotDrawNormalClass;
    final Class<?> rotDrawBoneClass;
    final Class<?> conflictPolyInterface;
    final boolean conflictPolygonFlag;
    final boolean legacyOverlayModel;

    final Object updaterInstance;
    final Object emptyDeformerGuid;
    final Object canvasCoordType;
    final Object appSettingInstance;
    final Object warpOInstance;
    final Object formAnimTInstance;
    final Object morphTargetInstance;
    final Object morphTargetUtilsInstance;
    final Object keyFormMorphCompanion;
    final Object morphConstraintEvalInstance;
    final Object copyInfoDefault;
    final Object utilBInstance;
    final Object appArtMeshInstance;
    final Object rotModeBone;
    final Object rotModeAuto;

    final MethodHandle updaterUpdateAffectersSubset;
    final MethodHandle updaterOptimizeUpdateParam;
    final MethodHandle updCtxGetSubDivision;
    final MethodHandle updCtxGetSubParam;
    final MethodHandle updCtxGetBoolA;
    final MethodHandle updCtxGetBoolF;

    final MethodHandle modelGetAllDeformers;
    final MethodHandle modelGetAllArtMeshes;
    final MethodHandle modelGetAllParts;
    final MethodHandle modelGetAllAliases;
    final MethodHandle modelGetAllArtPaths;
    final MethodHandle modelGetAllAffecters;
    final MethodHandle modelGetAllDrawables;
    final MethodHandle modelGetParameterSet;
    final MethodHandle modelGetSource;
    final MethodHandle modelGetDeformer;
    final MethodHandle modelGetDrawable;
    final MethodHandle modelGetOpacity;
    final MethodHandle modelGetBaseColor;
    final MethodHandle modelGetRenderType;
    final MethodHandle modelGetZOffset;
    final MethodHandle modelGetSubDivisionEnabled;
    final MethodHandle modelGetSyncPartVis;
    final MethodHandle modelGetLastUpdatedTime;
    final MethodHandle modelSetLastUpdatedTime;
    final MethodHandle modelSourceIsEditing;

    final MethodHandle paramSetGetUpdateVersion;
    final MethodHandle paramSetGetParameters;
    final MethodHandle paramGetValue;
    final MethodHandle paramGetGuid;
    final MethodHandle paramGetSource;
    final MethodHandle paramSourceGetGuid;
    final MethodHandle paramSourceGetMaxValue;

    final MethodHandle viewGetDoc;
    final MethodHandle viewGetEditMode;
    final MethodHandle completePackGetCurrentViewContext;
    final MethodHandle completePackGetMainViewPanel;
    final MethodHandle glPanelGetWidth;
    final MethodHandle glPanelGetHeight;
    final MethodHandle overlayViewGetModelingView;
    final MethodHandle overlayViewGetCompletePack;
    final MethodHandle overlayViewGetModel;
    final MethodHandle modelingViewGetSceneGraph;
    final MethodHandle modelingViewIsDeformerVisible;
    final MethodHandle modelingViewIsDrawableLocked;
    final MethodHandle modelingViewIsDrawableEditable;
    final MethodHandle modelingViewIsBoundingBoxVisible;
    final MethodHandle modelingViewIsGridVisible;
    final MethodHandle modelingViewIsControllerVisible;
    final MethodHandle modelingViewGetDevelopSetting;
    final MethodHandle developSettingGetC;
    final MethodHandle modelingViewGetAppearanceSetting;
    final MethodHandle appearanceSettingGetA;
    final MethodHandle modelingViewGetOptionOrNull;
    final MethodHandle modelingViewGetCameraManager;
    final MethodHandle modelingViewGetLastActionPack;
    final MethodHandle cameraGetScale;
    final MethodHandle cameraGetWrapper;
    final MethodHandle cameraWrapperGetLookAt;
    final MethodHandle vec3GetX;
    final MethodHandle vec3GetY;
    final MethodHandle vec3GetZ;
    final MethodHandle utilBGetMousePos;
    final MethodHandle pointGetX;
    final MethodHandle pointGetY;
    final MethodHandle editModeIsEditing;
    final MethodHandle editModeMainGetSelector;
    final MethodHandle selectorGetSelectedObjects;
    final MethodHandle selectorGetSelectedCount;
    final MethodHandle selectorGetHoveringObject;
    final MethodHandle selectionGetGuid;
    final MethodHandle selectorIsSelected;
    final MethodHandle controllableGetSource;
    final MethodHandle controllableSourceGetTargetGuid;

    final MethodHandle appSettingGetDraw;
    final MethodHandle drawSettingGetOptimizeArtMesh;
    final MethodHandle appSettingGetCanvas;
    final MethodHandle canvasSettingGetHideSelected;
    final MethodHandle appSettingGetDeformer;
    final MethodHandle deformerSettingGetRotDeformer;
    final MethodHandle rotSettingGetMode;
    final MethodHandle appSettingGetDev;
    final MethodHandle devSettingGetShowChildUnderWarp;
    final MethodHandle warpOIsCtrlChildMode;
    final MethodHandle formAnimIsActive;
    final MethodHandle morphTargetApply;
    final MethodHandle morphTargetUseExtended;
    final MethodHandle morphTargetResolveBaseForm;
    final MethodHandle morphTargetSetGetList;
    final MethodHandle morphTargetSetGetConstraintSet;
    final MethodHandle morphTargetConstraintGetByParam;
    final MethodHandle morphItemGetParameterGuid;
    final MethodHandle morphItemGetKeyValue;
    final MethodHandle morphItemGetKeyform;
    final MethodHandle morphConstraintEval;

    final MethodHandle controllableGetPreDeformForm;
    final MethodHandle controllableGetPreClampMorphedForm;
    final MethodHandle controllableSetPreClampMorphedForm;

    final MethodHandle deformerGetGuid;
    final MethodHandle deformerGetTargetGuid;
    final MethodHandle deformerGetSource;
    final MethodHandle deformerGetDirtyDeformedForm;
    final MethodHandle deformerSetDirtyDeformedForm;
    final MethodHandle deformerGetInterpolatedForm;
    final MethodHandle deformerGetDeformedForm;
    final MethodHandle deformerGetAffectedForm;
    final MethodHandle deformerGetCalculatedForm;
    final MethodHandle deformerGetAnimatedForm;
    final MethodHandle deformerGetLocalAnimatedForm;
    final MethodHandle deformerGetCopiedUpdateVersion;
    final MethodHandle deformerIsParamOutsideHierarchy;
    final MethodHandle deformerSetParamOutsideAnyParents;
    final MethodHandle deformerGetCalcTotalScale;
    final MethodHandle deformerSetCalcTotalScale;
    final MethodHandle deformerGetCalcTotalOpacity;
    final MethodHandle deformerSetCalcTotalOpacity;
    final MethodHandle deformerGetCalcTotalMultiplyColor;
    final MethodHandle deformerGetCalcTotalScreenColor;
    final MethodHandle deformerGetCreateTransform;
    final MethodHandle deformerTransformTestImpl;
    final MethodHandle deformerGetChildren;
    final MethodHandle warpDeformerSetDeformedForm;
    final MethodHandle rotDeformerSetDeformedForm;

    final MethodHandle sourceGetUpdateVersion;
    final MethodHandle sourceReinitInstance;
    final MethodHandle sourceGetKeyforms;
    final MethodHandle sourceGetDefaultKeyForm;
    final MethodHandle sourceGetExtKeyformGrid;
    final MethodHandle sourceGetKeyformGrid;
    final MethodHandle sourceGetCoordType;
    final MethodHandle sourceGetMorphTargets;
    final MethodHandle sourceGetMorphSet;
    final MethodHandle sourceGetExtMorphSet;
    final MethodHandle sourceIsVisibleHierarchy;
    final MethodHandle rotSourceGetUseBoneUi;

    final MethodHandle formGetEditVersion;
    final MethodHandle formGetSource;
    final MethodHandle formIsParameterOutside;
    final MethodHandle formZero;
    final MethodHandle formBlend;
    final MethodHandle formPostBlend;
    final MethodHandle formDeepCopy;
    final MethodHandle formReinit;
    final MethodHandle deformerFormInterpolate;
    final MethodHandle deformerFormSetCoordType;
    final MethodHandle deformerFormGetOpacity;
    final MethodHandle deformerFormSetOpacity;
    final MethodHandle deformerFormGetMultiplyColor;
    final MethodHandle deformerFormGetScreenColor;
    final MethodHandle warpFormGetPositions;
    final MethodHandle warpFormSetPositions;
    final MethodHandle rotFormGetOriginX;
    final MethodHandle rotFormGetOriginY;
    final MethodHandle rotFormGetAngle;
    final MethodHandle rotFormGetScale;
    final MethodHandle rotFormSetAngle;
    final MethodHandle rotFormSetScale;
    final MethodHandle rotFormIsReflectX;
    final MethodHandle rotFormIsReflectY;

    final MethodHandle floatColorGetR;
    final MethodHandle floatColorGetG;
    final MethodHandle floatColorGetB;
    final MethodHandle floatColorGetA;
    final MethodHandle floatColorSetR;
    final MethodHandle floatColorSetG;
    final MethodHandle floatColorSetB;
    final MethodHandle floatColorSetA;
    final MethodHandle floatColorClamp;
    final MethodHandle colorGetRed;
    final MethodHandle colorGetGreen;
    final MethodHandle colorGetBlue;
    final MethodHandle colorGetAlpha;

    final MethodHandle meshIsInvisible;
    final MethodHandle meshGetGuid;
    final MethodHandle meshGetTargetGuid;
    final MethodHandle meshGetSource;
    final MethodHandle meshGetInterpolatedForm;
    final MethodHandle meshGetDeformedForm;
    final MethodHandle meshSetDeformedForm;
    final MethodHandle meshGetAffectedForm;
    final MethodHandle meshSetAffectedForm;
    final MethodHandle meshGetCalculatedForm;
    final MethodHandle meshGetAnimatedForm;
    final MethodHandle meshGetLocalAnimatedForm;
    final MethodHandle meshGetCopiedMeshVersion;
    final MethodHandle meshSetParamOutsideAnyParents;
    final MethodHandle meshGetDrawOrder;
    final MethodHandle meshGetContainer;
    final MethodHandle meshContainerGetMesh;
    final MethodHandle meshBaseGetPositions;
    final MethodHandle meshSourceGetMeshVersion;
    final MethodHandle meshSourceIsVisibleHierarchy;
    final MethodHandle meshSourceIsVisible;
    final MethodHandle meshFormGetPositions;
    final MethodHandle meshFormTransform;
    final MethodHandle meshFormIsCompatible;
    final MethodHandle meshFormSetCoordType;
    final MethodHandle meshFormGetOpacity;
    final MethodHandle meshFormGetMultiplyColor;
    final MethodHandle meshFormGetScreenColor;

    final MethodHandle artPathGetTargetGuid;
    final MethodHandle partGetSource;
    final MethodHandle partSourceIsVisible;
    final MethodHandle partSourceIsVisibleHierarchy;
    final MethodHandle partGetInterpolatedForm;
    final MethodHandle partGetDrawOrder;

    final MethodHandle glueGetSource;
    final MethodHandle glueGetInterpolatedForm;
    final MethodHandle glueGetCachedEditVersion;
    final MethodHandle glueSourceGetEditVersion;
    final MethodHandle glueSourceGetMeshAGuid;
    final MethodHandle glueSourceGetMeshBGuid;
    final MethodHandle glueFormGetIntensity;

    final MethodHandle conflictGetCount;
    final MethodHandle actionPackGetEditMode;
    final MethodHandle actionPackGetSelected;
    final MethodHandle actionPackGetQ;
    final MethodHandle actionPackGetS;
    final MethodHandle actionPackGetU;
    final MethodHandle actionPackGetF;
    final MethodHandle actionPackGetG;
    final MethodHandle actionPackGetW;
    final MethodHandle actionPackGetXSet;
    final MethodHandle actionPackGetK;
    final MethodHandle actionPackGetD;
    final MethodHandle actionPackGetLList;
    final MethodHandle actionPackGetAm;
    final MethodHandle actionPackGetL;
    final MethodHandle actionPackGetM;
    final MethodHandle actionPackGetP;
    final MethodHandle actionPackGetDeformers;
    final MethodHandle jViewGetModelingView;
    final MethodHandle entitySetEnabled;
    final MethodHandle entityGetEnabled;
    final MethodHandle entityGetParent;
    final MethodHandle sceneGraphSetVolatile;
    final HostHandles.HostField sceneGraphWillRenderField;
    final MethodHandle artMeshSelectionDrawGetMesh;
    final MethodHandle meshSourceGetSelection;
    final MethodHandle artMeshSettingGetShowPoint;
    final MethodHandle canvasSettingGetShowPopupHovering;
    final MethodHandle warpDrawUpdate;
    final MethodHandle rotNormalDrawUpdate;
    final MethodHandle rotBoneDrawUpdate;
    final EditSubtreeCompiledAccessors.HostOps acc;

    private record UpdaterMatch(
            ModelUpdateTargetTable.Row row,
            Class<?> updaterClass,
            Class<?> updateContextClass,
            Class<?> contextParamClass) {}

    private EditSubtreeHandles(final ClassLoader loader, final UpdaterMatch updater)
            throws ReflectiveOperationException {
        final MethodType objUnary = MethodType.methodType(Object.class, Object.class);
        final MethodType boolUnary = MethodType.methodType(boolean.class, Object.class);
        final MethodType intUnary = MethodType.methodType(int.class, Object.class);
        final MethodType floatUnary = MethodType.methodType(float.class, Object.class);
        final MethodType floatArrUnary = MethodType.methodType(float[].class, Object.class);
        final MethodType voidUnary = MethodType.methodType(void.class, Object.class);
        final MethodType setBool = MethodType.methodType(void.class, Object.class, boolean.class);
        final MethodType setFloat = MethodType.methodType(void.class, Object.class, float.class);
        final MethodType setObj = MethodType.methodType(void.class, Object.class, Object.class);

        final Class<?> modelClass = HostHandles.load(loader, CMODEL_OWNER);
        final Class<?> modelSourceClass = HostHandles.load(loader, CMODEL_SOURCE_OWNER);
        final Class<?> paramSetClass = HostHandles.load(loader, CPARAM_SET_OWNER);
        final Class<?> paramClass = HostHandles.load(loader, CPARAM_OWNER);
        final Class<?> paramSrcClass = HostHandles.load(loader, CPARAM_SRC_OWNER);
        final Class<?> viewClass = HostHandles.load(loader, CE_VIEW_OWNER);
        modelingViewClass = HostHandles.load(loader, CE_MODELING_VIEW_OWNER);
        formAnimViewClass = tryOptionalLoad(loader, CE_FORM_ANIM_VIEW_OWNER);
        final Class<?> completePackClass = HostHandles.load(loader, COMPLETE_PACK_OWNER);
        final Class<?> panelWrapperClass = tryOptionalLoad(loader, PANEL_WRAPPER_OWNER);
        modelingDocClass = HostHandles.load(loader, CMODELING_DOC_OWNER);
        editModeClass = HostHandles.load(loader, AC_EDIT_MODE_OWNER);
        editModeMainClass = HostHandles.load(loader, EDIT_MODE_MAIN_OWNER);
        final Class<?> selectorMainClass = HostHandles.load(loader, SELECTOR_MAIN_OWNER);
        final Class<?> guidSelectorClass = HostHandles.load(loader, GUID_SELECTOR_OWNER);
        final Class<?> guidSelectionClass = HostHandles.load(loader, AC_GUID_SELECTION_OWNER);
        deformerGuidClass = HostHandles.load(loader, DEFORMER_GUID_OWNER);
        drawableGuidClass = HostHandles.load(loader, DRAWABLE_GUID_OWNER);
        actionPackClass = HostHandles.load(loader, ACTION_PACK_OWNER);
        final Class<?> actionPackBaseClass = HostHandles.load(loader, ACTION_PACK_BASE_OWNER);
        final Class<?> acDeformerClass = HostHandles.load(loader, AC_DEFORMER_OWNER);
        final Class<?> acDeformerSourceClass = HostHandles.load(loader, AC_DEFORMER_SOURCE_OWNER);
        final Class<?> acDeformerFormClass = HostHandles.load(loader, AC_DEFORMER_FORM_OWNER);
        final Class<?> acControllableClass = HostHandles.load(loader, AC_CONTROLLABLE_OWNER);
        final Class<?> acControllableSourceClass = HostHandles.load(loader, AC_CONTROLLABLE_SOURCE_OWNER);
        final Class<?> acFormClass = HostHandles.load(loader, AC_FORM_OWNER);
        warpDeformerClass = HostHandles.load(loader, WarpDeformEditTarget.WARP_DEFORMER_OWNER);
        final Class<?> warpFormClass = HostHandles.load(loader, WarpDeformEditTarget.WARP_FORM_OWNER);
        rotDeformerClass = HostHandles.load(loader, ROT_DEFORMER_OWNER);
        final Class<?> rotFormClass = HostHandles.load(loader, ROT_FORM_OWNER);
        final Class<?> rotSourceClass = HostHandles.load(loader, ROT_SOURCE_OWNER);
        artMeshClass = HostHandles.load(loader, ART_MESH_OWNER);
        final Class<?> artMeshFormClass = HostHandles.load(loader, ART_MESH_FORM_OWNER);
        final Class<?> artMeshSourceClass = HostHandles.load(loader, ART_MESH_SOURCE_OWNER);
        final Class<?> acDrawableClass = HostHandles.load(loader, AC_DRAWABLE_OWNER);
        final Class<?> artPathClass = HostHandles.load(loader, ART_PATH_OWNER);
        final Class<?> partClass = HostHandles.load(loader, CPART_OWNER);
        final Class<?> partSourceClass = HostHandles.load(loader, CPART_SOURCE_OWNER);
        glueClass = HostHandles.load(loader, GLUE_OWNER);
        final Class<?> glueSourceClass = HostHandles.load(loader, GLUE_SOURCE_OWNER);
        final Class<?> glueFormClass = HostHandles.load(loader, GLUE_FORM_OWNER);
        final Class<?> deformerGuidCompanionClass = HostHandles.load(loader, DEFORMER_GUID_COMPANION_OWNER);
        final Class<?> coordTypeCompanionClass = HostHandles.load(loader, COORD_TYPE_COMPANION_OWNER);
        final Class<?> floatColorClass = HostHandles.load(loader, CFLOAT_COLOR_OWNER);
        final Class<?> colorClass = HostHandles.load(loader, CCOLOR_OWNER);
        final Class<?> sceneGraphClass = HostHandles.load(loader, SCENE_GRAPH_OWNER);
        final Class<?> entityClass = HostHandles.load(loader, WarpDeformEditTarget.GENTITY_OWNER);
        final Class<?> cameraMgrClass = HostHandles.load(loader, CAMERA_MGR_OWNER);
        final Class<?> cameraWrapperClass = tryOptionalLoad(loader, CAMERA_WRAPPER_OWNER);
        final Class<?> vec3Class = tryOptionalLoad(loader, WarpDeformEditTarget.GVECTOR3_OWNER);
        final Class<?> utilBClass = tryOptionalLoad(loader, UTIL_B_OWNER);
        final Class<?> cPointClass = tryOptionalLoad(loader, CPOINT_OWNER);
        final Class<?> copyInfoClass = tryOptionalLoad(loader, COPY_INFO_OWNER);
        final Class<?> copyModeClass = tryOptionalLoad(loader, COPY_MODE_OWNER);
        final Class<?> iCopyableClass = tryOptionalLoad(loader, ICOPYABLE_OWNER);
        final Class<?> iBlendableClass = tryOptionalLoad(loader, IBLENDABLE_OWNER);
        final Class<?> morphUtilsClass = tryOptionalLoad(loader, MORPH_UTILS_OWNER);
        final Class<?> iMorphSetClass = tryOptionalLoad(loader, I_MORPH_SET_OWNER);
        final Class<?> iMorphItemClass = tryOptionalLoad(loader, I_MORPH_ITEM_OWNER);
        final Class<?> morphConstraintSetClass = tryOptionalLoad(loader, MORPH_CONSTRAINT_SET_OWNER);
        final Class<?> keyFormMorphCompanionClass = tryOptionalLoad(loader, KEYFORM_MORPH_COMPANION_OWNER);
        final Class<?> artMeshSelectionClass = tryOptionalLoad(loader, ART_MESH_SELECTION_OWNER);
        final Class<?> selectionDrawMeshClass = tryOptionalLoad(loader, SELECTION_DRAW_MESH_OWNER);
        final Class<?> appSettingClass = HostHandles.load(loader, APP_SETTING_OWNER);
        final Class<?> drawSettingClass = HostHandles.load(loader, DRAW_SETTING_OWNER);
        final Class<?> canvasSettingClass = HostHandles.load(loader, CANVAS_SETTING_OWNER);
        final Class<?> artMeshSettingClass = tryOptionalLoad(loader, ARTMESH_SETTING_OWNER);
        final Class<?> deformerSettingClass = HostHandles.load(loader, DEFORMER_SETTING_OWNER);
        final Class<?> rotSettingClass = HostHandles.load(loader, ROT_SETTING_OWNER);
        final Class<?> rotModeClass = HostHandles.load(loader, ROT_MODE_OWNER);
        final Class<?> devSettingClass = HostHandles.load(loader, DEV_SETTING_OWNER);
        final Class<?> warpOClass = HostHandles.load(loader, WARP_O_OWNER);
        final Class<?> formAnimTClass = HostHandles.load(loader, FORM_ANIM_T_OWNER);
        final Class<?> meshContainerClass = HostHandles.load(loader, WarpDeformEditTarget.GMESH_CONTAINER_OWNER);
        final Class<?> meshBaseClass = HostHandles.load(loader, WarpDeformEditTarget.MESH_BASE_OWNER);
        warpDrawClass = HostHandles.load(loader, WarpDeformEditTarget.WARP_DRAW_OWNER);
        rotDrawNormalClass = HostHandles.load(loader, ROT_DRAW_NORMAL_OWNER);
        rotDrawBoneClass = HostHandles.load(loader, ROT_DRAW_BONE_OWNER);

        final String version = updater.row().version();
        conflictPolygonFlag = updater.row().conflictPolygonFlag();
        legacyOverlayModel = version.startsWith(LEGACY_VERSION_PREFIX);
        final String updCtxInternal = updater.row().updateContext();

        try {
            updaterInstance = Objects.requireNonNull(
                    HostHandles.field(updater.updaterClass(), SINGLETON_FIELD).get(null), "updater.a");
            appSettingInstance = Objects.requireNonNull(
                    HostHandles.field(appSettingClass, INSTANCE_FIELD).get(null), "AppSetting.INSTANCE");
            warpOInstance = Objects.requireNonNull(
                    HostHandles.field(warpOClass, SINGLETON_FIELD).get(null), "warp.o.a");
            formAnimTInstance = Objects.requireNonNull(
                    HostHandles.field(formAnimTClass, SINGLETON_FIELD).get(null), "formAnimation.t.a");
            final Object defGuidComp = Objects.requireNonNull(
                    HostHandles.field(HostHandles.load(loader, DEFORMER_GUID_OWNER), COMPANION_FIELD)
                            .get(null),
                    "CDeformerGuid.Companion");
            emptyDeformerGuid = HostHandles.virtual(
                            deformerGuidCompanionClass, SINGLETON_FIELD, "()L" + DEFORMER_GUID_OWNER + ";", objUnary)
                    .invokeExact(defGuidComp);
            final Object coordComp = Objects.requireNonNull(
                    HostHandles.field(HostHandles.load(loader, COORD_TYPE_OWNER), COMPANION_FIELD)
                            .get(null),
                    "CoordType.Companion");
            canvasCoordType = HostHandles.virtual(
                            coordTypeCompanionClass, "c", "()L" + COORD_TYPE_OWNER + ";", objUnary)
                    .invokeExact(coordComp);
            rotModeAuto =
                    Objects.requireNonNull(HostHandles.field(rotModeClass, "a").get(null), "Mode.a");
            rotModeBone =
                    Objects.requireNonNull(HostHandles.field(rotModeClass, "b").get(null), "Mode.b");
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new ReflectiveOperationException("Failed loading singleton/enum constants", failure);
        }

        if (!legacyOverlayModel && !version.startsWith(PRE_SUBSET_AFFECTER_PREFIX)) {
            updaterUpdateAffectersSubset = HostHandles.virtual(
                    updater.updaterClass(),
                    "a",
                    "(L"
                            + CMODEL_OWNER
                            + ";L"
                            + CPARAM_SET_OWNER
                            + ";Ljava/util/List;Ljava/util/List;L"
                            + updCtxInternal
                            + ";F)V",
                    MethodType.methodType(
                            void.class,
                            Object.class,
                            Object.class,
                            Object.class,
                            Object.class,
                            Object.class,
                            Object.class,
                            float.class));
        } else {
            updaterUpdateAffectersSubset = null;
        }
        updaterOptimizeUpdateParam = HostHandles.virtual(updater.updaterClass(), "b", "()Z", boolUnary);

        updCtxGetSubDivision = HostHandles.virtual(updater.updateContextClass(), "d", "()Z", boolUnary);
        updCtxGetSubParam = tryOptionalVirtual(updater.updateContextClass(), "m", "()Ljava/lang/Integer;", objUnary);
        updCtxGetBoolA = HostHandles.virtual(updater.updateContextClass(), "a", "()Z", boolUnary);
        updCtxGetBoolF = HostHandles.virtual(updater.updateContextClass(), "f", "()Z", boolUnary);

        modelGetAllDeformers = HostHandles.virtual(modelClass, "getAllDeformers", "()Ljava/util/List;", objUnary);
        modelGetAllArtMeshes = HostHandles.virtual(modelClass, "getAllArtMeshes", "()Ljava/util/List;", objUnary);
        modelGetAllParts = HostHandles.virtual(modelClass, "getAllParts", "()Ljava/util/List;", objUnary);
        modelGetAllAliases = tryOptionalVirtual(modelClass, "getAllAliases", "()Ljava/util/List;", objUnary);
        modelGetAllArtPaths = HostHandles.virtual(modelClass, "getAllArtPaths", "()Ljava/util/List;", objUnary);
        modelGetAllAffecters = HostHandles.virtual(modelClass, "getAllAffecters", "()Ljava/util/List;", objUnary);
        modelGetAllDrawables = HostHandles.virtual(modelClass, "getAllDrawables", "()Ljava/util/List;", objUnary);
        modelGetParameterSet =
                HostHandles.virtual(modelClass, "getParameterSet", "()L" + CPARAM_SET_OWNER + ";", objUnary);
        modelGetSource = HostHandles.virtual(modelClass, "getSource", "()L" + CMODEL_SOURCE_OWNER + ";", objUnary);
        modelGetDeformer = HostHandles.virtual(
                modelClass,
                "getDeformer",
                "(L" + DEFORMER_GUID_OWNER + ";)L" + AC_DEFORMER_OWNER + ";",
                MethodType.methodType(Object.class, Object.class, Object.class));
        modelGetDrawable = HostHandles.virtual(
                modelClass,
                "getDrawable",
                "(L" + DRAWABLE_GUID_OWNER + ";)L" + AC_DRAWABLE_OWNER + ";",
                MethodType.methodType(Object.class, Object.class, Object.class));
        modelGetOpacity = HostHandles.virtual(modelClass, "getOpacity", "()F", floatUnary);
        modelGetBaseColor = HostHandles.virtual(modelClass, "getBaseColor", "()L" + CCOLOR_OWNER + ";", objUnary);
        modelGetRenderType = HostHandles.virtual(
                modelClass, "getRenderType", "()Lcom/live2d/graphics3d/rendering/RenderType;", objUnary);
        modelGetZOffset = HostHandles.virtual(modelClass, "getZ_offset", "()F", floatUnary);
        modelGetSubDivisionEnabled = HostHandles.virtual(modelClass, "getSubDivisionEnabled", "()Z", boolUnary);
        modelGetSyncPartVis = HostHandles.virtual(modelClass, "getSyncWithPartSourceVisibility", "()Z", boolUnary);
        modelGetLastUpdatedTime = HostHandles.virtual(
                modelClass, "getLastUpdatedTimeMSec", "()J", MethodType.methodType(long.class, Object.class));
        modelSetLastUpdatedTime = HostHandles.virtual(
                modelClass,
                "setLastUpdatedTimeMSec",
                "(J)V",
                MethodType.methodType(void.class, Object.class, long.class));
        modelSourceIsEditing = HostHandles.virtual(modelSourceClass, "isModelEditing", "()Z", boolUnary);

        paramSetGetUpdateVersion = HostHandles.virtual(paramSetClass, "getUpdateVersion", "()I", intUnary);
        paramSetGetParameters = HostHandles.virtual(paramSetClass, "getParameters", "()Ljava/util/List;", objUnary);
        paramGetValue = HostHandles.virtual(paramClass, "getValue", "()F", floatUnary);
        paramGetGuid = tryOptionalVirtual(paramClass, "getGuid", "()L" + CPARAM_GUID_OWNER + ";", objUnary);
        paramGetSource = tryOptionalVirtual(paramClass, "getSource", "()L" + CPARAM_SRC_OWNER + ";", objUnary);
        paramSourceGetGuid = tryOptionalVirtual(paramSrcClass, "getGuid", "()L" + CPARAM_GUID_OWNER + ";", objUnary);
        paramSourceGetMaxValue = tryOptionalVirtual(paramSrcClass, "getMaxValue", "()F", floatUnary);

        viewGetDoc = HostHandles.virtual(viewClass, "getDoc", "()Lcom/live2d/cubism/doc/IDocument;", objUnary);
        viewGetEditMode =
                HostHandles.virtual(viewClass, "getCurrentEditMode", "()Lcom/live2d/doc/IEditMode;", objUnary);
        completePackGetCurrentViewContext =
                tryOptionalVirtual(completePackClass, "getCurrentViewContext", "()L" + CE_VIEW_OWNER + ";", objUnary);
        completePackGetMainViewPanel = panelWrapperClass == null
                ? null
                : tryOptionalVirtual(
                        completePackClass, "getMainViewPanel", "()L" + PANEL_WRAPPER_OWNER + ";", objUnary);
        glPanelGetWidth = tryOptionalVirtual(panelWrapperClass, "getWidth", "()I", intUnary);
        glPanelGetHeight = tryOptionalVirtual(panelWrapperClass, "getHeight", "()I", intUnary);
        modelingViewGetSceneGraph = HostHandles.virtual(
                modelingViewClass, "getSceneGraph", "()Lcom/live2d/cubism/view/context/CESceneGraphEx;", objUnary);
        modelingViewIsDeformerVisible = HostHandles.virtual(modelingViewClass, "isDeformerVisible", "()Z", boolUnary);
        modelingViewIsDrawableLocked = tryOptionalVirtual(modelingViewClass, "isDrawableLocked", "()Z", boolUnary);
        modelingViewIsDrawableEditable = tryOptionalVirtual(modelingViewClass, "isDrawableEditable", "()Z", boolUnary);
        modelingViewIsBoundingBoxVisible =
                tryOptionalVirtual(modelingViewClass, "isBoundingBoxVisible", "()Z", boolUnary);
        modelingViewIsGridVisible = tryOptionalVirtual(modelingViewClass, "isGridVisible", "()Z", boolUnary);
        modelingViewIsControllerVisible =
                tryOptionalVirtual(modelingViewClass, "isControllerVisible", "()Z", boolUnary);
        final MethodHandle[] devAndAppHandles =
                resolveModelingViewSettingHandles(modelingViewClass, objUnary, boolUnary);
        modelingViewGetDevelopSetting = devAndAppHandles[0];
        developSettingGetC = devAndAppHandles[1];
        modelingViewGetAppearanceSetting = devAndAppHandles[2];
        appearanceSettingGetA = devAndAppHandles[3];
        modelingViewGetOptionOrNull = tryOptionalVirtual(
                modelingViewClass,
                "getOption",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                setObj.changeReturnType(Object.class));
        modelingViewGetCameraManager =
                HostHandles.virtual(modelingViewClass, "getCameraManager", "()L" + CAMERA_MGR_OWNER + ";", objUnary);
        modelingViewGetLastActionPack = HostHandles.virtual(
                modelingViewClass, "getLastActionPack", "()L" + ACTION_PACK_BASE_OWNER + ";", objUnary);
        cameraGetScale = HostHandles.virtual(cameraMgrClass, "getCameraScale", "()F", floatUnary);
        cameraGetWrapper = cameraWrapperClass == null
                ? null
                : tryOptionalVirtual(cameraMgrClass, "getCameraWrapper", "()L" + CAMERA_WRAPPER_OWNER + ";", objUnary);
        cameraWrapperGetLookAt = vec3Class == null
                ? null
                : tryOptionalVirtual(
                        cameraWrapperClass,
                        "getCameraLookAt",
                        "()L" + WarpDeformEditTarget.GVECTOR3_OWNER + ";",
                        objUnary);
        vec3GetX = tryOptionalVirtual(vec3Class, "getX", "()F", floatUnary);
        vec3GetY = tryOptionalVirtual(vec3Class, "getY", "()F", floatUnary);
        vec3GetZ = tryOptionalVirtual(vec3Class, "getZ", "()F", floatUnary);
        utilBInstance = tryStaticField(utilBClass, SINGLETON_FIELD);
        utilBGetMousePos =
                cPointClass == null ? null : tryOptionalVirtual(utilBClass, "s", "()L" + CPOINT_OWNER + ";", objUnary);
        pointGetX = tryOptionalVirtual(cPointClass, "getX", "()I", intUnary);
        pointGetY = tryOptionalVirtual(cPointClass, "getY", "()I", intUnary);
        appArtMeshInstance = tryStaticField(artMeshSettingClass, INSTANCE_FIELD);
        artMeshSettingGetShowPoint = tryOptionalVirtual(artMeshSettingClass, "getShowArtMeshPoint", "()Z", boolUnary);
        canvasSettingGetShowPopupHovering =
                tryOptionalVirtual(canvasSettingClass, "isShowPopupHoveringObject", "()Z", boolUnary);
        editModeIsEditing = HostHandles.virtual(editModeClass, "isEditing", "()Z", boolUnary);
        editModeMainGetSelector =
                HostHandles.virtual(editModeMainClass, "getSelector", "()L" + SELECTOR_MAIN_OWNER + ";", objUnary);
        selectorGetSelectedObjects =
                HostHandles.virtual(selectorMainClass, "getSelectedObjects", "()Ljava/util/List;", objUnary);
        selectorGetSelectedCount = HostHandles.virtual(guidSelectorClass, "getSelectedCount", "()I", intUnary);
        selectorGetHoveringObject = HostHandles.virtual(
                guidSelectorClass, "getHoveringObject", "()L" + AC_GUID_SELECTION_OWNER + ";", objUnary);
        selectionGetGuid = HostHandles.virtual(guidSelectionClass, "getGuid", "()L" + GUID_OWNER + ";", objUnary);
        selectorIsSelected = HostHandles.virtual(
                guidSelectorClass,
                "isSelected",
                "(L" + GUID_OWNER + ";)Z",
                MethodType.methodType(boolean.class, Object.class, Object.class));
        controllableGetSource = HostHandles.virtual(
                acControllableClass, "getSource", "()L" + AC_CONTROLLABLE_SOURCE_OWNER + ";", objUnary);
        controllableSourceGetTargetGuid = HostHandles.virtual(
                acControllableSourceClass, "getTargetDeformerGuid", "()L" + DEFORMER_GUID_OWNER + ";", objUnary);

        appSettingGetDraw = HostHandles.virtual(appSettingClass, "getDraw", "()L" + DRAW_SETTING_OWNER + ";", objUnary);
        drawSettingGetOptimizeArtMesh = HostHandles.virtual(drawSettingClass, "getOptimizeArtMesh", "()Z", boolUnary);
        appSettingGetCanvas =
                HostHandles.virtual(appSettingClass, "getCanvas", "()L" + CANVAS_SETTING_OWNER + ";", objUnary);
        canvasSettingGetHideSelected =
                HostHandles.virtual(canvasSettingClass, "getHideSelectedState", "()Z", boolUnary);
        appSettingGetDeformer =
                HostHandles.virtual(appSettingClass, "getDeformer", "()L" + DEFORMER_SETTING_OWNER + ";", objUnary);
        deformerSettingGetRotDeformer = HostHandles.virtual(
                deformerSettingClass, "getRotationDeformer", "()L" + ROT_SETTING_OWNER + ";", objUnary);
        rotSettingGetMode = HostHandles.virtual(rotSettingClass, "getMode", "()L" + ROT_MODE_OWNER + ";", objUnary);
        appSettingGetDev =
                HostHandles.virtual(appSettingClass, "getDeveloper", "()L" + DEV_SETTING_OWNER + ";", objUnary);
        devSettingGetShowChildUnderWarp =
                HostHandles.virtual(devSettingClass, "getShowChildUnderWarpDeformerCtrlMode", "()Z", boolUnary);
        warpOIsCtrlChildMode = HostHandles.virtual(warpOClass, "a", "()Z", boolUnary);
        formAnimIsActive = HostHandles.virtual(
                formAnimTClass,
                "a",
                "(L" + CE_VIEW_OWNER + ";L" + CMODEL_OWNER + ";)Z",
                MethodType.methodType(boolean.class, Object.class, Object.class, Object.class));

        final Object[] morphResolved = resolveMorphTarget(loader);
        morphTargetInstance = morphResolved[0];
        morphTargetApply = (MethodHandle) morphResolved[1];
        morphConstraintEvalInstance = morphResolved[2];
        morphConstraintEval = (MethodHandle) morphResolved[3];
        morphTargetUtilsInstance = tryStaticField(morphUtilsClass, SINGLETON_FIELD);
        morphTargetUseExtended = tryOptionalVirtual(
                morphUtilsClass,
                "d",
                "(L" + AC_CONTROLLABLE_SOURCE_OWNER + ";)Z",
                MethodType.methodType(boolean.class, Object.class, Object.class));
        keyFormMorphCompanion = tryStaticField(HostHandles.load(loader, KEYFORM_MORPH_OWNER), COMPANION_FIELD);
        morphTargetResolveBaseForm = tryOptionalVirtual(
                keyFormMorphCompanionClass,
                "a",
                "(L" + AC_CONTROLLABLE_SOURCE_OWNER + ";)L" + AC_FORM_OWNER + ";",
                setObj.changeReturnType(Object.class));
        morphTargetSetGetList = iMorphSetClass == null
                ? null
                : tryOptionalInterface(
                        iMorphSetClass,
                        "getMorphTargetList",
                        "(L" + CPARAM_GUID_OWNER + ";)Ljava/util/List;",
                        MethodType.methodType(Object.class, Object.class, Object.class));
        morphTargetSetGetConstraintSet = iMorphSetClass == null
                ? null
                : tryOptionalInterface(
                        iMorphSetClass,
                        "getBlendWeightConstraintSet",
                        "()L" + MORPH_CONSTRAINT_SET_OWNER + ";",
                        objUnary);
        morphTargetConstraintGetByParam = tryOptionalVirtual(
                morphConstraintSetClass,
                "getConstraintsByMorphTargetParameter",
                "(L" + CPARAM_GUID_OWNER + ";)Ljava/util/List;",
                MethodType.methodType(Object.class, Object.class, Object.class));
        morphItemGetParameterGuid = iMorphItemClass == null
                ? null
                : tryOptionalInterface(iMorphItemClass, "getParameterGuid", "()L" + CPARAM_GUID_OWNER + ";", objUnary);
        morphItemGetKeyValue = iMorphItemClass == null
                ? null
                : tryOptionalInterface(iMorphItemClass, "getKeyValue", "()Ljava/lang/Float;", objUnary);
        morphItemGetKeyform = iMorphItemClass == null
                ? null
                : tryOptionalInterface(iMorphItemClass, "getKeyform", "()L" + AC_FORM_OWNER + ";", objUnary);

        copyInfoDefault = resolveDefaultCopyInfo(copyInfoClass, copyModeClass);
        controllableGetPreDeformForm =
                tryOptionalVirtual(acControllableClass, "getPreDeformForm", "()L" + AC_FORM_OWNER + ";", objUnary);
        controllableGetPreClampMorphedForm = tryOptionalVirtual(
                acControllableClass, "getPreClampMorphedForm", "()L" + AC_FORM_OWNER + ";", objUnary);
        controllableSetPreClampMorphedForm =
                tryOptionalVirtual(acControllableClass, "setPreClampMorphedForm", "(L" + AC_FORM_OWNER + ";)V", setObj);

        deformerGetGuid = HostHandles.virtual(acDeformerClass, "getGuid", "()L" + DEFORMER_GUID_OWNER + ";", objUnary);
        deformerGetTargetGuid = HostHandles.virtual(
                acDeformerClass, "getTargetDeformerGuid", "()L" + DEFORMER_GUID_OWNER + ";", objUnary);
        deformerGetSource =
                HostHandles.virtual(acDeformerClass, "getSource", "()L" + AC_DEFORMER_SOURCE_OWNER + ";", objUnary);
        deformerGetDirtyDeformedForm = HostHandles.virtual(acDeformerClass, "getDirtyDeformedForm", "()Z", boolUnary);
        deformerSetDirtyDeformedForm = HostHandles.virtual(acDeformerClass, "setDirtyDeformedForm", "(Z)V", setBool);
        deformerGetInterpolatedForm = HostHandles.virtual(
                acDeformerClass, "getInterpolatedForm", "()L" + AC_DEFORMER_FORM_OWNER + ";", objUnary);
        deformerGetDeformedForm =
                HostHandles.virtual(acDeformerClass, "getDeformedForm", "()L" + AC_DEFORMER_FORM_OWNER + ";", objUnary);
        deformerGetAffectedForm =
                tryOptionalVirtual(acDeformerClass, "getAffectedForm", "()L" + AC_DEFORMER_FORM_OWNER + ";", objUnary);
        deformerGetCalculatedForm = HostHandles.virtual(
                acDeformerClass, "getCalculatedForm", "()L" + AC_DEFORMER_FORM_OWNER + ";", objUnary);
        deformerGetAnimatedForm =
                HostHandles.virtual(acDeformerClass, "getAnimatedForm", "()L" + AC_DEFORMER_FORM_OWNER + ";", objUnary);
        deformerGetLocalAnimatedForm = HostHandles.virtual(
                acDeformerClass, "getLocalAnimatedForm", "()L" + AC_DEFORMER_FORM_OWNER + ";", objUnary);
        deformerGetCopiedUpdateVersion =
                HostHandles.virtual(acDeformerClass, "getCopiedUpdateVersion", "()I", intUnary);
        deformerIsParamOutsideHierarchy =
                HostHandles.virtual(acDeformerClass, "isParameterOutsideInHierarchy", "()Z", boolUnary);
        deformerSetParamOutsideAnyParents =
                HostHandles.virtual(acDeformerClass, "setParameterOutsideAnyParents", "(Z)V", setBool);
        deformerGetCalcTotalScale = HostHandles.virtual(acDeformerClass, "getCalculatedTotalScale", "()F", floatUnary);
        deformerSetCalcTotalScale = HostHandles.virtual(acDeformerClass, "setCalculatedTotalScale", "(F)V", setFloat);
        deformerGetCalcTotalOpacity =
                HostHandles.virtual(acDeformerClass, "getCalculatedTotalOpacity", "()F", floatUnary);
        deformerSetCalcTotalOpacity =
                HostHandles.virtual(acDeformerClass, "setCalculatedTotalOpacity", "(F)V", setFloat);
        deformerGetCalcTotalMultiplyColor = HostHandles.virtual(
                acDeformerClass, "getCalculatedTotalMultiplyColor", "()L" + CFLOAT_COLOR_OWNER + ";", objUnary);
        deformerGetCalcTotalScreenColor = HostHandles.virtual(
                acDeformerClass, "getCalculatedTotalScreenColor", "()L" + CFLOAT_COLOR_OWNER + ";", objUnary);
        deformerGetCreateTransform = HostHandles.virtual(
                acDeformerClass, "getCreateLocalToCanvasTransform", "()L" + TRANSFORM_INTERFACE_OWNER + ";", objUnary);
        deformerTransformTestImpl = HostHandles.virtual(
                acDeformerClass, "transformDeformer_testImpl", "(L" + AC_DEFORMER_OWNER + ";)V", setObj);
        deformerGetChildren = HostHandles.virtual(
                acDeformerClass, "getChildren", "()Lcom/live2d/graphics3d/entity/Entities;", objUnary);
        warpDeformerSetDeformedForm = tryOptionalVirtual(
                warpDeformerClass, "setDeformedForm", "(L" + WarpDeformEditTarget.WARP_FORM_OWNER + ";)V", setObj);
        rotDeformerSetDeformedForm =
                tryOptionalVirtual(rotDeformerClass, "setDeformedForm", "(L" + ROT_FORM_OWNER + ";)V", setObj);

        sourceGetUpdateVersion = HostHandles.virtual(acDeformerSourceClass, "getUpdateVersion", "()I", intUnary);
        sourceReinitInstance = HostHandles.virtual(
                acControllableSourceClass, "reinitInstance", "(L" + AC_CONTROLLABLE_OWNER + ";)V", setObj);
        sourceGetKeyforms = HostHandles.virtual(
                acControllableSourceClass, "getKeyforms", "()Lcom/live2d/type/CArrayList;", objUnary);
        sourceGetDefaultKeyForm = tryOptionalVirtual(
                acControllableSourceClass, "getDefaultKeyForm", "()L" + AC_FORM_OWNER + ";", objUnary);
        sourceGetExtKeyformGrid = HostHandles.virtual(
                acControllableSourceClass,
                "getExtendedKeyformGridSourceIfAvailable",
                "()L" + KEYFORM_GRID_SOURCE_OWNER + ";",
                objUnary);
        sourceGetKeyformGrid = HostHandles.virtual(
                acControllableSourceClass, "getKeyformGridSource", "()L" + KEYFORM_GRID_SOURCE_OWNER + ";", objUnary);
        sourceGetCoordType = HostHandles.virtual(
                acControllableSourceClass, "getCoordType", "()L" + COORD_TYPE_OWNER + ";", objUnary);
        sourceGetMorphTargets = HostHandles.virtual(
                acControllableSourceClass, "getKeyformMorphTargets", "()Ljava/util/List;", objUnary);
        sourceGetMorphSet = tryOptionalVirtual(
                acControllableSourceClass, "getKeyformMorphTargetSet", "()L" + MORPH_SET_OWNER + ";", objUnary);
        sourceGetExtMorphSet = tryOptionalVirtual(
                acControllableSourceClass,
                "getExtendedKeyformMorphTargetSetIfAvailable",
                "()L" + EXT_MORPH_SET_OWNER + ";",
                objUnary);
        sourceIsVisibleHierarchy = HostHandles.virtual(acDeformerSourceClass, "isVisibleInHierarchy", "()Z", boolUnary);
        rotSourceGetUseBoneUi = HostHandles.virtual(rotSourceClass, "getUseBoneUi_testImpl", "()Z", boolUnary);

        formGetEditVersion = HostHandles.virtual(acFormClass, "getEditVersion", "()I", intUnary);
        formGetSource =
                tryOptionalVirtual(acFormClass, "getSource", "()L" + AC_CONTROLLABLE_SOURCE_OWNER + ";", objUnary);
        formIsParameterOutside = tryOptionalVirtual(acFormClass, "isParameterOutside", "()Z", boolUnary);
        formZero = iBlendableClass == null
                ? null
                : tryOptionalInterface(iBlendableClass, "zero", "()L" + IBLENDABLE_OWNER + ";", objUnary);
        formBlend = iBlendableClass == null
                ? null
                : tryOptionalInterface(
                        iBlendableClass,
                        "blend",
                        "(L" + IBLENDABLE_OWNER + ";F)V",
                        MethodType.methodType(void.class, Object.class, Object.class, float.class));
        formPostBlend =
                iBlendableClass == null ? null : tryOptionalInterface(iBlendableClass, "postBlend", "()V", voidUnary);
        formDeepCopy = (iCopyableClass == null || copyInfoClass == null)
                ? null
                : tryOptionalInterface(
                        iCopyableClass,
                        "deepCopy",
                        "(L" + COPY_INFO_OWNER + ";)L" + ICOPYABLE_OWNER + ";",
                        MethodType.methodType(Object.class, Object.class, Object.class));
        formReinit = (iCopyableClass == null || copyInfoClass == null)
                ? null
                : tryOptionalInterface(
                        iCopyableClass,
                        "reinit",
                        "(L" + ICOPYABLE_OWNER + ";L" + COPY_INFO_OWNER + ";)V",
                        MethodType.methodType(void.class, Object.class, Object.class, Object.class));
        deformerFormInterpolate = HostHandles.virtual(
                acDeformerFormClass,
                "interpolate__testImpl",
                "(L" + KEYFORM_GRID_SOURCE_OWNER + ";L" + CPARAM_SET_OWNER + ";L" + updCtxInternal + ";)V",
                MethodType.methodType(void.class, Object.class, Object.class, Object.class, Object.class));
        deformerFormSetCoordType =
                HostHandles.virtual(acDeformerFormClass, "setCoordType", "(L" + COORD_TYPE_OWNER + ";)V", setObj);
        deformerFormGetOpacity = HostHandles.virtual(acDeformerFormClass, "getOpacity", "()F", floatUnary);
        deformerFormSetOpacity = tryOptionalVirtual(acDeformerFormClass, "setOpacity", "(F)V", setFloat);
        deformerFormGetMultiplyColor = HostHandles.virtual(
                acDeformerFormClass, "getMultiplyColor", "()L" + CFLOAT_COLOR_OWNER + ";", objUnary);
        deformerFormGetScreenColor =
                HostHandles.virtual(acDeformerFormClass, "getScreenColor", "()L" + CFLOAT_COLOR_OWNER + ";", objUnary);
        warpFormGetPositions = HostHandles.virtual(warpFormClass, "getPositions", "()[F", floatArrUnary);
        warpFormSetPositions = tryOptionalVirtual(
                warpFormClass, "setPositions", "([F)V", MethodType.methodType(void.class, Object.class, float[].class));
        rotFormGetOriginX = HostHandles.virtual(rotFormClass, "getOriginX", "()F", floatUnary);
        rotFormGetOriginY = HostHandles.virtual(rotFormClass, "getOriginY", "()F", floatUnary);
        rotFormGetAngle = HostHandles.virtual(rotFormClass, "getAngle", "()F", floatUnary);
        rotFormGetScale = HostHandles.virtual(rotFormClass, "getScale", "()F", floatUnary);
        rotFormSetAngle = tryOptionalVirtual(rotFormClass, "setAngle", "(F)V", setFloat);
        rotFormSetScale = tryOptionalVirtual(rotFormClass, "setScale", "(F)V", setFloat);
        rotFormIsReflectX = HostHandles.virtual(rotFormClass, "isReflectX", "()Z", boolUnary);
        rotFormIsReflectY = HostHandles.virtual(rotFormClass, "isReflectY", "()Z", boolUnary);

        floatColorGetR = HostHandles.virtual(floatColorClass, "getR", "()F", floatUnary);
        floatColorGetG = HostHandles.virtual(floatColorClass, "getG", "()F", floatUnary);
        floatColorGetB = HostHandles.virtual(floatColorClass, "getB", "()F", floatUnary);
        floatColorGetA = HostHandles.virtual(floatColorClass, "getA", "()F", floatUnary);
        floatColorSetR = HostHandles.virtual(floatColorClass, "setR", "(F)V", setFloat);
        floatColorSetG = HostHandles.virtual(floatColorClass, "setG", "(F)V", setFloat);
        floatColorSetB = HostHandles.virtual(floatColorClass, "setB", "(F)V", setFloat);
        floatColorSetA = HostHandles.virtual(floatColorClass, "setA", "(F)V", setFloat);
        floatColorClamp = tryOptionalVirtual(floatColorClass, "clamp", "()L" + CFLOAT_COLOR_OWNER + ";", objUnary);
        colorGetRed = HostHandles.virtual(colorClass, "getRed", "()F", floatUnary);
        colorGetGreen = HostHandles.virtual(colorClass, "getGreen", "()F", floatUnary);
        colorGetBlue = HostHandles.virtual(colorClass, "getBlue", "()F", floatUnary);
        colorGetAlpha = HostHandles.virtual(colorClass, "getAlpha", "()F", floatUnary);

        meshIsInvisible = tryOptionalVirtual(artMeshClass, "isInvisible", "()Z", boolUnary);

        meshGetGuid = HostHandles.virtual(artMeshClass, "getGuid", "()L" + DRAWABLE_GUID_OWNER + ";", objUnary);
        meshGetTargetGuid =
                HostHandles.virtual(artMeshClass, "getTargetDeformerGuid", "()L" + DEFORMER_GUID_OWNER + ";", objUnary);
        meshGetSource = HostHandles.virtual(artMeshClass, "getSource", "()L" + ART_MESH_SOURCE_OWNER + ";", objUnary);
        meshGetInterpolatedForm =
                HostHandles.virtual(artMeshClass, "getInterpolatedForm", "()L" + ART_MESH_FORM_OWNER + ";", objUnary);
        meshGetDeformedForm =
                HostHandles.virtual(artMeshClass, "getDeformedForm", "()L" + ART_MESH_FORM_OWNER + ";", objUnary);
        meshSetDeformedForm =
                HostHandles.virtual(artMeshClass, "setDeformedForm", "(L" + ART_MESH_FORM_OWNER + ";)V", setObj);
        meshGetAffectedForm =
                HostHandles.virtual(artMeshClass, "getAffectedForm", "()L" + ART_MESH_FORM_OWNER + ";", objUnary);
        meshSetAffectedForm =
                HostHandles.virtual(artMeshClass, "setAffectedForm", "(L" + ART_MESH_FORM_OWNER + ";)V", setObj);
        meshGetCalculatedForm =
                HostHandles.virtual(artMeshClass, "getCalculatedForm", "()L" + ART_MESH_FORM_OWNER + ";", objUnary);
        meshGetAnimatedForm =
                HostHandles.virtual(artMeshClass, "getAnimatedForm", "()L" + ART_MESH_FORM_OWNER + ";", objUnary);
        meshGetLocalAnimatedForm =
                HostHandles.virtual(artMeshClass, "getLocalAnimatedForm", "()L" + ART_MESH_FORM_OWNER + ";", objUnary);
        meshGetCopiedMeshVersion = HostHandles.virtual(artMeshClass, "getCopiedMeshVersion", "()I", intUnary);
        meshSetParamOutsideAnyParents =
                HostHandles.virtual(artMeshClass, "setParameterOutsideAnyParents", "(Z)V", setBool);
        meshGetDrawOrder = HostHandles.virtual(acDrawableClass, "getDrawOrder", "()I", intUnary);
        meshGetContainer = HostHandles.virtual(
                artMeshClass, "getMeshContainer", "()L" + WarpDeformEditTarget.GMESH_CONTAINER_OWNER + ";", objUnary);
        meshContainerGetMesh = HostHandles.virtual(
                meshContainerClass, "getMesh", "()L" + WarpDeformEditTarget.MESH_BASE_OWNER + ";", objUnary);
        meshBaseGetPositions = HostHandles.virtual(meshBaseClass, "getPositions", "()[F", floatArrUnary);
        meshSourceGetMeshVersion = HostHandles.virtual(artMeshSourceClass, "getMeshVersion", "()I", intUnary);
        meshSourceIsVisibleHierarchy =
                HostHandles.virtual(artMeshSourceClass, "isVisibleInHierarchy", "()Z", boolUnary);
        meshSourceIsVisible = HostHandles.virtual(artMeshSourceClass, "isVisible", "()Z", boolUnary);
        meshFormGetPositions = HostHandles.virtual(artMeshFormClass, "getPositions", "()[F", floatArrUnary);
        meshFormTransform = HostHandles.virtual(
                artMeshFormClass,
                "transform",
                "(L" + TRANSFORM_INTERFACE_OWNER + ";L" + AC_FORM_OWNER + ";)L" + ART_MESH_FORM_OWNER + ";",
                MethodType.methodType(Object.class, Object.class, Object.class, Object.class));
        meshFormIsCompatible = HostHandles.virtual(
                artMeshFormClass,
                "isCompatible",
                "(L" + AC_FORM_OWNER + ";)Z",
                MethodType.methodType(boolean.class, Object.class, Object.class));
        meshFormSetCoordType =
                HostHandles.virtual(artMeshFormClass, "setCoordType", "(L" + COORD_TYPE_OWNER + ";)V", setObj);
        meshFormGetOpacity = HostHandles.virtual(artMeshFormClass, "getOpacity", "()F", floatUnary);
        meshFormGetMultiplyColor =
                HostHandles.virtual(artMeshFormClass, "getMultiplyColor", "()L" + CFLOAT_COLOR_OWNER + ";", objUnary);
        meshFormGetScreenColor =
                HostHandles.virtual(artMeshFormClass, "getScreenColor", "()L" + CFLOAT_COLOR_OWNER + ";", objUnary);

        artPathGetTargetGuid =
                HostHandles.virtual(artPathClass, "getTargetDeformerGuid", "()L" + DEFORMER_GUID_OWNER + ";", objUnary);
        partGetSource = HostHandles.virtual(partClass, "getSource", "()L" + CPART_SOURCE_OWNER + ";", objUnary);
        partSourceIsVisible = HostHandles.virtual(partSourceClass, "isVisible", "()Z", boolUnary);
        partSourceIsVisibleHierarchy = HostHandles.virtual(partSourceClass, "isVisibleInHierarchy", "()Z", boolUnary);
        partGetInterpolatedForm =
                HostHandles.virtual(partClass, "getInterpolatedForm", "()L" + AC_FORM_OWNER + ";", objUnary);
        partGetDrawOrder = HostHandles.virtual(partClass, "getDrawOrder", "()I", intUnary);

        glueGetSource = HostHandles.virtual(glueClass, "getSource", "()L" + GLUE_SOURCE_OWNER + ";", objUnary);
        glueGetInterpolatedForm =
                HostHandles.virtual(glueClass, "getInterpolatedForm", "()L" + GLUE_FORM_OWNER + ";", objUnary);
        glueGetCachedEditVersion = HostHandles.virtual(
                HostHandles.load(loader, AC_AFFECTER_OWNER), "getCached_editVersion", "()I", intUnary);
        glueSourceGetEditVersion = HostHandles.virtual(glueSourceClass, "getEditVersion$cubism", "()I", intUnary);
        glueSourceGetMeshAGuid = HostHandles.virtual(
                glueSourceClass, "getTargetArtMeshA_guid", "()L" + DRAWABLE_GUID_OWNER + ";", objUnary);
        glueSourceGetMeshBGuid = HostHandles.virtual(
                glueSourceClass, "getTargetArtMeshB_guid", "()L" + DRAWABLE_GUID_OWNER + ";", objUnary);
        glueFormGetIntensity = HostHandles.virtual(glueFormClass, "getIntensity", "()F", floatUnary);

        if (conflictPolygonFlag) {
            conflictPolyInterface = HostHandles.load(loader, CONFLICT_POLY_OWNER);
            conflictGetCount =
                    HostHandles.virtual(conflictPolyInterface, "getCalcConflictPolygonCount", "()I", intUnary);
        } else {
            conflictPolyInterface = null;
            conflictGetCount = null;
        }

        actionPackGetEditMode =
                HostHandles.virtual(actionPackBaseClass, "f", "()L" + EDIT_MODE_MAIN_OWNER + ";", objUnary);
        actionPackGetSelected = HostHandles.virtual(actionPackClass, "r", "()Ljava/util/List;", objUnary);
        actionPackGetQ = tryOptionalVirtual(actionPackBaseClass, "Q", "()Ljava/util/List;", objUnary);
        actionPackGetS = tryOptionalVirtual(actionPackBaseClass, "S", "()Ljava/util/List;", objUnary);
        actionPackGetU = tryOptionalVirtual(actionPackBaseClass, "U", "()Ljava/util/List;", objUnary);
        actionPackGetF = HostHandles.virtual(actionPackBaseClass, "F", "()Ljava/util/List;", objUnary);
        actionPackGetG = HostHandles.virtual(actionPackBaseClass, "G", "()Ljava/util/List;", objUnary);
        actionPackGetW = tryOptionalVirtual(actionPackBaseClass, "w", "()Ljava/util/Set;", objUnary);
        actionPackGetXSet = tryOptionalVirtual(actionPackBaseClass, "x", "()Ljava/util/Set;", objUnary);
        actionPackGetK = tryOptionalVirtual(actionPackBaseClass, "K", "()Ljava/util/List;", objUnary);
        actionPackGetD = tryOptionalVirtual(actionPackBaseClass, "D", "()Ljava/util/List;", objUnary);
        actionPackGetLList = tryOptionalVirtual(actionPackBaseClass, "L", "()Ljava/util/List;", objUnary);
        actionPackGetAm = tryOptionalMethodReturnType(actionPackBaseClass, "am", objUnary);
        actionPackGetL = HostHandles.virtual(actionPackBaseClass, "l", "()Z", boolUnary);
        actionPackGetM = HostHandles.virtual(actionPackBaseClass, "m", "()Z", boolUnary);
        actionPackGetP = tryOptionalVirtual(actionPackBaseClass, "p", "()Z", boolUnary);

        final Class<?> overlayViewClass = HostHandles.load(
                loader,
                legacyOverlayModel
                        ? WarpDeformEditTarget.LEGACY_OVERLAY_VIEW_OWNER
                        : WarpDeformEditTarget.OVERLAY_VIEW_OWNER);
        overlayViewGetModelingView =
                tryOptionalVirtual(overlayViewClass, "a", "()L" + CE_MODELING_VIEW_OWNER + ";", objUnary);
        overlayViewGetCompletePack =
                tryOptionalVirtual(overlayViewClass, "b", "()L" + COMPLETE_PACK_OWNER + ";", objUnary);
        overlayViewGetModel = tryOptionalVirtual(overlayViewClass, "c", "()L" + CMODEL_OWNER + ";", objUnary);
        if (legacyOverlayModel) {
            jViewGetModelingView = overlayViewGetModelingView;
            actionPackGetDeformers = HostHandles.virtual(actionPackBaseClass, "V", "()Ljava/util/List;", objUnary);
        } else {
            jViewGetModelingView = null;
            actionPackGetDeformers = tryOptionalVirtual(actionPackClass, "X", "()Ljava/util/List;", objUnary);
        }

        entitySetEnabled = HostHandles.virtual(entityClass, "setEnabled", "(Z)V", setBool);
        entityGetEnabled = HostHandles.virtual(entityClass, "getEnabled", "()Z", boolUnary);
        entityGetParent = HostHandles.virtual(
                entityClass, "getParentEntity", "()L" + WarpDeformEditTarget.GENTITY_OWNER + ";", objUnary);
        sceneGraphSetVolatile = HostHandles.virtual(
                sceneGraphClass, "setVolatile", "(L" + WarpDeformEditTarget.GENTITY_OWNER + ";)V", setObj);
        sceneGraphWillRenderField = tryOptionalField(sceneGraphClass, "volatileEntities_willRender");
        artMeshSelectionDrawGetMesh =
                tryOptionalVirtual(selectionDrawMeshClass, "getArtMesh", "()L" + ART_MESH_OWNER + ";", objUnary);
        meshSourceGetSelection = tryOptionalVirtual(
                artMeshSourceClass, "getSelection", "()L" + ART_MESH_SELECTION_OWNER + ";", objUnary);
        final MethodType drawUpdateType = MethodType.methodType(void.class, Object.class, Object.class, boolean.class);
        final String drawUpdateDesc = "(L" + ACTION_PACK_OWNER + ";Z)V";
        warpDrawUpdate = HostHandles.virtual(warpDrawClass, "update", drawUpdateDesc, drawUpdateType);
        rotNormalDrawUpdate = HostHandles.virtual(rotDrawNormalClass, "update", drawUpdateDesc, drawUpdateType);
        rotBoneDrawUpdate = HostHandles.virtual(rotDrawBoneClass, "update", drawUpdateDesc, drawUpdateType);

        final EditSubtreeCompiledAccessors.HostOps compiled = EditSubtreeCompiledAccessors.tryCompile(loader);
        acc = compiled != null ? compiled : EditSubtreeCompiledAccessors.fallback(this);
    }

    /** Resolves all reviewed host handles against {@code loader}, failing closed when absent. */
    static EditSubtreeHandles resolve(final ClassLoader loader) throws ReflectiveOperationException {
        Objects.requireNonNull(loader, "loader");
        final UpdaterMatch updater = findUpdaterMatch(loader);
        return new EditSubtreeHandles(loader, updater);
    }

    private static UpdaterMatch findUpdaterMatch(final ClassLoader loader) throws ReflectiveOperationException {
        final Set<String> tried = new HashSet<>();
        for (final ModelUpdateTargetTable.Row row : ModelUpdateTargetTable.all()) {
            final String key = row.updater()
                    + '|'
                    + row.updateContext()
                    + '|'
                    + row.contextParam()
                    + '|'
                    + row.conflictPolygonFlag();
            if (!tried.add(key)) {
                continue;
            }
            try {
                final Class<?> upd = HostHandles.load(loader, row.updater());
                final Class<?> ctx = HostHandles.load(loader, row.updateContext());
                final Class<?> param = HostHandles.load(loader, row.contextParam());
                final String coreDesc = "(L"
                        + CMODEL_OWNER
                        + ";Ljava/util/List;L"
                        + row.updateContext()
                        + ";L"
                        + CE_VIEW_OWNER
                        + ";L"
                        + row.contextParam()
                        + ";)V";
                HostHandles.virtual(
                        upd,
                        "a",
                        coreDesc,
                        MethodType.methodType(
                                void.class,
                                Object.class,
                                Object.class,
                                Object.class,
                                Object.class,
                                Object.class,
                                Object.class));
                return new UpdaterMatch(row, upd, ctx, param);
            } catch (ReflectiveOperationException ignored) {
                // Continue probing the remaining reviewed host generations.
            }
        }
        throw new ReflectiveOperationException("No reviewed updater build matched classloader " + loader);
    }

    private static Object[] resolveMorphTarget(final ClassLoader loader) {
        final String[] owners = {MORPH_C_OWNER, MORPH_Y_OWNER, MORPH_X_OWNER};
        for (final String owner : owners) {
            try {
                final Class<?> cls = HostHandles.load(loader, owner);
                final Object instance = HostHandles.field(cls, SINGLETON_FIELD).get(null);
                final MethodHandle apply = HostHandles.virtual(
                        cls,
                        "a",
                        "(L" + AC_CONTROLLABLE_OWNER + ";Ljava/util/List;)V",
                        MethodType.methodType(void.class, Object.class, Object.class, Object.class));
                if (instance != null) {
                    final Object[] eval = resolveMorphConstraintEvaluator(loader, owner);
                    return new Object[] {instance, apply, eval[0], eval[1]};
                }
            } catch (Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
                // Probe older morph-target owner names across host releases.
            }
        }
        return new Object[] {null, null, null, null};
    }

    private static Object[] resolveMorphConstraintEvaluator(final ClassLoader loader, final String morphOwner) {
        try {
            final Class<?> inner = HostHandles.load(loader, morphOwner + "$c");
            final Object instance = HostHandles.field(inner, SINGLETON_FIELD).get(null);
            final MethodHandle mh = HostHandles.virtual(
                    inner,
                    "a",
                    "(Ljava/util/List;Ljava/util/List;)Ljava/lang/Float;",
                    MethodType.methodType(Object.class, Object.class, Object.class, Object.class));
            if (instance != null) {
                return new Object[] {instance, mh};
            }
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
        }
        return new Object[] {null, null};
    }

    private static Class<?> tryOptionalLoad(final ClassLoader loader, final String owner) {
        try {
            return HostHandles.load(loader, owner);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return null;
        }
    }

    private static Object tryStaticField(final Class<?> owner, final String fieldName) {
        if (owner == null) {
            return null;
        }
        try {
            return HostHandles.field(owner, fieldName).get(null);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return null;
        }
    }

    private static HostHandles.HostField tryOptionalField(final Class<?> owner, final String fieldName) {
        if (owner == null) {
            return null;
        }
        try {
            return HostHandles.field(owner, fieldName);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return null;
        }
    }

    private static MethodHandle tryOptionalVirtual(
            final Class<?> owner, final String name, final String descriptor, final MethodType shape) {
        if (owner == null) {
            return null;
        }
        try {
            return HostHandles.virtual(owner, name, descriptor, shape);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return null;
        }
    }

    private static MethodHandle tryOptionalInterface(
            final Class<?> owner, final String name, final String descriptor, final MethodType shape) {
        return tryOptionalVirtual(owner, name, descriptor, shape);
    }

    private static MethodHandle tryOptionalMethodReturnType(
            final Class<?> owner, final String name, final MethodType shape) {
        if (owner == null) {
            return null;
        }
        try {
            final java.lang.reflect.Method method = owner.getMethod(name);
            final String desc = "()L" + method.getReturnType().getName().replace('.', '/') + ";";
            return HostHandles.virtual(owner, name, desc, shape);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return null;
        }
    }

    private static MethodHandle[] resolveModelingViewSettingHandles(
            final Class<?> modelingViewClass, final MethodType objUnary, final MethodType boolUnary) {
        final MethodHandle devGetter = tryOptionalMethodReturnType(modelingViewClass, "getDevelopSetting", objUnary);
        MethodHandle devGetC = null;
        if (devGetter != null) {
            try {
                final Class<?> ret =
                        modelingViewClass.getMethod("getDevelopSetting").getReturnType();
                devGetC = tryOptionalVirtual(ret, "c", "()Z", boolUnary);
            } catch (Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
            }
        }
        final MethodHandle appGetter = tryOptionalMethodReturnType(modelingViewClass, "getAppearanceSetting", objUnary);
        MethodHandle appGetA = null;
        if (appGetter != null) {
            try {
                final Class<?> ret =
                        modelingViewClass.getMethod("getAppearanceSetting").getReturnType();
                appGetA = tryOptionalVirtual(ret, "a", "()Z", boolUnary);
            } catch (Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
            }
        }
        return new MethodHandle[] {devGetter, devGetC, appGetter, appGetA};
    }

    private static Object resolveDefaultCopyInfo(final Class<?> copyInfoClass, final Class<?> copyModeClass) {
        if (copyInfoClass == null || copyModeClass == null) {
            return null;
        }
        try {
            final Object modeA =
                    HostHandles.field(copyModeClass, SINGLETON_FIELD).get(null);
            final java.lang.reflect.Constructor<?> ctor = copyInfoClass.getConstructor(copyModeClass, boolean.class);
            return ctor.newInstance(modeA, false);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return null;
        }
    }

    /** Queries {@code MethodHandle} safely using {@code invokeExact} under the private lookup. */
    static long packColorRgba(final EditSubtreeHandles h, final Object floatColor) {
        final int r = Float.floatToRawIntBits(h.acc.colorGetR(floatColor));
        final int g = Float.floatToRawIntBits(h.acc.colorGetG(floatColor));
        final int b = Float.floatToRawIntBits(h.acc.colorGetB(floatColor));
        final int a = Float.floatToRawIntBits(h.acc.colorGetA(floatColor));
        long hash = r * COLOR_HASH_MIX_A;
        hash = (hash ^ g) * COLOR_HASH_MIX_B;
        hash = (hash ^ b) * COLOR_HASH_MIX_C;
        return hash ^ a;
    }

    static long packCColorRgba(final EditSubtreeHandles h, final Object color) throws Throwable {
        if (color == null) {
            return 0L;
        }
        final int r = Float.floatToRawIntBits((float) h.colorGetRed.invokeExact(color));
        final int g = Float.floatToRawIntBits((float) h.colorGetGreen.invokeExact(color));
        final int b = Float.floatToRawIntBits((float) h.colorGetBlue.invokeExact(color));
        final int a = Float.floatToRawIntBits((float) h.colorGetAlpha.invokeExact(color));
        long hash = r * COLOR_HASH_MIX_A;
        hash = (hash ^ g) * COLOR_HASH_MIX_B;
        hash = (hash ^ b) * COLOR_HASH_MIX_C;
        return hash ^ a;
    }

    static boolean sameFloatColor(
            final float r,
            final float g,
            final float b,
            final float a,
            final Object other,
            final EditSubtreeHandles h) {
        return Float.floatToRawIntBits(r) == Float.floatToRawIntBits(h.acc.colorGetR(other))
                && Float.floatToRawIntBits(g) == Float.floatToRawIntBits(h.acc.colorGetG(other))
                && Float.floatToRawIntBits(b) == Float.floatToRawIntBits(h.acc.colorGetB(other))
                && Float.floatToRawIntBits(a) == Float.floatToRawIntBits(h.acc.colorGetA(other));
    }
}
