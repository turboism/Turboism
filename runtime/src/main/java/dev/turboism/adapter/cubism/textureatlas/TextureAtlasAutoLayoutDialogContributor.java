package dev.turboism.adapter.cubism.textureatlas;

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
import java.util.Objects;

/**
 * Contributes the Turboism algorithm-selection controls into the exact native
 * automatic-layout settings dialog (port of the verified legacy dialog injection).
 *
 * <p>Only standard Swing APIs are used; the host dialog is treated as an opaque
 * {@link JDialog}. The selected algorithm is bridged to the plugin through a
 * system property, and any failure fails open to the untouched native dialog.</p>
 */
public final class TextureAtlasAutoLayoutDialogContributor {

    /** Shared bridge key consumed by the texture-atlas plugin and the runtime dialog ingress. */
    public static final String ALGORITHM_KEY = "dev.turboism.texture-atlas.dialog.algorithm";
    public static final String ALGO_NATIVE = "native";
    public static final String ALGO_MAXRECTS = "maxrects";
    public static final String PARALLEL_KEY = "dev.turboism.texture-atlas.dialog.parallel";

    private static final int SPACER_ROW = 5;
    private static final int SPACER_ROW_PUSHED = 8;

    private TextureAtlasAutoLayoutDialogContributor() {
    }

    /** Loader-neutral ingress entry; fails open on any non-JDialog or UI failure. */
    public static void contribute(final Object dialog) {
        try {
            inject(Objects.requireNonNull(dialog, "dialog"));
        } catch (RuntimeException | LinkageError failure) {
            System.err.println(
                "Turboism texture-atlas dialog contribution failed safely: " + failure
            );
        }
    }

    private static void inject(final Object dialog) {
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

    static void injectInto(final JPanel center) {
        if (!(center.getLayout() instanceof GridBagLayout layout)) {
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

        final JLabel algorithmLabel = new JLabel("排版算法");
        final GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = 6;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(insetY, 0, insetY, 12);
        center.add(algorithmLabel, labelConstraints);

        final JComboBox<String> algorithmCombo = new JComboBox<>(new String[]{
            "原生算法", "MaxRects-BSSF"
        });
        algorithmCombo.setSelectedIndex(resolveAlgorithm());
        algorithmCombo.setToolTipText(
            "<html><b>原生算法</b>：Cubism 内置排版，不经过 Turboism。<br>"
                + "<b>MaxRects-BSSF</b>（推荐）：确定性矩形装箱，空间利用率高。</html>"
        );
        algorithmCombo.addActionListener(event -> {
            final int selected = algorithmCombo.getSelectedIndex();
            System.getProperties().put(
                ALGORITHM_KEY, selected == 0 ? ALGO_NATIVE : ALGO_MAXRECTS
            );
        });
        final GridBagConstraints comboConstraints = new GridBagConstraints();
        comboConstraints.gridx = 1;
        comboConstraints.gridy = 6;
        comboConstraints.gridwidth = 2;
        comboConstraints.anchor = GridBagConstraints.WEST;
        comboConstraints.weightx = 1.0;
        comboConstraints.insets = new Insets(insetY, 0, insetY, 0);
        center.add(algorithmCombo, comboConstraints);

        final JLabel parallelLabel = new JLabel("并行化");
        final GridBagConstraints parallelLabelConstraints = new GridBagConstraints();
        parallelLabelConstraints.gridx = 0;
        parallelLabelConstraints.gridy = 7;
        parallelLabelConstraints.anchor = GridBagConstraints.WEST;
        parallelLabelConstraints.insets = new Insets(insetY, 0, insetY, 12);
        center.add(parallelLabel, parallelLabelConstraints);

        final JCheckBox parallelCheck = new JCheckBox(
            "启用并行搜索（纹理 ≥16 时生效）",
            "true".equals(System.getProperty(PARALLEL_KEY, "false"))
        );
        parallelCheck.setToolTipText(
            "<html>将纹理按大小分组后在独立线程并行执行排版搜索，<br>"
                + "适合纹理数量较多时加速；纹理过少（&lt;16）时自动禁用。</html>"
        );
        parallelCheck.addActionListener(event -> System.getProperties().put(
            PARALLEL_KEY, String.valueOf(parallelCheck.isSelected())
        ));
        final GridBagConstraints parallelConstraints = new GridBagConstraints();
        parallelConstraints.gridx = 1;
        parallelConstraints.gridy = 7;
        parallelConstraints.gridwidth = 2;
        parallelConstraints.anchor = GridBagConstraints.WEST;
        parallelConstraints.weightx = 1.0;
        parallelConstraints.insets = new Insets(insetY, 0, insetY, 0);
        center.add(parallelCheck, parallelConstraints);
    }

    private static int resolveAlgorithm() {
        final String configured = System.getProperty(ALGORITHM_KEY, ALGO_MAXRECTS);
        return ALGO_NATIVE.equals(configured) ? 0 : 1;
    }

    private static JPanel findGridBagPanel(final Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JPanel panel && panel.getLayout() instanceof GridBagLayout) {
                return panel;
            }
            if (component instanceof Container nested) {
                final JPanel found = findGridBagPanel(nested);
                if (found != null) return found;
            }
        }
        return null;
    }
}
