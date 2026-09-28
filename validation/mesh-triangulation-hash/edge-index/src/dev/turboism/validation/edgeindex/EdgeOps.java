package dev.turboism.validation.edgeindex;

import java.util.ArrayList;
import java.util.List;

/**
 * Contract shared by the native-semantics reference and the indexed candidate.
 *
 * <p>Modeled from the official Cubism 5.3.03 {@code GEditableMesh2} bytecode
 * (jar sha256 bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166,
 * javap -c -p; byte-level reading only, no official class is ever defined or
 * executed):</p>
 *
 * <ul>
 *   <li>{@code addEdgeIfNotExists(i1,i2,type)} forwards to
 *       {@code addEdge(i1,i2,type,checkExisting=false,checkSimilar=z)}; the apply
 *       loop always uses {@code checkSimilar=false}, so only that path is modeled.
 *       The {@code checkCross_exe}/{@code checkSimilarEdge_exe} branches need
 *       geometry state and are out of scope for this slice.</li>
 *   <li>null {@code type} → {@code Intrinsics.checkNotNullParameter} throws
 *       {@code NullPointerException} with the "Parameter specified as non-null is
 *       null: ..." prefix (kotlin-stdlib 1.7.21, throwParameterIsNullNPE).</li>
 *   <li>{@code i1==i2} → log {@code "illegal indexed edge : <i1> - <i2>"} via
 *       {@code util/log/a.b}, return -1, <b>no</b> version bump.</li>
 *   <li>otherwise endpoints normalize to (min,max), then {@code setEdgeUpdated()}
 *       bumps the version unconditionally — before the existence check.</li>
 *   <li>{@code checkExitingTypedEdge} → {@code chechExistingEdge_exe(lo,hi,false)}:
 *       linear scan 0..size-1, first edge with matching endpoints regardless of
 *       type; on hit, retype in place at the same index iff the existing type's
 *       priority is strictly lower than the new type's; return that index.</li>
 *   <li>miss → append a new MEdge at the tail and return the pre-append size.</li>
 *   <li>{@code clearAutoTriangulation()} = order-preserving
 *       {@code removeIf(type==AUTO_TRIANGULATION)}, no version bump.</li>
 * </ul>
 *
 * <p>{@code progress()} models {@code j/a.d()}: the bundled implementation
 * {@code j/b.d()} delegates to {@code a$b.a(this)}, which throws
 * {@code jp.noids.framework.e.a} (a checked Exception) when {@code c()} reports
 * cancelled. All six call sites in the official apply method sit outside the
 * edge-insertion loop; cancel inside the loop is impossible and is therefore
 * not modeled. The modeled exception type is simulation-level — what is
 * verified is the abort/propagation boundary, not the production class name.</p>
 *
 * <p>Impl-specific counters are declared in each subclass so units never mix:
 * the reference counts endpoint-pair comparisons; the candidate counts index
 * build entries and lookups separately.</p>
 */
abstract class EdgeOps {
    final List<MEdge> edges = new ArrayList<>();
    final List<String> events = new ArrayList<>();
    int version;
    private int progressCallsUntilCancel = Integer.MAX_VALUE;

    /** Models the production cancel exception propagation, not its type. */
    static final class ModeledCancel extends Exception {
        private static final long serialVersionUID = 1L;
        ModeledCancel() {
            super("modeled-cancel(jp.noids.framework.e.a)");
        }
    }

    /** Arm {@link #progress()} to throw ModeledCancel after n prior calls. */
    void armCancelAfter(final int progressCalls) {
        progressCallsUntilCancel = progressCalls;
    }

    void clearAutoTriangulation() {
        edges.removeIf(e -> e.type == EdgeType.AUTO_TRIANGULATION);
    }

    /** Models {@code j/a.d()} progress/cancel points; positions must match. */
    void progress() throws ModeledCancel {
        events.add("progress.d");
        if (progressCallsUntilCancel != Integer.MAX_VALUE
            && --progressCallsUntilCancel < 0) {
            throw new ModeledCancel();
        }
    }

    /** Batch boundary hook; the indexed candidate builds its temporary index here. */
    void beginBatch() {
    }

    void endBatch() {
    }

    abstract int addEdgeIfNotExists(int i1, int i2, EdgeType type);

    /** Kotlin Intrinsics.checkNotNullParameter shape (stdlib 1.7.21 → NPE). */
    static NullPointerException nullTypeException() {
        return new NullPointerException(
            "Parameter specified as non-null is null: "
                + "method GEditableMesh2.addEdgeIfNotExists, parameter type");
    }

    void logIllegalEdge(final int i1, final int i2) {
        events.add("log:illegal indexed edge : " + i1 + " - " + i2);
    }

    /** Full ordered structural state for sequence-level equivalence checks. */
    List<String> state() {
        final List<String> out = new ArrayList<>(edges.size());
        for (final MEdge e : edges) {
            out.add(e.index1 + "," + e.index2 + "," + e.type);
        }
        return out;
    }
}
