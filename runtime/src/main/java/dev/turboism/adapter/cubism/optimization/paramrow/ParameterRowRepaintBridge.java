package dev.turboism.adapter.cubism.optimization.paramrow;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.JComponent;

/**
 * Synchronizes Parameter Palette rows incrementally across unchanged parameters and elides
 * redundant Swing repaints and double-row layout scans.
 */
public final class ParameterRowRepaintBridge implements AutoCloseable {

    /** Explicit system-property switch overriding the derived preference. */
    public static final String ENABLE_PROPERTY = ParameterRowRepaintTarget.ENABLE_PROPERTY;

    /** Property key for the {@code Predicate<Object[]>} incremental selection update callback. */
    public static final String SELECTION_UPDATE_PROPERTY = "turboism.param-row.update-selection";

    /** Property key for the {@code Consumer<Object>} structure invalidation callback. */
    public static final String INVALIDATE_PROPERTY = "turboism.param-row.invalidate";

    /** Property key exposing diagnostic counters. */
    public static final String STATS_PROPERTY = "turboism.param-row.stats";

    private static final Object[] EMPTY_REFS = new Object[0];

    private static final int UPDATE_ARG_COUNT = 3;
    private static final int ARG_CONTROLLER_INDEX = 0;
    private static final int ARG_DOCUMENT_INDEX = 1;
    private static final int ARG_MODEL_INDEX = 2;

    private static final long SPLITMIX_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long SPLITMIX_MUL_1 = 0xBF58476D1CE4E5B9L;
    private static final long SPLITMIX_MUL_2 = 0x94D049BB133111EBL;
    private static final int SPLITMIX_SHIFT_1 = 30;
    private static final int SPLITMIX_SHIFT_2 = 27;
    private static final int SPLITMIX_SHIFT_3 = 31;

    private static final long KEYFORM_PRIMARY_SALT = 0x243F6A8885A308D3L;
    private static final long KEYFORM_EXTENDED_SALT = 0x13198A2E03707344L;
    private static final long KEYFORM_MORPH_SALT = 0xA4093822299F31D0L;
    private static final long INITIAL_PASS_ID = 1L;
    private static final int FLAG_TRUE_HASH = 1231;
    private static final int FLAG_FALSE_HASH = 1237;
    private static final int NULL_COLOR_ARGB = 0;
    private static final long SINGLE_ROW_KIND_TAG = 1L;
    private static final long DOUBLE_ROW_KIND_TAG = 2L;
    private static final long GROUP_ROW_KIND_TAG = 3L;
    private static final long GENERIC_ROW_KIND_TAG = 4L;

    private static final String STAT_INVOCATIONS = "invocations";
    private static final String STAT_BASELINE_PASSES = "baselinePasses";
    private static final String STAT_INCREMENTAL_PASSES = "incrementalPasses";
    private static final String STAT_ELIDED_ROWS = "elidedRows";
    private static final String STAT_REPAINTED_ROWS = "repaintedRows";
    private static final String STAT_RESIZED_DOUBLES = "resizedDoubles";
    private static final String STAT_INVALIDATIONS = "invalidations";
    private static final String STAT_FAILURES = "failures";

    private final Class<?> singleRowType;
    private final Class<?> doubleRowType;
    private final Class<?> groupRowType;
    private final Class<?> paramRowType;
    private final Class<?> controllableType;
    private final Class<?> widgetType;

    private final MethodHandle childrenBoxGetHandle;
    private final MethodHandle rowListGetHandle;
    private final MethodHandle selectedObjectsGetHandle;
    private final MethodHandle highlightGetHandle;
    private final MethodHandle highlightSetHandle;
    private final MethodHandle highlightGuidHandle;
    private final MethodHandle selectedRowsHandle;
    private final MethodHandle showKeyformsHandle;

    private final MethodHandle rowLayoutChildrenHandle;
    private final MethodHandle rowParam1IdHandle;
    private final MethodHandle rowParam1ValGetHandle;
    private final MethodHandle rowParam1ValSetHandle;
    private final MethodHandle rowParam2IdHandle;
    private final MethodHandle rowParam2ValGetHandle;
    private final MethodHandle rowParam2ValSetHandle;

    private final MethodHandle singleParamHandle;
    private final MethodHandle singleBlockerHandle;
    private final MethodHandle singleSetSelectedObjectsHandle;
    private final MethodHandle singleMainWidgetHandle;

    private final MethodHandle doubleParam1Handle;
    private final MethodHandle doubleParam2Handle;
    private final MethodHandle doubleBlockerHandle;
    private final MethodHandle doubleCombinedEnableHandle;
    private final MethodHandle doubleResizeHandle;
    private final MethodHandle doubleMainWidgetHandle;

    private final MethodHandle groupDataHandle;
    private final MethodHandle groupFoldingPaneHandle;
    private final MethodHandle groupRowLayoutHandle;
    private final MethodHandle groupMainWidgetHandle;
    private final MethodHandle foldingExpandHandle;
    private final MethodHandle blockerIsBlockHandle;

    private final MethodHandle widgetRepaintHandle;
    private final MethodHandle widgetVisibleHandle;
    private final MethodHandle widgetBgColorHandle;
    private final MethodHandle widgetJComponentHandle;
    private final MethodHandle colorJColorHandle;

    private final MethodHandle groupNameHandle;
    private final MethodHandle groupVisColorHandle;
    private final MethodHandle groupFolderOpenedHandle;

    private final MethodHandle modelParamSetHandle;
    private final MethodHandle modelAllObjectsHandle;
    private final MethodHandle paramSetGetHandle;
    private final MethodHandle paramGetValueHandle;

    private final MethodHandle srcGuidHandle;
    private final MethodHandle srcIdHandle;
    private final MethodHandle srcNameHandle;
    private final MethodHandle srcMinHandle;
    private final MethodHandle srcMaxHandle;
    private final MethodHandle srcDefaultHandle;
    private final MethodHandle srcRepeatHandle;
    private final MethodHandle srcCombinedHandle;
    private final MethodHandle srcMorphTargetHandle;
    private final MethodHandle srcTypeHandle;
    private final MethodHandle srcVisColorHandle;

    private final MethodHandle controllableSourceHandle;
    private final MethodHandle primaryGridHandle;
    private final MethodHandle extendedGridHandle;
    private final MethodHandle morphTargetSetHandle;
    private final MethodHandle extendedMorphTargetSetHandle;
    private final MethodHandle gridBindingsHandle;
    private final MethodHandle bindingGuidHandle;
    private final MethodHandle bindingKeysHandle;
    private final MethodHandle morphTargetsHandle;
    private final MethodHandle morphGuidHandle;
    private final MethodHandle morphKeyValueHandle;

    private final AtomicBoolean active = new AtomicBoolean();
    private final Object lock = new Object();
    private final Map<Object, ControllerSnapshot> cache = new WeakHashMap<>();

    private final Predicate<Object[]> selectionCallback = this::updateSelection;
    private final Consumer<Object> invalidateCallback = this::invalidateController;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;

    private Properties installedProperties;

    private long invocations;
    private long baselinePasses;
    private long incrementalPasses;
    private long elidedRows;
    private long repaintedRows;
    private long resizedDoubles;
    private long invalidations;
    private long failures;

    private static final int MODEL_BITS_INDEX = 0;
    private static final int ROW_BITS_INDEX = 1;
    private static final int CACHED_VALUE_PAIR_SIZE = 2;

    private static final class RowSnapshot {
        long lastVisitedPass;
        long visualHash;
        long doubleResizeHash;
        final int[] val1Bits = new int[CACHED_VALUE_PAIR_SIZE];
        final int[] val2Bits = new int[CACHED_VALUE_PAIR_SIZE];
    }

    private static final class ControllerSnapshot {
        final WeakReference<Object> docRef;
        final WeakReference<Object> modelRef;
        final IdentityHashMap<Object, RowSnapshot> rows = new IdentityHashMap<>();
        final HashMap<Object, long[]> keyformHashes = new HashMap<>();
        Object[] selectedObjects = EMPTY_REFS;
        Object[] selectedPaletteRows = EMPTY_REFS;
        boolean showKeyforms;
        int rootChildrenCount;
        int flatRowsCount;
        long passId = INITIAL_PASS_ID;

        ControllerSnapshot(final Object doc, final Object model) {
            this.docRef = new WeakReference<>(doc);
            this.modelRef = new WeakReference<>(model);
        }
    }

    private record PassContext(
            Object model,
            Object paramSet,
            List<?> selectedObjects,
            Object[] selectedPaletteRows,
            boolean showKeyforms,
            boolean selectionChanged,
            Object highlightGuid,
            ControllerSnapshot snapshot,
            long passId,
            boolean baseline) {}

    /**
     * Resolves all reviewed host classes, fields, and method handles on the admitted host class loader.
     *
     * @param loader admitted host class loader
     * @param target reviewed version target descriptors
     * @throws ReflectiveOperationException when any reviewed member is unavailable
     */
    public ParameterRowRepaintBridge(
            final ClassLoader loader, final ParameterRowRepaintTarget target)
            throws ReflectiveOperationException {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(target, "target");

        final Class<?> controllerType = HostHandles.load(loader, target.controllerOwner());
        final Class<?> highlightType = HostHandles.load(loader, target.highlightInnerOwner());
        final Class<?> rowLayoutType = HostHandles.load(loader, ParameterRowRepaintTarget.ROW_LAYOUT_OWNER);
        paramRowType = HostHandles.load(loader, ParameterRowRepaintTarget.PARAM_ROW_INTERFACE);
        singleRowType = HostHandles.load(loader, ParameterRowRepaintTarget.SINGLE_ROW_OWNER);
        doubleRowType = HostHandles.load(loader, ParameterRowRepaintTarget.DOUBLE_ROW_OWNER);
        groupRowType = HostHandles.load(loader, ParameterRowRepaintTarget.GROUP_ROW_OWNER);
        widgetType = HostHandles.load(loader, ParameterRowRepaintTarget.WIDGET_OWNER);
        final Class<?> foldingPaneType = HostHandles.load(loader, ParameterRowRepaintTarget.FOLDING_PANE_OWNER);
        final Class<?> blockerType = HostHandles.load(loader, ParameterRowRepaintTarget.BLOCKER_OWNER);
        final Class<?> colorType = HostHandles.load(loader, ParameterRowRepaintTarget.COLOR_OWNER);
        final Class<?> paramGroupType =
                HostHandles.load(loader, ParameterRowRepaintTarget.PARAMETER_GROUP_OWNER);
        final Class<?> modelType = HostHandles.load(loader, ParameterRowRepaintTarget.MODEL_OWNER);
        final Class<?> paramSetType = HostHandles.load(loader, ParameterRowRepaintTarget.PARAMETER_SET_OWNER);
        final Class<?> paramType = HostHandles.load(loader, ParameterRowRepaintTarget.PARAMETER_OWNER);
        final Class<?> paramSourceType =
                HostHandles.load(loader, ParameterRowRepaintTarget.PARAMETER_SOURCE_OWNER);
        controllableType = HostHandles.load(loader, ParameterRowRepaintTarget.CONTROLLABLE_INTERFACE);
        final Class<?> controllableSourceType =
                HostHandles.load(loader, ParameterRowRepaintTarget.CONTROLLABLE_SOURCE_OWNER);
        final Class<?> gridSourceType =
                HostHandles.load(loader, ParameterRowRepaintTarget.KEYFORM_GRID_SOURCE_OWNER);
        final Class<?> bindingSourceType =
                HostHandles.load(loader, ParameterRowRepaintTarget.KEYFORM_BINDING_SOURCE_OWNER);
        final Class<?> morphSetType =
                HostHandles.load(loader, ParameterRowRepaintTarget.MORPH_TARGET_SET_OWNER);
        final Class<?> morphTargetType =
                HostHandles.load(loader, ParameterRowRepaintTarget.MORPH_TARGET_OWNER);

        final MethodType objToObj = MethodType.methodType(Object.class, Object.class);
        final MethodType objToList = MethodType.methodType(List.class, Object.class);
        final MethodType objToBool = MethodType.methodType(boolean.class, Object.class);
        final MethodType objToFloat = MethodType.methodType(float.class, Object.class);
        final MethodType objFloatToVoid = MethodType.methodType(void.class, Object.class, float.class);
        final MethodType objToVoid = MethodType.methodType(void.class, Object.class);
        final MethodType objObjToVoid = MethodType.methodType(void.class, Object.class, Object.class);
        final MethodType objObjToObj = MethodType.methodType(Object.class, Object.class, Object.class);

        childrenBoxGetHandle = HostHandles.getter(
                controllerType,
                ParameterRowRepaintTarget.CHILDREN_BOX_FIELD,
                ParameterRowRepaintTarget.CHILDREN_BOX_DESCRIPTOR,
                objToObj);
        rowListGetHandle = HostHandles.getter(
                controllerType,
                ParameterRowRepaintTarget.ROW_LIST_FIELD,
                ParameterRowRepaintTarget.ROW_LIST_DESCRIPTOR,
                MethodType.methodType(List.class, Object.class));
        selectedObjectsGetHandle = HostHandles.getter(
                controllerType,
                ParameterRowRepaintTarget.SELECTED_OBJECTS_FIELD,
                ParameterRowRepaintTarget.SELECTED_OBJECTS_DESCRIPTOR,
                objToList);
        highlightGetHandle = HostHandles.getter(
                controllerType, target.highlightField(), target.highlightFieldDescriptor(), objToObj);
        highlightSetHandle = HostHandles.setter(
                controllerType, target.highlightField(), target.highlightFieldDescriptor(), objObjToVoid);
        highlightGuidHandle = HostHandles.virtual(
                highlightType,
                ParameterRowRepaintTarget.HIGHLIGHT_GUID_METHOD,
                ParameterRowRepaintTarget.PARAM_GUID_GETTER_DESCRIPTOR,
                objToObj);
        selectedRowsHandle = HostHandles.virtual(
                controllerType,
                ParameterRowRepaintTarget.GET_SELECTED_ROWS_METHOD,
                ParameterRowRepaintTarget.LIST_GETTER_DESCRIPTOR,
                objToList);
        showKeyformsHandle = HostHandles.virtual(
                controllerType,
                target.showKeyformsMethod(),
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                objToBool);

        rowLayoutChildrenHandle = HostHandles.virtual(
                rowLayoutType,
                ParameterRowRepaintTarget.GET_CHILDREN_METHOD,
                ParameterRowRepaintTarget.LIST_GETTER_DESCRIPTOR,
                objToList);
        rowParam1IdHandle = HostHandles.virtual(
                paramRowType,
                ParameterRowRepaintTarget.GET_PARAM1_ID_METHOD,
                ParameterRowRepaintTarget.PARAM_ID_GETTER_DESCRIPTOR,
                objToObj);
        rowParam1ValGetHandle = HostHandles.virtual(
                paramRowType,
                ParameterRowRepaintTarget.GET_PARAM1_VALUE_METHOD,
                ParameterRowRepaintTarget.FLOAT_GETTER_DESCRIPTOR,
                objToFloat);
        rowParam1ValSetHandle = HostHandles.virtual(
                paramRowType,
                ParameterRowRepaintTarget.SET_PARAM1_VALUE_METHOD,
                ParameterRowRepaintTarget.FLOAT_SETTER_DESCRIPTOR,
                objFloatToVoid);
        rowParam2IdHandle = HostHandles.virtual(
                paramRowType,
                ParameterRowRepaintTarget.GET_PARAM2_ID_METHOD,
                ParameterRowRepaintTarget.PARAM_ID_GETTER_DESCRIPTOR,
                objToObj);
        rowParam2ValGetHandle = HostHandles.virtual(
                paramRowType,
                ParameterRowRepaintTarget.GET_PARAM2_VALUE_METHOD,
                ParameterRowRepaintTarget.FLOAT_GETTER_DESCRIPTOR,
                objToFloat);
        rowParam2ValSetHandle = HostHandles.virtual(
                paramRowType,
                ParameterRowRepaintTarget.SET_PARAM2_VALUE_METHOD,
                ParameterRowRepaintTarget.FLOAT_SETTER_DESCRIPTOR,
                objFloatToVoid);

        singleParamHandle = HostHandles.virtual(
                singleRowType,
                ParameterRowRepaintTarget.GET_PARAMETER_METHOD,
                ParameterRowRepaintTarget.PARAM_SOURCE_GETTER_DESCRIPTOR,
                objToObj);
        singleBlockerHandle = HostHandles.virtual(
                singleRowType,
                ParameterRowRepaintTarget.GET_BLOCKER_METHOD,
                ParameterRowRepaintTarget.BLOCKER_GETTER_DESCRIPTOR,
                objToObj);
        singleSetSelectedObjectsHandle = HostHandles.virtual(
                singleRowType,
                ParameterRowRepaintTarget.SET_SELECTED_OBJECTS_METHOD,
                ParameterRowRepaintTarget.SET_SELECTED_OBJECTS_DESCRIPTOR,
                MethodType.methodType(void.class, Object.class, List.class));
        singleMainWidgetHandle = HostHandles.virtual(
                singleRowType,
                ParameterRowRepaintTarget.GET_MAIN_WIDGET_METHOD,
                ParameterRowRepaintTarget.WIDGET_GETTER_DESCRIPTOR,
                objToObj);

        doubleParam1Handle = HostHandles.virtual(
                doubleRowType,
                ParameterRowRepaintTarget.GET_PARAMETER_METHOD,
                ParameterRowRepaintTarget.PARAM_SOURCE_GETTER_DESCRIPTOR,
                objToObj);
        doubleParam2Handle = HostHandles.virtual(
                doubleRowType,
                ParameterRowRepaintTarget.GET_PARAMETER_SEC_METHOD,
                ParameterRowRepaintTarget.PARAM_SOURCE_GETTER_DESCRIPTOR,
                objToObj);
        doubleBlockerHandle = HostHandles.virtual(
                doubleRowType,
                ParameterRowRepaintTarget.GET_BLOCKER_METHOD,
                ParameterRowRepaintTarget.BLOCKER_GETTER_DESCRIPTOR,
                objToObj);
        doubleCombinedEnableHandle = HostHandles.virtual(
                doubleRowType,
                ParameterRowRepaintTarget.GET_COMBINED_ENABLE_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                objToBool);
        doubleResizeHandle = HostHandles.virtual(
                doubleRowType,
                ParameterRowRepaintTarget.RESIZE_METHOD,
                ParameterRowRepaintTarget.VOID_METHOD_DESCRIPTOR,
                objToVoid);
        doubleMainWidgetHandle = HostHandles.virtual(
                doubleRowType,
                ParameterRowRepaintTarget.GET_MAIN_WIDGET_METHOD,
                ParameterRowRepaintTarget.WIDGET_GETTER_DESCRIPTOR,
                objToObj);

        groupDataHandle = HostHandles.virtual(
                groupRowType,
                ParameterRowRepaintTarget.GET_PARAMETER_GROUP_METHOD,
                ParameterRowRepaintTarget.PARAMETER_GROUP_GETTER_DESCRIPTOR,
                objToObj);
        groupFoldingPaneHandle = HostHandles.virtual(
                groupRowType,
                ParameterRowRepaintTarget.GET_FOLDING_PANE_METHOD,
                ParameterRowRepaintTarget.FOLDING_PANE_GETTER_DESCRIPTOR,
                objToObj);
        groupRowLayoutHandle = HostHandles.virtual(
                groupRowType,
                ParameterRowRepaintTarget.GET_ROW_LAYOUT_METHOD,
                ParameterRowRepaintTarget.ROW_LAYOUT_GETTER_DESCRIPTOR,
                objToObj);
        groupMainWidgetHandle = HostHandles.virtual(
                groupRowType,
                ParameterRowRepaintTarget.GET_MAIN_WIDGET_METHOD,
                ParameterRowRepaintTarget.WIDGET_GETTER_DESCRIPTOR,
                objToObj);
        foldingExpandHandle = HostHandles.virtual(
                foldingPaneType,
                ParameterRowRepaintTarget.IS_EXPAND_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                objToBool);
        blockerIsBlockHandle = HostHandles.virtual(
                blockerType,
                ParameterRowRepaintTarget.IS_BLOCK_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                objToBool);

        widgetRepaintHandle = HostHandles.virtual(
                widgetType,
                ParameterRowRepaintTarget.REPAINT_METHOD,
                ParameterRowRepaintTarget.VOID_METHOD_DESCRIPTOR,
                objToVoid);
        widgetVisibleHandle = HostHandles.virtual(
                widgetType,
                ParameterRowRepaintTarget.IS_VISIBLE_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                objToBool);
        widgetBgColorHandle = HostHandles.virtual(
                widgetType,
                ParameterRowRepaintTarget.GET_BACKGROUND_COLOR_METHOD,
                ParameterRowRepaintTarget.COLOR_GETTER_DESCRIPTOR,
                objToObj);
        widgetJComponentHandle = HostHandles.virtual(
                widgetType,
                ParameterRowRepaintTarget.GET_JCOMPONENT_METHOD,
                ParameterRowRepaintTarget.JCOMPONENT_GETTER_DESCRIPTOR,
                MethodType.methodType(JComponent.class, Object.class));
        colorJColorHandle = HostHandles.virtual(
                colorType,
                ParameterRowRepaintTarget.GET_JCOLOR_METHOD,
                ParameterRowRepaintTarget.JCOLOR_GETTER_DESCRIPTOR,
                MethodType.methodType(Color.class, Object.class));

        groupNameHandle = HostHandles.virtual(
                paramGroupType,
                ParameterRowRepaintTarget.GET_NAME_METHOD,
                ParameterRowRepaintTarget.STRING_GETTER_DESCRIPTOR,
                MethodType.methodType(String.class, Object.class));
        groupVisColorHandle = HostHandles.virtual(
                paramGroupType,
                ParameterRowRepaintTarget.GET_VISIBILITY_COLOR_METHOD,
                ParameterRowRepaintTarget.COLOR_GETTER_DESCRIPTOR,
                objToObj);
        groupFolderOpenedHandle = HostHandles.virtual(
                paramGroupType,
                ParameterRowRepaintTarget.GET_FOLDER_OPENED_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                objToBool);

        modelParamSetHandle = HostHandles.virtual(
                modelType,
                ParameterRowRepaintTarget.GET_PARAMETER_SET_METHOD,
                ParameterRowRepaintTarget.PARAMETER_SET_GETTER_DESCRIPTOR,
                objToObj);
        modelAllObjectsHandle = HostHandles.virtual(
                modelType,
                ParameterRowRepaintTarget.GET_ALL_OBJECTS_METHOD,
                ParameterRowRepaintTarget.LIST_GETTER_DESCRIPTOR,
                objToList);
        paramSetGetHandle = HostHandles.virtual(
                paramSetType,
                ParameterRowRepaintTarget.PARAM_SET_GET_METHOD,
                ParameterRowRepaintTarget.PARAM_SET_GET_DESCRIPTOR,
                objObjToObj);
        paramGetValueHandle = HostHandles.virtual(
                paramType,
                ParameterRowRepaintTarget.GET_VALUE_METHOD,
                ParameterRowRepaintTarget.FLOAT_GETTER_DESCRIPTOR,
                objToFloat);

        srcGuidHandle = HostHandles.virtual(
                paramSourceType,
                ParameterRowRepaintTarget.GET_GUID_METHOD,
                ParameterRowRepaintTarget.PARAM_GUID_GETTER_DESCRIPTOR,
                objToObj);
        srcIdHandle = HostHandles.virtual(
                paramSourceType,
                ParameterRowRepaintTarget.GET_ID_METHOD,
                ParameterRowRepaintTarget.PARAM_ID_GETTER_DESCRIPTOR,
                objToObj);
        srcNameHandle = HostHandles.virtual(
                paramSourceType,
                ParameterRowRepaintTarget.GET_NAME_METHOD,
                ParameterRowRepaintTarget.STRING_GETTER_DESCRIPTOR,
                MethodType.methodType(String.class, Object.class));
        srcMinHandle = HostHandles.virtual(
                paramSourceType,
                ParameterRowRepaintTarget.GET_MIN_VALUE_METHOD,
                ParameterRowRepaintTarget.FLOAT_GETTER_DESCRIPTOR,
                objToFloat);
        srcMaxHandle = HostHandles.virtual(
                paramSourceType,
                ParameterRowRepaintTarget.GET_MAX_VALUE_METHOD,
                ParameterRowRepaintTarget.FLOAT_GETTER_DESCRIPTOR,
                objToFloat);
        srcDefaultHandle = HostHandles.virtual(
                paramSourceType,
                ParameterRowRepaintTarget.GET_DEFAULT_VALUE_METHOD,
                ParameterRowRepaintTarget.FLOAT_GETTER_DESCRIPTOR,
                objToFloat);
        srcRepeatHandle = HostHandles.virtual(
                paramSourceType,
                ParameterRowRepaintTarget.IS_REPEAT_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                objToBool);
        srcCombinedHandle = HostHandles.virtual(
                paramSourceType,
                ParameterRowRepaintTarget.GET_COMBINED_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                objToBool);
        srcMorphTargetHandle = HostHandles.virtual(
                paramSourceType,
                ParameterRowRepaintTarget.IS_MORPH_TARGET_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                objToBool);
        srcTypeHandle = HostHandles.virtual(
                paramSourceType,
                ParameterRowRepaintTarget.GET_PARAM_TYPE_METHOD,
                ParameterRowRepaintTarget.PARAM_TYPE_GETTER_DESCRIPTOR,
                objToObj);
        srcVisColorHandle = HostHandles.virtual(
                paramSourceType,
                ParameterRowRepaintTarget.GET_VISIBILITY_COLOR_ALPHA50_METHOD,
                ParameterRowRepaintTarget.COLOR_GETTER_DESCRIPTOR,
                objToObj);

        controllableSourceHandle = HostHandles.virtual(
                controllableType,
                ParameterRowRepaintTarget.GET_SOURCE_METHOD,
                ParameterRowRepaintTarget.CONTROLLABLE_SOURCE_GETTER_DESCRIPTOR,
                objToObj);
        primaryGridHandle = HostHandles.virtual(
                controllableSourceType,
                ParameterRowRepaintTarget.GET_KEYFORM_GRID_METHOD,
                ParameterRowRepaintTarget.KEYFORM_GRID_GETTER_DESCRIPTOR,
                objToObj);
        extendedGridHandle = HostHandles.virtual(
                controllableSourceType,
                ParameterRowRepaintTarget.GET_EXT_KEYFORM_GRID_METHOD,
                ParameterRowRepaintTarget.KEYFORM_GRID_GETTER_DESCRIPTOR,
                objToObj);
        morphTargetSetHandle = HostHandles.virtual(
                controllableSourceType,
                ParameterRowRepaintTarget.GET_MORPH_TARGET_SET_METHOD,
                ParameterRowRepaintTarget.MORPH_TARGET_SET_GETTER_DESCRIPTOR,
                objToObj);
        extendedMorphTargetSetHandle = HostHandles.virtual(
                controllableSourceType,
                ParameterRowRepaintTarget.GET_EXT_MORPH_TARGET_SET_METHOD,
                ParameterRowRepaintTarget.EXT_MORPH_TARGET_SET_GETTER_DESCRIPTOR,
                objToObj);
        gridBindingsHandle = HostHandles.virtual(
                gridSourceType,
                ParameterRowRepaintTarget.GET_KEYFORM_BINDINGS_METHOD,
                ParameterRowRepaintTarget.LIST_GETTER_DESCRIPTOR,
                objToList);
        bindingGuidHandle = HostHandles.virtual(
                bindingSourceType,
                ParameterRowRepaintTarget.GET_PARAMETER_GUID_METHOD,
                ParameterRowRepaintTarget.PARAM_GUID_GETTER_DESCRIPTOR,
                objToObj);
        bindingKeysHandle = HostHandles.virtual(
                bindingSourceType,
                ParameterRowRepaintTarget.GET_KEYS_METHOD,
                ParameterRowRepaintTarget.LIST_GETTER_DESCRIPTOR,
                objToList);
        morphTargetsHandle = HostHandles.virtual(
                morphSetType,
                ParameterRowRepaintTarget.GET_MORPH_TARGETS_METHOD,
                ParameterRowRepaintTarget.LIST_GETTER_DESCRIPTOR,
                objToList);
        morphGuidHandle = HostHandles.virtual(
                morphTargetType,
                ParameterRowRepaintTarget.GET_PARAMETER_GUID_METHOD,
                ParameterRowRepaintTarget.PARAM_GUID_GETTER_DESCRIPTOR,
                objToObj);
        morphKeyValueHandle = HostHandles.virtual(
                morphTargetType,
                ParameterRowRepaintTarget.GET_KEY_VALUE_METHOD,
                ParameterRowRepaintTarget.BOXED_FLOAT_GETTER_DESCRIPTOR,
                MethodType.methodType(Float.class, Object.class));
    }

    /** Publishes incremental selection and invalidation callbacks into {@link System#getProperties()}. */
    public synchronized void install() {
        if (active.get()) {
            throw new IllegalStateException("parameter row repaint bridge already installed");
        }
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(SELECTION_UPDATE_PROPERTY)
                    || properties.containsKey(INVALIDATE_PROPERTY)
                    || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("parameter row repaint bridge slots occupied");
            }
            properties.put(SELECTION_UPDATE_PROPERTY, selectionCallback);
            properties.put(INVALIDATE_PROPERTY, invalidateCallback);
            properties.put(STATS_PROPERTY, statistics);
            installedProperties = properties;
            synchronized (lock) {
                cache.clear();
            }
            active.set(true);
        }
    }

    @Override
    public synchronized void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        synchronized (lock) {
            cache.clear();
        }
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) {
            synchronized (properties) {
                properties.remove(SELECTION_UPDATE_PROPERTY, selectionCallback);
                properties.remove(INVALIDATE_PROPERTY, invalidateCallback);
                properties.remove(STATS_PROPERTY, statistics);
            }
        }
    }

    /** Returns an immutable snapshot of diagnostic counters. */
    public Map<String, Long> snapshot() {
        synchronized (lock) {
            return Map.of(
                    STAT_INVOCATIONS, invocations,
                    STAT_BASELINE_PASSES, baselinePasses,
                    STAT_INCREMENTAL_PASSES, incrementalPasses,
                    STAT_ELIDED_ROWS, elidedRows,
                    STAT_REPAINTED_ROWS, repaintedRows,
                    STAT_RESIZED_DOUBLES, resizedDoubles,
                    STAT_INVALIDATIONS, invalidations,
                    STAT_FAILURES, failures);
        }
    }

    private void invalidateController(final Object controller) {
        if (!active.get() || controller == null) {
            return;
        }
        synchronized (lock) {
            cache.remove(controller);
            invalidations++;
        }
    }

    private boolean updateSelection(final Object[] args) {
        if (!active.get() || args == null || args.length != UPDATE_ARG_COUNT) {
            return false;
        }
        final Object controller = args[ARG_CONTROLLER_INDEX];
        final Object doc = args[ARG_DOCUMENT_INDEX];
        final Object model = args[ARG_MODEL_INDEX];
        if (controller == null || doc == null || model == null) {
            return false;
        }
        try {
            synchronized (lock) {
                invocations++;
                return updateSelectionLocked(controller, doc, model);
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            synchronized (lock) {
                failures++;
                cache.remove(controller);
            }
            return false;
        }
    }

    private boolean updateSelectionLocked(final Object controller, final Object doc, final Object model)
            throws Throwable {
        final Object highlightGuid = consumeHighlightGuid(controller);
        final Object childrenBox = childrenBoxGetHandle.invoke(controller);
        if (childrenBox == null) {
            return false;
        }
        final List<?> rootChildren = (List<?>) rowLayoutChildrenHandle.invoke(childrenBox);
        final List<?> flatRows = (List<?>) rowListGetHandle.invoke(controller);
        final List<?> selectedObjects = (List<?>) selectedObjectsGetHandle.invoke(controller);
        final List<?> paletteSelectedRows = (List<?>) selectedRowsHandle.invoke(controller);
        final boolean showKeyforms = (boolean) showKeyformsHandle.invoke(controller);
        final Object paramSet = modelParamSetHandle.invoke(model);

        ControllerSnapshot snapshot = cache.get(controller);
        final boolean baseline = isStructuralMismatch(
                snapshot, doc, model, rootChildren.size(), flatRows.size());
        if (baseline) {
            snapshot = new ControllerSnapshot(doc, model);
            snapshot.rootChildrenCount = rootChildren.size();
            snapshot.flatRowsCount = flatRows.size();
            cache.put(controller, snapshot);
            baselinePasses++;
        } else {
            incrementalPasses++;
        }

        final long passId = ++snapshot.passId;
        final boolean selectionChanged =
                baseline || !sameElements(snapshot.selectedObjects, selectedObjects);
        if (selectionChanged) {
            snapshot.selectedObjects = copyRefs(selectedObjects);
        }

        final boolean paletteSelectionChanged =
                baseline || !sameElements(snapshot.selectedPaletteRows, paletteSelectedRows);
        if (paletteSelectionChanged) {
            snapshot.selectedPaletteRows = copyRefs(paletteSelectedRows);
        }
        snapshot.showKeyforms = showKeyforms;

        computeKeyformHashes(model, snapshot.keyformHashes);
        final PassContext context = new PassContext(
                model,
                paramSet,
                selectedObjects,
                snapshot.selectedPaletteRows,
                showKeyforms,
                selectionChanged,
                highlightGuid,
                snapshot,
                passId,
                baseline);

        processWidgetList(rootChildren, context);
        if (snapshot.rows.size() > countVisitedRows(snapshot, passId)) {
            pruneUnvisitedRows(snapshot, passId);
        }
        syncRemainingFlatRows(flatRows, paramSet, snapshot, passId);
        return true;
    }

    private Object consumeHighlightGuid(final Object controller) throws Throwable {
        final Object highlight = highlightGetHandle.invoke(controller);
        if (highlight == null) {
            return null;
        }
        // Matches native af()/Y() clearing the transient highlight right as selection updates begin.
        highlightSetHandle.invokeExact(controller, (Object) null);
        return highlightGuidHandle.invokeExact(highlight);
    }

    private static boolean isStructuralMismatch(
            final ControllerSnapshot snapshot,
            final Object doc,
            final Object model,
            final int rootChildrenCount,
            final int flatRowsCount) {
        if (snapshot == null) {
            return true;
        }
        return snapshot.docRef.get() != doc
                || snapshot.modelRef.get() != model
                || snapshot.rootChildrenCount != rootChildrenCount
                || snapshot.flatRowsCount != flatRowsCount;
    }

    private void processWidgetList(final List<?> widgets, final PassContext context) throws Throwable {
        final int size = widgets.size();
        for (int i = 0; i < size; i++) {
            final Object widget = widgets.get(i);
            if (widget == null) {
                continue;
            }
            if (singleRowType.isInstance(widget)) {
                processSingleRow(widget, context);
            } else if (doubleRowType.isInstance(widget)) {
                processDoubleRow(widget, context);
            } else if (groupRowType.isInstance(widget)) {
                processGroupRow(widget, context);
            } else if (widgetType.isInstance(widget)) {
                processGenericWidget(widget, context);
            }
        }
    }

    private void processSingleRow(final Object widget, final PassContext context) throws Throwable {
        final Object paramSource = singleParamHandle.invoke(widget);
        final Object blocker = singleBlockerHandle.invoke(widget);
        final Object mainWidget = singleMainWidgetHandle.invoke(widget);
        final boolean isNew = !context.snapshot().rows.containsKey(widget);
        final RowSnapshot state = context.snapshot().rows.computeIfAbsent(widget, ignored -> new RowSnapshot());
        state.lastVisitedPass = context.passId();

        final boolean needSelectedObjectsUpdate = context.baseline() || isNew || context.selectionChanged();
        if (needSelectedObjectsUpdate) {
            singleSetSelectedObjectsHandle.invoke(widget, context.selectedObjects());
        }

        final Object param1Id = rowParam1IdHandle.invoke(widget);
        final Object param1Guid = paramSource == null ? null : srcGuidHandle.invoke(paramSource);
        final boolean locked = blocker != null && (boolean) blockerIsBlockHandle.invoke(blocker);
        final boolean rowSelected = containsRef(context.selectedPaletteRows(), widget);
        final long keyformHash = lookupKeyformHash(context.snapshot().keyformHashes, param1Guid);
        final boolean highlightChanged =
                param1Guid != null && Objects.equals(param1Guid, context.highlightGuid());

        long hash = SINGLE_ROW_KIND_TAG;
        hash = mixParamSource(hash, paramSource);
        hash = mix64(hash, keyformHash);
        hash = mixFlag(hash, context.showKeyforms());
        hash = mixFlag(hash, locked);
        hash = mixFlag(hash, rowSelected);
        hash = mixWidgetVisuals(hash, widget, mainWidget);

        final boolean visualChanged = context.baseline()
                || isNew
                || highlightChanged
                || context.selectionChanged()
                || state.visualHash != hash;
        state.visualHash = hash;

        final boolean forceSync = context.baseline() || isNew;
        final boolean valueChanged = syncRowValue(
                widget,
                param1Id,
                context.paramSet(),
                rowParam1ValGetHandle,
                rowParam1ValSetHandle,
                state.val1Bits,
                forceSync);
        if (visualChanged && !valueChanged) {
            widgetRepaintHandle.invoke(widget);
        }
        if (visualChanged || valueChanged) {
            repaintedRows++;
        } else {
            elidedRows++;
        }
    }

    private void processDoubleRow(final Object widget, final PassContext context) throws Throwable {
        final Object param1 = doubleParam1Handle.invoke(widget);
        final Object param2 = doubleParam2Handle.invoke(widget);
        final Object blocker = doubleBlockerHandle.invoke(widget);
        final Object mainWidget = doubleMainWidgetHandle.invoke(widget);
        final boolean combinedEnable = (boolean) doubleCombinedEnableHandle.invoke(widget);
        final boolean isNew = !context.snapshot().rows.containsKey(widget);
        final RowSnapshot state = context.snapshot().rows.computeIfAbsent(widget, ignored -> new RowSnapshot());
        state.lastVisitedPass = context.passId();

        final Object param1Guid = param1 == null ? null : srcGuidHandle.invoke(param1);
        final Object param2Guid = param2 == null ? null : srcGuidHandle.invoke(param2);
        final long keyformHash1 = lookupKeyformHash(context.snapshot().keyformHashes, param1Guid);
        final long keyformHash2 = lookupKeyformHash(context.snapshot().keyformHashes, param2Guid);

        long resizeHash = mix64(keyformHash1, keyformHash2);
        resizeHash = mixFlag(resizeHash, combinedEnable);
        resizeHash = mixFlag(resizeHash, context.showKeyforms());
        if (context.baseline() || isNew || state.doubleResizeHash != resizeHash) {
            doubleResizeHandle.invoke(widget);
            state.doubleResizeHash = resizeHash;
            resizedDoubles++;
        }

        final boolean locked = blocker != null && (boolean) blockerIsBlockHandle.invoke(blocker);
        final boolean rowSelected = containsRef(context.selectedPaletteRows(), widget);
        final boolean highlightChanged =
                (param1Guid != null && Objects.equals(param1Guid, context.highlightGuid()))
                        || (param2Guid != null && Objects.equals(param2Guid, context.highlightGuid()));

        long hash = DOUBLE_ROW_KIND_TAG;
        hash = mixParamSource(hash, param1);
        hash = mixParamSource(hash, param2);
        hash = mix64(hash, resizeHash);
        hash = mixFlag(hash, locked);
        hash = mixFlag(hash, rowSelected);
        hash = mixWidgetVisuals(hash, widget, mainWidget);

        final boolean visualChanged = context.baseline()
                || isNew
                || highlightChanged
                || context.selectionChanged()
                || state.visualHash != hash;
        state.visualHash = hash;

        final boolean forceSync = context.baseline() || isNew;
        final Object param1Id = rowParam1IdHandle.invoke(widget);
        final Object param2Id = rowParam2IdHandle.invoke(widget);
        final boolean val1Changed = syncRowValue(
                widget,
                param1Id,
                context.paramSet(),
                rowParam1ValGetHandle,
                rowParam1ValSetHandle,
                state.val1Bits,
                forceSync);
        final boolean val2Changed = syncRowValue(
                widget,
                param2Id,
                context.paramSet(),
                rowParam2ValGetHandle,
                rowParam2ValSetHandle,
                state.val2Bits,
                forceSync);
        final boolean valueChanged = val1Changed || val2Changed;

        if (visualChanged && !valueChanged) {
            widgetRepaintHandle.invoke(widget);
        }
        if (visualChanged || valueChanged) {
            repaintedRows++;
        } else {
            elidedRows++;
        }
    }

    private void processGroupRow(final Object widget, final PassContext context) throws Throwable {
        final Object rowLayout = groupRowLayoutHandle.invoke(widget);
        if (rowLayout != null) {
            final List<?> children = (List<?>) rowLayoutChildrenHandle.invoke(rowLayout);
            processWidgetList(children, context);
        }

        final Object groupData = groupDataHandle.invoke(widget);
        final Object foldingPane = groupFoldingPaneHandle.invoke(widget);
        final Object mainWidget = groupMainWidgetHandle.invoke(widget);
        final boolean isNew = !context.snapshot().rows.containsKey(widget);
        final RowSnapshot state = context.snapshot().rows.computeIfAbsent(widget, ignored -> new RowSnapshot());
        state.lastVisitedPass = context.passId();

        final boolean expanded = foldingPane != null && (boolean) foldingExpandHandle.invoke(foldingPane);
        final boolean rowSelected = containsRef(context.selectedPaletteRows(), widget);

        long hash = GROUP_ROW_KIND_TAG;
        hash = mixFlag(hash, expanded);
        hash = mixFlag(hash, rowSelected);
        if (groupData != null) {
            final String name = (String) groupNameHandle.invoke(groupData);
            final Object visColor = groupVisColorHandle.invoke(groupData);
            final boolean folderOpened = (boolean) groupFolderOpenedHandle.invoke(groupData);
            hash = mix64(hash, Objects.hashCode(name));
            hash = mix64(hash, colorArgb(visColor));
            hash = mixFlag(hash, folderOpened);
        }
        hash = mixWidgetVisuals(hash, widget, mainWidget);

        final boolean changed = context.baseline() || isNew || state.visualHash != hash;
        state.visualHash = hash;
        if (changed) {
            widgetRepaintHandle.invoke(widget);
            repaintedRows++;
        } else {
            elidedRows++;
        }
    }

    private void processGenericWidget(final Object widget, final PassContext context) throws Throwable {
        final boolean isNew = !context.snapshot().rows.containsKey(widget);
        final RowSnapshot state = context.snapshot().rows.computeIfAbsent(widget, ignored -> new RowSnapshot());
        state.lastVisitedPass = context.passId();

        final long hash = mixWidgetVisuals(GENERIC_ROW_KIND_TAG, widget, null);
        final boolean changed = context.baseline() || isNew || state.visualHash != hash;
        state.visualHash = hash;
        if (changed) {
            widgetRepaintHandle.invoke(widget);
            repaintedRows++;
        } else {
            elidedRows++;
        }
    }

    private boolean syncRowValue(
            final Object row,
            final Object paramId,
            final Object paramSet,
            final MethodHandle valGetHandle,
            final MethodHandle valSetHandle,
            final int[] cachedPair,
            final boolean forceSync)
            throws Throwable {
        if (paramId == null || paramSet == null) {
            return false;
        }
        final Object param = paramSetGetHandle.invoke(paramSet, paramId);
        if (param == null) {
            return false;
        }
        final float modelVal = (float) paramGetValueHandle.invoke(param);
        final int modelBits = Float.floatToRawIntBits(modelVal);
        final float rowVal = (float) valGetHandle.invoke(row);
        final int rowBits = Float.floatToRawIntBits(rowVal);
        if (forceSync
                || modelBits != cachedPair[MODEL_BITS_INDEX]
                || rowBits != cachedPair[ROW_BITS_INDEX]) {
            valSetHandle.invoke(row, modelVal);
            final float appliedRowVal = (float) valGetHandle.invoke(row);
            cachedPair[MODEL_BITS_INDEX] = modelBits;
            cachedPair[ROW_BITS_INDEX] = Float.floatToRawIntBits(appliedRowVal);
            return true;
        }
        return false;
    }

    private void syncRemainingFlatRows(
            final List<?> flatRows, final Object paramSet, final ControllerSnapshot snapshot, final long passId)
            throws Throwable {
        if (paramSet == null) {
            return;
        }
        final int count = flatRows.size();
        for (int i = 0; i < count; i++) {
            final Object row = flatRows.get(i);
            if (row == null) {
                continue;
            }
            final RowSnapshot existing = snapshot.rows.get(row);
            if (existing != null && existing.lastVisitedPass == passId) {
                continue;
            }
            final boolean isNew = existing == null;
            final RowSnapshot state = isNew ? snapshot.rows.computeIfAbsent(row, ignored -> new RowSnapshot()) : existing;
            state.lastVisitedPass = passId;
            if (paramRowType.isInstance(row)) {
                syncRowValue(
                        row,
                        rowParam1IdHandle.invoke(row),
                        paramSet,
                        rowParam1ValGetHandle,
                        rowParam1ValSetHandle,
                        state.val1Bits,
                        isNew);
                syncRowValue(
                        row,
                        rowParam2IdHandle.invoke(row),
                        paramSet,
                        rowParam2ValGetHandle,
                        rowParam2ValSetHandle,
                        state.val2Bits,
                        isNew);
            }
        }
    }

    private void computeKeyformHashes(final Object model, final HashMap<Object, long[]> out)
            throws Throwable {
        for (final long[] bucket : out.values()) {
            bucket[0] = 0L;
        }
        final List<?> objects = (List<?>) modelAllObjectsHandle.invoke(model);
        if (objects == null) {
            return;
        }
        final int count = objects.size();
        for (int objIdx = 0; objIdx < count; objIdx++) {
            final Object obj = objects.get(objIdx);
            if (!controllableType.isInstance(obj)) {
                continue;
            }
            final Object source = controllableSourceHandle.invoke(obj);
            if (source == null) {
                continue;
            }
            accumulateGrid(out, primaryGridHandle.invoke(source), objIdx, KEYFORM_PRIMARY_SALT);
            accumulateGrid(out, extendedGridHandle.invoke(source), objIdx, KEYFORM_EXTENDED_SALT);
            accumulateMorphs(out, morphTargetSetHandle.invoke(source), objIdx, KEYFORM_MORPH_SALT);
            accumulateMorphs(out, extendedMorphTargetSetHandle.invoke(source), objIdx, KEYFORM_EXTENDED_SALT);
        }
    }

    private void accumulateGrid(
            final HashMap<Object, long[]> out, final Object grid, final int objIdx, final long salt)
            throws Throwable {
        if (grid == null) {
            return;
        }
        final List<?> bindings = (List<?>) gridBindingsHandle.invoke(grid);
        if (bindings == null) {
            return;
        }
        final int size = bindings.size();
        for (int i = 0; i < size; i++) {
            final Object binding = bindings.get(i);
            if (binding == null) {
                continue;
            }
            final Object guid = bindingGuidHandle.invoke(binding);
            final List<?> keys = (List<?>) bindingKeysHandle.invoke(binding);
            if (guid == null || keys == null) {
                continue;
            }
            final int keyCount = keys.size();
            long h = mix64(salt, objIdx);
            h = mix64(h, keyCount);
            for (int k = 0; k < keyCount; k++) {
                final Object keyObj = keys.get(k);
                final int bits = keyObj instanceof Float f ? Float.floatToRawIntBits(f) : 0;
                h = mix64(h, bits);
            }
            final long[] bucket = out.computeIfAbsent(guid, ignored -> new long[1]);
            bucket[0] = mix64(bucket[0] == 0L ? SPLITMIX_GAMMA : bucket[0], h);
        }
    }

    private void accumulateMorphs(
            final HashMap<Object, long[]> out, final Object morphSet, final int objIdx, final long salt)
            throws Throwable {
        if (morphSet == null) {
            return;
        }
        final List<?> targets = (List<?>) morphTargetsHandle.invoke(morphSet);
        if (targets == null) {
            return;
        }
        final int size = targets.size();
        for (int i = 0; i < size; i++) {
            final Object target = targets.get(i);
            if (target == null) {
                continue;
            }
            final Object guid = morphGuidHandle.invoke(target);
            final Float keyValue = (Float) morphKeyValueHandle.invoke(target);
            if (guid == null) {
                continue;
            }
            long h = mix64(salt, objIdx);
            h = mix64(h, keyValue == null ? 0L : Float.floatToRawIntBits(keyValue));
            final long[] bucket = out.computeIfAbsent(guid, ignored -> new long[1]);
            bucket[0] = mix64(bucket[0] == 0L ? SPLITMIX_GAMMA : bucket[0], h);
        }
    }

    private long mixParamSource(long hash, final Object paramSource) throws Throwable {
        if (paramSource == null) {
            return mix64(hash, 0L);
        }
        final Object guid = srcGuidHandle.invoke(paramSource);
        final Object id = srcIdHandle.invoke(paramSource);
        final String name = (String) srcNameHandle.invoke(paramSource);
        final float minVal = (float) srcMinHandle.invoke(paramSource);
        final float maxVal = (float) srcMaxHandle.invoke(paramSource);
        final float defVal = (float) srcDefaultHandle.invoke(paramSource);
        final boolean repeat = (boolean) srcRepeatHandle.invoke(paramSource);
        final boolean combined = (boolean) srcCombinedHandle.invoke(paramSource);
        final boolean morphTarget = (boolean) srcMorphTargetHandle.invoke(paramSource);
        final Object paramType = srcTypeHandle.invoke(paramSource);
        final Object visColor = srcVisColorHandle.invoke(paramSource);

        hash = mix64(hash, Objects.hashCode(guid));
        hash = mix64(hash, Objects.hashCode(id));
        hash = mix64(hash, Objects.hashCode(name));
        hash = mix64(hash, Float.floatToRawIntBits(minVal));
        hash = mix64(hash, Float.floatToRawIntBits(maxVal));
        hash = mix64(hash, Float.floatToRawIntBits(defVal));
        hash = mixFlag(hash, repeat);
        hash = mixFlag(hash, combined);
        hash = mixFlag(hash, morphTarget);
        hash = mix64(hash, Objects.hashCode(paramType));
        return mix64(hash, colorArgb(visColor));
    }

    private long mixWidgetVisuals(long hash, final Object widget, final Object mainWidget) throws Throwable {
        hash = mixSingleWidget(hash, widget);
        if (mainWidget != null) {
            hash = mixSingleWidget(hash, mainWidget);
        }
        return hash;
    }

    private long mixSingleWidget(long hash, final Object widget) throws Throwable {
        final boolean visible = (boolean) widgetVisibleHandle.invoke(widget);
        final Object bgColor = widgetBgColorHandle.invoke(widget);
        final JComponent comp = (JComponent) widgetJComponentHandle.invoke(widget);
        hash = mixFlag(hash, visible);
        hash = mix64(hash, colorArgb(bgColor));
        if (comp != null) {
            final Color jBg = comp.getBackground();
            hash = mix64(hash, jBg == null ? NULL_COLOR_ARGB : jBg.getRGB());
            hash = mix64(hash, comp.getY());
            hash = mix64(hash, comp.getWidth());
            hash = mix64(hash, comp.getHeight());
        }
        return hash;
    }

    private int colorArgb(final Object cColor) throws Throwable {
        if (cColor == null) {
            return NULL_COLOR_ARGB;
        }
        final Color awt = (Color) colorJColorHandle.invoke(cColor);
        return awt == null ? NULL_COLOR_ARGB : awt.getRGB();
    }

    private static long lookupKeyformHash(final HashMap<Object, long[]> map, final Object guid) {
        if (guid == null) {
            return 0L;
        }
        final long[] bucket = map.get(guid);
        return bucket == null ? 0L : bucket[0];
    }

    private static int countVisitedRows(final ControllerSnapshot snapshot, final long passId) {
        int visited = 0;
        for (final RowSnapshot state : snapshot.rows.values()) {
            if (state.lastVisitedPass == passId) {
                visited++;
            }
        }
        return visited;
    }

    private static void pruneUnvisitedRows(final ControllerSnapshot snapshot, final long passId) {
        snapshot.rows.entrySet().removeIf(entry -> entry.getValue().lastVisitedPass != passId);
    }

    private static boolean sameElements(final Object[] cached, final List<?> live) {
        final int liveSize = live == null ? 0 : live.size();
        if (cached.length != liveSize) {
            return false;
        }
        for (int i = 0; i < liveSize; i++) {
            if (!Objects.equals(cached[i], live.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static Object[] copyRefs(final List<?> live) {
        if (live == null || live.isEmpty()) {
            return EMPTY_REFS;
        }
        final Object[] copy = new Object[live.size()];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = live.get(i);
        }
        return copy;
    }

    private static boolean containsRef(final Object[] refs, final Object needle) {
        for (int i = 0; i < refs.length; i++) {
            if (refs[i] == needle) {
                return true;
            }
        }
        return false;
    }

    private static long mixFlag(final long seed, final boolean flag) {
        return mix64(seed, flag ? FLAG_TRUE_HASH : FLAG_FALSE_HASH);
    }

    private static long mix64(final long seed, final long value) {
        long z = seed + value + SPLITMIX_GAMMA;
        z = (z ^ (z >>> SPLITMIX_SHIFT_1)) * SPLITMIX_MUL_1;
        z = (z ^ (z >>> SPLITMIX_SHIFT_2)) * SPLITMIX_MUL_2;
        return z ^ (z >>> SPLITMIX_SHIFT_3);
    }
}
