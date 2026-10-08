package dev.turboism.adapter.cubism.warpalt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behaviour contract of the weight-mirror path against stub host-shape classes.
 * The stub selector re-enters the bridge at the head of {@code add}/{@code
 * setWeight} exactly like the instrumented host methods do, so the tests cover
 * the recursive counterpart write and the reentrancy guard for real.
 */
final class NativeWarpAltMirrorBridgeWeightTest {

    private StubSource source;
    private StubPointSelector selector;
    private dev.turboism.sdk.plugin.Registration registration;

    @BeforeEach
    void setUp() {
        source = new StubSource(2, 2); // 3x3 point grid
        selector = new StubPointSelector();
        NativeWarpAltMirrorBridge.setWarpRefClassNameForTesting(StubWarpPointRef.class.getName());
        NativeWarpAltMirrorBridge.install();
        registration = NativeWarpAltMirrorBridge.moveParticipation().participate();
    }

    @AfterEach
    void tearDown() {
        if (registration != null) registration.close();
        NativeWarpAltMirrorBridge.uninstall();
    }

    private StubWarpPointRef ref(final int index) {
        return new StubWarpPointRef(source, index, new StubForm());
    }

    private float weightOf(final int index) {
        return selector.weightMap.getOrDefault(ref(index), Float.NaN);
    }

    @Test
    void verticalAxisMirrorsWeightAcrossRows() {
        NativeWarpAltMirrorBridge.setArmedAxis(1);

        selector.add(ref(0), 0.5f, true); // top-left (row 0, col 0)

        // counterpart of (0,0) across the vertical mirror is (2,0) -> index 6
        assertEquals(0.5f, weightOf(6), 1.0e-6f);
        assertEquals(0.5f, weightOf(0), 1.0e-6f);
        assertEquals(1, NativeWarpAltMirrorBridge.weightMirrorAppliedCount());
        assertEquals(0, NativeWarpAltMirrorBridge.weightMirrorLastSourceIndex());
        assertEquals(6, NativeWarpAltMirrorBridge.weightMirrorLastCounterpartIndex());
        assertEquals(2, selector.addCalls, "source write plus one mirrored write");
    }

    @Test
    void horizontalAxisMirrorsWeightAcrossColumns() {
        NativeWarpAltMirrorBridge.setArmedAxis(2);

        selector.add(ref(0), 0.7f, true); // (row 0, col 0)

        // counterpart across the horizontal mirror is (0,2) -> index 2
        assertEquals(0.7f, weightOf(2), 1.0e-6f);
        assertEquals(1, NativeWarpAltMirrorBridge.weightMirrorAppliedCount());
        assertEquals(2, NativeWarpAltMirrorBridge.weightMirrorLastCounterpartIndex());
    }

    @Test
    void nonSquareGridUsesSourceDivisions() {
        source = new StubSource(3, 1); // 4 cols x 2 rows -> 8 points
        selector = new StubPointSelector();
        NativeWarpAltMirrorBridge.setArmedAxis(2);

        selector.add(ref(1), 0.4f, true); // (row 0, col 1) -> (row 0, col 2) = index 2

        assertEquals(0.4f, weightOf(2), 1.0e-6f);
        assertEquals(1, NativeWarpAltMirrorBridge.weightMirrorAppliedCount());
    }

    @Test
    void centerPointMirrorsOntoItselfIsSkipped() {
        NativeWarpAltMirrorBridge.setArmedAxis(1);

        selector.add(ref(4), 0.9f, true); // exact centre of the 3x3 grid

        assertEquals(1, selector.addCalls, "self-counterpart must not mirror");
        assertEquals(0, NativeWarpAltMirrorBridge.weightMirrorAppliedCount());
    }

    @Test
    void axisOffDoesNotMirror() {
        NativeWarpAltMirrorBridge.setArmedAxis(0);

        selector.add(ref(0), 0.5f, true);

        assertEquals(1, selector.addCalls);
        assertTrue(Float.isNaN(weightOf(6)));
        assertEquals(0, NativeWarpAltMirrorBridge.weightMirrorAppliedCount());
    }

    @Test
    void noParticipationDoesNotMirror() {
        registration.close();
        registration = null;
        NativeWarpAltMirrorBridge.setArmedAxis(1);

        selector.add(ref(0), 0.5f, true);

        assertEquals(1, selector.addCalls);
        assertEquals(0, NativeWarpAltMirrorBridge.weightMirrorAppliedCount());
    }

    @Test
    void nonWarpRefIsIgnored() {
        NativeWarpAltMirrorBridge.setArmedAxis(1);

        NativeWarpAltMirrorBridge.mirrorWeightAdd(selector, new Object(), 0.5f, true);

        assertEquals(0, selector.addCalls);
        assertEquals(0, NativeWarpAltMirrorBridge.weightMirrorAppliedCount());
    }

    @Test
    void setWeightMirrorsWithoutTouchingSelectionList() {
        NativeWarpAltMirrorBridge.setArmedAxis(1);

        selector.setWeight(ref(0), 0.3f);

        assertEquals(0.3f, weightOf(6), 1.0e-6f);
        assertEquals(2, selector.setCalls);
        assertEquals(0, selector.addCalls);
        assertEquals(1, NativeWarpAltMirrorBridge.weightMirrorAppliedCount());
    }

    @Test
    void existingCounterpartSelectionReusesStoredInstanceWithoutDuplicates() {
        NativeWarpAltMirrorBridge.setArmedAxis(1);
        final StubR stored = new StubR(ref(6), new StubTransform());
        selector.selected.add(stored);
        selector.weightMap.put(stored, 0.1f);

        selector.add(ref(0), 0.9f, true);

        assertEquals(2, selector.selected.size(), "stored counterpart plus the source — no duplicate");
        assertTrue(selector.selected.stream().anyMatch(entry -> entry == stored), "the stored ref instance is reused");
        assertEquals(0.9f, selector.weightMap.get(stored), 1.0e-6f);
        assertEquals(1, NativeWarpAltMirrorBridge.weightMirrorAppliedCount());
    }

    @Test
    void subtypeCounterpartPreservesItsTransform() {
        NativeWarpAltMirrorBridge.setArmedAxis(1);
        final StubTransform transform = new StubTransform();

        selector.add(new StubR(ref(0), transform), 0.6f, true);

        final Object mirrored = selector.writeRefs.get(0);
        assertTrue(mirrored instanceof StubR, "counterpart keeps the transform-carrying subtype");
        assertSame(transform, ((StubR) mirrored).transform());
        assertEquals(6, ((StubR) mirrored).a());
    }

    @Test
    void participationExposesAppliedCountToProbes() {
        NativeWarpAltMirrorBridge.setArmedAxis(2);

        selector.add(ref(0), 0.5f, true);

        assertEquals(1, NativeWarpAltMirrorBridge.moveParticipation().weightMirrorAppliedCount());
        assertEquals(0, NativeWarpAltMirrorBridge.moveParticipation().weightMirrorLastSourceIndex());
        assertEquals(2, NativeWarpAltMirrorBridge.moveParticipation().weightMirrorLastCounterpartIndex());
    }

    /** Stub CWarpDeformerSource: getCol/getRow are the division counts. */
    static final class StubSource {
        private final int col;
        private final int row;
        final float[] positions;

        StubSource(final int col, final int row) {
            this.col = col;
            this.row = row;
            this.positions = new float[2 * (col + 1) * (row + 1)];
        }

        public int getCol() {
            return col;
        }

        public int getRow() {
            return row;
        }
    }

    /** Stub CWarpDeformerForm marker. */
    static final class StubForm {}

    /** Stub transform payload carried by the r-shaped subtype. */
    static final class StubTransform {}

    /** Stub WarpPointRef: a() index, d() source, f() form, g() positions. */
    static class StubWarpPointRef {
        private final StubSource source;
        private final int index;
        private final StubForm form;

        public StubWarpPointRef(final StubSource source, final int index, final StubForm form) {
            this.source = source;
            this.index = index;
            this.form = form;
        }

        public int a() {
            return index;
        }

        public StubSource d() {
            return source;
        }

        public StubForm f() {
            return form;
        }

        public float[] g() {
            return source.positions;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof StubWarpPointRef ref && ref.source == source && ref.index == index;
        }

        @Override
        public int hashCode() {
            return source.hashCode() + index * 31;
        }
    }

    /** Stub warp.r subtype: wraps a base ref with a transform payload. */
    static final class StubR extends StubWarpPointRef {
        private StubTransform a;

        public StubR(final StubWarpPointRef inner, final StubTransform transform) {
            super(inner.d(), inner.a(), inner.f());
            this.a = transform;
        }

        StubTransform transform() {
            return a;
        }
    }

    /** Stub PointSelector whose add/setWeight re-enter the bridge like the injected head does. */
    static final class StubPointSelector {
        final Map<Object, Float> weightMap = new HashMap<>();
        final List<Object> selected = new ArrayList<>();
        final List<Object> writeRefs = new ArrayList<>();
        int addCalls;
        int setCalls;

        public boolean add(final StubWarpPointRef ref, final float weight, final boolean reorder) {
            NativeWarpAltMirrorBridge.mirrorWeightAdd(this, ref, weight, reorder);
            addCalls++;
            writeRefs.add(ref);
            weightMap.put(ref, weight);
            if (reorder) {
                selected.remove(ref);
            }
            return selected.add(ref);
        }

        public void setWeight(final StubWarpPointRef ref, final float weight) {
            NativeWarpAltMirrorBridge.mirrorWeightSet(this, ref, weight);
            setCalls++;
            writeRefs.add(ref);
            weightMap.put(ref, weight);
        }

        public StubWarpPointRef getCompatible(final StubWarpPointRef ref) {
            for (final Object stored : selected) {
                if (stored.equals(ref)) {
                    return (StubWarpPointRef) stored;
                }
            }
            return null;
        }
    }
}
