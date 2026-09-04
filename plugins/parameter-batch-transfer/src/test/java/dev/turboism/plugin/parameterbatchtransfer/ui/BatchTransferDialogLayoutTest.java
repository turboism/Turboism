package dev.turboism.plugin.parameterbatchtransfer.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BatchTransferDialogLayoutTest {

    @Test
    void fourBindingsOccupyFourDistinctGridRows() {
        final List<String> parameterIds = List.of(
            "ParamBodyAngleX",
            "ParamBodyAngleY",
            "ParamEyeROpen",
            "ParamAngleZ"
        );
        final List<BatchTransferDialog.RowComponents> components = parameterIds.stream()
            .map(id -> new BatchTransferDialog.RowComponents(
                new JLabel(id),
                new JComboBox<>(new String[]{id}),
                new JCheckBox()
            ))
            .toList();

        final JPanel rows = BatchTransferDialog.layoutRows(components);
        final GridBagLayout layout = (GridBagLayout) rows.getLayout();
        final Set<Integer> sourceRows = new HashSet<>();
        final ArrayList<Integer> allRows = new ArrayList<>();

        assertEquals(parameterIds.size() * 3, rows.getComponentCount());
        for (int index = 0; index < components.size(); index++) {
            final BatchTransferDialog.RowComponents row = components.get(index);
            final GridBagConstraints source = layout.getConstraints(row.source());
            final GridBagConstraints target = layout.getConstraints(row.target());
            final GridBagConstraints invert = layout.getConstraints(row.invert());

            assertEquals(index, source.gridy);
            assertEquals(source.gridy, target.gridy);
            assertEquals(source.gridy, invert.gridy);
            sourceRows.add(source.gridy);
            allRows.add(source.gridy);
        }

        assertEquals(List.of(0, 1, 2, 3), allRows);
        assertEquals(parameterIds.size(), sourceRows.size());
    }
}
