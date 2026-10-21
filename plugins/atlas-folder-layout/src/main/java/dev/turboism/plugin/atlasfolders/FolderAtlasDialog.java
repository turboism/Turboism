package dev.turboism.plugin.atlasfolders;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.swing.*;

/** Modal settings shared by all folder-layout pages in one operation. */
final class FolderAtlasDialog {
    record Options(int maxEdge, String algorithm, int margin, int padding, boolean automaticSize) {}

    record Algorithm(String id, String label, boolean available) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** Refresh from the live registry on each opening; preserve third-party registration order. */
    static List<Algorithm> algorithmOptions(PluginLocalization texts, List<TextureAtlasLayoutAlgorithm> registered) {
        List<Algorithm> options = new ArrayList<>();
        registered.stream()
                .sorted(Comparator.comparingInt(a -> !available(a)
                        ? 3
                        : switch (a.id()) {
                            case "maxrects" -> 0;
                            case "dalsoo" -> 1;
                            default -> 2;
                        }))
                .forEach(a -> options.add(new Algorithm(
                        a.id(),
                        a.displayName() + (!available(a) ? " — " + texts.text("folders.currentPageOnly") : ""),
                        available(a))));
        options.add(new Algorithm(
                "native",
                texts.text("texture-atlas.algorithm.native") + " — " + texts.text("folders.currentPageOnly"),
                false));
        return List.copyOf(options);
    }

    private static boolean available(TextureAtlasLayoutAlgorithm algorithm) {
        return !algorithm.isNative() && algorithm.planner().supportsCompleteAtlas();
    }

    static Optional<Options> show(PluginLocalization texts, List<TextureAtlasLayoutAlgorithm> registered) {
        if (GraphicsEnvironment.isHeadless()) return Optional.empty();
        if (registered.stream().noneMatch(FolderAtlasDialog::available)) {
            JOptionPane.showMessageDialog(
                    null,
                    texts.text("folders.noAlgorithms"),
                    texts.text("folders.action"),
                    JOptionPane.INFORMATION_MESSAGE);
            return Optional.empty();
        }
        JComboBox<Integer> size = new JComboBox<>(new Integer[] {1024, 2048, 4096, 8192});
        size.setSelectedItem(4096);
        JComboBox<Algorithm> algorithm =
                new JComboBox<>(algorithmOptions(texts, registered).toArray(Algorithm[]::new));
        algorithm.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean selected, boolean focus) {
                Component component = super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Algorithm option) component.setEnabled(option.available());
                return component;
            }
        });
        algorithm.addActionListener(ignored -> {
            if (algorithm.getSelectedItem() instanceof Algorithm selected
                    && !selected.available()
                    && algorithm.getSelectedIndex() != 0) algorithm.setSelectedIndex(0);
        });
        JCheckBox automatic = new JCheckBox(texts.text("folders.autoSize"), true);
        JSpinner margin = new JSpinner(new SpinnerNumberModel(3, 0, 64, 1));
        JSpinner padding = new JSpinner(new SpinnerNumberModel(6, 0, 128, 1));
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.anchor = GridBagConstraints.WEST;
        row(form, c, 0, texts.text("folders.maxSize"), size);
        row(form, c, 1, texts.text("folders.algorithm"), algorithm);
        row(form, c, 2, texts.text("folders.margin"), margin);
        row(form, c, 3, texts.text("folders.padding"), padding);
        c.gridx = 0;
        c.gridy = 4;
        c.gridwidth = 2;
        form.add(automatic, c);
        c.gridy = 5;
        form.add(new JLabel(texts.text("folders.groupingHint")), c);
        c.gridy = 6;
        form.add(new JLabel(texts.text("folders.nativeHint")), c);
        c.gridy = 7;
        form.add(new JLabel(texts.text("folders.replaceHint")), c);
        var owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        int result = JOptionPane.showOptionDialog(
                owner,
                form,
                texts.text("folders.action"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                new String[] {texts.text("folders.start"), texts.text("folders.cancel")},
                texts.text("folders.start"));
        if (result != 0) return Optional.empty();
        if (!(algorithm.getSelectedItem() instanceof Algorithm selected) || !selected.available())
            return Optional.empty();
        return Optional.of(new Options(
                (Integer) size.getSelectedItem(),
                ((Algorithm) algorithm.getSelectedItem()).id(),
                (Integer) margin.getValue(),
                (Integer) padding.getValue(),
                automatic.isSelected()));
    }

    private static void row(JPanel form, GridBagConstraints c, int y, String label, JComponent field) {
        JLabel name = new JLabel(label);
        name.setLabelFor(field);
        c.gridy = y;
        c.gridx = 0;
        form.add(name, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, c);
        c.fill = GridBagConstraints.NONE;
    }
}
