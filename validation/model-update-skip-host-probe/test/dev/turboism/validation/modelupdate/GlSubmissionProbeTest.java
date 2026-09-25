package dev.turboism.validation.modelupdate;

/** Standalone validation-tool regression; no Cubism or native GL is loaded. */
public final class GlSubmissionProbeTest {
    public interface TestGL {
        TestGL getGL2ES2();
        Object getContext();
        void glBindBuffer(int target, int buffer);
        void glBindVertexArray(int array);
        void glVertexArrayElementBuffer(int array, int buffer);
        void glBufferData(int target, long size, java.nio.Buffer data, int usage);
        int glGetError();
        void glBufferSubData(int target, long offset, long size, java.nio.Buffer data);
        void glDrawElements(int mode, int count, int type, long indices);
        void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.Buffer data);
        void glFail();
    }
    public static final class NativeGL implements TestGL {
        int uploads, errorQueries;
        java.nio.Buffer lastData;
        int lastTarget;
        long lastOffset, lastSize;
        Object context = new Object();
        final IllegalStateException expected = new IllegalStateException("native-error");
        @Override public TestGL getGL2ES2() { return this; }
        @Override public Object getContext() { return context; }
        @Override public void glBindBuffer(int target, int buffer) { }
        @Override public void glBindVertexArray(int array) { }
        @Override public void glVertexArrayElementBuffer(int array, int buffer) { }
        @Override public void glBufferData(int target, long size, java.nio.Buffer data, int usage) { }
        @Override public int glGetError() { errorQueries++; return 0; }
        @Override public void glBufferSubData(int target, long offset, long size, java.nio.Buffer data) {
            uploads++; lastTarget = target; lastOffset = offset; lastSize = size; lastData = data;
            if (size < 0) throw expected;
        }
        @Override public void glDrawElements(int mode, int count, int type, long indices) { }
        @Override public void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.Buffer data) { }
        @Override public void glFail() { throw expected; }
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--verify-real-api")) {
            Class<?> api = Class.forName(args.length > 1 ? args[1] : "com.jogamp.opengl.GL3", false,
                GlSubmissionProbeTest.class.getClassLoader());
            System.out.println(api.getName() + " inherited method count=" + api.getMethods().length);
            check(api.getMethod("getGL3").getReturnType().isAssignableFrom(api),
                "renderer getGL3 view must be covered, not returned unwrapped");
            // Define but do not initialize a Windows-binding proxy on the Linux test JVM.
            // Instantiation would initialize GlueGen pointer signatures and require
            // platform-native libraries; the real Windows-host leg checks instantiation.
            java.lang.reflect.Proxy.getProxyClass(api.getClassLoader(), api);
            System.out.println("Real JOGL API proxy generation PASS (no GL context or Cubism loaded)");
        }
        NativeGL nativeGl = new NativeGL();
        GlSubmissionProbe probe = new GlSubmissionProbe(TestGL.class, nativeGl);
        TestGL wrapped = (TestGL) probe.wrapped();
        check(wrapped.getGL2ES2() == wrapped, "GL views must not escape the decorator");
        check(wrapped.equals(wrapped) && !wrapped.equals(nativeGl), "proxy identity");
        wrapped.glBufferSubData(34962, 0, 16, null);
        check(nativeGl.uploads == 1, "disabled observer must still delegate");
        probe.start();
        wrapped.glBufferSubData(34962, 0, 32, null);
        wrapped.glBufferSubData(34962, 0, 64, null);
        check(wrapped.glGetError() == 0, "return value preserved");
        probe.stop();
        wrapped.glBufferSubData(34962, 0, 128, null);
        check(nativeGl.uploads == 4, "exactly one invocation per upload");
        String report = probe.report();
        check(report.contains("glCalls.glBufferSubData.calls=2\n"), "only measured calls counted");
        check(report.contains("glCalls.glBufferSubData.bytes=96\n"), "exact upload bytes");
        check(report.contains("glCalls.glGetError.calls=1\n"), "query count");
        check(!report.contains("glCalls.getGL2ES2"), "view accessors are not GL calls");
        boolean incompleteRejected = false;
        try { probe.requireValid(); } catch (IllegalStateException incomplete) { incompleteRejected = true; }
        check(incompleteRejected, "upload-only observation cannot certify renderer and readback coverage");
        probe.start();
        wrapped.glBufferSubData(34962, 0, 16, null);
        wrapped.glDrawElements(4, 3, 5125, 0);
        wrapped.glReadPixels(0, 0, 1, 1, 6408, 5121, null);
        probe.stop();
        probe.requireValid();
        probe.start();
        try { wrapped.glFail(); throw new AssertionError("exception was swallowed"); }
        catch (IllegalStateException expected) { check(expected == nativeGl.expected, "target exception identity"); }
        probe.stop();
        check(probe.report().contains("glCalls.glFail.exceptions=1\n"), "exceptions counted");
        probe.close();
        repeatedUploadObservation();
        uploadTargetAccounting();
        System.out.println("GlSubmissionProbeTest PASS (forwarding, views, intervals, bytes, exceptions, exact-payload observation, target reconciliation)");
    }
    private static void repeatedUploadObservation() throws Exception {
        String property = "turboism.validation.modelUpdateUploadPayloads";
        String prior = System.getProperty(property);
        System.setProperty(property, "true");
        NativeGL nativeGl = new NativeGL();
        try (GlSubmissionProbe probe = new GlSubmissionProbe(TestGL.class, nativeGl)) {
            TestGL gl = (TestGL) probe.wrapped();
            java.nio.FloatBuffer data = java.nio.FloatBuffer.wrap(new float[1024]);
            probe.start();
            gl.glBindBuffer(34962, 7);
            gl.glBufferSubData(34962, 0, 4096, data); // baseline
            gl.glBufferSubData(34962, 0, 4096, data); // exact duplicate
            data.put(517, 1f); // deliberately beyond a small prefix sample
            gl.glBufferSubData(34962, 0, 4096, data);
            gl.glBufferSubData(34962, 0, 4096, data); // exact duplicate
            data.put(1023, -0.0f); // raw-bit tail difference
            gl.glBufferSubData(34962, 0, 4096, data);
            gl.glBindBuffer(34962, 8);
            gl.glBufferSubData(34962, 0, 4096, data); // different buffer
            nativeGl.context = new Object();
            gl.glBufferSubData(34962, 0, 4096, data); // unknown binding in new context
            gl.glBindBuffer(34962, 8);
            gl.glBufferSubData(34962, 0, 4096, data); // new context baseline
            gl.glBufferData(34962, 4096, null, 35048);
            gl.glBufferSubData(34962, 0, 4096, data); // storage recreated
            probe.stop();
            String report = probe.report();
            check(report.contains("uploadPayload.exactDuplicateCalls=2\n"),
                "must count complete raw-bit duplicates, not sampled or different-resource matches");
            check(report.contains("uploadPayload.exactDuplicateBytes=8192\n"), "duplicate bytes");
            check(report.contains("uploadPayload.gpuResidencyVerified=false\n"), "no GPU-content proof claimed");
            check(report.contains("uploadPayload.retainedBytes=0\n"), "stop releases mirrors");
            check(nativeGl.uploads == 9, "observation must never omit a native upload");
            check(nativeGl.errorQueries == 0, "observation must not consume GL errors");
            check(data.position() == 0 && data.limit() == 1024, "caller buffer view untouched");
            probe.start();
            java.nio.IntBuffer indices = java.nio.IntBuffer.wrap(new int[]{0, 1, 2});
            gl.glBindBuffer(34963, 19);
            gl.glBufferSubData(34963, 0, 12, indices);
            gl.glBufferSubData(34963, 0, 12, indices);
            gl.glBindVertexArray(20);
            gl.glBufferSubData(34963, 0, 12, indices); // new VAO's binding is unknown
            gl.glBindBuffer(34963, 19);
            gl.glBufferSubData(34963, 0, 12, indices);
            gl.glVertexArrayElementBuffer(20, 88);
            gl.glBufferSubData(34963, 0, 12, indices); // direct-state mutation also retires binding
            probe.stop();
            String elements = probe.report();
            check(elements.contains("glDuplicateUploads.ELEMENT_ARRAY_BUFFER.calls=2\n"),
                "VAO transition must reach observer before another element bind");
            check(elements.contains("uploadPayload.unknownBindings=2\n"), "unknown VAO state disclosed");
            check(elements.contains("glUploads.ELEMENT_ARRAY_BUFFER.calls=5\n"), "all element uploads forwarded");
            check(nativeGl.uploads == 14 && nativeGl.errorQueries == 0, "element observation changes no GL calls");
        } finally {
            if (prior == null) System.clearProperty(property); else System.setProperty(property, prior);
        }
    }
    private static void uploadTargetAccounting() throws Exception {
        NativeGL nativeGl = new NativeGL();
        try (GlSubmissionProbe probe = new GlSubmissionProbe(TestGL.class, nativeGl)) {
            TestGL gl = (TestGL) probe.wrapped();
            java.nio.IntBuffer data = java.nio.IntBuffer.allocate(32);
            data.position(3); data.limit(25);
            gl.glBufferSubData(34962, 0, 4, data); // not measured
            probe.start();
            gl.glBufferSubData(34962, 0, 32, data);
            gl.glBufferSubData(34962, 0, 64, data);
            gl.glBufferSubData(34963, 0, 24, data);
            gl.glBufferSubData(36662, 12, 8, data); // other target, forwarded unchanged
            check(nativeGl.lastTarget == 36662 && nativeGl.lastOffset == 12
                && nativeGl.lastSize == 8 && nativeGl.lastData == data, "native arguments and buffer identity");
            try { gl.glBufferSubData(34963, 0, -1, data); throw new AssertionError("upload exception swallowed"); }
            catch (IllegalStateException expected) { check(expected == nativeGl.expected, "upload exception identity"); }
            probe.stop();
            gl.glBufferSubData(34962, 0, 4, data); // not measured
            check(nativeGl.uploads == 7, "exactly one call including failures/outside interval");
            check(data.position() == 3 && data.limit() == 25, "buffer view unchanged");
            check(nativeGl.errorQueries == 0, "attribution must not add error queries");
            java.util.Map<String, Long> metrics = new java.util.HashMap<>();
            for (String line : probe.report().split("\\n")) {
                String[] pair = line.split("=", 2);
                if (pair.length == 2 && pair[1].matches("-?[0-9]+")) metrics.put(pair[0], Long.parseLong(pair[1]));
            }
            String total = "glCalls.glBufferSubData.";
            String targets = "glUploads.";
            check(Long.valueOf(2).equals(metrics.get(targets + "ARRAY_BUFFER.calls")), "array upload count");
            check(Long.valueOf(96).equals(metrics.get(targets + "ARRAY_BUFFER.bytes")), "array upload bytes");
            check(Long.valueOf(2).equals(metrics.get(targets + "ELEMENT_ARRAY_BUFFER.calls")), "element failures count");
            check(Long.valueOf(24).equals(metrics.get(targets + "ELEMENT_ARRAY_BUFFER.bytes")), "failed bytes not transferred");
            check(Long.valueOf(1).equals(metrics.get(targets + "OTHER.calls")), "other targets retained");
            for (String metric : java.util.List.of("calls", "bytes", "nanos", "exceptions")) {
                long sum = 0;
                for (String target : java.util.List.of("ARRAY_BUFFER", "ELEMENT_ARRAY_BUFFER", "OTHER")) {
                    sum += metrics.get(targets + target + "." + metric);
                }
                check(sum == metrics.get(total + metric), "target totals reconcile: " + metric);
            }
            probe.start(); probe.stop();
            check(!probe.report().contains("glUploads.ARRAY_BUFFER.calls"), "start resets target metrics");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
