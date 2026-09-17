package dev.turboism.validation.modelupdate;

import java.util.ArrayList;
import java.util.List;

/** Exercises the actual gesture lifecycle without starting an Editor or moving the system mouse. */
public final class NativeDragSequenceTest {
    public static void main(String[] args) throws Exception {
        List<String> events = new ArrayList<>();
        NativeDragSequence.execute(4, new NativeDragSequence.Driver() {
            public void press() { events.add("press"); }
            public void drag(int step) { events.add("drag" + step); }
            public void release() { events.add("release"); }
        });
        check(events.equals(List.of("press", "drag0", "drag1", "drag2", "drag3", "release")), "continuous gesture order");
        events.clear();
        Exception original = new Exception("failed frame");
        try {
            NativeDragSequence.execute(4, new NativeDragSequence.Driver() {
                public void press() { events.add("press"); }
                public void drag(int step) throws Exception { throw original; }
                public void release() { events.add("release"); }
            });
            throw new AssertionError("failure swallowed");
        } catch (Exception expected) { check(expected == original, "preserve primary exception"); }
        check(events.equals(List.of("press", "release")), "failed drag must release");
        check(!NativeDragSequence.same(new float[] {0f}, new float[] {-0f}), "raw signed zero");
        check(NativeDragSequence.same(new float[] {Float.intBitsToFloat(0x7fc00001)},
            new float[] {Float.intBitsToFloat(0x7fc00001)}), "raw NaN equality");
        check(!NativeDragSequence.same(new float[] {1, 2, 3}, new float[] {1, 2, 4}), "tail change");
        check(NativeDragSequence.allVerticesMoved(new float[] {0, 0, 1, 0, 0, 1},
            new float[] {2, 1, 3, 1, 2, 2}), "whole mesh movement");
        check(!NativeDragSequence.allVerticesMoved(new float[] {0, 0, 1, 0, 0, 1},
            new float[] {2, 1, 1, 0, 0, 1}), "single vertex is not whole mesh movement");
        Exception cleanup = new Exception("release failure");
        try {
            NativeDragSequence.execute(2, new NativeDragSequence.Driver() {
                public void press() throws Exception { throw original; }
                public void drag(int step) { throw new AssertionError("unreachable"); }
                public void release() throws Exception { throw cleanup; }
            });
            throw new AssertionError("press failure swallowed");
        } catch (Exception expected) {
            check(expected == original && expected.getSuppressed()[0] == cleanup, "cleanup preserves primary failure");
        }
        System.out.println("NativeDragSequenceTest PASS (continuous events, cleanup, raw geometry, whole mesh)");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
