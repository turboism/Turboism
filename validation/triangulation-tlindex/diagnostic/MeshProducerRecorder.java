import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;

/** Validation only. Invocation tickets bind producer results without publishing or refreshing caches. */
public final class MeshProducerRecorder {
    private static Scope active;
    private static long nextScope;
    private static boolean abandonedInvocation;
    private MeshProducerRecorder() {}

    record Event(long invocation, int cycle, String sourceId, long threadId,
                 long startedNanos, long returnedNanos, MeshResultSnapshot.Result result, String failure) {}
    private record Ticket(long scope, long invocation, String sourceId, long threadId, long startedNanos) {}

    static final class Scope implements AutoCloseable {
        private final long number;
        private final int cycle;
        private final List<String> expected;
        private final IdentityHashMap<Object, String> sources;
        private final List<Event> events = new ArrayList<>();
        private final IdentityHashMap<Ticket, Boolean> pending = new IdentityHashMap<>();
        private String failure;
        private long invocations;
        private int inFlight;
        private boolean closed;

        private Scope(int cycle, List<String> expected, IdentityHashMap<Object, String> sources) {
            require(cycle > 0 && expected != null && !expected.isEmpty() && sources != null,
                "missing command scope");
            require(new HashSet<>(expected).size() == expected.size(), "duplicate selected source");
            require(sources.size() == expected.size(), "source/mesh count mismatch");
            require(!sources.containsKey(null) && !sources.containsValue(null), "null mesh/source");
            require(new HashSet<>(sources.values()).equals(new HashSet<>(expected)), "source binding mismatch");
            this.number = ++nextScope;
            this.cycle = cycle;
            this.expected = List.copyOf(expected);
            this.sources = new IdentityHashMap<>(sources);
        }

        /** Command has returned; all producer invocations and all selected sources must be accounted for. */
        List<Event> finish() {
            synchronized (MeshProducerRecorder.class) {
                require(active == this && !closed, "inactive command scope");
                require(failure == null, "producer observation failed: " + failure);
                require(inFlight == 0, "producer still in flight");
                HashSet<String> observed = new HashSet<>();
                for (Event event : events) observed.add(event.sourceId());
                require(observed.equals(new HashSet<>(expected)), "incomplete selected source coverage");
                return events();
            }
        }

        /** Includes unsuccessful observations, so a failed diagnostic can preserve partial evidence. */
        List<Event> events() {
            synchronized (MeshProducerRecorder.class) {
                return events.stream().sorted(java.util.Comparator.comparingLong(Event::invocation)).toList();
            }
        }

        int retainedMeshCount() {
            synchronized (MeshProducerRecorder.class) { return sources.size(); }
        }

        @Override public void close() {
            synchronized (MeshProducerRecorder.class) {
                if (closed) return;
                closed = true;
                if (inFlight != 0) abandonedInvocation = true;
                sources.clear();
                events.clear();
                pending.clear();
                if (active == this) active = null;
            }
        }
    }

    static synchronized Scope begin(int cycle, List<String> expected, IdentityHashMap<Object, String> sources) {
        require(active == null && !abandonedInvocation, "concurrent or abandoned producer command scope");
        Scope scope = new Scope(cycle, expected, sources);
        active = scope;
        return scope;
    }

    static synchronized void requireIdle() {
        require(active == null && !abandonedInvocation, "producer scope precedes registration");
    }

    /** Inserted at method entry. A null ticket must never be reclassified under a later scope. */
    public static synchronized Object started(Object mesh) {
        try {
            if (active == null) return null;
            String source = active.sources.get(mesh);
            if (source == null) {
                active.failure = "UNBOUND_PRODUCER_MESH";
                return null;
            }
            Ticket ticket = new Ticket(active.number, ++active.invocations, source,
                Thread.currentThread().getId(), System.nanoTime());
            active.inFlight++;
            active.pending.put(ticket, Boolean.TRUE);
            return ticket;
        } catch (Throwable observationFailure) {
            if (active != null) active.failure = "PRODUCER_ENTRY_OBSERVATION_FAILED";
            return null;
        }
    }

    /** Never throws into the native producer. No filesystem I/O or retained host array/object. */
    public static synchronized void returned(Object opaqueTicket, Object mesh) {
        if (!(opaqueTicket instanceof Ticket ticket) || active == null || active.number != ticket.scope()) return;
        if (active.pending.remove(ticket) == null) {
            active.failure = "DUPLICATE_PRODUCER_COMPLETION";
            return;
        }
        try {
            require(ticket.sourceId().equals(active.sources.get(mesh)), "producer mesh changed");
            require(ticket.threadId() == Thread.currentThread().getId(), "producer thread changed");
            MeshResultSnapshot.Result result = snapshotProduced(mesh);
            active.events.add(new Event(ticket.invocation(), active.cycle, ticket.sourceId(), ticket.threadId(),
                ticket.startedNanos(), System.nanoTime(), result, null));
        } catch (Throwable observationFailure) {
            recordFailure(ticket, "PRODUCER_RETURN_OBSERVATION_FAILED");
        } finally {
            active.inFlight--;
        }
    }

    /** Catch-and-rethrow instrumentation preserves the original Throwable identity. */
    public static synchronized void failed(Object opaqueTicket, Throwable original) {
        if (!(opaqueTicket instanceof Ticket ticket) || active == null || active.number != ticket.scope()) return;
        if (active.pending.remove(ticket) == null) {
            active.failure = "DUPLICATE_PRODUCER_COMPLETION";
            return;
        }
        active.inFlight--;
        recordFailure(ticket, "NATIVE_PRODUCER_FAILED");
    }

    private static void recordFailure(Ticket ticket, String reason) {
        active.failure = reason;
        try {
            active.events.add(new Event(ticket.invocation(), active.cycle, ticket.sourceId(), ticket.threadId(),
                ticket.startedNanos(), System.nanoTime(), null, reason));
        } catch (Throwable cannotRecord) {
            // Failure stays latched even if allocating diagnostic evidence is unavailable.
        }
    }

    private static MeshResultSnapshot.Result snapshotProduced(Object mesh) throws Exception {
        int edge = integer(call(mesh, "get_edge_edit_version"));
        int position = integer(call(mesh, "get_postion_edit_version"));
        int vertexCache = integer(call(mesh, "getCache_version_gl_vertex$core"));
        require(position == vertexCache, "position cache stale at producer return");
        int count = integer(call(mesh, "getPointCount"));
        Object xy = call(mesh, "getCached_positions$core");
        Object triangles = call(mesh, "getCached_indices$core");
        require(xy instanceof float[] && triangles instanceof int[], "missing produced arrays");
        MeshResultSnapshot.Result result = MeshResultSnapshot.snapshot(count, edge, (float[]) xy, (int[]) triangles);
        require(edge == integer(call(mesh, "get_edge_edit_version"))
                && position == integer(call(mesh, "get_postion_edit_version"))
                && vertexCache == integer(call(mesh, "getCache_version_gl_vertex$core"))
                && count == integer(call(mesh, "getPointCount"))
                && xy == call(mesh, "getCached_positions$core")
                && triangles == call(mesh, "getCached_indices$core"), "mesh changed during producer observation");
        return result;
    }

    private static int integer(Object value) {
        require(value instanceof Integer, "unexpected native integer");
        return (Integer) value;
    }
    private static Object call(Object receiver, String method) throws Exception {
        try { return receiver.getClass().getMethod(method).invoke(receiver); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception error) throw error;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
