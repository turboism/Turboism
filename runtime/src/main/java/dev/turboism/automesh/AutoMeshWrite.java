package dev.turboism.automesh;

import dev.turboism.automesh.host.AutoMeshResolvedTarget;
import java.util.List;
import java.util.Objects;

/**
 * One confirmed selection and the layout every target receives.
 *
 * <p>The targets are the very host objects the funnel delivered, and the layouts are the plugin
 * results in the same order. One request is one Editor Undo entry, so the writer must either
 * replace every target or leave every target untouched.</p>
 *
 * @param undoLabel the label one Undo entry carries
 * @param targets the resolved targets, in selection order
 * @param layouts the plugin layout of each target, in the same order
 */
public record AutoMeshWrite(String undoLabel, List<AutoMeshResolvedTarget> targets, List<AutoMeshWriteLayout> layouts) {

    public AutoMeshWrite {
        undoLabel = Objects.requireNonNull(undoLabel, "undoLabel");
        targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        layouts = List.copyOf(Objects.requireNonNull(layouts, "layouts"));
        if (targets.size() != layouts.size()) {
            throw new IllegalArgumentException("every resolved target needs exactly one layout");
        }
    }
}
