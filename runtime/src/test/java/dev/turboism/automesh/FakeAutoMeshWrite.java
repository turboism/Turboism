package dev.turboism.automesh;

import dev.turboism.automesh.host.AutoMeshFunnel;
import dev.turboism.automesh.host.AutoMeshResolvedTarget;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.Drawable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recording double of the Auto Mesh write port.
 *
 * <p>Tests assert what the dispatcher asked the Editor to replace, and the double answers the
 * three outcomes the Editor can produce: committed, refused with a reason, or failing.</p>
 */
public final class FakeAutoMeshWrite implements AutoMeshEditableWrite {

    /** Reason code of the refusal this double raises when {@link #refuse} is set. */
    public static final String REFUSAL_REASON = "editor-refused";

    /** Message of the refusal and of the failure this double raises. */
    private static final String MESSAGE = "The Editor refused the write.";

    private final List<AutoMeshWrite> writes = new CopyOnWriteArrayList<>();
    private final List<String> threads = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    /** When set, the next write answers a refusal instead of a committed result. */
    public boolean refuse;

    /** When set, the next write throws an unchecked failure instead of a committed result. */
    public boolean fail;

    /** When set, the next write reports a different target list than the one it received. */
    public List<ArtMeshId> override = null;

    @Override
    public List<ArtMeshId> write(final AutoMeshWrite write) throws AutoMeshWriteRefusal {
        calls.incrementAndGet();
        threads.add(Thread.currentThread().getName());
        writes.add(write);
        if (fail) {
            throw new IllegalStateException(MESSAGE);
        }
        if (refuse) {
            throw new AutoMeshWriteRefusal(REFUSAL_REASON, MESSAGE);
        }
        if (override != null) {
            return override;
        }
        final List<ArtMeshId> ids = new ArrayList<>(write.targets().size());
        for (final AutoMeshResolvedTarget target : write.targets()) {
            ids.add(target.drawable().id());
        }
        return List.copyOf(ids);
    }

    /** @return how often the dispatcher asked for a write */
    public int calls() {
        return calls.get();
    }

    /** @return every write the dispatcher requested, in request order */
    public List<AutoMeshWrite> writes() {
        return List.copyOf(writes);
    }

    /** @return the thread every write ran on */
    public List<String> threads() {
        return List.copyOf(threads);
    }

    /**
     * Builds one resolved target the way a funnel would deliver it.
     *
     * @param funnel the funnel that delivered the target
     * @param drawable the SDK view of the ArtMesh
     * @return a target with opaque host handles
     */
    public static AutoMeshResolvedTarget target(final AutoMeshFunnel funnel, final Drawable drawable) {
        return new AutoMeshResolvedTarget(funnel, drawable, new Object(), new Object(), new Object());
    }

    /**
     * Builds one resolved target without an editable mesh.
     *
     * @param funnel the funnel that delivered the target
     * @param drawable the SDK view of the ArtMesh
     * @return a target whose host exposed no editable mesh
     */
    public static AutoMeshResolvedTarget withoutEditableMesh(final AutoMeshFunnel funnel, final Drawable drawable) {
        return new AutoMeshResolvedTarget(funnel, drawable, new Object(), null, new Object());
    }
}
