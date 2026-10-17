package dev.turboism.ui.appearance.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import javax.swing.JLabel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NativeParameterAppearanceBridgeTest {
    @AfterEach
    void clear() {
        NativeParameterAppearanceBridge.clearForTesting();
    }

    @Test
    void replaysNonSwingRowsCreatedBeforeBridgeInstallation() throws Exception {
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        coordinator.replaceHostGeneration(7);
        final SingleRow parameter = new SingleRow("ParamAngleX", "Angle X");
        final FolderRow folder = new FolderRow("GroupA", "Group A");

        NativeParameterAppearanceBridge.install(selectors(), new ParameterControlAppearanceProvider(7, coordinator));
        NativeParameterAppearanceBridge.replayExistingRows(List.of(parameter, folder));
        javax.swing.SwingUtilities.invokeAndWait(() -> {});

        final var bindings = coordinator.parameterControlBindings();
        assertEquals(2, bindings.size());
        assertEquals("ParamAngleX", bindings.get(0).id());
        assertSame(parameter.label.component(), bindings.get(0).label());
        assertEquals("GroupA", bindings.get(1).id());
        assertSame(folder.label().component(), bindings.get(1).label());
    }

    @Test
    void appearanceChangesCaptureRowsCreatedBeforeHookInstallationWithoutFilterContributions() throws Exception {
        final var coordinator = new PaletteAppearanceCoordinator();
        coordinator.replaceHostGeneration(7);
        final var parameter = new SingleRow("ParamAngleX", "Angle X");
        final var folder = new FolderRow("GroupA", "Group A");
        coordinator.bindParameterRowSource(() -> List.of(parameter, folder));
        NativeParameterAppearanceBridge.install(selectors(), new ParameterControlAppearanceProvider(7, coordinator));
        final var scope = new PaletteAppearanceCoordinator.Scope("doc", 1, "model", 1, 7, 1);
        final var icon = new javax.swing.ImageIcon(new java.awt.image.BufferedImage(16, 16, 2));
        coordinator.bindNativeIconResolver((ref, disabled) -> java.util.Optional.of(icon));
        coordinator.reconcile(scope);
        final var registration = coordinator.register(
                "plugin",
                1,
                scope,
                PaletteAppearanceCoordinator.Palette.PARAMETER,
                "ParamAngleX",
                PaletteAppearanceCoordinator.Property.LEADING_ACTION,
                new dev.turboism.sdk.ui.appearance.PaletteRowAction(
                        new dev.turboism.sdk.ui.resource.UiIconRef(dev.turboism.sdk.ui.resource.CubismIcon.LOCK),
                        dev.turboism.sdk.ui.appearance.PaletteRowAction.State.LOCKED,
                        "lock",
                        () -> {}));
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        assertEquals(2, coordinator.parameterControlBindings().size());
        assertEquals(
                dev.turboism.sdk.ui.appearance.PaletteRowAction.State.LOCKED,
                parameter.label.component().getClientProperty(NativeRowActionTracker.MARKER));
        registration.close();
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        assertEquals(null, parameter.label.component().getIcon());
        coordinator.clearParameterRowSource();
        javax.swing.SwingUtilities.invokeAndWait(NativeParameterAppearanceBridge::uninstall);
        assertEquals(0, coordinator.parameterControlBindings().size());
    }

    @Test
    void forwardsOffEdtRowCallbacksToTheEdt() throws Exception {
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        coordinator.replaceHostGeneration(7);
        final SingleRow parameter = new SingleRow("ParamAngleX", "Angle X");
        NativeParameterAppearanceBridge.install(selectors(), new ParameterControlAppearanceProvider(7, coordinator));

        NativeParameterAppearanceBridge.afterParameterRow(parameter);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});

        final var bindings = coordinator.parameterControlBindings();
        assertEquals(1, bindings.size());
        assertEquals("ParamAngleX", bindings.get(0).id());
        assertSame(parameter.label.component(), bindings.get(0).label());
    }

    @Test
    void reportsOnlySelectedAttachedSourcesAndClearsOnUninstall() throws Exception {
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        coordinator.replaceHostGeneration(7);
        final SingleRow parameter = new SingleRow("ParamAngleX", "Angle X");
        NativeParameterAppearanceBridge.install(selectors(), new ParameterControlAppearanceProvider(7, coordinator));
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            NativeParameterAppearanceBridge.afterParameterSelection(parameter, true);
            assertEquals(List.of(), NativeParameterAppearanceBridge.selectedParameterSources());
            parameter.label.showing = true;
            assertEquals(List.of(parameter.source), NativeParameterAppearanceBridge.selectedParameterSources());
            NativeParameterAppearanceBridge.afterParameterSelection(parameter, false);
            assertEquals(List.of(), NativeParameterAppearanceBridge.selectedParameterSources());
            NativeParameterAppearanceBridge.afterParameterSelection(parameter, true);
            NativeParameterAppearanceBridge.uninstall();
            assertEquals(List.of(), NativeParameterAppearanceBridge.selectedParameterSources());
        });
    }

    private static NativeParameterAppearanceBridge.Selectors selectors() {
        return new NativeParameterAppearanceBridge.Selectors(
                owner(SingleRow.class),
                owner(DoubleRow.class),
                owner(FolderRow.class),
                "source",
                "secondarySource",
                "source",
                "label",
                "secondaryLabel",
                "label",
                owner(ParameterSource.class),
                owner(FolderSource.class),
                "getId",
                "getId",
                "getIdString",
                owner(CLabel.class),
                "component",
                SingleRow.class.getClassLoader());
    }

    private static String owner(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    static final class SingleRow {
        private final ParameterSource source;
        private final CLabel label;

        SingleRow(final String id, final String name) {
            source = new ParameterSource(id);
            label = new CLabel(name);
        }

        public ParameterSource source() {
            return source;
        }
    }

    static final class DoubleRow {
        private final ParameterSource source = new ParameterSource("unused");
        private final ParameterSource secondarySource = new ParameterSource("unused-secondary");
        private final CLabel label = new CLabel("unused");
        private final CLabel secondaryLabel = new CLabel("unused-secondary");

        public ParameterSource source() {
            return source;
        }

        public ParameterSource secondarySource() {
            return secondarySource;
        }
    }

    static final class FolderRow {
        private final FolderSource source;
        private final CLabel label;

        FolderRow(final String id, final String name) {
            source = new FolderSource(id);
            label = new CLabel(name);
        }

        public FolderSource source() {
            return source;
        }

        public CLabel label() {
            return label;
        }
    }

    static class ParameterSource {
        private final Id id;

        ParameterSource(final String id) {
            this.id = new Id(id);
        }

        public Id getId() {
            return id;
        }
    }

    static final class FolderSource extends ParameterSource {
        FolderSource(final String id) {
            super(id);
        }
    }

    record Id(String value) {
        public String getIdString() {
            return value;
        }
    }

    static final class CLabel {
        private final JLabel component;
        private boolean showing;

        CLabel(final String text) {
            component = new JLabel(text) {
                @Override
                public boolean isShowing() {
                    return showing;
                }
            };
        }

        public JLabel component() {
            return component;
        }
    }
}
