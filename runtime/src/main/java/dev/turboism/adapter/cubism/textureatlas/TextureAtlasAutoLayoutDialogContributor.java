package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import java.awt.Component;
import java.awt.Container;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Loader-neutral ingress contributing the algorithm selector and parallel-search
 * checkbox into the native automatic-layout settings dialog.
 *
 * <p>The algorithm list is read live from {@link TextureAtlasLayoutAlgorithmRegistry}:
 * any plugin-registered algorithm appears in the combo. The parallel checkbox is
 * enabled only while the selected algorithm declares {@code supportsParallel};
 * selecting a non-parallel algorithm (for example the native pass-through) unchecks
 * and disables the control. Rotation granularity, lock preset, scale mode,
 * auto-scale tolerance/max-try, and kernel selection are enabled only while the
 * selected algorithm declares {@code supportsPolygonOptions}; the bridged values
 * are ignored by every other planner. All static UI strings come from the
 * runtime resource bundle
 * {@code dev.turboism.adapter.cubism.textureatlas.messages}.</p>
 */
public final class TextureAtlasAutoLayoutDialogContributor {

    /** Shared bridge keys mirroring the runtime-owned selection for host-side observers. */
    public static final String ALGORITHM_KEY = "dev.turboism.texture-atlas.dialog.algorithm";
    public static final String PARALLEL_KEY = "dev.turboism.texture-atlas.dialog.parallel";
    public static final String ROTATION_KEY = "dev.turboism.texture-atlas.dialog.rotation";
    public static final String LOCK_PRESET_KEY = "dev.turboism.texture-atlas.dialog.lock-preset";
    public static final String AUTO_SCALE_KEY = "dev.turboism.texture-atlas.dialog.auto-scale";
    public static final String FIXED_SCALE_PERCENT_KEY =
        "dev.turboism.texture-atlas.dialog.fixed-scale-percent";
    public static final String AUTO_SCALE_TOLERANCE_KEY =
        "dev.turboism.texture-atlas.dialog.auto-scale-tolerance";
    public static final String AUTO_SCALE_MAX_TRY_KEY =
        "dev.turboism.texture-atlas.dialog.auto-scale-max-try";
    public static final String KERNEL_KEY = "dev.turboism.texture-atlas.dialog.kernel";
    public static final String VALIDATION_OBSERVER_KEY =
        "dev.turboism.texture-atlas.dialog.validation-observer";
    public static final String ALGO_NATIVE =
        dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection.NATIVE_ALGORITHM_ID;
    public static final String ALGO_MAXRECTS = "maxrects";

    /** Lock-preset names mirrored by the dalsoo plugin's PolygonLayoutLockPreset. */
    private static final String[] LOCK_PRESETS =
        {"NONE", "ALL", "ANGLE", "SCALE", "ANGLE_SCALE", "POS_ANGLE"};
    /** Kernel ids mirrored by the dalsoo plugin settings (useAbey). */
    private static final String[] KERNELS = {"abey", "dalalah"};
    private static final String[] SCALE_MODES = {"auto", "fixed"};
    private static final int MAX_FIXED_SCALE_PERCENT = 800;
    private static final int MAX_AUTO_SCALE_TRY = 64;
    private static final String DEFAULT_TOLERANCE = "0.005";

    private static final int SPACER_ROW = 5;
    private static final int SPACER_ROW_PUSHED = 16;

    private final RuntimeTextureAtlasLayoutAlgorithmRegistry registry;
    private final TextureAtlasAutoLayoutSelection selection;
    private final ResourceBundle bundle;

    public TextureAtlasAutoLayoutDialogContributor(
        final RuntimeTextureAtlasLayoutAlgorithmRegistry registry,
        final Locale locale
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.selection = registry.selectionState();
        this.bundle = ResourceBundle.getBundle(
            "dev.turboism.adapter.cubism.textureatlas.messages",
            locale == null ? Locale.getDefault() : locale
        );
    }

    /** Loader-neutral ingress entry; fails open on any non-JDialog or UI failure. */
    public java.util.function.Consumer<Object> ingress() {
        return this::contribute;
    }

    private void contribute(final Object dialog) {
        try {
            inject(Objects.requireNonNull(dialog, "dialog"));
        } catch (RuntimeException | LinkageError failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                "texture-atlas",
                "Texture-atlas dialog contribution failed safely",
                failure
            );
        }
    }

    private void inject(final Object dialog) {
        if (!(dialog instanceof JDialog jDialog)) {
            return;
        }
        final JPanel center = findGridBagPanel(jDialog.getContentPane());
        if (center == null) {
            return;
        }
        injectInto(center);

        jDialog.pack();
        jDialog.setMinimumSize(jDialog.getSize());
        center.revalidate();
        center.repaint();
    }

    void injectInto(final JPanel center) {
        if (!(center.getLayout() instanceof GridBagLayout layout)) {
            return;
        }

        final List<TextureAtlasLayoutAlgorithm> algorithms = registry.algorithms();
        if (algorithms.isEmpty()) {
            return;
        }

        // Push any existing spacer on the native layout row 5 down so the
        // contributed rows stay visible (port of the legacy dialog injection).
        for (Component component : center.getComponents()) {
            final GridBagConstraints constraints = layout.getConstraints(component);
            if (constraints.gridy == SPACER_ROW) {
                constraints.gridy = SPACER_ROW_PUSHED;
                layout.setConstraints(component, constraints);
            }
        }

        final int insetY = 4;

        final JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        final GridBagConstraints separatorConstraints = new GridBagConstraints();
        separatorConstraints.gridx = 0;
        separatorConstraints.gridy = 5;
        separatorConstraints.gridwidth = 3;
        separatorConstraints.fill = GridBagConstraints.HORIZONTAL;
        separatorConstraints.insets = new Insets(insetY + 4, 0, insetY + 4, 0);
        center.add(separator, separatorConstraints);

        final JLabel algorithmLabel = new JLabel(bundle.getString("dialog.algorithm.label"));
        final GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = 6;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(insetY, 0, insetY, 12);
        center.add(algorithmLabel, labelConstraints);

        // Index 0 is the synthetic native entry; plugin registrations follow.
        final String[] names = new String[algorithms.size() + 1];
        names[0] = bundle.getString("dialog.algorithm.native");
        for (int i = 0; i < algorithms.size(); i++) {
            names[i + 1] = algorithms.get(i).displayName();
        }
        final JComboBox<String> algorithmCombo = new JComboBox<>(names);
        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection current =
            selection.selection();
        int initialIndex = 0;
        for (int i = 0; i < algorithms.size(); i++) {
            if (algorithms.get(i).id().equals(current.algorithmId())) {
                initialIndex = i + 1;
                break;
            }
        }
        algorithmCombo.setSelectedIndex(initialIndex);
        algorithmCombo.setToolTipText(bundle.getString("dialog.algorithm.tooltip"));

        final TextureAtlasRotationMode[] rotations = TextureAtlasRotationMode.values();
        final String[] rotationLabels = new String[rotations.length];
        for (int i = 0; i < rotations.length; i++) {
            rotationLabels[i] = bundle.getString("dialog.rotation.option."
                + rotations[i].name().toLowerCase(Locale.ROOT));
        }
        final JComboBox<String> rotationCombo = new JComboBox<>(rotationLabels);
        rotationCombo.setSelectedIndex(indexOf(rotations,
            System.getProperty(ROTATION_KEY, TextureAtlasRotationMode.QUARTER.name())));
        rotationCombo.setToolTipText(bundle.getString("dialog.rotation.tooltip"));

        final String[] lockLabels = new String[LOCK_PRESETS.length];
        for (int i = 0; i < LOCK_PRESETS.length; i++) {
            lockLabels[i] = bundle.getString("dialog.lock.option."
                + LOCK_PRESETS[i].toLowerCase(Locale.ROOT));
        }
        final JComboBox<String> lockCombo = new JComboBox<>(lockLabels);
        lockCombo.setSelectedIndex(indexOf(LOCK_PRESETS,
            System.getProperty(LOCK_PRESET_KEY, LOCK_PRESETS[0])));
        lockCombo.setToolTipText(bundle.getString("dialog.lock.tooltip"));

        final String[] scaleModeLabels = {
            bundle.getString("dialog.scale-mode.option.auto"),
            bundle.getString("dialog.scale-mode.option.fixed")
        };
        final JComboBox<String> scaleModeCombo = new JComboBox<>(scaleModeLabels);
        scaleModeCombo.setSelectedIndex(
            "false".equals(System.getProperty(AUTO_SCALE_KEY, "true")) ? 1 : 0);
        scaleModeCombo.setToolTipText(bundle.getString("dialog.scale-mode.tooltip"));

        final JTextField fixedScaleField = new JTextField(
            System.getProperty(FIXED_SCALE_PERCENT_KEY, "100"), 6);
        fixedScaleField.setToolTipText(bundle.getString("dialog.fixed-scale.tooltip"));
        final JTextField toleranceField = new JTextField(
            toleranceText(System.getProperty(AUTO_SCALE_TOLERANCE_KEY)), 6);
        toleranceField.setToolTipText(
            bundle.getString("dialog.auto-scale-tolerance.tooltip"));
        final JTextField maxTryField = new JTextField(
            System.getProperty(AUTO_SCALE_MAX_TRY_KEY, "0"), 6);
        maxTryField.setToolTipText(
            bundle.getString("dialog.auto-scale-max-try.tooltip"));

        final String[] kernelLabels = {
            bundle.getString("dialog.kernel.option.abey"),
            bundle.getString("dialog.kernel.option.dalalah")
        };
        final JComboBox<String> kernelCombo = new JComboBox<>(kernelLabels);
        kernelCombo.setSelectedIndex(indexOf(KERNELS,
            System.getProperty(KERNEL_KEY, KERNELS[0])));
        kernelCombo.setToolTipText(bundle.getString("dialog.kernel.tooltip"));

        final JCheckBox parallelCheck = new JCheckBox(
            bundle.getString("dialog.parallel.check"),
            current.parallel()
        );
        parallelCheck.setToolTipText(bundle.getString("dialog.parallel.tooltip"));

        final Runnable publishSelection = () -> {
            final int index = algorithmCombo.getSelectedIndex();
            // The synthetic native entry uses the "native" id (never a real registration):
            // dispatch resolves a missing id to the native fallback, and an explicit
            // native choice stays distinguishable from an unset selection.
            final String algorithmId = index <= 0
                ? ALGO_NATIVE
                : algorithms.get(Math.min(index - 1, algorithms.size() - 1)).id();
            final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection next =
                new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection(
                    algorithmId, parallelCheck.isSelected()
                );
            selection.select(next);
            // Mirror the runtime-owned selection onto the bridge keys for host-side observers.
            System.getProperties().put(ALGORITHM_KEY, algorithmId);
            System.getProperties().put(PARALLEL_KEY, String.valueOf(next.parallel()));
        };

        final Runnable syncControls = () -> {
            final int index = algorithmCombo.getSelectedIndex();
            final TextureAtlasLayoutAlgorithm selected = index <= 0
                ? null
                : algorithms.get(Math.min(index - 1, algorithms.size() - 1));
            final boolean supported = selected != null && selected.supportsParallel();
            parallelCheck.setEnabled(supported);
            if (!supported && parallelCheck.isSelected()) {
                parallelCheck.setSelected(false);
                publishSelection.run();
            }
            // polygon-option controls only apply to algorithms declaring
            // supportsPolygonOptions; other selections leave them disabled and
            // the bridged values are ignored by every other planner
            final boolean polygon = selected != null && selected.supportsPolygonOptions();
            final boolean autoScale =
                !"false".equals(System.getProperty(AUTO_SCALE_KEY, "true"));
            rotationCombo.setEnabled(polygon);
            lockCombo.setEnabled(polygon);
            scaleModeCombo.setEnabled(polygon);
            fixedScaleField.setEnabled(polygon && !autoScale);
            toleranceField.setEnabled(polygon && autoScale);
            maxTryField.setEnabled(polygon && autoScale);
            kernelCombo.setEnabled(polygon);
        };
        syncControls.run();

        algorithmCombo.addActionListener(event -> {
            syncControls.run();
            publishSelection.run();
        });
        parallelCheck.addActionListener(event -> publishSelection.run());
        rotationCombo.addActionListener(event -> System.getProperties().put(
            ROTATION_KEY, rotations[rotationCombo.getSelectedIndex()].name()));
        lockCombo.addActionListener(event -> System.getProperties().put(
            LOCK_PRESET_KEY, LOCK_PRESETS[lockCombo.getSelectedIndex()]));
        scaleModeCombo.addActionListener(event -> {
            System.getProperties().put(AUTO_SCALE_KEY,
                SCALE_MODES[scaleModeCombo.getSelectedIndex()].equals("auto")
                    ? "true" : "false");
            syncControls.run();
        });
        kernelCombo.addActionListener(event -> System.getProperties().put(
            KERNEL_KEY, KERNELS[kernelCombo.getSelectedIndex()]));
        wireIntField(fixedScaleField, FIXED_SCALE_PERCENT_KEY, 1,
            MAX_FIXED_SCALE_PERCENT);
        wireToleranceField(toleranceField);
        wireIntField(maxTryField, AUTO_SCALE_MAX_TRY_KEY, 0, MAX_AUTO_SCALE_TRY);

        final GridBagConstraints comboConstraints = new GridBagConstraints();
        comboConstraints.gridx = 1;
        comboConstraints.gridy = 6;
        comboConstraints.gridwidth = 2;
        comboConstraints.anchor = GridBagConstraints.WEST;
        comboConstraints.weightx = 1.0;
        comboConstraints.insets = new Insets(insetY, 0, insetY, 0);
        center.add(algorithmCombo, comboConstraints);

        addOptionRow(center, 7, "dialog.rotation.label", rotationCombo, insetY);
        addOptionRow(center, 8, "dialog.lock.label", lockCombo, insetY);
        addOptionRow(center, 9, "dialog.scale-mode.label", scaleModeCombo, insetY);
        addOptionRow(center, 10, "dialog.fixed-scale.label", fixedScaleField, insetY);
        addOptionRow(center, 11, "dialog.auto-scale-tolerance.label",
            toleranceField, insetY);
        addOptionRow(center, 12, "dialog.auto-scale-max-try.label",
            maxTryField, insetY);
        addOptionRow(center, 13, "dialog.kernel.label", kernelCombo, insetY);
        final JLabel parallelLabel =
            addOptionRow(center, 14, "dialog.parallel.label", parallelCheck, insetY);

        final Map<String, JComponent> optionControls = new LinkedHashMap<>();
        optionControls.put("rotation", rotationCombo);
        optionControls.put("lockPreset", lockCombo);
        optionControls.put("scaleMode", scaleModeCombo);
        optionControls.put("fixedScale", fixedScaleField);
        optionControls.put("autoScaleTolerance", toleranceField);
        optionControls.put("autoScaleMaxTry", maxTryField);
        optionControls.put("kernel", kernelCombo);
        notifyValidationObserver(new DialogObservation(
            center,
            algorithmLabel,
            algorithmCombo,
            parallelLabel,
            parallelCheck,
            List.copyOf(algorithms),
            Map.copyOf(optionControls)
        ));
    }

    private JLabel addOptionRow(final JPanel center, final int gridy,
        final String labelKey, final JComponent control, final int insetY) {
        final JLabel label = new JLabel(bundle.getString(labelKey));
        final GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = gridy;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(insetY, 0, insetY, 12);
        center.add(label, labelConstraints);
        final GridBagConstraints controlConstraints = new GridBagConstraints();
        controlConstraints.gridx = 1;
        controlConstraints.gridy = gridy;
        controlConstraints.gridwidth = 2;
        controlConstraints.anchor = GridBagConstraints.WEST;
        controlConstraints.weightx = 1.0;
        controlConstraints.insets = new Insets(insetY, 0, insetY, 0);
        center.add(control, controlConstraints);
        return label;
    }

    private static int indexOf(final TextureAtlasRotationMode[] values,
        final String name) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].name().equals(name)) {
                return i;
            }
        }
        return TextureAtlasRotationMode.QUARTER.ordinal();
    }

    private static int indexOf(final String[] values, final String name) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        return 0;
    }

    /** Commits a bounded integer field into its bridge key; invalid input reverts. */
    private static void wireIntField(final JTextField field, final String key,
        final int min, final int max) {
        final Runnable commit = () -> {
            try {
                final int value = Integer.parseInt(field.getText().trim());
                if (value >= min && value <= max) {
                    System.getProperties().put(key, String.valueOf(value));
                    return;
                }
            } catch (NumberFormatException ignored) {
            }
            field.setText(System.getProperty(key, String.valueOf(min)));
        };
        field.addActionListener(event -> commit.run());
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(final FocusEvent event) {
                commit.run();
            }
        });
    }

    /** Tolerance is bridged in per-mille (0 falls back to the built-in default). */
    private static void wireToleranceField(final JTextField field) {
        final Runnable commit = () -> {
            try {
                final double value = Double.parseDouble(field.getText().trim());
                if (value > 0 && value <= 1 && Double.isFinite(value)) {
                    System.getProperties().put(AUTO_SCALE_TOLERANCE_KEY,
                        String.valueOf((int) Math.round(value * 1000)));
                    return;
                }
            } catch (NumberFormatException ignored) {
            }
            field.setText(toleranceText(
                System.getProperty(AUTO_SCALE_TOLERANCE_KEY)));
        };
        field.addActionListener(event -> commit.run());
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(final FocusEvent event) {
                commit.run();
            }
        });
    }

    private static String toleranceText(final String permille) {
        try {
            final int value = Integer.parseInt(permille == null ? "" : permille.trim());
            if (value > 0) {
                return BigDecimal.valueOf(value).movePointLeft(3)
                    .stripTrailingZeros().toPlainString();
            }
        } catch (NumberFormatException ignored) {
        }
        return DEFAULT_TOLERANCE;
    }

    private static void notifyValidationObserver(final DialogObservation observation) {
        final Object registered = System.getProperties().get(VALIDATION_OBSERVER_KEY);
        if (!(registered instanceof java.util.function.Consumer<?> consumer)) {
            return;
        }
        @SuppressWarnings("unchecked")
        final java.util.function.Consumer<Object> observer =
            (java.util.function.Consumer<Object>) consumer;
        try {
            observer.accept(observation);
        } catch (RuntimeException | Error failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                "texture-atlas",
                "Texture-atlas dialog validation observer failed safely",
                failure
            );
        }
    }

    /** Task-scoped exact-host observation of the injected semantic controls. */
    public record DialogObservation(
        JPanel center,
        JLabel algorithmLabel,
        JComboBox<String> algorithmCombo,
        JLabel parallelLabel,
        JCheckBox parallelCheck,
        List<TextureAtlasLayoutAlgorithm> algorithms,
        Map<String, JComponent> optionControls
    ) { }

    private static JPanel findGridBagPanel(final Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JPanel panel && panel.getLayout() instanceof GridBagLayout) {
                return panel;
            }
        }
        return null;
    }
}
