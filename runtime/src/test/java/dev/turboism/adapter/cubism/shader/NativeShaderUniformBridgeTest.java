package dev.turboism.adapter.cubism.shader;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class NativeShaderUniformBridgeTest {
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
}
