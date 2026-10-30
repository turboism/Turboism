package dev.turboism.adapter.cubism.mesh;

import dev.turboism.i18n.CubismHostLocale;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

/** Modeless configuration dialog for the Geometric Mesh Layout tool. */
final class GeometricMeshDialog {
    static final String DIALOG_NAME = "turboism:geometric-mesh-layout-dialog";
    private static final int SLIDER_MAX_RADIUS = 500;
    private static final int SLIDER_MAX_SIDES = 64;

    record Config(
            GeometricMeshShape.Preset preset,
            float radius,
            int sides,
            int concentricRings,
            boolean createCenter,
            boolean connectToCenter) {
        Config {
            Objects.requireNonNull(preset, "preset");
            radius = GeometricMeshShape.clampRadius(radius);
            sides = GeometricMeshShape.clampSides(sides);
            concentricRings = GeometricMeshShape.clampConcentricRings(concentricRings);
            if (!createCenter) {
                connectToCenter = false;
            }
        }

        GeometricMeshShape.LayoutPlan toLayoutPlan(final float centerX, final float centerY) {
            return GeometricMeshShape.generate(
                    centerX, centerY, radius, sides, concentricRings, createCenter, connectToCenter);
        }
    }

    record PresetItem(GeometricMeshShape.Preset preset, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    interface Listener {
        void onConfigChanged(Config config);

        void onConfirm(Config config);

        void onCancel();
    }

    private final Listener listener;
    private final Texts texts;
    private final JPanel rootPanel;
    private final JLabel anchorValueLabel;
    private final JComboBox<PresetItem> presetCombo;
    private final JSpinner radiusSpinner;
    private final JSlider radiusSlider;
    private final JSpinner sidesSpinner;
    private final JSlider sidesSlider;
    private final JSlider ringsSlider;
    private final JSpinner ringsSpinner;
    private final JCheckBox createCenterCheck;
    private final JCheckBox connectToCenterCheck;
    private final JButton confirmButton;
    private final JButton cancelButton;
    private JDialog dialog;
    private boolean syncingControls;
    private boolean positionedOnce;

    GeometricMeshDialog(final Config initial, final Point2 initialAnchor, final Listener listener) {
        this(initial, initialAnchor, listener, CubismHostLocale.resolve());
    }

    GeometricMeshDialog(
            final Config initial, final Point2 initialAnchor, final Listener listener, final Locale locale) {
        Objects.requireNonNull(initial, "initial");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.texts = Texts.forLocale(locale);

        rootPanel = new JPanel(new BorderLayout(0, 10));
        rootPanel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        final JPanel form = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 4, 5, 6);
        gbc.anchor = GridBagConstraints.WEST;

        // Row 0: Center anchor info
        final JLabel anchorTitleLabel = new JLabel(texts.anchorLabel());
        anchorValueLabel = new JLabel(formatAnchor(texts, initialAnchor));
        anchorValueLabel.setName("geometric-mesh-layout.anchor");
        anchorValueLabel.setFont(anchorValueLabel.getFont().deriveFont(Font.BOLD));
        gbc.gridx = 0;
        gbc.gridy = 0;
        form.add(anchorTitleLabel, gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(anchorValueLabel, gbc);

        // Row 1: Hint text
        final JLabel hintLabel = new JLabel(texts.anchorHint());
        hintLabel.setFont(hintLabel
                .getFont()
                .deriveFont(Math.max(11.0f, hintLabel.getFont().getSize2D() - 1.0f)));
        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(0, 4, 6, 6);
        form.add(hintLabel, gbc);
        gbc.insets = new Insets(5, 4, 5, 6);

        // Row 2: Preset dropdown (Circle, Triangle, Quadrilateral, Hexagon, Octagon)
        final JLabel presetLabel = new JLabel(texts.presetLabel());
        final PresetItem[] presetItems = new PresetItem[] {
            new PresetItem(GeometricMeshShape.Preset.CIRCLE, texts.presetCircle()),
            new PresetItem(GeometricMeshShape.Preset.TRIANGLE, texts.presetTriangle()),
            new PresetItem(GeometricMeshShape.Preset.QUADRILATERAL, texts.presetQuadrilateral()),
            new PresetItem(GeometricMeshShape.Preset.HEXAGON, texts.presetHexagon()),
            new PresetItem(GeometricMeshShape.Preset.OCTAGON, texts.presetOctagon())
        };
        presetCombo = new JComboBox<>(presetItems);
        presetCombo.setName("geometric-mesh-layout.preset");
        selectPresetItem(initial.preset());
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        form.add(presetLabel, gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(presetCombo, gbc);

        // Row 3: Slider 1 — Radius
        final JLabel radiusLabel = new JLabel(texts.radiusLabel());
        radiusSpinner = new JSpinner(new SpinnerNumberModel(
                (double) initial.radius(),
                (double) GeometricMeshShape.MIN_RADIUS,
                (double) GeometricMeshShape.MAX_RADIUS,
                1.0));
        radiusSpinner.setName("geometric-mesh-layout.radius");
        radiusSlider = new JSlider(
                (int) GeometricMeshShape.MIN_RADIUS,
                SLIDER_MAX_RADIUS,
                Math.min(
                        SLIDER_MAX_RADIUS,
                        Math.max((int) GeometricMeshShape.MIN_RADIUS, Math.round(initial.radius()))));
        radiusSlider.setName("geometric-mesh-layout.radius.slider");
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        form.add(radiusLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        form.add(radiusSlider, gbc);
        gbc.gridx = 2;
        gbc.weightx = 0.0;
        form.add(radiusSpinner, gbc);

        // Row 4: Slider 2 — Sides
        final JLabel sidesLabel = new JLabel(texts.sidesLabel());
        sidesSpinner = new JSpinner(
                new SpinnerNumberModel(initial.sides(), GeometricMeshShape.MIN_SIDES, GeometricMeshShape.MAX_SIDES, 1));
        sidesSpinner.setName("geometric-mesh-layout.sides");
        sidesSlider = new JSlider(
                GeometricMeshShape.MIN_SIDES,
                SLIDER_MAX_SIDES,
                Math.min(SLIDER_MAX_SIDES, Math.max(GeometricMeshShape.MIN_SIDES, initial.sides())));
        sidesSlider.setName("geometric-mesh-layout.sides.slider");
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        form.add(sidesLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        form.add(sidesSlider, gbc);
        gbc.gridx = 2;
        gbc.weightx = 0.0;
        form.add(sidesSpinner, gbc);

        // Row 5: Slider 3 — Concentric Rings (min = 0 by default)
        final JLabel ringsLabel = new JLabel(texts.concentricLabel());
        final int initialRings = GeometricMeshShape.clampConcentricRings(initial.concentricRings());
        ringsSlider = new JSlider(
                GeometricMeshShape.MIN_CONCENTRIC_RINGS, GeometricMeshShape.MAX_CONCENTRIC_RINGS, initialRings);
        ringsSlider.setName("geometric-mesh-layout.rings.slider");
        ringsSpinner = new JSpinner(new SpinnerNumberModel(
                initialRings, GeometricMeshShape.MIN_CONCENTRIC_RINGS, GeometricMeshShape.MAX_CONCENTRIC_RINGS, 1));
        ringsSpinner.setName("geometric-mesh-layout.rings");
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        form.add(ringsLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        form.add(ringsSlider, gbc);
        gbc.gridx = 2;
        gbc.weightx = 0.0;
        form.add(ringsSpinner, gbc);

        // Row 6 (bottom of form): Checkboxes (Create Center Point / Connect Perimeter to Center)
        createCenterCheck = new JCheckBox(texts.createCenterLabel(), initial.createCenter());
        createCenterCheck.setName("geometric-mesh-layout.create-center");
        connectToCenterCheck =
                new JCheckBox(texts.connectToCenterLabel(), initial.createCenter() && initial.connectToCenter());
        connectToCenterCheck.setName("geometric-mesh-layout.connect-center");
        connectToCenterCheck.setEnabled(initial.createCenter());
        final JPanel centerOptionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        centerOptionsPanel.setOpaque(false);
        centerOptionsPanel.add(createCenterCheck);
        centerOptionsPanel.add(connectToCenterCheck);
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(8, 2, 4, 6);
        form.add(centerOptionsPanel, gbc);

        rootPanel.add(form, BorderLayout.CENTER);

        // Bottom buttons: Confirm / Cancel
        final JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        confirmButton = new JButton(texts.confirmButton());
        confirmButton.setName("geometric-mesh-layout.confirm");
        cancelButton = new JButton(texts.cancelButton());
        cancelButton.setName("geometric-mesh-layout.cancel");
        buttonBar.add(confirmButton);
        buttonBar.add(cancelButton);
        rootPanel.add(buttonBar, BorderLayout.SOUTH);

        bindListeners();
    }

    private void selectPresetItem(final GeometricMeshShape.Preset preset) {
        for (int index = 0; index < presetCombo.getItemCount(); index++) {
            final PresetItem item = presetCombo.getItemAt(index);
            if (item != null && item.preset() == preset) {
                presetCombo.setSelectedIndex(index);
                return;
            }
        }
    }

    private void bindListeners() {
        presetCombo.addActionListener(event -> {
            if (syncingControls) return;
            final PresetItem selected = (PresetItem) presetCombo.getSelectedItem();
            if (selected != null) {
                final int targetSides = selected.preset().defaultSides();
                syncingControls = true;
                try {
                    sidesSpinner.setValue(targetSides);
                    sidesSlider.setValue(
                            Math.min(SLIDER_MAX_SIDES, Math.max(GeometricMeshShape.MIN_SIDES, targetSides)));
                } finally {
                    syncingControls = false;
                }
                listener.onConfigChanged(currentConfig());
            }
        });
        radiusSpinner.addChangeListener(event -> {
            if (syncingControls) return;
            syncingControls = true;
            try {
                final int sliderVal = Math.min(
                        SLIDER_MAX_RADIUS, Math.max((int) GeometricMeshShape.MIN_RADIUS, Math.round(radiusValue())));
                radiusSlider.setValue(sliderVal);
            } finally {
                syncingControls = false;
            }
            listener.onConfigChanged(currentConfig());
        });
        radiusSlider.addChangeListener(event -> {
            if (syncingControls) return;
            syncingControls = true;
            try {
                radiusSpinner.setValue((double) radiusSlider.getValue());
            } finally {
                syncingControls = false;
            }
            listener.onConfigChanged(currentConfig());
        });
        sidesSpinner.addChangeListener(event -> {
            if (syncingControls) return;
            final int sides = sidesValue();
            syncingControls = true;
            try {
                final int sliderVal = Math.min(SLIDER_MAX_SIDES, Math.max(GeometricMeshShape.MIN_SIDES, sides));
                sidesSlider.setValue(sliderVal);
                syncPresetComboIfExactMatch(sides);
            } finally {
                syncingControls = false;
            }
            listener.onConfigChanged(currentConfig());
        });
        sidesSlider.addChangeListener(event -> {
            if (syncingControls) return;
            final int sides = GeometricMeshShape.clampSides(sidesSlider.getValue());
            syncingControls = true;
            try {
                sidesSpinner.setValue(sides);
                syncPresetComboIfExactMatch(sides);
            } finally {
                syncingControls = false;
            }
            listener.onConfigChanged(currentConfig());
        });
        ringsSpinner.addChangeListener(event -> {
            if (syncingControls) return;
            final int rings = ringsValue();
            syncingControls = true;
            try {
                ringsSlider.setValue(rings);
            } finally {
                syncingControls = false;
            }
            listener.onConfigChanged(currentConfig());
        });
        ringsSlider.addChangeListener(event -> {
            if (syncingControls) return;
            final int rings = GeometricMeshShape.clampConcentricRings(ringsSlider.getValue());
            syncingControls = true;
            try {
                ringsSpinner.setValue(rings);
            } finally {
                syncingControls = false;
            }
            listener.onConfigChanged(currentConfig());
        });
        createCenterCheck.addActionListener(event -> {
            if (syncingControls) return;
            final boolean centerEnabled = createCenterCheck.isSelected();
            syncingControls = true;
            try {
                if (!centerEnabled) {
                    connectToCenterCheck.setSelected(false);
                }
                connectToCenterCheck.setEnabled(centerEnabled);
            } finally {
                syncingControls = false;
            }
            listener.onConfigChanged(currentConfig());
        });
        connectToCenterCheck.addActionListener(event -> {
            if (syncingControls) return;
            listener.onConfigChanged(currentConfig());
        });
        confirmButton.addActionListener(event -> listener.onConfirm(currentConfig()));
        cancelButton.addActionListener(event -> listener.onCancel());
    }

    private void syncPresetComboIfExactMatch(final int sides) {
        switch (sides) {
            case 3 -> selectPresetItem(GeometricMeshShape.Preset.TRIANGLE);
            case 4 -> selectPresetItem(GeometricMeshShape.Preset.QUADRILATERAL);
            case 6 -> selectPresetItem(GeometricMeshShape.Preset.HEXAGON);
            case 8 -> selectPresetItem(GeometricMeshShape.Preset.OCTAGON);
            case 16 -> selectPresetItem(GeometricMeshShape.Preset.CIRCLE);
            default -> {}
        }
    }

    Config currentConfig() {
        final PresetItem selected = (PresetItem) presetCombo.getSelectedItem();
        final GeometricMeshShape.Preset preset =
                selected != null ? selected.preset() : GeometricMeshShape.Preset.CIRCLE;
        final boolean createCenter = createCenterCheck.isSelected();
        final boolean connectToCenter = createCenter && connectToCenterCheck.isSelected();
        return new Config(preset, radiusValue(), sidesValue(), ringsValue(), createCenter, connectToCenter);
    }

    private float radiusValue() {
        final Object value = radiusSpinner.getValue();
        return value instanceof Number number
                ? GeometricMeshShape.clampRadius(number.floatValue())
                : GeometricMeshShape.DEFAULT_RADIUS;
    }

    private int sidesValue() {
        final Object value = sidesSpinner.getValue();
        return value instanceof Number number
                ? GeometricMeshShape.clampSides(number.intValue())
                : GeometricMeshShape.DEFAULT_SIDES;
    }

    private int ringsValue() {
        final Object value = ringsSpinner.getValue();
        return value instanceof Number number
                ? GeometricMeshShape.clampConcentricRings(number.intValue())
                : GeometricMeshShape.DEFAULT_CONCENTRIC_RINGS;
    }

    void updateAnchor(final Point2 anchor) {
        anchorValueLabel.setText(formatAnchor(texts, anchor));
    }

    void show(final JComponent canvasComponent) {
        final Window owner = canvasComponent == null ? null : SwingUtilities.getWindowAncestor(canvasComponent);
        if (dialog == null) {
            dialog = TurboismWindowFactory.dialog(owner, texts.title(), false);
            if (dialog == null) {
                return;
            }
            dialog.setName(DIALOG_NAME);
            dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
            dialog.setContentPane(rootPanel);
            dialog.getRootPane().setDefaultButton(confirmButton);
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(final WindowEvent event) {
                    listener.onCancel();
                }
            });
            dialog.pack();
            dialog.setResizable(false);
        }
        if (!positionedOnce && canvasComponent != null && canvasComponent.isShowing()) {
            try {
                final Point canvasOnScreen = canvasComponent.getLocationOnScreen();
                final int x = canvasOnScreen.x + Math.max(24, canvasComponent.getWidth() - dialog.getWidth() - 36);
                final int y = canvasOnScreen.y + 48;
                dialog.setLocation(x, y);
                positionedOnce = true;
            } catch (RuntimeException ignored) {
                dialog.setLocationRelativeTo(owner);
                positionedOnce = true;
            }
        } else if (!positionedOnce) {
            dialog.setLocationRelativeTo(owner);
            positionedOnce = true;
        }
        if (!dialog.isVisible()) {
            dialog.setVisible(true);
        }
    }

    void hide() {
        if (dialog != null && dialog.isVisible()) {
            dialog.setVisible(false);
        }
    }

    void dispose() {
        if (dialog != null) {
            dialog.dispose();
            dialog = null;
        }
    }

    boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    JPanel panelForTests() {
        return rootPanel;
    }

    private static String formatAnchor(final Texts texts, final Point2 anchor) {
        if (anchor == null) return texts.anchorUnset();
        return String.format(Locale.ROOT, "(%.1f, %.1f)", anchor.x(), anchor.y());
    }

    static Texts textsForLocale(final Locale locale) {
        return Texts.forLocale(locale);
    }

    record Texts(
            String title,
            String anchorLabel,
            String anchorHint,
            String anchorUnset,
            String presetLabel,
            String presetCircle,
            String presetTriangle,
            String presetQuadrilateral,
            String presetHexagon,
            String presetOctagon,
            String radiusLabel,
            String sidesLabel,
            String concentricLabel,
            String createCenterLabel,
            String connectToCenterLabel,
            String confirmButton,
            String cancelButton) {
        private static final String BUNDLE_NAME = "dev.turboism.adapter.cubism.mesh.messages";

        static Texts forLocale(final Locale rawLocale) {
            final Locale target = normalizeLocale(rawLocale == null ? CubismHostLocale.resolve() : rawLocale);
            final ResourceBundle bundle =
                    ResourceBundle.getBundle(BUNDLE_NAME, target, GeometricMeshDialog.class.getClassLoader());
            return new Texts(
                    bundle.getString("dialog.title"),
                    bundle.getString("dialog.anchor.label"),
                    bundle.getString("dialog.anchor.hint"),
                    bundle.getString("dialog.anchor.unset"),
                    bundle.getString("dialog.preset.label"),
                    bundle.getString("dialog.preset.circle"),
                    bundle.getString("dialog.preset.triangle"),
                    bundle.getString("dialog.preset.quadrilateral"),
                    bundle.getString("dialog.preset.hexagon"),
                    bundle.getString("dialog.preset.octagon"),
                    bundle.getString("dialog.radius.label"),
                    bundle.getString("dialog.sides.label"),
                    bundle.getString("dialog.concentric.label"),
                    bundle.getString("dialog.center.create"),
                    bundle.getString("dialog.center.connect"),
                    bundle.getString("dialog.button.confirm"),
                    bundle.getString("dialog.button.cancel"));
        }

        private static Locale normalizeLocale(final Locale locale) {
            if (!"zh".equalsIgnoreCase(locale.getLanguage())
                    || !locale.getScript().isBlank()) {
                return locale;
            }
            final String country = locale.getCountry().toUpperCase(Locale.ROOT);
            final String script =
                    switch (country) {
                        case "TW", "HK", "MO" -> "Hant";
                        default -> "Hans";
                    };
            return new Locale.Builder().setLanguage("zh").setScript(script).build();
        }
    }
}
