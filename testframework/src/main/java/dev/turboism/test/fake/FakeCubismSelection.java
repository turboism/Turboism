package dev.turboism.test.fake;

import java.util.ArrayList;
import java.util.List;

/**
 * Fake Cubism selection state. No real Cubism classes are used.
 */
public final class FakeCubismSelection {

    private String kind;
    private final List<String> selectedIds = new ArrayList<>();

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
        this.kind = kind;
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
}
