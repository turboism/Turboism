package dev.turboism.adapter.cubism.optimization.modelinstances;

import dev.turboism.adapter.cubism.optimization.ClassPinTable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reviewed class evidence for the native model-instance update coalescer.
 *
 * <p>The hook rewrites three long-stable public host seams: {@code
 * CModelSource.updateModelInstances()}, {@code ACEditMode.updateLastModifiedTime()}
 * and {@code CUndoManager.undo()/redo()}. The pins cover the source, document and
 * edit-mode classes whose bodies decide whether two update calls describe the same
 * committed edit. The undo-group class is not rewritten; its dependency method is
 * still shape-verified against the official artifact by the installer, as is the
 * rewritten undo manager's undo/redo surface.</p>
 */
public record ModelInstanceCoalesceTarget(String version) {

    /** Rewritten source entry that publishes all model instances. */
    public static final String SOURCE_OWNER = "com/live2d/cubism/doc/model/CModelSource";

    /** Rewritten edit-mode entry called only when a significant edit commits. */
    public static final String EDIT_MODE_OWNER = "com/live2d/cubism/doc/ACEditMode";

    /** Rewritten undo manager whose undo/redo transitions invalidate a cached token. */
    public static final String UNDO_MANAGER_OWNER = "com/live2d/undo/CUndoManager";

    /** Model instance publication method. */
    public static final String UPDATE_METHOD = "updateModelInstances";

    /** Significant-edit commit method. */
    public static final String COMMIT_METHOD = "updateLastModifiedTime";

    /** Undo transition methods that may restore a previous model state. */
    public static final String UNDO_METHOD = "undo";

    /** Redo transition method that may reapply a model state. */
    public static final String REDO_METHOD = "redo";

    private static final Set<String> PINNED_CLASSES =
            Set.of(SOURCE_OWNER, "com/live2d/cubism/doc/modeling/CModelingDocument", EDIT_MODE_OWNER);

    public ModelInstanceCoalesceTarget {
        version = Objects.requireNonNull(version, "version").strip();
        if (version.isEmpty()) {
            throw new IllegalArgumentException("version must not be blank");
        }
    }

    /**
     * Target classes for the three rewritten/read-adjacent host classes, filtered from
     * the already reviewed model-update table.
     *
     * @return version to declared internal names
     */
    public static Map<String, List<String>> reviewedClassSha256() {
        final Map<String, List<String>> all = ClassPinTable.load("model-update-skip");
        final Map<String, List<String>> filtered = new LinkedHashMap<>();
        for (final Map.Entry<String, List<String>> entry : all.entrySet()) {
            final List<String> classes = new ArrayList<>();
            for (final String name : PINNED_CLASSES) {
                if (!entry.getValue().contains(name)) {
                    throw new IllegalStateException("model-update pin table lacks " + name + " for " + entry.getKey());
                }
                classes.add(name);
            }
            filtered.put(entry.getKey(), List.copyOf(classes));
        }
        return Map.copyOf(filtered);
    }
}
