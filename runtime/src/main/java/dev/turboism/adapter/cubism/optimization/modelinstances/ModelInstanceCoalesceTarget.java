package dev.turboism.adapter.cubism.optimization.modelinstances;

import dev.turboism.adapter.cubism.optimization.ClassPinTable;
import java.util.LinkedHashMap;
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
     * Class digests for the three rewritten/read-adjacent host classes, filtered from
     * the already reviewed model-update pin table.
     *
     * @return version to (internal name to SHA-256) evidence
     */
    public static Map<String, Map<String, String>> reviewedClassSha256() {
        final Map<String, Map<String, String>> all = ClassPinTable.load("model-update-skip");
        final Map<String, Map<String, String>> filtered = new LinkedHashMap<>();
        for (final Map.Entry<String, Map<String, String>> entry : all.entrySet()) {
            final Map<String, String> classes = new LinkedHashMap<>();
            for (final String name : PINNED_CLASSES) {
                final String sha256 = entry.getValue().get(name);
                if (sha256 == null) {
                    throw new IllegalStateException("model-update pin table lacks " + name + " for " + entry.getKey());
                }
                classes.put(name, sha256);
            }
            filtered.put(entry.getKey(), Map.copyOf(classes));
        }
        return Map.copyOf(filtered);
    }
}
