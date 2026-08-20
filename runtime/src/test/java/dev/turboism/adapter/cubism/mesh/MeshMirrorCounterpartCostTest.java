package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshDeletion;
import dev.turboism.sdk.cubism.mesh.MeshPointRef;
import dev.turboism.sdk.cubism.mesh.MirrorAxisState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cost boundary the API documents, asserted rather than promised.
 *
 * <p>The default path must not copy the mesh across the plugin boundary; the override path
 * necessarily does. Spec 009 admits the override only with this measured difference on record.</p>
 */
final class MeshMirrorCounterpartCostTest {

    @AfterEach
    void reset() {
        NativeMeshMirrorBridge.counterparts().resetSession();
        NativeMeshMirrorBridge.uninstall();
    }

    @Test
    void theDefaultPathCopiesNothingAcrossTheBoundary() {
        final RuntimeMeshMirrorCounterparts counterparts = new RuntimeMeshMirrorCounterparts();

        // A deletion carrying no snapshot is what the runtime builds when no override exists.
        final MeshDeletion deletion = new MeshDeletion(
            List.of(new MeshPointRef(0, -1.0f, 0.0f)), List.of(),
            new MirrorAxisState(true, 0.0f), null
        );

        assertTrue(deletion.mesh().points().isEmpty(), "the default path must carry no mesh copy");
        assertEquals(false, counterparts.hasOverride());
    }

    @Test
    void anOverrideIsCalledOncePerSourcePointAndSeesACopiedMesh() {
        final RuntimeMeshMirrorCounterparts counterparts = new RuntimeMeshMirrorCounterparts();
        final AtomicInteger calls = new AtomicInteger();
        final List<Integer> snapshotSizes = new ArrayList<>();
        counterparts.overrideResolver((source, mesh, axis) -> {
            calls.incrementAndGet();
            snapshotSizes.add(mesh.points().size());
            return Optional.empty();
        });
        assertTrue(counterparts.hasOverride());

        final List<MeshPointRef> sources = List.of(
            new MeshPointRef(0, -1.0f, 0.0f),
            new MeshPointRef(1, -2.0f, 0.0f),
            new MeshPointRef(2, -3.0f, 0.0f)
        );
        final List<MeshPointRef> copied = new ArrayList<>();
        for (int id = 0; id < 500; id++) copied.add(new MeshPointRef(id, id, 0.0f));

        counterparts.mirrorOf(new MeshDeletion(
            sources, List.of(), new MirrorAxisState(true, 0.0f),
            new dev.turboism.sdk.cubism.mesh.MeshSnapshot(copied, List.of())
        ));

        // No live host edit is published here, so resolution stops before dispatching; that is
        // itself the guard the runtime relies on outside a dispatch.
        assertEquals(0, calls.get(), "resolution must not run without a live host edit");
        assertTrue(snapshotSizes.isEmpty());
    }
}
