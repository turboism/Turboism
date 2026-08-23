package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.selector.OwnedMocSelectorContract;
import dev.turboism.mapping.verification.selector.CorePublicApiSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.util.ArrayList;
import java.util.List;

/** Synthetic public Core surface used only to exercise verified adapter contracts. */
public final class TestCoreApiFixture {

    private TestCoreApiFixture() {
    }

    static void resetVersion() {
        Core.version = new Version(11, 12, 13);
    }

    static VerifiedMemberResolver resolver(final String artifactProfile) {
        return resolver(artifactProfile, null);
    }

    static VerifiedMemberResolver resolver(
        final String artifactProfile,
        final String omittedAlias
    ) {
        return resolver(
            artifactProfile,
            Core.class,
            Version.class,
            objectDescriptor(Version.class),
            "()I",
            omittedAlias,
            Core.class.getClassLoader()
        );
    }

    static VerifiedMemberResolver resolverForReviewedVersion(
        final String reviewedVersion,
        final String artifactProfile
    ) {
        return resolver(
            reviewedVersion,
            artifactProfile,
            Core.class,
            Version.class,
            objectDescriptor(Version.class),
            "()I",
            null,
            Core.class.getClassLoader()
        );
    }

    static VerifiedMemberResolver resolver(
        final String artifactProfile,
        final Class<?> coreType,
        final Class<?> versionType,
        final String versionDescriptor,
        final String majorDescriptor,
        final String omittedAlias,
        final ClassLoader classLoader
    ) {
        return resolver(
            artifactProfile,
            artifactProfile,
            coreType,
            versionType,
            versionDescriptor,
            majorDescriptor,
            omittedAlias,
            classLoader
        );
    }

    private static VerifiedMemberResolver resolver(
        final String reviewedVersion,
        final String artifactProfile,
        final Class<?> coreType,
        final Class<?> versionType,
        final String versionDescriptor,
        final String majorDescriptor,
        final String omittedAlias,
        final ClassLoader classLoader
    ) {
        return resolver(
            reviewedVersion, artifactProfile, coreType, versionType,
            versionDescriptor, majorDescriptor, omittedAlias, classLoader,
            java.util.List.of(), java.util.Set.of()
        );
    }

    private static VerifiedMemberResolver resolver(
        final String reviewedVersion,
        final String artifactProfile,
        final Class<?> coreType,
        final Class<?> versionType,
        final String versionDescriptor,
        final String majorDescriptor,
        final String omittedAlias,
        final ClassLoader classLoader,
        final java.util.List<StaticSelector> extraSelectors,
        final java.util.Set<String> extraCapabilities
    ) {
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(StaticSelector.classSelector(
            CorePublicApiSelectorContract.LIVE2D_CORE_CLASS,
            internalName(coreType)
        ));
        selectors.add(StaticSelector.classSelector(
            CorePublicApiSelectorContract.CORE_VERSION_CLASS,
            internalName(versionType)
        ));
        selectors.add(StaticSelector.staticMethod(
            CorePublicApiSelectorContract.GET_VERSION,
            internalName(coreType),
            "getVersion",
            versionDescriptor,
            StaticSelector.ACCESS_PUBLIC
        ));
        selectors.add(StaticSelector.staticMethod(
            CorePublicApiSelectorContract.GET_LATEST_MOC_VERSION,
            internalName(coreType),
            "getLatestMocVersion",
            "()I",
            StaticSelector.ACCESS_PUBLIC
        ));
        selectors.add(StaticSelector.staticMethod(
            CorePublicApiSelectorContract.GET_MOC_VERSION,
            internalName(coreType),
            "getMocVersion",
            "([B)I",
            StaticSelector.ACCESS_PUBLIC
        ));
        selectors.add(StaticSelector.staticMethod(
            CorePublicApiSelectorContract.HAS_MOC_CONSISTENCY,
            internalName(coreType),
            "hasMocConsistency",
            "([B)Z",
            StaticSelector.ACCESS_PUBLIC
        ));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.GET_MAJOR,
            versionType,
            "getMajor",
            majorDescriptor
        ));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.GET_MINOR,
            versionType,
            "getMinor",
            "()I"
        ));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.GET_PATCH,
            versionType,
            "getPatch",
            "()I"
        ));

        selectors.add(StaticSelector.classSelector(
            CorePublicApiSelectorContract.MODEL_CLASS,
            internalName(Model.class)
        ));
        selectors.add(StaticSelector.classSelector(
            CorePublicApiSelectorContract.CANVAS_INFO_CLASS,
            internalName(CanvasInfo.class)
        ));
        selectors.add(StaticSelector.classSelector(
            CorePublicApiSelectorContract.PARAMETERS_CLASS,
            internalName(Parameters.class)
        ));
        selectors.add(StaticSelector.classSelector(
            CorePublicApiSelectorContract.PARAMETER_TYPE_CLASS,
            internalName(ParameterType.class)
        ));
        selectors.add(StaticSelector.classSelector(CorePublicApiSelectorContract.PARTS_CLASS, internalName(Parts.class)));
        selectors.add(StaticSelector.classSelector(CorePublicApiSelectorContract.DRAWABLES_CLASS, internalName(Drawables.class)));
        selectors.add(StaticSelector.classSelector(CorePublicApiSelectorContract.DEFORMERS_CLASS, internalName(Deformers.class)));
        selectors.add(StaticSelector.classSelector(CorePublicApiSelectorContract.GLUES_CLASS, internalName(Glues.class)));

        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.MODEL_GET_CANVAS_INFO,
            Model.class,
            "getCanvasInfo",
            objectDescriptor(CanvasInfo.class)
        ));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.MODEL_GET_PARAMETERS,
            Model.class,
            "getParameters",
            objectDescriptor(Parameters.class)
        ));
        selectors.add(instanceMethod(CorePublicApiSelectorContract.MODEL_GET_PARTS, Model.class, "getParts", objectDescriptor(Parts.class)));
        if (CorePublicApiSelectorContract.ARTIFACT_PROFILE_5_3_02.equals(artifactProfile)) {
            selectors.add(instanceMethod(CorePublicApiSelectorContract.MODEL_GET_RENDER_ORDERS, Model.class, "getRenderOrders", "()[I"));
        }
        selectors.add(instanceMethod(CorePublicApiSelectorContract.MODEL_GET_DRAWABLES, Model.class, "getDrawables", objectDescriptor(Drawables.class)));
        selectors.add(instanceMethod(CorePublicApiSelectorContract.MODEL_GET_DEFORMERS, Model.class, "getDeformers", objectDescriptor(Deformers.class)));
        selectors.add(instanceMethod(CorePublicApiSelectorContract.MODEL_GET_GLUES, Model.class, "getGlues", objectDescriptor(Glues.class)));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.CANVAS_GET_SIZE_IN_PIXELS,
            CanvasInfo.class,
            "getSizeInPixels",
            "()[F"
        ));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.CANVAS_GET_ORIGIN_IN_PIXELS,
            CanvasInfo.class,
            "getOriginInPixels",
            "()[F"
        ));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.CANVAS_GET_PIXELS_PER_UNIT,
            CanvasInfo.class,
            "getPixelsPerUnit",
            "()F"
        ));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.PARAMETERS_GET_COUNT,
            Parameters.class,
            "getCount",
            "()I"
        ));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.PARAMETERS_GET_DEFAULT_VALUES,
            Parameters.class,
            "getDefaultValues",
            "()[F"
        ));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.PARAMETERS_GET_IDS,
            Parameters.class,
            "getIds",
            "()[Ljava/lang/String;"
        ));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.PARAMETERS_GET_KEY_COUNTS,
            Parameters.class,
            "getKeyCounts",
            "()[I"
        ));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.PARAMETERS_GET_KEY_VALUES,
            Parameters.class,
            "getKeyValues",
            "()[[F"
        ));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.PARAMETERS_GET_MAXIMUM_VALUES,
            Parameters.class,
            "getMaximumValues",
            "()[F"
        ));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.PARAMETERS_GET_MINIMUM_VALUES,
            Parameters.class,
            "getMinimumValues",
            "()[F"
        ));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.PARAMETERS_GET_TYPES,
            Parameters.class,
            "getTypes",
            arrayDescriptor(ParameterType.class)
        ));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.PARAMETERS_GET_VALUES,
            Parameters.class,
            "getValues",
            "()[F"
        ));
        selectors.add(instanceMethod(
            CorePublicApiSelectorContract.PARAMETER_TYPE_GET_NUMBER,
            ParameterType.class,
            "getNumber",
            "()I"
        ));
        if (CorePublicApiSelectorContract.ARTIFACT_PROFILE_5_3_02.equals(
            artifactProfile
        )) {
            selectors.add(instanceMethod(
                CorePublicApiSelectorContract.PARAMETERS_GET_REPEATS,
                Parameters.class,
                "getParameterRepeats",
                "()[Z"
            ));
        }
        addFamilySelectors(selectors, artifactProfile);

        final java.util.HashSet<String> capabilities =
            new java.util.HashSet<>(CorePublicApiSelectorContract.CAPABILITY_IDS);
        capabilities.addAll(extraCapabilities);
        final java.util.ArrayList<StaticSelector> all = new java.util.ArrayList<>(selectors);
        all.addAll(extraSelectors);
        return TestVerifiedResolvers.create(
            reviewedVersion,
            CorePublicApiSelectorContract.ADAPTER_SLICE_ID,
            capabilities,
            all.stream()
                .filter(selector -> !selector.alias().equals(omittedAlias))
                .toList(),
            classLoader
        );
    }

    /** Package-visible fixture resolver with extra selectors and capabilities. */
    public static VerifiedMemberResolver resolverWithExtras(
        final String artifactProfile,
        final java.util.List<StaticSelector> extraSelectors,
        final java.util.Set<String> extraCapabilities
    ) {
        final String reviewedVersion = "5.2.03".equals(artifactProfile) ? "5.2.03" : "5.3.02";
        return resolver(
            reviewedVersion,
            artifactProfile,
            Core.class,
            Version.class,
            objectDescriptor(Version.class),
            "()I",
            null,
            Core.class.getClassLoader(),
            extraSelectors,
            extraCapabilities
        );
    }

    /** Extra selectors for the owned-Moc lifecycle surface (both reviewed profiles). */
    public static List<StaticSelector> ownedMocSelectors() {
        return List.of(
            StaticSelector.classSelector(
                dev.turboism.mapping.verification.selector.OwnedMocSelectorContract.MOC_CLASS,
                internalName(Moc.class)
            ),
            StaticSelector.staticMethod(
                dev.turboism.mapping.verification.selector.OwnedMocSelectorContract.MOC_INSTANTIATE,
                internalName(Moc.class),
                "instantiate",
                "([B)L" + internalName(Moc.class) + ";",
                StaticSelector.ACCESS_PUBLIC
            ),
            StaticSelector.method(
                dev.turboism.mapping.verification.selector.OwnedMocSelectorContract.MOC_INSTANTIATE_MODEL,
                internalName(Moc.class),
                "instantiateModel",
                "()L" + internalName(Model.class) + ";",
                StaticSelector.ACCESS_PUBLIC
            ),
            StaticSelector.method(
                dev.turboism.mapping.verification.selector.OwnedMocSelectorContract.MOC_GET_NATIVE_HANDLE,
                internalName(Moc.class),
                "getNativeHandle",
                "()J",
                StaticSelector.ACCESS_PUBLIC
            ),
            StaticSelector.method(
                dev.turboism.mapping.verification.selector.OwnedMocSelectorContract.MOC_CLOSE,
                internalName(Moc.class),
                "close",
                "()V",
                StaticSelector.ACCESS_PUBLIC
            ),
            StaticSelector.method(
                dev.turboism.mapping.verification.selector.OwnedMocSelectorContract.MODEL_GET_NATIVE_HANDLE,
                internalName(Model.class),
                "getNativeHandle",
                "()J",
                StaticSelector.ACCESS_PUBLIC
            ),
            StaticSelector.method(
                dev.turboism.mapping.verification.selector.OwnedMocSelectorContract.MODEL_UPDATE,
                internalName(Model.class),
                "update",
                "()V",
                StaticSelector.ACCESS_PUBLIC
            ),
            StaticSelector.method(
                dev.turboism.mapping.verification.selector.OwnedMocSelectorContract.MODEL_CLOSE,
                internalName(Model.class),
                "close",
                "()V",
                StaticSelector.ACCESS_PUBLIC
            )
        );
    }

    /** Owned-Moc capability id for fixture resolvers. */
    public static java.util.Set<String> ownedMocCapability() {
        return java.util.Set.of(
            dev.turboism.mapping.verification.selector.OwnedMocSelectorContract.CAPABILITY_ID
        );
    }

    private static StaticSelector instanceMethod(
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(
            alias,
            internalName(owner),
            name,
            descriptor,
            StaticSelector.ACCESS_PUBLIC
        );
    }

    private static void addFamilySelectors(final List<StaticSelector> selectors, final String profile) {
        add(selectors, Parts.class, new Object[][]{
            {CorePublicApiSelectorContract.PARTS_COUNT, "getCount", "()I"},
            {CorePublicApiSelectorContract.PARTS_IDS, "getIds", "()[Ljava/lang/String;"},
            {CorePublicApiSelectorContract.PARTS_OPACITIES, "getOpacities", "()[F"},
            {CorePublicApiSelectorContract.PARTS_PARENT_PART_INDICES, "getParentPartIndices", "()[I"}
        });
        add(selectors, Drawables.class, new Object[][]{
            {CorePublicApiSelectorContract.DRAWABLES_COUNT, "getCount", "()I"},
            {CorePublicApiSelectorContract.DRAWABLES_IDS, "getIds", "()[Ljava/lang/String;"},
            {CorePublicApiSelectorContract.DRAWABLES_CONSTANT_FLAGS, "getConstantFlags", "()[B"},
            {CorePublicApiSelectorContract.DRAWABLES_DYNAMIC_FLAGS, "getDynamicFlags", "()[B"},
            {CorePublicApiSelectorContract.DRAWABLES_TEXTURE_INDICES, "getTextureIndices", "()[I"},
            {CorePublicApiSelectorContract.DRAWABLES_DRAW_ORDERS, "getDrawOrders", "()[I"},
            {CorePublicApiSelectorContract.DRAWABLES_OPACITIES, "getOpacities", "()[F"},
            {CorePublicApiSelectorContract.DRAWABLES_MASK_COUNTS, "getMaskCounts", "()[I"},
            {CorePublicApiSelectorContract.DRAWABLES_MASKS, "getMasks", "()[[I"},
            {CorePublicApiSelectorContract.DRAWABLES_VERTEX_COUNTS, "getVertexCounts", "()[I"},
            {CorePublicApiSelectorContract.DRAWABLES_VERTEX_POSITIONS, "getVertexPositions", "()[[F"},
            {CorePublicApiSelectorContract.DRAWABLES_VERTEX_UVS, "getVertexUvs", "()[[F"},
            {CorePublicApiSelectorContract.DRAWABLES_INDEX_COUNTS, "getIndexCounts", "()[I"},
            {CorePublicApiSelectorContract.DRAWABLES_INDICES, "getIndices", "()[[S"},
            {CorePublicApiSelectorContract.DRAWABLES_MULTIPLY_COLORS, "getMultiplyColors", "()[[F"},
            {CorePublicApiSelectorContract.DRAWABLES_SCREEN_COLORS, "getScreenColors", "()[[F"},
            {CorePublicApiSelectorContract.DRAWABLES_PARENT_PART_INDICES, "getParentPartIndices", "()[I"},
            {CorePublicApiSelectorContract.DRAWABLES_PARENT_DEFORMER_INDICES, "getParentDeformsers", "()[I"},
            {CorePublicApiSelectorContract.DRAWABLES_PARAMETER_COUNTS, "getParameterCounts", "()[I"},
            {CorePublicApiSelectorContract.DRAWABLES_PARAMETERS, "getParameters", "()[[I"}
        });
        if (CorePublicApiSelectorContract.ARTIFACT_PROFILE_5_2_03.equals(profile)) {
            selectors.add(instanceMethod(CorePublicApiSelectorContract.DRAWABLES_RENDER_ORDERS, Drawables.class, "getRenderOrders", "()[I"));
        } else {
            selectors.add(instanceMethod(CorePublicApiSelectorContract.DRAWABLES_BLEND_MODES, Drawables.class, "getBlendModes", "()[I"));
        }
        add(selectors, Deformers.class, new Object[][]{
            {CorePublicApiSelectorContract.DEFORMERS_COUNT, "getCount", "()I"},
            {CorePublicApiSelectorContract.DEFORMERS_IDS, "getIds", "()[Ljava/lang/String;"},
            {CorePublicApiSelectorContract.DEFORMERS_PARENT_DEFORMER_INDICES, "getParentDeformsers", "()[I"},
            {CorePublicApiSelectorContract.DEFORMERS_PARAMETER_COUNTS, "getParameterCounts", "()[I"},
            {CorePublicApiSelectorContract.DEFORMERS_PARAMETERS, "getParameters", "()[[I"}
        });
        add(selectors, Glues.class, new Object[][]{
            {CorePublicApiSelectorContract.GLUES_COUNT, "getCount", "()I"},
            {CorePublicApiSelectorContract.GLUES_IDS, "getIds", "()[Ljava/lang/String;"},
            {CorePublicApiSelectorContract.GLUES_DRAWABLES_A, "getDrawablesA", "()[I"},
            {CorePublicApiSelectorContract.GLUES_DRAWABLES_B, "getDrawablesB", "()[I"},
            {CorePublicApiSelectorContract.GLUES_PARAMETER_COUNTS, "getParameterCounts", "()[I"},
            {CorePublicApiSelectorContract.GLUES_PARAMETERS, "getParameters", "()[[I"}
        });
    }

    private static void add(final List<StaticSelector> selectors, final Class<?> owner, final Object[][] definitions) {
        for (Object[] definition : definitions) selectors.add(instanceMethod((String) definition[0], owner, (String) definition[1], (String) definition[2]));
    }

    static String internalName(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    static String objectDescriptor(final Class<?> returnType) {
        return "()L" + internalName(returnType) + ";";
    }

    private static String arrayDescriptor(final Class<?> componentType) {
        return "()[L" + internalName(componentType) + ";";
    }

    public static final class Core {
        private static Version version = new Version(11, 12, 13);

        public static Version getVersion() {
            return version;
        }

        public static int getLatestMocVersion() {
            return 6;
        }

        public static int getMocVersion(final byte[] bytes) {
            return bytes.length == 0 ? 0 : Byte.toUnsignedInt(bytes[0]);
        }

        public static boolean hasMocConsistency(final byte[] bytes) {
            return bytes.length > 1 && bytes[1] == 1;
        }
    }

    public record Version(int major, int minor, int patch) {
        public int getMajor() {
            return major;
        }

        public int getMinor() {
            return minor;
        }

        public int getPatch() {
            return patch;
        }
    }

    public static class Model {
        private final CanvasInfo canvasInfo;
        private final Parameters parameters;
        private final Parts parts;
        private final Drawables drawables;
        private final Deformers deformers;
        private final Glues glues;
        private final Moc moc;
        private final Runnable beforeCanvasRead;
        private final long nativeHandle;
        private int updateCount;
        private int closeCount;

        public Model(final CanvasInfo canvasInfo, final Parameters parameters) {
            this(canvasInfo, parameters, Parts.empty(), Drawables.empty(), Deformers.empty(), Glues.empty(), null, () -> { });
        }

        public Model(final CanvasInfo canvasInfo, final Parameters parameters, final Runnable beforeCanvasRead) {
            this(canvasInfo, parameters, Parts.empty(), Drawables.empty(), Deformers.empty(), Glues.empty(), null, beforeCanvasRead);
        }

        public Model(
            final CanvasInfo canvasInfo,
            final Parameters parameters,
            final Parts parts,
            final Drawables drawables,
            final Deformers deformers,
            final Glues glues,
            final Runnable beforeCanvasRead
        ) {
            this(canvasInfo, parameters, parts, drawables, deformers, glues, null, beforeCanvasRead);
        }

        public Model(
            final CanvasInfo canvasInfo,
            final Parameters parameters,
            final Parts parts,
            final Drawables drawables,
            final Deformers deformers,
            final Glues glues,
            final Moc moc,
            final Runnable beforeCanvasRead
        ) {
            this(canvasInfo, parameters, parts, drawables, deformers, glues, moc, beforeCanvasRead, 0L);
        }

        public Model(
            final CanvasInfo canvasInfo,
            final Parameters parameters,
            final Parts parts,
            final Drawables drawables,
            final Deformers deformers,
            final Glues glues,
            final Moc moc,
            final Runnable beforeCanvasRead,
            final long nativeHandle
        ) {
            this.canvasInfo = canvasInfo;
            this.parameters = parameters;
            this.parts = parts;
            this.drawables = drawables;
            this.deformers = deformers;
            this.glues = glues;
            this.moc = moc;
            this.beforeCanvasRead = beforeCanvasRead;
            this.nativeHandle = nativeHandle;
        }

        public long getNativeHandle() {
            return nativeHandle;
        }

        public void update() {
            updateCount++;
        }

        public void close() {
            closeCount++;
        }

        public int updateCount() {
            return updateCount;
        }

        public int closeCount() {
            return closeCount;
        }

        public CanvasInfo getCanvasInfo() { beforeCanvasRead.run(); return canvasInfo; }
        public Parameters getParameters() { return parameters; }
        public Parts getParts() { return parts; }
        public int[] getRenderOrders() { return drawables.getRenderOrders(); }
        public Drawables getDrawables() { return drawables; }
        public Deformers getDeformers() { return deformers; }
        public Glues getGlues() { return glues; }
        public Moc getMoc() { return moc; }
    }

    /** Minimal MOC stand-in exposing the verified owned-Moc lifecycle surface. */
    public static final class Moc {
        private static Model nextModel;
        private static long nextNativeHandle;

        private final int mocVersion;
        private final Model model;
        private final long nativeHandle;
        private int closeCount;

        public Moc(final int mocVersion) {
            this(mocVersion, null, 0L);
        }

        public Moc(final int mocVersion, final Model model, final long nativeHandle) {
            this.mocVersion = mocVersion;
            this.model = model;
            this.nativeHandle = nativeHandle;
        }

        /** Binds the model and native handle returned by the next {@link #instantiate}. */
        public static void prepare(final Model model, final long nativeHandle) {
            nextModel = model;
            nextNativeHandle = nativeHandle;
        }

        private static Moc lastInstantiated;

        public static Moc instantiate(final byte[] bytes) {
            final Moc moc = new Moc(Byte.toUnsignedInt(bytes[0]), nextModel, nextNativeHandle);
            nextModel = null;
            nextNativeHandle = 0L;
            lastInstantiated = moc;
            return moc;
        }

        /** Returns the most recent fixture Moc created by {@link #instantiate}. */
        public static Moc lastInstantiated() {
            return lastInstantiated;
        }

        public Model instantiateModel() {
            return model;
        }

        public long getNativeHandle() {
            return nativeHandle;
        }

        public int getMocVersion() {
            return mocVersion;
        }

        public void close() {
            closeCount++;
        }

        public int closeCount() {
            return closeCount;
        }
    }

    public static final class CanvasInfo {
        private final float[] sizeInPixels;
        private final float[] originInPixels;
        private final float pixelsPerUnit;

        public CanvasInfo(
            final float[] sizeInPixels,
            final float[] originInPixels,
            final float pixelsPerUnit
        ) {
            this.sizeInPixels = sizeInPixels;
            this.originInPixels = originInPixels;
            this.pixelsPerUnit = pixelsPerUnit;
        }

        public float[] getSizeInPixels() {
            return sizeInPixels;
        }

        public float[] getOriginInPixels() {
            return originInPixels;
        }

        public float getPixelsPerUnit() {
            return pixelsPerUnit;
        }
    }

    public static final class Parameters {
        private final String[] ids;
        private final ParameterType[] types;
        private final float[] minimumValues;
        private final float[] maximumValues;
        private final float[] defaultValues;
        private final float[] values;
        private final int[] keyCounts;
        private final float[][] keyValues;
        private final boolean[] repeats;

        public Parameters(
            final String[] ids,
            final ParameterType[] types,
            final float[] minimumValues,
            final float[] maximumValues,
            final float[] defaultValues,
            final float[] values,
            final int[] keyCounts,
            final float[][] keyValues,
            final boolean[] repeats
        ) {
            this.ids = ids;
            this.types = types;
            this.minimumValues = minimumValues;
            this.maximumValues = maximumValues;
            this.defaultValues = defaultValues;
            this.values = values;
            this.keyCounts = keyCounts;
            this.keyValues = keyValues;
            this.repeats = repeats;
        }

        public int getCount() {
            return ids.length;
        }

        public float[] getDefaultValues() {
            return defaultValues;
        }

        public String[] getIds() {
            return ids;
        }

        public int[] getKeyCounts() {
            return keyCounts;
        }

        public float[][] getKeyValues() {
            return keyValues;
        }

        public float[] getMaximumValues() {
            return maximumValues;
        }

        public float[] getMinimumValues() {
            return minimumValues;
        }

        public ParameterType[] getTypes() {
            return types;
        }

        public float[] getValues() {
            return values;
        }

        public boolean[] getParameterRepeats() {
            return repeats;
        }
    }

    public static final class ParameterType {
        private final int number;

        public ParameterType(final int number) {
            this.number = number;
        }

        public int getNumber() {
            return number;
        }
    }

    public record Parts(String[] ids, float[] opacities, int[] parentPartIndices) {
        static Parts empty() { return new Parts(new String[0], new float[0], new int[0]); }
        public int getCount() { return ids.length; }
        public String[] getIds() { return ids; }
        public float[] getOpacities() { return opacities; }
        public int[] getParentPartIndices() { return parentPartIndices; }
    }

    public record Drawables(
        String[] ids, byte[] constantFlags, byte[] dynamicFlags, int[] blendModes,
        int[] textureIndices, int[] drawOrders, int[] renderOrders, float[] opacities,
        int[] maskCounts, int[][] masks, int[] vertexCounts, float[][] vertexPositions,
        float[][] vertexUvs, int[] indexCounts, short[][] indices, float[][] multiplyColors,
        float[][] screenColors, int[] parentPartIndices, int[] parentDeformers,
        int[] parameterCounts, int[][] parameters
    ) {
        static Drawables empty() { return new Drawables(new String[0], new byte[0], new byte[0], new int[0], new int[0], new int[0], new int[0], new float[0], new int[0], new int[0][], new int[0], new float[0][], new float[0][], new int[0], new short[0][], new float[0][], new float[0][], new int[0], new int[0], new int[0], new int[0][]); }
        public int getCount() { return ids.length; }
        public String[] getIds() { return ids; }
        public byte[] getConstantFlags() { return constantFlags; }
        public byte[] getDynamicFlags() { return dynamicFlags; }
        public int[] getBlendModes() { return blendModes; }
        public int[] getTextureIndices() { return textureIndices; }
        public int[] getDrawOrders() { return drawOrders; }
        public int[] getRenderOrders() { return renderOrders; }
        public float[] getOpacities() { return opacities; }
        public int[] getMaskCounts() { return maskCounts; }
        public int[][] getMasks() { return masks; }
        public int[] getVertexCounts() { return vertexCounts; }
        public float[][] getVertexPositions() { return vertexPositions; }
        public float[][] getVertexUvs() { return vertexUvs; }
        public int[] getIndexCounts() { return indexCounts; }
        public short[][] getIndices() { return indices; }
        public float[][] getMultiplyColors() { return multiplyColors; }
        public float[][] getScreenColors() { return screenColors; }
        public int[] getParentPartIndices() { return parentPartIndices; }
        public int[] getParentDeformsers() { return parentDeformers; }
        public int[] getParameterCounts() { return parameterCounts; }
        public int[][] getParameters() { return parameters; }
    }

    public record Deformers(String[] ids, int[] parents, int[] parameterCounts, int[][] parameters) {
        static Deformers empty() { return new Deformers(new String[0], new int[0], new int[0], new int[0][]); }
        public int getCount() { return ids.length; }
        public String[] getIds() { return ids; }
        public int[] getParentDeformsers() { return parents; }
        public int[] getParameterCounts() { return parameterCounts; }
        public int[][] getParameters() { return parameters; }
    }

    public record Glues(String[] ids, int[] drawablesA, int[] drawablesB, int[] parameterCounts, int[][] parameters) {
        static Glues empty() { return new Glues(new String[0], new int[0], new int[0], new int[0], new int[0][]); }
        public int getCount() { return ids.length; }
        public String[] getIds() { return ids; }
        public int[] getDrawablesA() { return drawablesA; }
        public int[] getDrawablesB() { return drawablesB; }
        public int[] getParameterCounts() { return parameterCounts; }
        public int[][] getParameters() { return parameters; }
    }
}
