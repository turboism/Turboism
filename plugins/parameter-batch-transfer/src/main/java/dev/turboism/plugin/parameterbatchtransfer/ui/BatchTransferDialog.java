package dev.turboism.plugin.parameterbatchtransfer.ui;

import dev.turboism.plugin.parameterbatchtransfer.b1.domain.BatchTransferRow;
import dev.turboism.plugin.parameterbatchtransfer.b1.domain.BoundParameterSnapshot;
import dev.turboism.plugin.parameterbatchtransfer.service.ParameterBatchTransferService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JComponent;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
/**
 * Plugin-owned modal Swing dialog for one batch-transfer session.
 *
 * <p>One row per bound parameter: source label with M/C markers, a target
 * combo filtered by the service, and an invert checkbox. Header checkbox
 * selects inversion for all rows; M/C legend explains the markers.</p>
 */
public final class BatchTransferDialog extends JDialog {

    private static final int DIALOG_WIDTH = 720;
    private static final int SCROLL_MAX_HEIGHT = 420;
    private static final int SCROLL_MIN_HEIGHT = 180;
    private static final int ROW_HEIGHT = 32;

    private final PluginLocalization localization;
    private final ParameterBatchTransferService service;
    private final ParameterBatchTransferService.Session session;
    private final List<JComboBox<BoundParameterSnapshot>> targetCombos = new ArrayList<>();
    private final List<JCheckBox> invertChecks = new ArrayList<>();
    private boolean updatingTargets;
    private List<BatchTransferRow> result;

    public BatchTransferDialog(
        final PluginLocalization localization,
        final ParameterBatchTransferService service,
        final ParameterBatchTransferService.Session session
    ) {
        super((Frame) null, localization.text("dialog.title"), true);
        TurboismWindowFactory.style(this);
        this.localization = localization;
        this.service = service;
        this.session = session;

        final JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildRows(session), BorderLayout.CENTER);
        root.add(buildActions(), BorderLayout.SOUTH);

        setContentPane(root);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
    }

    /** Shows the dialog and returns the confirmed rows, or {@code null} when cancelled. */
    public List<BatchTransferRow> showDialog() {
        setVisible(true);
        return result;
    }

    private JPanel buildHeader() {
        final JPanel header = new JPanel(new GridBagLayout());
        final GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = 0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 2, 4, 6);
        constraints.weightx = 0.46;
        constraints.gridx = 0;
        header.add(centeredLabel(localization.text("dialog.sourceColumn")), constraints);
        constraints.weightx = 0.40;
        constraints.gridx = 1;
        header.add(centeredLabel(localization.text("dialog.targetColumn")), constraints);
        constraints.weightx = 0.14;
        constraints.gridx = 2;
        final JCheckBox invertAll = new JCheckBox(localization.text("dialog.invertAll"));
        invertAll.setOpaque(false);
        invertAll.setToolTipText(localization.text("dialog.invertColumn"));
        invertAll.setHorizontalAlignment(SwingConstants.CENTER);
        invertAll.addActionListener(event -> {
            final boolean selected = invertAll.isSelected();
            invertChecks.forEach(check -> check.setSelected(selected));
        });
        header.add(invertAll, constraints);

        final JPanel legend = new JPanel();
        legend.add(markerLabel("M", Font.BOLD));
        legend.add(new JLabel(localization.text("dialog.legend.morph")));
        legend.add(markerLabel("C", Font.BOLD));
        legend.add(new JLabel(localization.text("dialog.legend.combined")));

        final JPanel north = new JPanel(new BorderLayout(0, 4));
        north.add(header, BorderLayout.NORTH);
        north.add(legend, BorderLayout.SOUTH);
        return north;
    }

    private JScrollPane buildRows(final ParameterBatchTransferService.Session session) {
        final JPanel rows = new JPanel(new GridBagLayout());
        final GridBagConstraints constraints = new GridBagConstraints();
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(2, 0, 2, 0);
        constraints.weightx = 1.0;
        for (final BoundParameterSnapshot source : session.bound()) {
            constraints.gridx = 0;
            constraints.weightx = 0.46;
            rows.add(sourceView(source), constraints);
            constraints.gridx = 1;
            constraints.weightx = 0.40;
            final JComboBox<BoundParameterSnapshot> combo = targetCombo(source);
            rows.add(combo, constraints);
            targetCombos.add(combo);
            combo.addActionListener(event -> refreshTargetCombos());
            constraints.gridx = 2;
            constraints.weightx = 0.14;
            final JCheckBox invert = new JCheckBox();
            invert.setHorizontalAlignment(SwingConstants.CENTER);
            rows.add(invert, constraints);
            invertChecks.add(invert);
            constraints.gridy++;
        }
        refreshTargetCombos();
        final JScrollPane scroll = new JScrollPane(rows);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(
            DIALOG_WIDTH,
            Math.min(SCROLL_MAX_HEIGHT, Math.max(SCROLL_MIN_HEIGHT, session.bound().size() * ROW_HEIGHT))
        ));
        return scroll;
    }

    private JPanel buildActions() {
        final JButton confirm = new JButton(localization.text("dialog.confirm"));
        final JButton cancel = new JButton(localization.text("dialog.cancel"));
        confirm.addActionListener(event -> {
            final List<BatchTransferRow> rows = new ArrayList<>();
            for (int index = 0; index < targetCombos.size(); index++) {
                rows.add(new BatchTransferRow(
                    session.bound().get(index),
                    ((BoundParameterSnapshot) targetCombos.get(index).getSelectedItem()).parameterId(),
                    invertChecks.get(index).isSelected()
                ));
            }
            result = rows;
            dispose();
        });
        cancel.addActionListener(event -> dispose());
        final JPanel actions = new JPanel();
        actions.add(confirm);
        actions.add(cancel);
        getRootPane().setDefaultButton(confirm);
        return actions;
    }

    static List<BoundParameterSnapshot> availableTargets(
        final ParameterBatchTransferService service,
        final ParameterBatchTransferService.Session session,
        final BoundParameterSnapshot source,
        final Set<ParameterId> reserved
    ) {
        return service.targetCandidates(session, source).stream()
            .filter(candidate -> candidate.parameterId().equals(source.parameterId())
                || !reserved.contains(candidate.parameterId()))
            .toList();
    }

    static List<ParameterId> normalizeTargets(
        final ParameterBatchTransferService service,
        final ParameterBatchTransferService.Session session,
        final List<ParameterId> requested
    ) {
        if (requested.size() != session.bound().size()) {
            throw new IllegalArgumentException("requested target count must match bound rows");
        }
        final Set<ParameterId> reserved = new HashSet<>();
        final ArrayList<ParameterId> normalized = new ArrayList<>(requested.size());
        for (int index = 0; index < requested.size(); index++) {
            final BoundParameterSnapshot source = session.bound().get(index);
            final List<BoundParameterSnapshot> candidates = availableTargets(
                service, session, source, reserved
            );
            final ParameterId desired = requested.get(index);
            final ParameterId selected = desired != null && candidates.stream()
                .anyMatch(candidate -> candidate.parameterId().equals(desired))
                ? desired
                : source.parameterId();
            normalized.add(selected);
            if (!selected.equals(source.parameterId())) {
                reserved.add(selected);
            }
        }
        return List.copyOf(normalized);
    }

    private void refreshTargetCombos() {
        if (updatingTargets) return;
        updatingTargets = true;
        try {
            final List<ParameterId> requested = targetCombos.stream()
                .map(BatchTransferDialog::selectedParameterId)
                .toList();
            final List<ParameterId> normalized = normalizeTargets(service, session, requested);
            final Set<ParameterId> reserved = new HashSet<>();
            for (int index = 0; index < targetCombos.size(); index++) {
                final BoundParameterSnapshot source = session.bound().get(index);
                final List<BoundParameterSnapshot> candidates = availableTargets(
                    service, session, source, reserved
                );
                final ParameterId selectedId = normalized.get(index);
                final BoundParameterSnapshot selected = candidates.stream()
                    .filter(candidate -> candidate.parameterId().equals(selectedId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Normalized target is unavailable"));
                final JComboBox<BoundParameterSnapshot> combo = targetCombos.get(index);
                combo.setModel(new DefaultComboBoxModel<>(
                    candidates.toArray(new BoundParameterSnapshot[0])
                ));
                combo.setRenderer(new MarkerListRenderer());
                combo.setSelectedItem(selected);
                if (!selectedId.equals(source.parameterId())) {
                    reserved.add(selectedId);
                }
            }
        } finally {
            updatingTargets = false;
        }
    }

    private static ParameterId selectedParameterId(final JComboBox<BoundParameterSnapshot> combo) {
        final Object selected = combo.getSelectedItem();
        return selected instanceof BoundParameterSnapshot snapshot ? snapshot.parameterId() : null;
    }

    private JComboBox<BoundParameterSnapshot> targetCombo(final BoundParameterSnapshot source) {
        final List<BoundParameterSnapshot> candidates = availableTargets(
            service, session, source, Set.of()
        );
        final JComboBox<BoundParameterSnapshot> combo = new JComboBox<>(
            candidates.toArray(new BoundParameterSnapshot[0])
        );
        combo.setRenderer(new MarkerListRenderer());
        combo.setSelectedItem(candidates.stream()
            .filter(candidate -> candidate.parameterId().equals(source.parameterId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Source target is unavailable"))
        );
        return combo;
    }

    private static JComponent sourceView(final BoundParameterSnapshot source) {
        final JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.add(markerLabel(source.markers(), Font.PLAIN), BorderLayout.WEST);
        panel.add(new JLabel(source.label()), BorderLayout.CENTER);
        return panel;
    }

    private static JLabel markerLabel(final String markers, final int style) {
        final JLabel label = new JLabel(markers);
        label.setFont(label.getFont().deriveFont(style));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setPreferredSize(new Dimension(24, 18));
        label.setMinimumSize(new Dimension(24, 18));
        return label;
    }

    private static JLabel centeredLabel(final String text) {
        final JLabel label = new JLabel(text);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        return label;
    }

    private static final class MarkerListRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
            final javax.swing.JList<?> list,
            final Object value,
            final int index,
            final boolean isSelected,
            final boolean cellHasFocus
        ) {
            final JLabel label = (JLabel) super.getListCellRendererComponent(
                list,
                value instanceof BoundParameterSnapshot snapshot ? snapshot.label() : value,
                index,
                isSelected,
                cellHasFocus
            );
            if (value instanceof BoundParameterSnapshot snapshot && !snapshot.markers().isEmpty()) {
                label.setText(snapshot.markers() + "  " + snapshot.label());
            }
            return label;
        }
    }
}
