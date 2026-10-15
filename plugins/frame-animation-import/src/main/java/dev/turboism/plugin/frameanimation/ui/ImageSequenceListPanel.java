package dev.turboism.plugin.frameanimation.ui;

import dev.turboism.plugin.frameanimation.slice.NaturalOrderComparator;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Panel managing a user-ordered list of discrete animation frame images.
 */
public final class ImageSequenceListPanel extends JPanel {

    private final PluginLocalization loc;
    private final DefaultListModel<File> listModel = new DefaultListModel<>();
    private final JList<File> fileList = new JList<>(listModel);

    public ImageSequenceListPanel(final PluginLocalization localization) {
        this.loc = localization;
        setLayout(new BorderLayout(0, 6));

        fileList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fileList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    final JList<?> list,
                    final Object value,
                    final int index,
                    final boolean isSelected,
                    final boolean cellHasFocus) {
                final JLabel label =
                        (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof File file) {
                    label.setText((index + 1) + ". " + file.getName());
                }
                return label;
            }
        });

        final JScrollPane scroll = new JScrollPane(fileList);
        scroll.setPreferredSize(new Dimension(360, 180));
        add(scroll, BorderLayout.CENTER);

        final JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        final JButton btnAdd = new JButton(loc.text("dialog.file.addImages"));
        final JButton btnRemove = new JButton(loc.text("dialog.file.removeSelected"));
        final JButton btnUp = new JButton(loc.text("dialog.file.moveUp"));
        final JButton btnDown = new JButton(loc.text("dialog.file.moveDown"));
        final JButton btnSort = new JButton(loc.text("dialog.file.sortNatural"));

        btnAdd.addActionListener(e -> chooseFiles());
        btnRemove.addActionListener(e -> removeSelected());
        btnUp.addActionListener(e -> moveSelected(-1));
        btnDown.addActionListener(e -> moveSelected(1));
        btnSort.addActionListener(e -> sortNatural());

        toolbar.add(btnAdd);
        toolbar.add(btnRemove);
        toolbar.add(btnUp);
        toolbar.add(btnDown);
        toolbar.add(btnSort);
        add(toolbar, BorderLayout.SOUTH);
    }

    /** Returns an immutable list of currently selected image files in display order. */
    public List<File> getFiles() {
        final List<File> list = new ArrayList<>(listModel.size());
        for (int i = 0; i < listModel.size(); i++) {
            list.add(listModel.get(i));
        }
        return List.copyOf(list);
    }

    /** Appends image files to the list sorted by natural file name order. */
    public void addFiles(final List<File> files) {
        final List<File> sorted = new ArrayList<>(files);
        sorted.sort(NaturalOrderComparator.FILE_COMPARATOR);
        for (final File f : sorted) {
            listModel.addElement(f);
        }
    }

    private void chooseFiles() {
        final JFileChooser chooser = new JFileChooser();
        final File defaultDocs = new File(System.getProperty("user.home"), "Documents");
        if (defaultDocs.exists()) {
            chooser.setCurrentDirectory(defaultDocs);
        }
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("Images (*.png, *.jpg, *.jpeg)", "png", "jpg", "jpeg"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            final File[] selected = chooser.getSelectedFiles();
            if (selected != null && selected.length > 0) {
                addFiles(List.of(selected));
            }
        }
    }

    private void removeSelected() {
        final int[] indices = fileList.getSelectedIndices();
        for (int i = indices.length - 1; i >= 0; i--) {
            listModel.remove(indices[i]);
        }
    }

    private void moveSelected(final int delta) {
        final int selected = fileList.getSelectedIndex();
        if (selected < 0) return;
        final int target = selected + delta;
        if (target >= 0 && target < listModel.size()) {
            final File item = listModel.remove(selected);
            listModel.add(target, item);
            fileList.setSelectedIndex(target);
        }
    }

    private void sortNatural() {
        final List<File> items = new ArrayList<>();
        for (int i = 0; i < listModel.size(); i++) {
            items.add(listModel.get(i));
        }
        items.sort(NaturalOrderComparator.FILE_COMPARATOR);
        listModel.clear();
        for (final File f : items) {
            listModel.addElement(f);
        }
    }
}
