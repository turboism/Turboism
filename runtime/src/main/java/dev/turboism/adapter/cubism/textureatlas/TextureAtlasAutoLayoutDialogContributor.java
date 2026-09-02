package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import java.awt.Component;
import java.awt.Container;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Locale;
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
 * and disables the control. All static UI strings come from the runtime resource
 * bundle {@code dev.turboism.adapter.cubism.textureatlas.messages}.</p>
 */
public final class TextureAtlasAutoLayoutDialogContributor {

    /** Shared bridge key consumed by the texture-atlas plugin and the runtime dialog ingress. */
    public static final String ALGORITHM_KEY = "dev.turboism.texture-atlas.dialog.algorithm";
    public static final String PARALLEL_KEY = "dev.turboism.texture-atlas.dialog.parallel";
    public static final String VALIDATION_OBSERVER_KEY =
        "dev.turboism.texture-atlas.dialog.validation-observer";
    public static final String ALGO_NATIVE = "native";
    public static final String ALGO_MAXRECTS = "maxrects";

    private static final int SPACER_ROW = 5;
    private static final int SPACER_ROW_PUSHED = 8;

    private final TextureAtlasLayoutAlgorithmRegistry registry;
    private final ResourceBundle bundle;

    public TextureAtlasAutoLayoutDialogContributor(
        final TextureAtlasLayoutAlgorithmRegistry registry,
        final Locale locale
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
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

        final String[] names = algorithms.stream()
            .map(TextureAtlasLayoutAlgorithm::displayName)
            .toArray(String[]::new);
        final JComboBox<String> algorithmCombo = new JComboBox<>(names);
        final String configured = System.getProperty(ALGORITHM_KEY, "");
        int initialIndex = 0;
        for (int i = 0; i < algorithms.size(); i++) {
            if (algorithms.get(i).id().equals(configured)) {
                initialIndex = i;
                break;
            }
        }
        algorithmCombo.setSelectedIndex(initialIndex);
        algorithmCombo.setToolTipText(bundle.getString("dialog.algorithm.tooltip"));

        final JCheckBox parallelCheck = new JCheckBox(
            bundle.getString("dialog.parallel.check"),
            "true".equals(System.getProperty(PARALLEL_KEY, "false"))
        );
        parallelCheck.setToolTipText(bundle.getString("dialog.parallel.tooltip"));

        final Runnable syncParallel = () -> {
            final TextureAtlasLayoutAlgorithm selected =
                algorithms.get(Math.min(algorithmCombo.getSelectedIndex(), algorithms.size() - 1));
            final boolean supported = selected.supportsParallel();
            parallelCheck.setEnabled(supported);
            if (!supported) {
                if (parallelCheck.isSelected()) {
                    parallelCheck.setSelected(false);
                }
                System.getProperties().put(PARALLEL_KEY, "false");
            }
        };
        syncParallel.run();

        algorithmCombo.addActionListener(event -> {
            final int selected = algorithmCombo.getSelectedIndex();
            final TextureAtlasLayoutAlgorithm algorithm =
                algorithms.get(Math.min(selected, algorithms.size() - 1));
            System.getProperties().put(ALGORITHM_KEY, algorithm.id());
            syncParallel.run();
        });
        parallelCheck.addActionListener(event -> System.getProperties().put(
            PARALLEL_KEY, String.valueOf(parallelCheck.isSelected())
        ));

        final GridBagConstraints comboConstraints = new GridBagConstraints();
        comboConstraints.gridx = 1;
        comboConstraints.gridy = 6;
        comboConstraints.gridwidth = 2;
        comboConstraints.anchor = GridBagConstraints.WEST;
        comboConstraints.weightx = 1.0;
        comboConstraints.insets = new Insets(insetY, 0, insetY, 0);
        center.add(algorithmCombo, comboConstraints);

        final JLabel parallelLabel = new JLabel(bundle.getString("dialog.parallel.label"));
        final GridBagConstraints parallelLabelConstraints = new GridBagConstraints();
        parallelLabelConstraints.gridx = 0;
        parallelLabelConstraints.gridy = 7;
        parallelLabelConstraints.anchor = GridBagConstraints.WEST;
        parallelLabelConstraints.insets = new Insets(insetY, 0, insetY, 12);
        center.add(parallelLabel, parallelLabelConstraints);

        final GridBagConstraints parallelConstraints = new GridBagConstraints();
        parallelConstraints.gridx = 1;
        parallelConstraints.gridy = 7;
        parallelConstraints.gridwidth = 2;
        parallelConstraints.anchor = GridBagConstraints.WEST;
        parallelConstraints.weightx = 1.0;
        parallelConstraints.insets = new Insets(insetY, 0, insetY, 0);
        center.add(parallelCheck, parallelConstraints);
        notifyValidationObserver(new DialogObservation(
            center,
            algorithmLabel,
            algorithmCombo,
            parallelLabel,
            parallelCheck,
            List.copyOf(algorithms)
        ));
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
        List<TextureAtlasLayoutAlgorithm> algorithms
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
