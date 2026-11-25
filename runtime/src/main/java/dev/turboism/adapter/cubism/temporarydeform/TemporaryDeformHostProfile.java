package dev.turboism.adapter.cubism.temporarydeform;

import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Version-pinned Cubism internal names for temporary deform handler hooks and rendering. */
public record TemporaryDeformHostProfile(
        String editorVersion,
        String managerInternalName,
        String handlerInterfaceInternalName,
        String snapshotInternalName,
        String enumInternalName,
        String toolDetailInternalName,
        String lockMethodName,
        String unlockMethodName,
        String pointShaderClassName,
        String pointShaderMethodName,
        String cursorClassName,
        String hoverCursorMethodName,
        String defaultCursorMethodName,
        String shiftMethodName,
        String ctrlMethodName,
        String hitRadiusMethodName,
        boolean hasHandlerTypeMethod) {

    private static final String TEMP_PKG = "com/live2d/cubism/view/context/temporaryHandler/";
    private static final String MGR_LEGACY = TEMP_PKG + "TemporaryHandlerManager";
    private static final String MGR_5300 = TEMP_PKG + "g";
    private static final String MGR_5302 = TEMP_PKG + "f";
    private static final String IFACE_D = TEMP_PKG + "d";
    private static final String IFACE_E = TEMP_PKG + "e";
    private static final String ENUM_LEGACY = TEMP_PKG + "TemporaryHandlerManager$Type";
    private static final String ENUM_5300 = TEMP_PKG + "TemporaryHandlerType";

    private static final String LOCK_LEGACY = "c";
    private static final String UNLOCK_LEGACY = "d";
    private static final String LOCK_5300 = "d";
    private static final String UNLOCK_5300 = "e";

    private static final String SHADER_METHOD_LEGACY = "j";
    private static final String SHADER_METHOD_5300 = "p";
    private static final String HOVER_CURSOR_METHOD = "G";
    private static final String DEFAULT_CURSOR_METHOD = "C";

    private static final String SHIFT_LEGACY = "aB";
    private static final String CTRL_LEGACY = "az";
    private static final String HIT_LEGACY = "aM";
    private static final String SHIFT_5304 = "aE";
    private static final String CTRL_5304 = "aC";
    private static final String HIT_5304 = "aP";

    private static final Map<String, TemporaryDeformHostProfile> PROFILES = buildProfiles();

    public TemporaryDeformHostProfile {
        Objects.requireNonNull(editorVersion, "editorVersion");
        Objects.requireNonNull(managerInternalName, "managerInternalName");
        Objects.requireNonNull(handlerInterfaceInternalName, "handlerInterfaceInternalName");
        Objects.requireNonNull(snapshotInternalName, "snapshotInternalName");
        Objects.requireNonNull(enumInternalName, "enumInternalName");
        Objects.requireNonNull(toolDetailInternalName, "toolDetailInternalName");
        Objects.requireNonNull(lockMethodName, "lockMethodName");
        Objects.requireNonNull(unlockMethodName, "unlockMethodName");
        Objects.requireNonNull(pointShaderClassName, "pointShaderClassName");
        Objects.requireNonNull(pointShaderMethodName, "pointShaderMethodName");
        Objects.requireNonNull(cursorClassName, "cursorClassName");
        Objects.requireNonNull(hoverCursorMethodName, "hoverCursorMethodName");
        Objects.requireNonNull(defaultCursorMethodName, "defaultCursorMethodName");
        Objects.requireNonNull(shiftMethodName, "shiftMethodName");
        Objects.requireNonNull(ctrlMethodName, "ctrlMethodName");
        Objects.requireNonNull(hitRadiusMethodName, "hitRadiusMethodName");
    }

    /** Returns the verified host profile for an exact Cubism Editor version, if supported. */
    public static Optional<TemporaryDeformHostProfile> forVersion(final String version) {
        if (version == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(PROFILES.get(version));
    }

    /** Resolves the verified host profile from an admitted Editor-model resolver. */
    public static TemporaryDeformHostProfile from(final VerifiedMemberResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        final String version = resolver.admittedCubismVersion();
        return forVersion(version)
                .orElseThrow(() ->
                        new IllegalStateException("temporary deform tools do not support Cubism version " + version));
    }

    private static Map<String, TemporaryDeformHostProfile> buildProfiles() {
        final Map<String, TemporaryDeformHostProfile> map = new java.util.HashMap<>();
        register(
                map,
                ReviewedVersions.line("5.0"),
                MGR_LEGACY,
                IFACE_D,
                "ad",
                ENUM_LEGACY,
                "af",
                LOCK_LEGACY,
                UNLOCK_LEGACY,
                "com.live2d.cubism.setting.ec",
                SHADER_METHOD_LEGACY,
                "com.live2d.cubism.setting.P",
                SHIFT_LEGACY,
                CTRL_LEGACY,
                HIT_LEGACY,
                false);
        register(
                map,
                ReviewedVersions.line("5.1"),
                MGR_LEGACY,
                IFACE_D,
                "ad",
                ENUM_LEGACY,
                "af",
                LOCK_LEGACY,
                UNLOCK_LEGACY,
                "com.live2d.cubism.setting.eb",
                SHADER_METHOD_LEGACY,
                "com.live2d.cubism.setting.P",
                SHIFT_LEGACY,
                CTRL_LEGACY,
                HIT_LEGACY,
                false);
        register(
                map,
                ReviewedVersions.line("5.2"),
                MGR_LEGACY,
                IFACE_D,
                "V",
                ENUM_LEGACY,
                "X",
                LOCK_LEGACY,
                UNLOCK_LEGACY,
                "com.live2d.cubism.setting.ej",
                SHADER_METHOD_LEGACY,
                "com.live2d.cubism.setting.Q",
                SHIFT_LEGACY,
                CTRL_LEGACY,
                HIT_LEGACY,
                false);
        register(
                map,
                ReviewedVersions.between("5.3.00", "5.3.01"),
                MGR_5300,
                IFACE_E,
                "V",
                ENUM_5300,
                "X",
                LOCK_5300,
                UNLOCK_5300,
                "com.live2d.cubism.setting.el",
                SHADER_METHOD_5300,
                "com.live2d.cubism.setting.S",
                SHIFT_LEGACY,
                CTRL_LEGACY,
                HIT_LEGACY,
                true);
        register(
                map,
                ReviewedVersions.between("5.3.02", "5.3.02"),
                MGR_5302,
                IFACE_D,
                "W",
                ENUM_5300,
                "Y",
                LOCK_5300,
                UNLOCK_5300,
                "com.live2d.cubism.setting.el",
                SHADER_METHOD_5300,
                "com.live2d.cubism.setting.S",
                SHIFT_LEGACY,
                CTRL_LEGACY,
                HIT_LEGACY,
                true);
        register(
                map,
                ReviewedVersions.between("5.3.03", "5.3.03"),
                MGR_5302,
                IFACE_D,
                "R",
                ENUM_5300,
                "T",
                LOCK_5300,
                UNLOCK_5300,
                "com.live2d.cubism.setting.em",
                SHADER_METHOD_5300,
                "com.live2d.cubism.setting.T",
                SHIFT_LEGACY,
                CTRL_LEGACY,
                HIT_LEGACY,
                true);
        register(
                map,
                ReviewedVersions.since("5.3.04"),
                MGR_5302,
                IFACE_D,
                "R",
                ENUM_5300,
                "T",
                LOCK_5300,
                UNLOCK_5300,
                "com.live2d.cubism.setting.em",
                SHADER_METHOD_5300,
                "com.live2d.cubism.setting.T",
                SHIFT_5304,
                CTRL_5304,
                HIT_5304,
                true);
        return Map.copyOf(map);
    }

    private static void register(
            final Map<String, TemporaryDeformHostProfile> map,
            final Set<String> versions,
            final String managerInternalName,
            final String handlerInterfaceInternalName,
            final String snapshotInternalName,
            final String enumInternalName,
            final String toolDetailInternalName,
            final String lockMethodName,
            final String unlockMethodName,
            final String pointShaderClassName,
            final String pointShaderMethodName,
            final String cursorClassName,
            final String shiftMethodName,
            final String ctrlMethodName,
            final String hitRadiusMethodName,
            final boolean hasHandlerTypeMethod) {
        for (final String v : versions) {
            map.put(
                    v,
                    new TemporaryDeformHostProfile(
                            v,
                            managerInternalName,
                            handlerInterfaceInternalName,
                            snapshotInternalName,
                            enumInternalName,
                            toolDetailInternalName,
                            lockMethodName,
                            unlockMethodName,
                            pointShaderClassName,
                            pointShaderMethodName,
                            cursorClassName,
                            HOVER_CURSOR_METHOD,
                            DEFAULT_CURSOR_METHOD,
                            shiftMethodName,
                            ctrlMethodName,
                            hitRadiusMethodName,
                            hasHandlerTypeMethod));
        }
    }
}
