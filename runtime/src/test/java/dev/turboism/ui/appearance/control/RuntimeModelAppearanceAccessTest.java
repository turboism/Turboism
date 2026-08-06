package dev.turboism.ui.appearance.control;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.NativeLabelColorAuthoring;
import dev.turboism.adapter.cubism.NativeLabelColorTarget;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;
import dev.turboism.sdk.ui.appearance.PaletteEntry;
import dev.turboism.sdk.ui.appearance.PaletteEntryState;
import dev.turboism.sdk.ui.appearance.PresetColor;
import dev.turboism.sdk.ui.appearance.UiColor;
import dev.turboism.sdk.ui.appearance.model.DrawableAppearance;
import dev.turboism.sdk.ui.appearance.model.PartAppearance;
import dev.turboism.sdk.plugin.DisposableScope;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeModelAppearanceAccessTest {

    @Test
    void fivePalettePropertiesResolveIndependently() {
        final Fixture fixture = new Fixture("content-a", "model-a", 7L);
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        final RuntimeModelAppearanceAccess access = fixture.access("plugin-a", 1L, coordinator);
        final PaletteEntry entry = access.part(part("PartA"), 3L).partPaletteEntry().orElseThrow();
        final UiColor text = new UiColor(0.1F, 0.2F, 0.3F, 1.0F);
        final UiColor background = new UiColor(0.4F, 0.5F, 0.6F, 1.0F);

        entry.overrideFontSize(14.0F);
        entry.overrideBold(true);
        entry.overrideItalic(false);
        entry.overrideTextColor(text);
        entry.overrideBackgroundColor(background);

        assertEquals(new PaletteEntryState(
            Optional.of(14.0F), Optional.of(true), Optional.of(false),
            Optional.of(text), Optional.of(background)
        ), entry.resolved());
        assertTrue(entry.actual().isEmpty());
    }

    @Test
    void drawableFacadeExposesDeformerPartPaletteForArtMeshRows() {
        final Fixture fixture = new Fixture("content-a", "model-a", 7L);
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        final RuntimeModelAppearanceAccess access = fixture.access("plugin-a", 1L, coordinator);
        final PaletteEntry partEntry = access.drawable("model-a", "ArtMeshA", 3L).partPaletteEntry().orElseThrow();
        final PaletteEntry deformerEntry = access.drawable("model-a", "ArtMeshA", 3L).deformerPaletteEntry().orElseThrow();

        partEntry.overrideTextColor(new UiColor(0.1F, 0.2F, 0.3F, 1.0F));
        deformerEntry.overrideBackgroundColor(new UiColor(0.4F, 0.5F, 0.6F, 1.0F));

        assertEquals(Optional.of(new UiColor(0.1F, 0.2F, 0.3F, 1.0F)),
            partEntry.resolved().textColor());
        assertEquals(Optional.of(new UiColor(0.4F, 0.5F, 0.6F, 1.0F)),
            deformerEntry.resolved().backgroundColor());
    }

    @Test
    void fontSizeBoundariesAreAcceptedAndInvalidValuesLeaveStateUntouched() {
        final Fixture fixture = new Fixture("content-a", "model-a", 1L);
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        final RuntimeModelAppearanceAccess access = fixture.access("plugin-a", 1L, coordinator);
        final PaletteEntry entry = access.part(part("PartA"), 0L).partPaletteEntry().orElseThrow();

        final var lower = entry.overrideFontSize(6.0F);
        final PaletteEntryState lowerState = entry.resolved();
        final int size = coordinator.size();
        assertEquals(Optional.of(6.0F), lowerState.fontSize());

        for (float invalid : new float[] { Float.NaN, 5.0F, 97.0F }) {
            assertThrows(IllegalArgumentException.class, () -> entry.overrideFontSize(invalid));
            assertEquals(size, coordinator.size());
            assertEquals(lowerState, entry.resolved());
        }

        lower.close();
        final var upper = entry.overrideFontSize(96.0F);
        assertEquals(Optional.of(96.0F), entry.resolved().fontSize());
        upper.close();
    }

    @Test
    void upsertDoesNotLetAnOldRegistrationDeleteItsReplacement() {
        final Fixture fixture = new Fixture("content-a", "model-a", 1L);
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        final RuntimeModelAppearanceAccess access = fixture.access("plugin-a", 1L, coordinator);
        final PaletteEntry entry = access.part(part("PartA"), 0L).partPaletteEntry().orElseThrow();

        final var oldRegistration = entry.overrideBold(true);
        final var replacement = entry.overrideBold(false);
        oldRegistration.close();

        assertEquals(Optional.of(false), entry.resolved().bold());
        replacement.close();
        assertTrue(entry.resolved().bold().isEmpty());
    }

    @Test
    void contentSwitchKeepsIndependentOverridesAndOldFacadeFailsClosed() {
        final Fixture fixture = new Fixture("content-a", "model-a", 1L);
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        final RuntimeModelAppearanceAccess access = fixture.access("plugin-a", 1L, coordinator);
        final UiColor red = new UiColor(1.0F, 0.0F, 0.0F, 1.0F);
        final UiColor blue = new UiColor(0.0F, 0.0F, 1.0F, 1.0F);
        final PaletteEntry aEntry = access.part(part("PartA"), 0L).partPaletteEntry().orElseThrow();
        final var aRegistration = aEntry.overrideTextColor(red);

        fixture.replace("content-b", "model-b", 2L);
        final PaletteEntry bEntry = access.part(part("PartA"), 0L).partPaletteEntry().orElseThrow();
        final var bRegistration = bEntry.overrideTextColor(blue);

        assertEquals(Optional.of(blue), bEntry.resolved().textColor());
        assertThrows(IllegalStateException.class, aEntry::resolved);

        fixture.replace("content-a", "model-a", 3L);
        final PaletteEntry aAgain = access.part(part("PartA"), 0L).partPaletteEntry().orElseThrow();
        assertEquals(Optional.of(red), aAgain.resolved().textColor());
        assertThrows(IllegalStateException.class, bEntry::resolved);

        aRegistration.close();
        assertTrue(aAgain.resolved().textColor().isEmpty());
        fixture.replace("content-b", "model-b", 2L);
        final PaletteEntry bAgain = access.part(part("PartA"), 0L).partPaletteEntry().orElseThrow();
        assertEquals(Optional.of(blue), bAgain.resolved().textColor());
        bRegistration.close();
        assertTrue(bAgain.resolved().textColor().isEmpty());
    }

    @Test
    void emptyHostScopeCanRecoverWithoutRetainingTheOldFacade() {
        final Fixture fixture = new Fixture("content-a", "model-a", 1L);
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        final RuntimeModelAppearanceAccess access = fixture.access("plugin-a", 1L, coordinator);
        final PaletteEntry entry = access.part(part("PartA"), 0L).partPaletteEntry().orElseThrow();
        entry.overrideBold(true);

        fixture.empty();
        assertTrue(access.part(part("PartA"), 0L).partPaletteEntry().isEmpty());

        fixture.replace("content-b", "model-b", 3L);
        final PaletteEntry recovered = access.part(part("PartA"), 0L).partPaletteEntry().orElseThrow();
        assertTrue(recovered.resolved().bold().isEmpty());
    }

    @Test
    void pluginCleanupRemovesOnlyOwnedOverridesAndScopeCloseFailsClosed() throws Exception {
        final Fixture fixture = new Fixture("content-a", "model-a", 1L);
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        final RuntimeModelAppearanceAccess first = fixture.access("plugin-a", 1L, coordinator);
        final RuntimeModelAppearanceAccess second = fixture.access("plugin-b", 1L, coordinator);
        final PaletteEntry firstEntry = first.part(part("PartA"), 0L).partPaletteEntry().orElseThrow();
        final PaletteEntry secondEntry = second.part(part("PartA"), 0L).partPaletteEntry().orElseThrow();
        firstEntry.overrideBold(true);
        secondEntry.overrideBold(false);
        first.close();
        assertEquals(Optional.of(false), secondEntry.resolved().bold());

        final DisposableScope scope = new DisposableScope();
        second.bind(scope);
        scope.close();
        assertThrows(IllegalStateException.class, secondEntry::resolved);
    }

    @Test
    void unavailableDrawableAndNativeSeamFailClosed() {
        final Fixture fixture = new Fixture("content-a", "model-a", 1L);
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        final RuntimeModelAppearanceAccess access = fixture.access("plugin-a", 1L, coordinator);
        final PartAppearance partAppearance = access.part(part("PartA"), 0L);
        final DrawableAppearance drawableAppearance = access.drawable(null, 0L);

        assertTrue(partAppearance.nativeLabelColor().isEmpty());
        assertThrows(UnsupportedOperationException.class,
            () -> partAppearance.setNativeLabelColor(new NativeLabelColor.Default()));
        assertTrue(drawableAppearance.partPaletteEntry().isEmpty());
        assertTrue(drawableAppearance.deformerPaletteEntry().isEmpty());

        final NativeLabelColor custom = new NativeLabelColor.Preset(PresetColor.GRAY);
        final RuntimeModelAppearanceAccess supported = fixture.access(
            "plugin-b", 1L, coordinator,
            new NativeLabelColorAuthoring() {
                @Override
                public NativeLabelColorState readNativeLabelColor(final NativeLabelColorTarget target) {
                    return new NativeLabelColorState(custom, Optional.empty());
                }

                @Override
                public void setNativeLabelColor(
                    final NativeLabelColorTarget target,
                    final NativeLabelColor color
                ) {
                }
            }
        );
        assertEquals(Optional.of(custom), supported.part(part("PartA"), 0L)
            .nativeLabelColor().map(NativeLabelColorState::labelColor));
    }

    @Test
    void appearanceAndModelWritePermissionsAreIndependentAndDeniedBeforeNativeWrite() {
        final Fixture fixture = new Fixture("content-a", "model-a", 1L);
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        final java.util.List<String> checked = new java.util.ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        final NativeLabelColorAuthoring authoring = new NativeLabelColorAuthoring() {
            @Override
            public NativeLabelColorState readNativeLabelColor(final NativeLabelColorTarget target) {
                return new NativeLabelColorState(new NativeLabelColor.Default(), Optional.empty());
            }

            @Override
            public void setNativeLabelColor(
                final NativeLabelColorTarget target,
                final NativeLabelColor color
            ) {
                writes.incrementAndGet();
            }
        };
        final PermissionChecker appearanceOnly = (permission, operation) -> {
            checked.add(permission);
            if (!PermissionIds.TURBOISM_UI_APPEARANCE_MODIFY.equals(permission)) {
                throw new CubismPermissionException("denied " + permission);
            }
        };
        final RuntimeModelAppearanceAccess uiAccess = fixture.access(
            "plugin-ui", 1L, coordinator, authoring, appearanceOnly
        );
        final PaletteEntry uiEntry = uiAccess.part(part("PartA"), 0L).partPaletteEntry().orElseThrow();

        uiEntry.overrideBold(true);
        assertEquals(List.of(PermissionIds.TURBOISM_UI_APPEARANCE_MODIFY), checked);
        assertThrows(CubismPermissionException.class, () -> uiAccess.part(part("PartA"), 0L)
            .setNativeLabelColor(new NativeLabelColor.Default()));
        assertEquals(0, writes.get(), "permission denial must precede the native write seam");
        assertEquals(
            List.of(
                PermissionIds.TURBOISM_UI_APPEARANCE_MODIFY,
                PermissionIds.TURBOISM_CUBISM_MODEL_WRITE
            ),
            checked
        );

        checked.clear();
        final PermissionChecker modelWriteOnly = (permission, operation) -> {
            checked.add(permission);
            if (!PermissionIds.TURBOISM_CUBISM_MODEL_WRITE.equals(permission)) {
                throw new CubismPermissionException("denied " + permission);
            }
        };
        final RuntimeModelAppearanceAccess modelAccess = fixture.access(
            "plugin-model", 1L, coordinator, authoring, modelWriteOnly
        );
        final PaletteEntry modelEntry = modelAccess.part(part("PartA"), 0L).partPaletteEntry().orElseThrow();

        assertThrows(CubismPermissionException.class, () -> modelEntry.overrideBold(true));
        assertEquals(List.of(PermissionIds.TURBOISM_UI_APPEARANCE_MODIFY), checked);
        modelAccess.part(part("PartA"), 0L).setNativeLabelColor(new NativeLabelColor.Default());
        assertEquals(List.of(
            PermissionIds.TURBOISM_UI_APPEARANCE_MODIFY,
            PermissionIds.TURBOISM_CUBISM_MODEL_WRITE
        ), checked);
        assertEquals(1, writes.get());
    }

    private static Part part(final String id) {
        return new Part() {
            @Override public PartId id() { return new PartId(id); }
            @Override public void setName(final String name) { }
            @Override public float getOpacity() { return 1.0F; }
            @Override public int parentIndex() { return -1; }
            @Override public void setOpacity(final float opacity) { }
        };
    }

    private static final class Fixture {
        private final AtomicLong token;
        private final AtomicLong hostGeneration = new AtomicLong(4L);
        private final AtomicLong providerGeneration = new AtomicLong(9L);
        private String contentId;
        private String modelId;
        private boolean hostPresent = true;
        private HostSnapshotSource.HostModel model;

        private Fixture(final String contentId, final String modelId, final long token) {
            this.token = new AtomicLong(token);
            replace(contentId, modelId, token);
        }

        private void replace(final String contentId, final String modelId, final long token) {
        hostPresent = true;
            this.contentId = contentId;
            this.modelId = modelId;
            this.token.set(token);
            this.model = new HostSnapshotSource.HostModel(modelId, modelId, List.of(), List.of(), List.of());
        }

        private void empty() {
            hostPresent = false;
            token.incrementAndGet();
        }

        private RuntimeModelAppearanceAccess access(
            final String pluginId,
            final long pluginGeneration,
            final PaletteAppearanceCoordinator coordinator
        ) {
            return access(pluginId, pluginGeneration, coordinator, NativeLabelColorAuthoring.unavailable());
        }

        private RuntimeModelAppearanceAccess access(
            final String pluginId,
            final long pluginGeneration,
            final PaletteAppearanceCoordinator coordinator,
            final NativeLabelColorAuthoring authoring
        ) {
            return new RuntimeModelAppearanceAccess(
                pluginId,
                pluginGeneration,
                PermissionChecker.allowAll(),
                source(),
                coordinator,
                hostGeneration::get,
                providerGeneration::get,
                authoring
            );
        }

        private RuntimeModelAppearanceAccess access(
            final String pluginId,
            final long pluginGeneration,
            final PaletteAppearanceCoordinator coordinator,
            final NativeLabelColorAuthoring authoring,
            final PermissionChecker permissionChecker
        ) {
            return new RuntimeModelAppearanceAccess(
                pluginId,
                pluginGeneration,
                permissionChecker,
                source(),
                coordinator,
                hostGeneration::get,
                providerGeneration::get,
                authoring
            );
        }

        private HostSnapshotSource source() {
            return new HostSnapshotSource() {
                @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
                @Override public Optional<HostDocument> activeDocument() {
                    return hostPresent ? Optional.of(document()) : Optional.empty();
                }
                @Override public Optional<HostModel> activeModel() {
                    return hostPresent ? Optional.of(model) : Optional.empty();
                }
                @Override public HostSelection selection() {
                    return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
                }
                @Override public boolean isHostPresent() { return hostPresent; }
                @Override public long invalidationToken() { return token.get(); }
            };
        }

        private HostSnapshotSource.HostDocument document() {
            return new HostSnapshotSource.HostDocument(
                "document-" + contentId,
                "Document",
                DocumentKind.MODEL,
                "models/" + contentId + ".cmo3",
                Optional.of(Path.of(contentId + ".cmo3")),
                Optional.of(contentId),
                Optional.of(model),
                Optional.empty()
            );
        }
    }
}
