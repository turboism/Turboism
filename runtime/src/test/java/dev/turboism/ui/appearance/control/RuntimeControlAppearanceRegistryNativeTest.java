package dev.turboism.ui.appearance.control;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.ui.appearance.ControlAppearanceContribution;
import dev.turboism.sdk.ui.appearance.ControlAppearanceSnapshot;
import dev.turboism.sdk.ui.appearance.ControlAppearanceStyle;
import dev.turboism.sdk.ui.appearance.ControlAppearanceTarget;
import dev.turboism.sdk.ui.appearance.NativeControlAppearance;
import dev.turboism.sdk.ui.appearance.NativeControlBackground;
import dev.turboism.sdk.ui.appearance.PresetColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plugin-facing registry composition of native authoring and transient overlays. */
class RuntimeControlAppearanceRegistryNativeTest {

    @Test
    void snapshotLayersTheNativeValueWithTheResolvedTransientOverlay() {
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        final List<ControlAppearanceTarget> readTargets = new ArrayList<>();
        final RuntimeControlAppearanceRegistry registry = new RuntimeControlAppearanceRegistry(
            "plugin-a", 1, (permission, operation) -> { }, coordinator,
            TestNativeControlAppearanceAuthoring.of(
                target -> {
                    readTargets.add(target);
                    return new NativeControlAppearance(
                        new NativeControlBackground.Preset(PresetColor.GRAY),
                        Optional.of(new Color(0.5F, 0.5F, 0.5F, 1.0F))
                    );
                },
                (target, background) -> { }
            )
        );
        registry.register(new ControlAppearanceContribution(
            "part.foreground",
            new ControlAppearanceTarget.PartLabel(new PartId("PartA")),
            new ControlAppearanceStyle(
                Optional.of(new Color(1.0F, 0.0F, 0.0F, 1.0F)), Optional.empty(), Optional.empty()
            )
        ));

        ControlAppearanceSnapshot snapshot =
            registry.snapshot(new ControlAppearanceTarget.PartLabel(new PartId("PartA")));

        assertEquals(Optional.of(new NativeControlAppearance(
            new NativeControlBackground.Preset(PresetColor.GRAY),
            Optional.of(new Color(0.5F, 0.5F, 0.5F, 1.0F))
        )), snapshot.nativeAppearance());
        assertEquals(
            new Color(1.0F, 0.0F, 0.0F, 1.0F),
            snapshot.transientOverlay().orElseThrow().foreground().orElseThrow()
        );
        assertEquals(1, readTargets.size());
    }

    @Test
    void snapshotWithoutOverlayStillResolvesTheNativeValue() {
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        final RuntimeControlAppearanceRegistry registry = new RuntimeControlAppearanceRegistry(
            "plugin-a", 1, (permission, operation) -> { }, coordinator,
            TestNativeControlAppearanceAuthoring.of(
                target -> new NativeControlAppearance(
                    new NativeControlBackground.Default(),
                    Optional.of(new Color(0.0F, 0.0F, 0.0F, 1.0F))
                ),
                (target, background) -> { }
            )
        );

        ControlAppearanceSnapshot snapshot = registry.snapshot(
            new ControlAppearanceTarget.PartFolder(new PartId("PartA"))
        );

        assertTrue(snapshot.nativeAppearance().isPresent());
        assertTrue(snapshot.transientOverlay().isEmpty());
    }

    @Test
    void parameterLabelSnapshotIsOverlayOnlyAndWritesFailClosedBeforeTheSeam() {
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        final List<ControlAppearanceTarget> nativeCalls = new ArrayList<>();
        final RuntimeControlAppearanceRegistry registry = new RuntimeControlAppearanceRegistry(
            "plugin-a", 1, (permission, operation) -> { }, coordinator,
            TestNativeControlAppearanceAuthoring.of(
                target -> {
                    nativeCalls.add(target);
                    return new NativeControlAppearance(
                        new NativeControlBackground.Default(),
                        Optional.of(new Color(0.0F, 0.0F, 0.0F, 1.0F))
                    );
                },
                (target, background) -> nativeCalls.add(target)
            )
        );
        registry.register(new ControlAppearanceContribution(
            "parameter.label",
            new ControlAppearanceTarget.ParameterLabel(new ParameterId("ParamA")),
            new ControlAppearanceStyle(
                Optional.empty(), Optional.of(new Color(0.2F, 0.3F, 0.4F, 1.0F)), Optional.empty()
            )
        ));

        ControlAppearanceSnapshot snapshot = registry.snapshot(
            new ControlAppearanceTarget.ParameterLabel(new ParameterId("ParamA"))
        );
        assertTrue(snapshot.nativeAppearance().isEmpty());
        assertEquals(
            new Color(0.2F, 0.3F, 0.4F, 1.0F),
            snapshot.transientOverlay().orElseThrow().background().orElseThrow()
        );
        assertThrows(UnsupportedOperationException.class, () -> registry.setNativeBackground(
            new ControlAppearanceTarget.ParameterLabel(new ParameterId("ParamA")),
            new NativeControlBackground.Default()
        ));
        assertTrue(nativeCalls.isEmpty(), "ParameterLabel must not reach the native seam");
    }

    @Test
    void snapshotRequiresModelReadAndWritesRequireModelWritePermissions() {
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        final List<String> denied = new ArrayList<>();
        final PermissionChecker checker = (permission, operation) -> {
            if (permission.equals(PermissionIds.TURBOISM_CUBISM_MODEL_READ)
                || permission.equals(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE)) {
                denied.add(permission);
                throw new dev.turboism.sdk.permission.CubismPermissionException(
                    permission
                );
            }
        };
        final RuntimeControlAppearanceRegistry registry = new RuntimeControlAppearanceRegistry(
            "plugin-a", 1, checker, coordinator, TestNativeControlAppearanceAuthoring.unavailable()
        );
        final ControlAppearanceTarget.ParameterFolder folder =
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupA"));

        assertThrows(
            dev.turboism.sdk.permission.CubismPermissionException.class,
            () -> registry.snapshot(folder)
        );
        assertEquals(List.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ), denied);
        assertThrows(
            dev.turboism.sdk.permission.CubismPermissionException.class,
            () -> registry.setNativeBackground(folder, new NativeControlBackground.Default())
        );
        assertEquals(
            List.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ, PermissionIds.TURBOISM_CUBISM_MODEL_WRITE),
            denied
        );
    }

    @Test
    void registerStillRequiresAppearanceModifyWhileNativeAccessStaysPermissionIndependent() {
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        final List<String> denied = new ArrayList<>();
        final PermissionChecker checker = (permission, operation) -> {
            denied.add(permission);
            throw new dev.turboism.sdk.permission.CubismPermissionException(permission);
        };
        final RuntimeControlAppearanceRegistry registry = new RuntimeControlAppearanceRegistry(
            "plugin-a", 1, checker, coordinator, TestNativeControlAppearanceAuthoring.unavailable()
        );
        assertThrows(
            dev.turboism.sdk.permission.CubismPermissionException.class,
            () -> registry.register(new ControlAppearanceContribution(
                "deformer.foreground",
                new ControlAppearanceTarget.DeformerLabel(new DeformerId("WarpA")),
                new ControlAppearanceStyle(
                    Optional.of(new Color(1.0F, 1.0F, 1.0F, 1.0F)), Optional.empty(), Optional.empty()
                )
            ))
        );
        assertEquals(List.of(PermissionIds.TURBOISM_UI_APPEARANCE_MODIFY), denied);
    }

    @Test
    void transientOverlaysStayTargetSpecificEvenWhereNativeValuesAlias() {
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        final RuntimeControlAppearanceRegistry registry = new RuntimeControlAppearanceRegistry(
            "plugin-a", 1, (permission, operation) -> { }, coordinator,
            TestNativeControlAppearanceAuthoring.of(
                target -> new NativeControlAppearance(
                    new NativeControlBackground.Default(),
                    Optional.of(new Color(0.0F, 0.0F, 0.0F, 1.0F))
                ),
                (target, background) -> { }
            )
        );
        registry.register(new ControlAppearanceContribution(
            "part.label.background",
            new ControlAppearanceTarget.PartLabel(new PartId("PartA")),
            new ControlAppearanceStyle(
                Optional.empty(), Optional.of(new Color(0.1F, 0.1F, 0.1F, 1.0F)), Optional.empty()
            )
        ));

        final ControlAppearanceTarget.PartFolder folder =
            new ControlAppearanceTarget.PartFolder(new PartId("PartA"));
        assertTrue(registry.snapshot(folder).transientOverlay().isEmpty(),
            "PartFolder must not see the PartLabel overlay");
        assertEquals(
            new Color(0.1F, 0.1F, 0.1F, 1.0F),
            registry.snapshot(new ControlAppearanceTarget.PartLabel(new PartId("PartA")))
                .transientOverlay().orElseThrow().background().orElseThrow()
        );
    }
}
