package dev.turboism.adapter.cubism.optimization.geometry;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class WarpPositionProjectionBridgeTest {
    public static class Form {
        float[] positions;
        Object source = new Object();
        int sourceCalls;
        Form(float[] positions) { this.positions = positions; }
        public float[] getPositions() { return positions; }
        public Object getSource() { sourceCalls++; return source; }
    }
    public static class Vector {
        final float x, y;
        static volatile CountDownLatch entered, release;
        public Vector(float x, float y) {
            this.x = x; this.y = y;
            if (entered != null) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release absent"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }
        }
    }
    private static Function<Object,Object> callback() {
        @SuppressWarnings("unchecked") var function = (Function<Object,Object>) System.getProperties().get(WarpPositionProjectionBridge.CALLBACK_PROPERTY);
        return function;
    }

    @Test void projectsExactBitsIntoFreshOwnedVectorsAndPreservesInput() throws Exception {
        String old = System.getProperty(WarpPositionProjectionBridge.ENABLE_PROPERTY);
        try (var bridge = new WarpPositionProjectionBridge(Form.class, Vector.class)) {
            System.setProperty(WarpPositionProjectionBridge.ENABLE_PROPERTY,"true"); bridge.install();
            float[] values = {0f,-0f,Float.intBitsToFloat(0x7fc00003),Float.POSITIVE_INFINITY,Float.MIN_VALUE,-12.5f,99f};
            Form form = new Form(values.clone());
            var first = (List<?>) callback().apply(form);
            var second = (List<?>) callback().apply(form);
            assertNotSame(first,second); assertEquals(3,first.size());
            for(int i=0;i<first.size();i++) {
                Vector v = (Vector)first.get(i);
                assertNotSame(v,second.get(i));
                assertEquals(Float.floatToRawIntBits(values[2*i]),Float.floatToRawIntBits(v.x));
                assertEquals(Float.floatToRawIntBits(values[2*i+1]),Float.floatToRawIntBits(v.y));
            }
            assertArrayEquals(values,form.positions);
            assertEquals(6L,bridge.snapshot().get("points"));
            System.setProperty(WarpPositionProjectionBridge.ENABLE_PROPERTY,"false");
            assertNull(callback().apply(form));
        } finally { restore(old); }
    }

    @Test void rejectsForeignInvalidOversizeAndClosedCallsWithoutRetainingInputs() throws Exception {
        String old=System.getProperty(WarpPositionProjectionBridge.ENABLE_PROPERTY);
        try(var bridge=new WarpPositionProjectionBridge(Form.class,Vector.class)) {
            System.setProperty(WarpPositionProjectionBridge.ENABLE_PROPERTY,"true");bridge.install();
            var function=callback();
            assertNull(function.apply(null));assertNull(function.apply(new Object()));
            assertNull(function.apply(new Form(null)));
            assertNull(function.apply(new Form(new float[262146])));
            assertNull(function.apply(new Form(new float[2]) { @Override public float[] getPositions(){throw new AssertionError("foreign getter");} }));
            Form invalid=new Form(new float[2]);invalid.source=null;assertNull(function.apply(invalid));
            Form empty=new Form(new float[1]);empty.source=null;
            assertEquals(List.of(),function.apply(empty));assertEquals(0,empty.sourceCalls);
            bridge.close();assertNull(function.apply(new Form(new float[2])));
            assertFalse(System.getProperties().containsKey(WarpPositionProjectionBridge.CALLBACK_PROPERTY));
        } finally { restore(old); }
    }

    @Test void closeDiscardsOutstandingProjection() throws Exception {
        String old=System.getProperty(WarpPositionProjectionBridge.ENABLE_PROPERTY);
        var executor=Executors.newSingleThreadExecutor();
        Vector.entered=new CountDownLatch(1);Vector.release=new CountDownLatch(1);
        try(var bridge=new WarpPositionProjectionBridge(Form.class,Vector.class)) {
            System.setProperty(WarpPositionProjectionBridge.ENABLE_PROPERTY,"true");bridge.install();
            var function=callback();var result=executor.submit(()->function.apply(new Form(new float[]{1,2})));
            assertTrue(Vector.entered.await(5,TimeUnit.SECONDS));bridge.close();Vector.release.countDown();
            assertNull(result.get(5,TimeUnit.SECONDS));assertEquals(0L,bridge.snapshot().get("projected"));
        } finally { Vector.release.countDown();Vector.entered=null;Vector.release=null;executor.shutdownNow();restore(old); }
    }

    private static void restore(String old) {
        if(old==null)System.clearProperty(WarpPositionProjectionBridge.ENABLE_PROPERTY);
        else System.setProperty(WarpPositionProjectionBridge.ENABLE_PROPERTY,old);
    }
}
