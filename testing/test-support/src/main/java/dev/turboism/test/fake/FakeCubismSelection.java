package dev.turboism.test.fake;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fake Cubism selection state. No real Cubism classes are used.
 */
public final class FakeCubismSelection {

    private String kind;
    private final ObservableSelectedIds selectedIds = new ObservableSelectedIds();
    private Runnable changeListener = () -> {
    };

    public FakeCubismSelection() {
        this.kind = "";
    }

    public FakeCubismSelection(String kind) {
        this.kind = kind;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        if (!Objects.equals(this.kind, kind)) {
            this.kind = kind;
            changeListener.run();
        }
    }

    public List<String> getSelectedIds() {
        return selectedIds;
    }

    public void select(String id) {
        if (!selectedIds.contains(id)) {
            selectedIds.add(id);
        }
    }

    public void deselect(String id) {
        selectedIds.remove(id);
    }

    public void clear() {
        selectedIds.clear();
    }

    public boolean isSelected(String id) {
        return selectedIds.contains(id);
    }

    void setChangeListener(Runnable changeListener) {
        this.changeListener = Objects.requireNonNull(changeListener, "changeListener");
    }

    private final class ObservableSelectedIds extends ArrayList<String> {

        @Override
        public boolean add(String id) {
            final boolean changed = super.add(id);
            if (changed) {
                changeListener.run();
            }
            return changed;
        }

        @Override
        public boolean remove(Object id) {
            final boolean changed = super.remove(id);
            if (changed) {
                changeListener.run();
            }
            return changed;
        }

        @Override
        public void clear() {
            if (!isEmpty()) {
                super.clear();
                changeListener.run();
            }
        }
    }
}
