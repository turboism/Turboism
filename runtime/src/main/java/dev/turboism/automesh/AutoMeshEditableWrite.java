package dev.turboism.automesh;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import java.util.List;

/**
 * Replaces the editable meshes a guarded funnel delivered with the plugin layouts.
 *
 * <p>The Editor derives uvs and keyforms from the editable mesh, so the write reproduces the
 * Editor's own generator contract: one Undo envelope, canvas geometry in the editable mesh, and the
 * topology notifications the host fires for its own result. An implementation fails closed: a host
 * member it cannot reach raises {@link AutoMeshWriteRefusal} and changes no target.</p>
 *
 * <p>The port stays loader-neutral. Implementations live beside the host adapter, which is the only
 * layer that knows what the opaque target handles mean.</p>
 */
@FunctionalInterface
public interface AutoMeshEditableWrite<T extends AutoMeshTarget> {

    /**
     * Writes one confirmed selection.
     *
     * @param write the targets and the layout each one receives
     * @return the ArtMeshes this write replaced, in target order
     * @throws AutoMeshWriteRefusal when the host cannot accept the write, in which case no target
     *     changed
     */
    List<ArtMeshId> write(AutoMeshWrite<T> write) throws AutoMeshWriteRefusal;
}
