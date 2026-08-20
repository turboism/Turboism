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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mirror counterpart resolution over the live host edit.
 *
 * <p>The default path resolves against host objects directly, so nothing is copied across the
 * plugin boundary per point. Registering an override reverses that: a {@link MeshSnapshot} is
 * materialised and the plugin is called once per source point.</p>
 */
public final class RuntimeMeshMirrorCounterparts implements MeshMirrorCounterparts {

    private final AtomicReference<MeshMirrorCounterpartResolver> override = new AtomicReference<>();

    @Override
    public Registration overrideResolver(final MeshMirrorCounterpartResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        if (!override.compareAndSet(null, resolver)) {
            throw new IllegalStateException("a mirror counterpart resolver is already registered");
        }
        return () -> override.compareAndSet(resolver, null);
    }

    public boolean hasOverride() {
        return override.get() != null;
    }

    public void resetSession() {
        override.set(null);
    }

    @Override
    public MeshEditContribution mirrorOf(final MeshDeletion deletion) {
        if (deletion == null) return MeshEditContribution.none();
        final NativeMeshMirrorBridge.LiveEdit live = NativeMeshMirrorBridge.liveEdit();
        if (live == null || !deletion.mirrorAxis().enabled()) return MeshEditContribution.none();
        try {
            final MeshMirrorCounterpartResolver resolver = override.get();
            return resolver == null ? resolveInProcess(deletion, live) : resolveThrough(resolver, deletion, live);
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
        final Set<Integer> sourceIds = new LinkedHashSet<>();
        for (MeshPointRef point : deletion.points()) sourceIds.add(point.id());

        final List<MeshPointRef> points = new ArrayList<>();
        final List<MeshEdgeRef> edges = new ArrayList<>();
        final Set<Integer> seenPoints = new LinkedHashSet<>();
        for (Object context : NativeMeshMirrorBridge.contexts(live.pack())) {
            final Object mesh = NativeMeshMirrorBridge.call(context, "b", new Class<?>[0]);
            if (mesh == null) continue;
            for (MeshPointRef source : deletion.points()) {
                final Object resolved = NativeMeshMirrorBridge.pointById(mesh, source.id());
                if (resolved == null) continue;
                final Object counterpart = NativeMeshMirrorBridge.counterpartPoint(
                    live.mirror(), resolved, mesh, live.pack(), context
                );
                if (counterpart == null) continue;
                final int id = NativeMeshMirrorBridge.pointId(counterpart);
                if (sourceIds.contains(id) || !seenPoints.add(id)) continue;
                points.add(toRef(counterpart, id));
            }
            for (MeshEdgeRef source : deletion.edges()) {
                final Object liveEdge = liveEdge(mesh, source);
                if (liveEdge == null) continue;
                final Object counterpart = NativeMeshMirrorBridge.counterpartEdge(
                    live.mirror(), liveEdge, mesh, live.pack(), context
                );
                if (counterpart == null) continue;
                final MeshEdgeRef ref = toEdgeRef(counterpart);
                if (ref != null && !ref.equals(source) && !edges.contains(ref)) edges.add(ref);
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
        final MeshSnapshot snapshot = deletion.mesh().points().isEmpty()
            ? snapshot(live)
            : deletion.mesh();
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
            final MeshEdgeRef candidate = toEdgeRef(edge);
            if (candidate != null
                && candidate.startPointId() == ref.startPointId()
                && candidate.endPointId() == ref.endPointId()) return edge;
        }
        return null;
    }
}
