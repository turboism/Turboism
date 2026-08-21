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
import java.util.List;
import java.util.Optional;

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
            final MeshEditContribution contribution = resolver == null || deletion.points().isEmpty()
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
            final List<Object> compatibleSources = new ArrayList<>();
            for (Object source : live.sourcePoints()) {
                final Object compatible = live.pointSourcesById()
                    ? NativeMeshMirrorBridge.pointById(
                        mesh, NativeMeshMirrorBridge.pointId(source)
                    )
                    : NativeMeshMirrorBridge.compatiblePoint(mesh, source);
                if (compatible != null && !NativeMeshMirrorBridge.containsIdentity(
                    compatibleSources, compatible
                )) compatibleSources.add(compatible);
            }
            for (Object compatible : compatibleSources) {
                final Object counterpart = NativeMeshMirrorBridge.counterpartPoint(
                    live.mirror(), compatible, mesh, live.pack(), context
                );
                if (counterpart == null || NativeMeshMirrorBridge.containsIdentity(compatibleSources, counterpart)
                    || NativeMeshMirrorBridge.containsIdentity(seenPoints, counterpart)) continue;
                final int id = NativeMeshMirrorBridge.pointId(counterpart);
                final MeshPointRef ref = toRef(counterpart, id);
                if (ref != null) {
                    seenPoints.add(counterpart);
                    NativeMeshMirrorBridge.rememberDefaultCounterpart(counterpart);
                    points.add(ref);
                }
            }
            final List<Object> compatibleEdges = new ArrayList<>();
            for (Object source : live.sourceEdges()) {
                final Object compatible = live.endpointEdgeSources()
                    ? endpointCompatibleEdge(mesh, source)
                    : compatibleEdge(mesh, source);
                if (compatible != null && !NativeMeshMirrorBridge.containsIdentity(
                    compatibleEdges, compatible
                )) compatibleEdges.add(compatible);
            }
            for (Object compatible : compatibleEdges) {
                final Object counterpart = NativeMeshMirrorBridge.counterpartEdge(
                    live.mirror(), compatible, mesh, live.pack(), context
                );
                if (counterpart == null || NativeMeshMirrorBridge.containsIdentity(
                    compatibleEdges, counterpart
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

    private static Object compatibleEdge(final Object mesh, final Object reference)
        throws ReflectiveOperationException {
        if (reference == null) return null;
        for (Object candidate : NativeMeshMirrorBridge.edges(mesh)) {
            if (candidate == reference) return candidate;
        }
        return null;
    }

    /**
     * The native eraser supplies candidate-edge wrappers rather than identities from
     * {@code context.b()}. 5.3.02 reconstructs those sources by endpoint ids independently in
     * each current context; this mode is deliberately confined to the eraser dispatch so the
     * discrete edge action keeps its stricter exact-identity mesh selection.
     */
    private static Object endpointCompatibleEdge(final Object mesh, final Object reference)
        throws ReflectiveOperationException {
        final MeshEdgeRef ref = toEdgeRef(reference);
        return ref == null ? null : liveEdge(mesh, ref);
    }

    /** The opt-in path: materialise the mesh and ask the plugin once per source point. */
    private MeshEditContribution resolveThrough(
        final MeshMirrorCounterpartResolver resolver,
        final MeshDeletion deletion,
        final NativeMeshMirrorBridge.LiveEdit live
    ) throws ReflectiveOperationException {
        final MeshSnapshot snapshot = deletion.mesh().points().isEmpty() ? snapshot(live) : deletion.mesh();
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
            if (points.contains(found)) continue;
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
