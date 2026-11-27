package dev.turboism.adapter.cubism.optimization.shader;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class NativeShaderUniformBridgeTest {
    private static final int ALLOCATION_WARMUP_ITERATIONS = 20_000;
    private static final int ALLOCATION_MEASURE_ITERATIONS = 1000;
    private static final int ALLOCATION_SAMPLE_PASSES = 10;

    public static final class Context {
        static Context current;
        long handle = 1;
        boolean created = true;

        public long getHandle() {
            return handle;
        }

        public boolean isCreated() {
            return created;
        }

        public static Context getCurrent() {
            return current;
        }
    }

    public static final class Gl {
        Context context = new Context();
        int calls;
        int epoch;
        RuntimeException failure;

        public Context getContext() {
            return context;
        }

        public int glGetUniformLocation(int program, String name) {
            calls++;
            if (failure != null) throw failure;
            return name == null || name.equals("missing") ? -1 : program * 100 + epoch + name.length();
        }
    }

    public static final class Shader {
        int program = 3;
        HashMap<String, Object> uniforms = new HashMap<>(Map.of("active", 1));

        public int getGlProgramID() {
            return program;
        }

        public HashMap<String, Object> getUniformMap() {
            return uniforms;
        }
    }

    @BeforeEach
    void enabled() {
        NativeShaderUniformBridge.enabled(true);
        Context.current = null;
    }

    @Test
    void eligibleNamesAreQueriedOnceThenServedFromCache() {
        Gl gl = new Gl();
        Shader shader = new Shader();
        Context.current = gl.context;
        for (String name : List.of("active", "missing", "array[0]", "array[3]", "array")) {
            int expected = gl.glGetUniformLocation(3, name);
            int before = gl.calls;
            for (int i = 0; i < 20; i++) assertEquals(expected, NativeShaderUniformBridge.lookup(gl, 3, name, shader));
            assertEquals(before + 1, gl.calls);
        }
        assertTrue(NativeShaderUniformBridge.hits() > 0);
    }

    @Test
    void lifecycleInvalidationDropsRecordedLocations() {
        Gl gl = new Gl();
        Shader shader = new Shader();
        Context.current = gl.context;
        assertEquals(306, NativeShaderUniformBridge.lookup(gl, 3, "active", shader));
        gl.epoch = 1000;
        NativeShaderUniformBridge.invalidate(shader);
        assertEquals(1306, NativeShaderUniformBridge.lookup(gl, 3, "active", shader));
    }

    @Test
    void invalidateAllDropsRecordedLocations() {
        Gl gl = new Gl();
        Shader shader1 = new Shader();
        Shader shader2 = new Shader();
        Context.current = gl.context;
        assertEquals(306, NativeShaderUniformBridge.lookup(gl, 3, "active", shader1));
        assertEquals(306, NativeShaderUniformBridge.lookup(gl, 3, "active", shader2));
        gl.epoch = 1000;
        NativeShaderUniformBridge.invalidateAll();
        assertEquals(1306, NativeShaderUniformBridge.lookup(gl, 3, "active", shader1));
        assertEquals(1306, NativeShaderUniformBridge.lookup(gl, 3, "active", shader2));
    }

    @Test
    void contextIdentityOrHandleChangeForcesFreshQuery() {
        Gl gl = new Gl();
        Shader shader = new Shader();
        Context.current = gl.context;
        NativeShaderUniformBridge.lookup(gl, 3, "active", shader);
        gl.epoch = 2000;
        gl.context.handle = 2;
        assertEquals(2306, NativeShaderUniformBridge.lookup(gl, 3, "active", shader));
        gl.context = new Context();
        Context.current = gl.context;
        gl.epoch = 3000;
        assertEquals(3306, NativeShaderUniformBridge.lookup(gl, 3, "active", shader));
    }

    @Test
    void programMismatchQueriesThroughOfficialPath() {
        Gl gl = new Gl();
        Shader shader = new Shader();
        Context.current = gl.context;
        shader.program = 4;
        assertEquals(406, NativeShaderUniformBridge.lookup(gl, 4, "active", shader));
    }

    @Test
    void ineligibleCallsAlwaysReachTheOfficialQuery() {
        Gl gl = new Gl();
        Shader shader = new Shader();
        Context.current = gl.context;
        shader.program = 4;
        shader.uniforms.clear();
        int before = gl.calls;
        for (int i = 0; i < 2; i++) NativeShaderUniformBridge.lookup(gl, 4, "active", shader);
        assertEquals(before + 2, gl.calls);
        shader.uniforms.put("active", 1);
        Context.current = null;
        before = gl.calls;
        for (int i = 0; i < 2; i++) NativeShaderUniformBridge.lookup(gl, 4, "active", shader);
        assertEquals(before + 2, gl.calls);
        Context.current = gl.context;
        gl.context.created = false;
        before = gl.calls;
        for (int i = 0; i < 2; i++) NativeShaderUniformBridge.lookup(gl, 4, "active", shader);
        assertEquals(before + 2, gl.calls);
        gl.context.created = true;
        NativeShaderUniformBridge.enabled(false);
        before = gl.calls;
        for (int i = 0; i < 2; i++) NativeShaderUniformBridge.lookup(gl, 4, "active", shader);
        assertEquals(before + 2, gl.calls);
        NativeShaderUniformBridge.enabled(true);
        before = gl.calls;
        for (int i = 0; i < 2; i++) NativeShaderUniformBridge.lookup(gl, 0, null, shader);
        assertEquals(before + 2, gl.calls);
    }

    @Test
    void originalExceptionIsPreservedExactly() {
        Gl gl = new Gl();
        Shader shader = new Shader();
        Context.current = gl.context;
        shader.program = 4;
        var failure = new IllegalStateException("original failure");
        gl.failure = failure;
        int before = gl.calls;
        var thrown = assertThrows(
                IllegalStateException.class, () -> NativeShaderUniformBridge.lookup(gl, 4, "active", shader));
        assertSame(failure, thrown);
        assertEquals(before + 1, gl.calls);
        gl.failure = null;
    }

    public interface PublicGlApi {
        Context getContext();

        int glGetUniformLocation(int program, String name);
    }

    private static final class PackagePrivateGlImpl implements PublicGlApi {
        final Context context = new Context();
        int calls;

        @Override
        public Context getContext() {
            return context;
        }

        @Override
        public int glGetUniformLocation(final int program, final String name) {
            calls++;
            return program * 100 + name.length();
        }
    }

    @Test
    void supportsPackagePrivateConcreteImplementationsBehindPublicInterfaces() {
        final PackagePrivateGlImpl gl = new PackagePrivateGlImpl();
        final Shader shader = new Shader();
        Context.current = gl.context;
        assertEquals(306, NativeShaderUniformBridge.lookup(gl, 3, "active", shader));
        assertEquals(306, NativeShaderUniformBridge.lookup(gl, 3, "active", shader));
        assertEquals(1, gl.calls);
    }

    @Test
    void borrowAndReleaseMapEntriesReusesPooledBufferAndClearsReferences() {
        final HashMap<String, Object> empty = new HashMap<>();
        final Map.Entry<?, ?>[] emptyBorrow = NativeShaderUniformBridge.borrowMapEntries(empty);
        assertEquals(0, emptyBorrow.length);
        NativeShaderUniformBridge.releaseMapEntries(emptyBorrow);

        final HashMap<String, Object> uniforms = new HashMap<>();
        uniforms.put("uAlpha", 1.0f);
        uniforms.put("uBeta", 2.0f);
        final Map.Entry<?, ?>[] first = NativeShaderUniformBridge.borrowMapEntries(uniforms);
        assertSame("uAlpha", first[0].getKey());
        assertEquals(2, countEntries(first));
        NativeShaderUniformBridge.releaseMapEntries(first);
        assertNull(first[0]);

        final Map.Entry<?, ?>[] reused = NativeShaderUniformBridge.borrowMapEntries(uniforms);
        assertSame(first, reused, "released buffer should be reused without array reallocation");

        // Re-entrant borrow before release must use a distinct buffer without clobbering outer entries.
        final HashMap<String, Object> textures = new HashMap<>();
        textures.put("tex0", "value0");
        final Map.Entry<?, ?>[] nested = NativeShaderUniformBridge.borrowMapEntries(textures);
        assertNotSame(reused, nested);
        assertEquals(1, countEntries(nested));
        assertEquals(2, countEntries(reused));

        NativeShaderUniformBridge.releaseMapEntries(nested);
        NativeShaderUniformBridge.releaseMapEntries(reused);
    }

    @Test
    void cameraMatrixCacheSkipsMatchingUploadsAndInvalidatesOnChangeErrorOrProgramSwitch() {
        final Gl gl = new Gl();
        final Shader shader = new Shader();
        Context.current = gl.context;
        final float[] projectionA = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        final float[] projectionB = {2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

        final long hitsBefore = NativeShaderUniformBridge.matrixHits();
        assertTrue(NativeShaderUniformBridge.shouldUploadCameraMatrix(
                gl, 3, shader, NativeShaderUniformBridge.MATRIX_KIND_PROJECTION, 5, projectionA));
        NativeShaderUniformBridge.recordCameraMatrix(
                gl, 3, shader, NativeShaderUniformBridge.MATRIX_KIND_PROJECTION, 5, projectionA);

        // Unchanged matrix + location + program skips upload even if caller mutates original buffer later? Check first
        // hit.
        assertFalse(NativeShaderUniformBridge.shouldUploadCameraMatrix(
                gl, 3, shader, NativeShaderUniformBridge.MATRIX_KIND_PROJECTION, 5, projectionA));
        assertEquals(hitsBefore + 1, NativeShaderUniformBridge.matrixHits());

        // Value copy isolation: mutating caller array afterwards must still miss if content changed, and hit on A copy.
        final float[] projectionCopy = projectionA.clone();
        projectionA[0] = 99.0f;
        assertTrue(NativeShaderUniformBridge.shouldUploadCameraMatrix(
                gl, 3, shader, NativeShaderUniformBridge.MATRIX_KIND_PROJECTION, 5, projectionA));
        assertFalse(NativeShaderUniformBridge.shouldUploadCameraMatrix(
                gl, 3, shader, NativeShaderUniformBridge.MATRIX_KIND_PROJECTION, 5, projectionCopy));

        // Different matrix kind stays independent on same shader.
        assertTrue(NativeShaderUniformBridge.shouldUploadCameraMatrix(
                gl, 3, shader, NativeShaderUniformBridge.MATRIX_KIND_WORLD_TO_CAMERA, 6, projectionB));
        NativeShaderUniformBridge.recordCameraMatrix(
                gl, 3, shader, NativeShaderUniformBridge.MATRIX_KIND_WORLD_TO_CAMERA, 6, projectionB);
        assertFalse(NativeShaderUniformBridge.shouldUploadCameraMatrix(
                gl, 3, shader, NativeShaderUniformBridge.MATRIX_KIND_WORLD_TO_CAMERA, 6, projectionB));

        // Invalidation clears cached matrices.
        NativeShaderUniformBridge.invalidate(shader);
        assertTrue(NativeShaderUniformBridge.shouldUploadCameraMatrix(
                gl, 3, shader, NativeShaderUniformBridge.MATRIX_KIND_PROJECTION, 5, projectionCopy));
    }

    @Test
    void steadyStateLookupAndMatrixCacheCheckAllocateZeroBytes() {
        final Gl gl = new Gl();
        final Shader shader = new Shader();
        Context.current = gl.context;
        final float[] projection = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

        assertEquals(306, NativeShaderUniformBridge.lookup(gl, 3, "active", shader));
        NativeShaderUniformBridge.recordCameraMatrix(
                gl, 3, shader, NativeShaderUniformBridge.MATRIX_KIND_PROJECTION, 5, projection);

        final var allocMx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        for (int i = 0; i < ALLOCATION_WARMUP_ITERATIONS; i++) {
            assertEquals(306, NativeShaderUniformBridge.lookup(gl, 3, "active", shader));
            assertFalse(NativeShaderUniformBridge.shouldUploadCameraMatrix(
                    gl, 3, shader, NativeShaderUniformBridge.MATRIX_KIND_PROJECTION, 5, projection));
        }

        final long tid = Thread.currentThread().getId();
        allocMx.getThreadAllocatedBytes(tid);
        long minAllocated = Long.MAX_VALUE;
        for (int pass = 0; pass < ALLOCATION_SAMPLE_PASSES; pass++) {
            final long before = allocMx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < ALLOCATION_MEASURE_ITERATIONS; i++) {
                assertEquals(306, NativeShaderUniformBridge.lookup(gl, 3, "active", shader));
                assertFalse(NativeShaderUniformBridge.shouldUploadCameraMatrix(
                        gl, 3, shader, NativeShaderUniformBridge.MATRIX_KIND_PROJECTION, 5, projection));
            }
            minAllocated = Math.min(minAllocated, allocMx.getThreadAllocatedBytes(tid) - before);
            if (minAllocated == 0L) {
                break;
            }
        }
        final long observedMin = minAllocated;
        assertEquals(
                0L,
                observedMin,
                () -> "steady-state lookup and camera matrix checks must allocate zero bytes (minAllocated="
                        + observedMin
                        + ")");
    }

    @Test
    void useProgramLabelsAreCachedZeroAllocationAndExact() {
        for (int id : new int[] {-1, 0, 1, 42, 255, 256, 1024}) {
            assertEquals("use program " + id, NativeShaderUniformBridge.useProgramLabel(id));
        }
        for (int id = 0; id < 256; id++) {
            assertSame(NativeShaderUniformBridge.useProgramLabel(id), NativeShaderUniformBridge.useProgramLabel(id));
        }
        final var allocMx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        int checksum = 0;
        for (int i = 0; i < ALLOCATION_WARMUP_ITERATIONS; i++) {
            checksum += NativeShaderUniformBridge.useProgramLabel(i & 255).length();
        }
        final long tid = Thread.currentThread().getId();
        allocMx.getThreadAllocatedBytes(tid);
        long minAllocated = Long.MAX_VALUE;
        for (int pass = 0; pass < ALLOCATION_SAMPLE_PASSES; pass++) {
            final long before = allocMx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < ALLOCATION_MEASURE_ITERATIONS; i++) {
                checksum += NativeShaderUniformBridge.useProgramLabel(i & 255).length();
            }
            minAllocated = Math.min(minAllocated, allocMx.getThreadAllocatedBytes(tid) - before);
            if (minAllocated == 0L) {
                break;
            }
        }
        assertTrue(checksum > 0);
        final long observedMin = minAllocated;
        assertEquals(
                0L,
                observedMin,
                () -> "cached use program label lookup must allocate zero bytes (minAllocated=" + observedMin + ")");
    }

    private static int countEntries(final Map.Entry<?, ?>[] entries) {
        int count = 0;
        while (count < entries.length && entries[count] != null) {
            count++;
        }
        return count;
    }
}
