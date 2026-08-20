package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshDeletion;
import dev.turboism.sdk.cubism.mesh.MeshEdgeKind;
import dev.turboism.sdk.cubism.mesh.MeshEdgeRef;
import dev.turboism.sdk.cubism.mesh.MeshEditContribution;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterpartResolver;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts;
import dev.turboism.sdk.cubism.mesh.MeshPointRef;
import dev.turboism.sdk.cubism.mesh.MeshSnapshot;
import dev.turboism.sdk.plugin.Registration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Mirror counterpart resolution over the live host edit. */
public final class RuntimeMeshMirrorCounterparts implements MeshMirrorCounterparts {

    @Override
    public Registration overrideResolver(final MeshMirrorCounterpartResolver resolver) {
        throw new UnsupportedOperationException("resolver overrides require a per-plugin counterpart service");
    }

    @Override
    public MeshEditContribution mirrorOf(final MeshDeletion deletion) {
        return mirrorOf(deletion, null);
    }

    MeshEditContribution mirrorOf(
        final MeshDeletion deletion,
        final MeshMirrorCounterpartResolver resolver
    ) {
        if (deletion == null) return MeshEditContribution.none();
        final NativeMeshMirrorBridge.LiveEdit live = NativeMeshMirrorBridge.liveEdit();
        if (live == null || !deletion.mirrorAxis().enabled()) return MeshEditContribution.none();
        try {
            final MeshEditContribution contribution = resolver == null
                ? resolveInProcess(deletion, live)
                : resolveThrough(resolver, deletion, live);
            if (resolver == null && !contribution.isEmpty()) {
                NativeMeshMirrorBridge.rememberDefaultContribution(contribution);
            }
            return contribution;
        } catch (Throwable failure) {
            NativeMeshMirrorBridge.diagnostic(
                "COUNTERPART_RESOLUTION_FAILED reason=" + failure.getClass().getName()
            );
            return MeshEditContribution.none();
        }
    }

    /** The 008 rule, evaluated against live host geometry with no per-point boundary crossing. */
    private MeshEditContribution resolveInProcess(
        final MeshDeletion deletion,
        final NativeMeshMirrorBridge.LiveEdit live
    ) throws ReflectiveOperationException {
        final List<MeshPointRef> points = new ArrayList<>();
        final List<MeshEdgeRef> edges = new ArrayList<>();
        final List<Object> seenPoints = new ArrayList<>();
        for (Object context : NativeMeshMirrorBridge.contexts(live.pack())) {
            final Object mesh = NativeMeshMirrorBridge.call(context, "b", new Class<?>[0]);
            if (mesh == null) continue;
            for (Object source : live.sourcePoints()) {
                final Object sourceMesh = NativeMeshMirrorBridge.pointMesh(source);
                if (sourceMesh != null && sourceMesh != mesh) continue;
                if (sourceMesh == null && !NativeMeshMirrorBridge.containsIdentity(
                    NativeMeshMirrorBridge.points(mesh), source
                )) continue;
                final Object counterpart = NativeMeshMirrorBridge.counterpartPoint(
                    live.mirror(), source, mesh, live.pack(), context
                );
                if (counterpart == null || NativeMeshMirrorBridge.containsIdentity(live.sourcePoints(), counterpart)
                    || NativeMeshMirrorBridge.containsIdentity(seenPoints, counterpart)) continue;
                final int id = NativeMeshMirrorBridge.pointId(counterpart);
                final MeshPointRef ref = toRef(counterpart, id);
                if (ref != null) {
                    seenPoints.add(counterpart);
                    NativeMeshMirrorBridge.rememberDefaultCounterpart(counterpart);
                    points.add(ref);
                }
            }
            for (Object source : live.sourceEdges()) {
                if (!NativeMeshMirrorBridge.containsIdentity(
                    NativeMeshMirrorBridge.edges(mesh), source
                )) continue;
                final Object counterpart = NativeMeshMirrorBridge.counterpartEdge(
                    live.mirror(), source, mesh, live.pack(), context
                );
                if (counterpart == null || NativeMeshMirrorBridge.containsIdentity(
                    live.sourceEdges(), counterpart
                )) continue;
                final MeshEdgeRef ref = toEdgeRef(counterpart);
                if (ref != null) {
                    NativeMeshMirrorBridge.rememberDefaultCounterpartEdge(counterpart);
                    if (!edges.contains(ref)) edges.add(ref);
                }
            }
        }
        return points.isEmpty() && edges.isEmpty()
            ? MeshEditContribution.none()
            : new MeshEditContribution(points, edges);
    }

    /** The opt-in path: materialise the mesh and ask the plugin once per source point. */
    private MeshEditContribution resolveThrough(
        final MeshMirrorCounterpartResolver resolver,
        final MeshDeletion deletion,
        final NativeMeshMirrorBridge.LiveEdit live
    ) throws ReflectiveOperationException {
        final MeshSnapshot snapshot = deletion.mesh().points().isEmpty() ? snapshot(live) : deletion.mesh();
        final Set<Integer> sourceIds = new LinkedHashSet<>();
        for (MeshPointRef point : deletion.points()) sourceIds.add(point.id());

        final List<MeshPointRef> points = new ArrayList<>();
        for (MeshPointRef source : deletion.points()) {
            final Optional<MeshPointRef> counterpart;
            try {
                counterpart = resolver.counterpart(source, snapshot, deletion.mirrorAxis());
            } catch (Throwable failure) {
                NativeMeshMirrorBridge.diagnostic(
                    "COUNTERPART_OVERRIDE_FAILED reason=" + failure.getClass().getName()
                );
                return MeshEditContribution.none();
            }
            if (counterpart == null || counterpart.isEmpty()) continue;
            final MeshPointRef found = counterpart.orElseThrow();
            if (sourceIds.contains(found.id()) || points.contains(found)) continue;
            points.add(found);
        }
        return points.isEmpty() ? MeshEditContribution.none() : MeshEditContribution.ofPoints(points);
    }

    /** Copies the live mesh across the boundary; only the override path pays for this. */
    MeshSnapshot snapshot(final NativeMeshMirrorBridge.LiveEdit live) throws ReflectiveOperationException {
        final List<MeshPointRef> points = new ArrayList<>();
        final List<MeshEdgeRef> edges = new ArrayList<>();
        for (Object context : NativeMeshMirrorBridge.contexts(live.pack())) {
            final Object mesh = NativeMeshMirrorBridge.call(context, "b", new Class<?>[0]);
            if (mesh == null) continue;
            for (Object point : NativeMeshMirrorBridge.points(mesh)) {
                final int id = NativeMeshMirrorBridge.pointId(point);
                final MeshPointRef ref = toRef(point, id);
                if (ref != null) points.add(ref);
            }
            final Object hostEdges = NativeMeshMirrorBridge.call(mesh, "getEdges", new Class<?>[0]);
            if (hostEdges instanceof Iterable<?> iterable) {
                for (Object edge : iterable) {
                    final MeshEdgeRef ref = toEdgeRef(edge);
                    if (ref != null) edges.add(ref);
                }
            }
        }
        return new MeshSnapshot(points, edges);
    }

    private static MeshPointRef toRef(final Object point, final int id) throws ReflectiveOperationException {
        if (id < 0) return null;
        final Object position = NativeMeshMirrorBridge.call(point, "getPos", new Class<?>[0]);
        if (position == null) return null;
        final Object x = NativeMeshMirrorBridge.call(position, "getX", new Class<?>[0]);
        final Object y = NativeMeshMirrorBridge.call(position, "getY", new Class<?>[0]);
        if (!(x instanceof Number first) || !(y instanceof Number second)) return null;
        return new MeshPointRef(id, first.floatValue(), second.floatValue());
    }

    private static MeshEdgeRef toEdgeRef(final Object edge) throws ReflectiveOperationException {
        final Object first = NativeMeshMirrorBridge.call(edge, "getIndex1", new Class<?>[0]);
        final Object second = NativeMeshMirrorBridge.call(edge, "getIndex2", new Class<?>[0]);
        if (!(first instanceof Number start) || !(second instanceof Number end)) return null;
        if (start.intValue() == end.intValue()) return null;
        return new MeshEdgeRef(start.intValue(), end.intValue(), MeshEdgeKind.UNKNOWN);
    }

    private static Object liveEdge(final Object mesh, final MeshEdgeRef ref)
        throws ReflectiveOperationException {
        final Object hostEdges = NativeMeshMirrorBridge.call(mesh, "getEdges", new Class<?>[0]);
        if (!(hostEdges instanceof Iterable<?> iterable)) return null;
        for (Object edge : iterable) {
            final Object first = NativeMeshMirrorBridge.call(edge, "getIndex1", new Class<?>[0]);
            final Object second = NativeMeshMirrorBridge.call(edge, "getIndex2", new Class<?>[0]);
            if (!(first instanceof Number start) || !(second instanceof Number end)) continue;
            final int low = Math.min(start.intValue(), end.intValue());
            final int high = Math.max(start.intValue(), end.intValue());
            if (ref.startPointId() == low && ref.endPointId() == high) return edge;
        }
        return null;
    }

    void resetSession() {
        // Resolver ownership lives in each per-plugin facade, whose disposable scope closes it.
    }
}
