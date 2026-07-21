package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.CorePublicApiSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.util.ArrayList;
import java.util.List;

/** Synthetic public Core surface used only to exercise verified adapter contracts. */
final class TestCoreApiFixture {

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

    static VerifiedMemberResolver resolver(
        final String artifactProfile,
        final Class<?> coreType,
        final Class<?> versionType,
        final String versionDescriptor,
        final String majorDescriptor,
        final String omittedAlias,
        final ClassLoader classLoader
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

        return TestVerifiedResolvers.create(
            artifactProfile,
            CorePublicApiSelectorContract.ADAPTER_SLICE_ID,
            CorePublicApiSelectorContract.CAPABILITY_IDS,
            selectors.stream()
                .filter(selector -> !selector.alias().equals(omittedAlias))
                .toList(),
            classLoader
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

    public static final class Model {
        private final CanvasInfo canvasInfo;
        private final Parameters parameters;
        private final Runnable beforeCanvasRead;

        public Model(
            final CanvasInfo canvasInfo,
            final Parameters parameters
        ) {
            this(canvasInfo, parameters, () -> { });
        }

        public Model(
            final CanvasInfo canvasInfo,
            final Parameters parameters,
            final Runnable beforeCanvasRead
        ) {
            this.canvasInfo = canvasInfo;
            this.parameters = parameters;
            this.beforeCanvasRead = beforeCanvasRead;
        }

        public CanvasInfo getCanvasInfo() {
            beforeCanvasRead.run();
            return canvasInfo;
        }

        public Parameters getParameters() {
            return parameters;
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
}
