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
        void glUniform1i(int location, int v0);
        void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.Buffer data);
        void glGenBuffers(int n, java.nio.IntBuffer buffers);
        void glDeleteBuffers(int n, java.nio.IntBuffer buffers);
        void glFail();
        void glActiveTexture(int texture);
        void glBindTexture(int target, int texture);
        void glBindSampler(int unit, int sampler);
        void glUseProgram(int program);
        void glEnable(int cap);
        void glDisable(int cap);
        void glEnablei(int cap, int index);
        void glBlendFunc(int sfactor, int dfactor);
        void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha);
        void glBlendEquation(int mode);
        void glCullFace(int mode);
        void glDepthMask(boolean flag);
        void glStencilFunc(int func, int ref, int mask);
        void glStencilFuncSeparate(int face, int func, int ref, int mask);
        void glViewport(int x, int y, int width, int height);
        void glScissor(int x, int y, int width, int height);
        void glPixelStorei(int pname, int param);
        void glEnableVertexAttribArray(int index);
        void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer);
        void glUniform1f(int location, float v0);
        void glUniformMatrix4fv(int location, int count, boolean transpose, java.nio.FloatBuffer value);
        void glLinkProgram(int program);
        void glDeleteTextures(int n, java.nio.IntBuffer textures);
        void glTexParameteri(int target, int pname, int param);
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
        @Override public void glUniform1i(int location, int v0) { }
        @Override public void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.Buffer data) { }
        @Override public void glGenBuffers(int n, java.nio.IntBuffer buffers) { }
        @Override public void glDeleteBuffers(int n, java.nio.IntBuffer buffers) { deletes++; }
        @Override public void glFail() { throw expected; }
        int enables, deletes, textures;
        boolean failNextEnable;
        @Override public void glActiveTexture(int texture) { }
        @Override public void glBindTexture(int target, int texture) { textures++; }
        @Override public void glBindSampler(int unit, int sampler) { }
        @Override public void glUseProgram(int program) { }
        @Override public void glEnable(int cap) {
            enables++;
            if (failNextEnable) { failNextEnable = false; throw expected; }
        }
        @Override public void glDisable(int cap) { }
        @Override public void glEnablei(int cap, int index) { }
        @Override public void glBlendFunc(int sfactor, int dfactor) { }
        @Override public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) { }
        @Override public void glBlendEquation(int mode) { }
        @Override public void glCullFace(int mode) { }
        @Override public void glDepthMask(boolean flag) { }
        @Override public void glStencilFunc(int func, int ref, int mask) { }
        @Override public void glStencilFuncSeparate(int face, int func, int ref, int mask) { }
        @Override public void glViewport(int x, int y, int width, int height) { }
        @Override public void glScissor(int x, int y, int width, int height) { }
        @Override public void glPixelStorei(int pname, int param) { }
        @Override public void glEnableVertexAttribArray(int index) { }
        @Override public void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) { }
        @Override public void glUniform1f(int location, float v0) { }
        @Override public void glUniformMatrix4fv(int location, int count, boolean transpose, java.nio.FloatBuffer value) { }
        @Override public void glLinkProgram(int program) { }
        @Override public void glDeleteTextures(int n, java.nio.IntBuffer textures) { deletes++; }
        @Override public void glTexParameteri(int target, int pname, int param) { }
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
        check(report.contains("glCategories.enabled=false\n"), "category attribution defaults off");
        check(!report.contains("glCategories.draw.calls"), "disabled categories emit no partition lines");
        check(report.contains("glRedundancy.enabled=false\n"), "redundancy observation defaults off");
        check(!report.contains("glRedundancy.glBindBuffer"), "disabled redundancy emits no method lines");
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
        categoryAttribution();
        categoryAttributionAllocation();
        redundancyAccounting();
        redundancyAllocation();
        System.out.println("GlSubmissionProbeTest PASS (forwarding, views, intervals, bytes, exceptions, exact-payload observation, target reconciliation, category partition, redundancy accounting, allocation neutrality)");
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

    private static void categoryAttribution() throws Exception {
        String property = "turboism.validation.modelUpdateGlCallCategories";
        String prior = System.getProperty(property);
        System.setProperty(property, "true");
        try {
            NativeGL nativeGl = new NativeGL();
            try (GlSubmissionProbe probe = new GlSubmissionProbe(TestGL.class, nativeGl)) {
                TestGL gl = (TestGL) probe.wrapped();
                gl.glDrawElements(4, 3, 5125, 0); // outside the measured window
                probe.start();
                gl.glBindBuffer(34962, 7);
                gl.glBufferSubData(34962, 0, 32, null);
                gl.glBufferData(34962, 64, null, 35048);
                gl.glUniform1i(3, 0);
                gl.glGetError();
                gl.glGenBuffers(1, null);
                gl.glDeleteBuffers(1, null);
                gl.glDrawElements(4, 3, 5125, 0);
                gl.glReadPixels(0, 0, 1, 1, 6408, 5121, null);
                probe.stop();
                gl.glDrawElements(4, 3, 5125, 0); // stopped; not attributed
                String report = probe.report();
                check(report.contains("glCategories.enabled=true\n"), "opt-in flag honored");
                check(report.contains("glCategories.draw.calls=1\n"), "draw partition");
                check(report.contains("glCategories.upload.calls=2\n"), "upload partition");
                check(report.contains("glCategories.query.calls=0\n"), "query partition loses glGetError");
                check(report.contains("glCategories.errorCheck.calls=1\n"), "error-check partition");
                check(report.contains("glCategories.bufferLifecycle.calls=2\n"), "lifecycle partition");
                check(report.contains("glCategories.uniformWrite.calls=1\n"), "uniform-write partition");
                check(report.contains("glCategories.state.calls=1\n"), "state partition");
                check(report.contains("glCategories.readback.calls=1\n"), "readback partition");
                check(report.contains("glCategories.other.calls=0\n"), "uncategorized family disclosed");
                check(report.contains("glCategories.observedCalls=9\n"), "partition covers measured calls only");
                check(report.contains("glCategories.observedNanos="), "delegate totals emitted");
                check(report.contains("glCategories.observerNanos="), "observer overhead exported separately");
                check(report.contains("glCalls.glDrawElements.calls=1\n"), "per-method counts unchanged");
                check(report.contains("glCategories.topMethods.bound=10\n"), "top-methods bound disclosed");
                check(report.contains("glCategories.top.1.method="), "top method emitted");
                check(report.contains("glCategories.top.1.calls=")
                    && report.contains("glCategories.top.1.nanos="), "top method metrics emitted");
                check(report.contains("glCategories.top.9.method="), "all nine called methods ranked");
                check(!report.contains("glCategories.top.10.method="), "only called methods ranked");
                probe.requireValid();
                probe.start();
                try { gl.glFail(); throw new AssertionError("exception was swallowed"); }
                catch (IllegalStateException expected) { check(expected == nativeGl.expected, "exception identity kept"); }
                probe.stop();
                report = probe.report();
                check(report.contains("glCategories.other.calls=1\n"), "exceptional calls join their category");
                check(report.contains("glCategories.observedCalls=1\n"), "start resets category metrics");
                boolean rejected = false;
                try { probe.requireValid(); } catch (IllegalStateException expected) { rejected = true; }
                check(rejected, "exceptional window still fails validity");
            }
        } finally {
            if (prior == null) System.clearProperty(property); else System.setProperty(property, prior);
        }
    }

    /**
     * The category path must not add per-call container allocation over the
     * existing probe: same delegate calls, only bookkeeping differs.
     */
    private static void categoryAttributionAllocation() throws Exception {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean counters)
            || !counters.isThreadAllocatedMemorySupported()) {
            System.out.println("category allocation check skipped: thread allocation counter unavailable");
            return;
        }
        boolean wasEnabled = counters.isThreadAllocatedMemoryEnabled();
        if (!wasEnabled) counters.setThreadAllocatedMemoryEnabled(true);
        String property = "turboism.validation.modelUpdateGlCallCategories";
        String prior = System.getProperty(property);
        try {
            long offBytes = allocatedPerCalls(counters, false);
            long onBytes = allocatedPerCalls(counters, true);
            check(onBytes <= offBytes + ALLOCATION_CALLS * 32L,
                "category attribution must not add hot allocation: off=" + offBytes + " on=" + onBytes);
        } finally {
            if (prior == null) System.clearProperty(property); else System.setProperty(property, prior);
            if (!wasEnabled) counters.setThreadAllocatedMemoryEnabled(false);
        }
    }

    private static final int ALLOCATION_CALLS = 20_000;

    private static long allocatedPerCalls(com.sun.management.ThreadMXBean counters, boolean categories)
            throws Exception {
        if (categories) System.setProperty("turboism.validation.modelUpdateGlCallCategories", "true");
        else System.clearProperty("turboism.validation.modelUpdateGlCallCategories");
        NativeGL nativeGl = new NativeGL();
        try (GlSubmissionProbe probe = new GlSubmissionProbe(TestGL.class, nativeGl)) {
            TestGL gl = (TestGL) probe.wrapped();
            probe.start();
            for (int i = 0; i < ALLOCATION_CALLS; i++) gl.glBindBuffer(34962, i);
            long before = counters.getThreadAllocatedBytes(Thread.currentThread().getId());
            for (int i = 0; i < ALLOCATION_CALLS; i++) {
                gl.glBindBuffer(34962, i);
                gl.glBufferSubData(34962, 0, 4, null);
                gl.glDrawElements(4, 3, 5125, 0);
            }
            long allocated = counters.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            probe.stop();
            check(nativeGl.uploads == ALLOCATION_CALLS, "measured window forwarded every call");
            check(allocated >= 0L, "allocation counter readable");
            return allocated;
        }
    }

    /**
     * Opt-in redundant-state observation: exact-repeat calls are counted per
     * method, untracked mutators invalidate conservatively, and the first call
     * after any invalidation is never redundant. Every native call still runs.
     */
    private static void redundancyAccounting() throws Exception {
        String property = "turboism.validation.modelUpdateGlRedundancy";
        String prior = System.getProperty(property);
        System.setProperty(property, "true");
        try {
            NativeGL nativeGl = new NativeGL();
            try (GlSubmissionProbe probe = new GlSubmissionProbe(TestGL.class, nativeGl)) {
                TestGL gl = (TestGL) probe.wrapped();
                probe.start();

                gl.glBindBuffer(34962, 7);   // record
                gl.glBindBuffer(34962, 7);   // redundant
                gl.glBindBuffer(34962, 8);   // change
                gl.glBindBuffer(34962, 7);   // change back, not redundant
                gl.glBindBuffer(34963, 7);   // independent target slot
                gl.glBindBuffer(34963, 7);   // redundant

                gl.glActiveTexture(33984);   // record GL_TEXTURE0
                gl.glActiveTexture(33984);   // redundant
                gl.glBindTexture(3553, 5);   // record unit0/2D
                gl.glBindTexture(3553, 5);   // redundant
                gl.glActiveTexture(33985);   // switch unit
                gl.glBindTexture(3553, 5);   // unit1: not redundant
                gl.glActiveTexture(33984);
                gl.glBindTexture(3553, 5);   // unit0 entry persisted: redundant

                gl.glBindSampler(0, 3);
                gl.glBindSampler(0, 3);      // redundant
                gl.glBindSampler(1, 3);      // different unit: not

                gl.glEnable(3042);           // record
                gl.glEnable(3042);           // redundant
                gl.glDisable(3042);          // cap now false
                gl.glEnable(3042);           // change: not redundant
                gl.glEnablei(3042, 0);       // untracked indexed write: invalidates cap
                gl.glEnable(3042);           // first after invalidation: not
                gl.glEnable(3042);           // redundant

                gl.glBlendFunc(770, 771);
                gl.glBlendFunc(770, 771);    // redundant
                gl.glBlendFuncSeparate(770, 771, 1, 0);
                gl.glBlendFuncSeparate(770, 771, 1, 0); // redundant, own slot
                gl.glBlendEquation(32774);
                gl.glBlendEquation(32774);   // redundant

                gl.glDepthMask(true);
                gl.glDepthMask(true);        // redundant
                gl.glCullFace(1029);
                gl.glCullFace(1029);         // redundant
                gl.glStencilFunc(519, 0, 255);
                gl.glStencilFunc(519, 0, 255); // redundant
                gl.glStencilFuncSeparate(1028, 519, 0, 255); // invalidates raster
                gl.glStencilFunc(519, 0, 255);   // first after invalidation: not

                gl.glViewport(0, 0, 100, 100);
                gl.glViewport(0, 0, 100, 100); // redundant
                gl.glScissor(0, 0, 10, 10);
                gl.glScissor(0, 0, 10, 10);    // redundant
                gl.glPixelStorei(3317, 4);
                gl.glPixelStorei(3317, 4);     // redundant
                gl.glPixelStorei(3317, 1);     // change
                gl.glPixelStorei(3317, 4);     // not

                gl.glBindBuffer(34962, 9);     // array buffer for pointer keys
                gl.glEnableVertexAttribArray(0);
                gl.glEnableVertexAttribArray(0);  // redundant
                gl.glEnableVertexAttribArray(1);  // different index: not
                gl.glVertexAttribPointer(0, 2, 5126, false, 0, 0L);
                gl.glVertexAttribPointer(0, 2, 5126, false, 0, 0L); // redundant
                gl.glBindBuffer(34962, 10);       // pointer keys include ARRAY_BUFFER
                gl.glVertexAttribPointer(0, 2, 5126, false, 0, 0L); // not redundant
                gl.glVertexAttribPointer(0, 2, 5126, false, 0, 0L); // redundant
                gl.glBindVertexArray(33);         // invalidates attribs + element binding
                gl.glVertexAttribPointer(0, 2, 5126, false, 0, 0L); // not
                gl.glEnableVertexAttribArray(0);  // not

                gl.glUseProgram(9);
                gl.glUseProgram(9);            // redundant
                gl.glUniform1i(4, 1);
                gl.glUniform1i(4, 1);          // redundant
                gl.glUniform1i(4, 2);          // change
                gl.glUniform1i(4, 1);          // change back: not
                gl.glUseProgram(10);
                gl.glUniform1i(4, 1);          // different program key: not
                gl.glUseProgram(9);
                gl.glUniform1i(4, 1);          // (9,4)=1 persisted: redundant
                gl.glUniform1f(4, 0.5f);       // clobbers the (9,4) slot: not
                gl.glUniform1f(4, 0.5f);       // redundant (float scalar form)
                java.nio.FloatBuffer matrix = java.nio.FloatBuffer.wrap(new float[16]);
                gl.glUniformMatrix4fv(7, 1, false, matrix);
                gl.glUniformMatrix4fv(7, 1, false, matrix); // redundant buffer payload
                matrix.put(0, 1f);
                gl.glUniformMatrix4fv(7, 1, false, matrix); // changed payload: not
                gl.glLinkProgram(9);           // relink invalidates uniform state
                gl.glUniform1i(4, 1);          // first after relink: not

                gl.glBindBuffer(34962, 44);
                gl.glBindBuffer(34962, 44);    // redundant
                gl.glDeleteBuffers(1, null);   // invalidates buffer bindings
                gl.glBindBuffer(34962, 44);    // first after deletion: not
                gl.glBindBuffer(34962, 44);    // redundant
                gl.glTexParameteri(3553, 10241, 9729); // untracked state: no invalidation
                gl.glBindBuffer(34962, 44);    // still redundant
                nativeGl.context = new Object();
                gl.glBindBuffer(34962, 44);    // context switch: not
                gl.glBindBuffer(34962, 44);    // redundant under new context

                // requireValid workload coverage for the redundancy invariant.
                gl.glBufferData(34962, 64, null, 35048);
                gl.glDrawElements(4, 3, 5125, 0);
                gl.glReadPixels(0, 0, 1, 1, 6408, 5121, null);
                probe.stop();
                probe.requireValid();

                String report = probe.report();
                check(report.contains("glRedundancy.enabled=true\n"), "opt-in flag honored");
                check(report.contains("glRedundancy.glBindBuffer.calls=15\n"), "bind calls");
                check(report.contains("glRedundancy.glBindBuffer.redundant=6\n"), "bind redundant");
                check(report.contains("glRedundancy.glActiveTexture.redundant=1\n"), "active-texture redundant");
                check(report.contains("glRedundancy.glBindTexture.calls=4\n"), "texture calls");
                check(report.contains("glRedundancy.glBindTexture.redundant=2\n"), "texture per-unit separation");
                check(report.contains("glRedundancy.glBindSampler.redundant=1\n"), "sampler per-unit separation");
                check(report.contains("glRedundancy.glEnable.calls=5\n"), "enable calls including invalidation");
                check(report.contains("glRedundancy.glEnable.redundant=2\n"), "enable redundant after invalidations");
                check(report.contains("glRedundancy.glBlendFunc.redundant=1\n"), "blend-func redundant");
                check(report.contains("glRedundancy.glBlendFuncSeparate.redundant=1\n"), "separate blend slot");
                check(report.contains("glRedundancy.glDepthMask.redundant=1\n"), "depth-mask redundant");
                check(report.contains("glRedundancy.glStencilFunc.calls=3\n"), "stencil-func calls");
                check(report.contains("glRedundancy.glStencilFunc.redundant=1\n"), "stencil invalidated by Separate");
                check(report.contains("glRedundancy.glViewport.redundant=1\n"), "viewport redundant");
                check(report.contains("glRedundancy.glPixelStorei.calls=4\n"), "pixel-store calls");
                check(report.contains("glRedundancy.glEnableVertexAttribArray.calls=4\n"), "attrib-enable calls");
                check(report.contains("glRedundancy.glEnableVertexAttribArray.redundant=1\n"), "attrib per-index separation");
                check(report.contains("glRedundancy.glVertexAttribPointer.calls=5\n"), "attrib-pointer calls");
                check(report.contains("glRedundancy.glVertexAttribPointer.redundant=2\n"), "pointer includes array-buffer binding");
                check(report.contains("glRedundancy.glUseProgram.redundant=1\n"), "use-program redundant");
                check(report.contains("glRedundancy.uniformRedundant.calls=12\n"), "uniform aggregate calls");
                check(report.contains("glRedundancy.uniformRedundant.redundant=4\n"), "uniform aggregate redundant");
                check(!report.contains("glRedundancy.glUniform1i."), "uniforms aggregate only, no per-method lines");
                check(report.contains("glRedundancy.calls=") && report.contains("glRedundancy.redundant=")
                    && report.contains("glRedundancy.redundantNanos="), "summary keys emitted");
                check(report.contains("glRedundancy.invalidations="), "invalidation counter emitted");
                check(nativeGl.enables == 5 && nativeGl.textures == 4 && nativeGl.deletes == 1,
                    "every native call forwarded exactly once");

                // Reconciliation: per-method redundant totals equal the summary.
                java.util.Map<String, Long> metrics = new java.util.HashMap<>();
                for (String line : report.split("\\n")) {
                    String[] pair = line.split("=", 2);
                    if (pair.length == 2 && pair[1].matches("-?[0-9]+")) metrics.put(pair[0], Long.parseLong(pair[1]));
                }
                long redundantSum = 0L;
                for (java.util.Map.Entry<String, Long> entry : metrics.entrySet()) {
                    if (entry.getKey().startsWith("glRedundancy.") && entry.getKey().endsWith(".redundant")
                        && !entry.getKey().equals("glRedundancy.redundant")) redundantSum += entry.getValue();
                }
                check(redundantSum == metrics.get("glRedundancy.redundant"), "summary reconciles with per-method lines");

                probe.start(); // a new window starts with all state unknown
                gl.glBindBuffer(34962, 44);    // record: not redundant
                gl.glBindBuffer(34962, 44);    // redundant
                nativeGl.failNextEnable = true;
                try { gl.glEnable(3042); throw new AssertionError("exception swallowed"); }
                catch (IllegalStateException expected) { check(expected == nativeGl.expected, "enable exception identity"); }
                gl.glEnable(3042);           // failed delegate kept no state: not
                gl.glEnable(3042);           // redundant
                probe.stop();
                report = probe.report();
                check(report.contains("glRedundancy.glBindBuffer.calls=2\n"), "start resets state knowledge");
                check(report.contains("glRedundancy.glBindBuffer.redundant=1\n"), "first window call never redundant");
                check(report.contains("glRedundancy.glEnable.calls=3\n"), "exception window calls counted");
                check(report.contains("glRedundancy.glEnable.redundant=1\n"), "failed call invalidates its entry");
                check(nativeGl.enables == 8, "exception path forwarded natively too");
            }
        } finally {
            if (prior == null) System.clearProperty(property); else System.setProperty(property, prior);
        }
    }

    /**
     * The redundancy path must not add per-call container allocation over the
     * existing probe beyond reflective context-read noise: same delegate calls,
     * only bookkeeping differs.
     */
    private static void redundancyAllocation() throws Exception {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean counters)
            || !counters.isThreadAllocatedMemorySupported()) {
            System.out.println("redundancy allocation check skipped: thread allocation counter unavailable");
            return;
        }
        boolean wasEnabled = counters.isThreadAllocatedMemoryEnabled();
        if (!wasEnabled) counters.setThreadAllocatedMemoryEnabled(true);
        String property = "turboism.validation.modelUpdateGlRedundancy";
        String prior = System.getProperty(property);
        try {
            long offBytes = redundancyAllocatedPerCalls(counters, false);
            long onBytes = redundancyAllocatedPerCalls(counters, true);
            check(onBytes <= offBytes + ALLOCATION_CALLS * 32L,
                "redundancy observation must not add hot allocation: off=" + offBytes + " on=" + onBytes);
        } finally {
            if (prior == null) System.clearProperty(property); else System.setProperty(property, prior);
            if (!wasEnabled) counters.setThreadAllocatedMemoryEnabled(false);
        }
    }

    private static long redundancyAllocatedPerCalls(com.sun.management.ThreadMXBean counters, boolean redundancy)
            throws Exception {
        if (redundancy) System.setProperty("turboism.validation.modelUpdateGlRedundancy", "true");
        else System.clearProperty("turboism.validation.modelUpdateGlRedundancy");
        NativeGL nativeGl = new NativeGL();
        try (GlSubmissionProbe probe = new GlSubmissionProbe(TestGL.class, nativeGl)) {
            TestGL gl = (TestGL) probe.wrapped();
            probe.start();
            for (int i = 0; i < ALLOCATION_CALLS; i++) gl.glBindBuffer(34962, i);
            long before = counters.getThreadAllocatedBytes(Thread.currentThread().getId());
            for (int i = 0; i < ALLOCATION_CALLS; i++) {
                gl.glBindBuffer(34962, i);
                gl.glBufferSubData(34962, 0, 4, null);
                gl.glDrawElements(4, 3, 5125, 0);
            }
            long allocated = counters.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            probe.stop();
            check(nativeGl.uploads == ALLOCATION_CALLS, "measured window forwarded every call");
            check(allocated >= 0L, "allocation counter readable");
            return allocated;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
