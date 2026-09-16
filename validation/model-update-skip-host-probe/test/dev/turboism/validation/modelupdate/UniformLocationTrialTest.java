package dev.turboism.validation.modelupdate;

public final class UniformLocationTrialTest {
    public interface TestGL {
        Object getContext();
        TestGL getGL3();
        int glGetUniformLocation(int program, String name);
        int glGetError();
        void glUseProgram(int program);
        void glUniform1f(int location, float value);
        void glLinkProgram(int program);
        void glProgramBinary(int program, int format, Object bytes, int length);
        void glDeleteProgram(int program);
        void glDrawElements(int mode, int count, int type, long offset);
    }
    public static final class NativeGL implements TestGL {
        Object context = new Object();
        int queryCalls, errorCalls, drawCalls, uniformWrites, result = 3, error;
        float uniformValue;
        boolean throwQuery;
        final IllegalArgumentException problem = new IllegalArgumentException("native query failed");
        public Object getContext() { return context; }
        public TestGL getGL3() { return this; }
        public int glGetUniformLocation(int program, String name) {
            queryCalls++; if (throwQuery) throw problem; return result;
        }
        public int glGetError() { errorCalls++; int value = error; error = 0; return value; }
        public void glUseProgram(int program) { }
        public void glUniform1f(int location, float value) { uniformWrites++; uniformValue = value; }
        public void glLinkProgram(int program) { result++; }
        public void glProgramBinary(int program, int format, Object bytes, int length) { result++; }
        public void glDeleteProgram(int program) { result = -1; }
        public void glDrawElements(int mode, int count, int type, long offset) { drawCalls++; }
    }
    public static void main(String[] args) throws Exception {
        NativeGL nativeGl = new NativeGL();
        UniformLocationTrial trial = new UniformLocationTrial(TestGL.class, nativeGl, false);
        TestGL gl = (TestGL) trial.wrapped();
        check(gl.getGL3() == gl, "GL view preserved");
        trial.setEnabled(true); trial.beginFrame();
        gl.glGetUniformLocation(7, "x");
        gl.glGetUniformLocation(7, "x");
        check(nativeGl.queryCalls == 2, "no reuse until native error confirmation");
        gl.glGetError();
        check(gl.glGetUniformLocation(7, "x") == 3 && nativeGl.queryCalls == 2, "confirmed query omitted");
        gl.glDrawElements(4, 3, 5125, 0);
        check(nativeGl.drawCalls == 1 && nativeGl.errorCalls == 1, "no draws or error queries added/removed");
        gl.glLinkProgram(7);
        check(gl.glGetUniformLocation(7, "x") == 4 && nativeGl.queryCalls == 3, "relink refresh");
        gl.glGetError(); gl.glProgramBinary(7, 0, null, 0);
        check(gl.glGetUniformLocation(7, "x") == 5, "binary refresh");
        gl.glGetError(); gl.glDeleteProgram(7);
        nativeGl.error = 1282;
        check(gl.glGetUniformLocation(7, "x") == -1, "invalid native query forwarded");
        gl.glGetError();
        int before = nativeGl.queryCalls;
        gl.glGetUniformLocation(7, "x");
        check(nativeGl.queryCalls == before + 1, "failed query not cached");
        trial.endFrame(); trial.beginFrame();
        nativeGl.result = 9;
        gl.glGetUniformLocation(7, "x"); gl.glGetError();
        nativeGl.throwQuery = true;
        trial.setEnabled(false);
        try { gl.glGetUniformLocation(7, "x"); throw new AssertionError("expected native exception"); }
        catch (IllegalArgumentException expected) { check(expected == nativeGl.problem, "exception identity"); }
        trial.close();

        NativeGL shadowNative = new NativeGL();
        UniformLocationTrial shadow = new UniformLocationTrial(TestGL.class, shadowNative, true);
        TestGL checked = (TestGL) shadow.wrapped();
        shadow.setEnabled(true); shadow.beginFrame();
        checked.glGetUniformLocation(7, "x"); checked.glGetError();
        checked.glGetUniformLocation(7, "x");
        check(shadowNative.queryCalls == 2 && shadow.snapshot().get("shadowQueries") == 1L, "shadow never omits");
        shadowNative.result = 77;
        check(checked.glGetUniformLocation(7, "x") == 77, "shadow returns native on mismatch");
        check(shadow.snapshot().get("shadowMismatches") == 1L, "hidden mutation detected");
        shadow.close();
        NativeGL valuesNative = new NativeGL();
        UniformLocationTrial values = new UniformLocationTrial(TestGL.class, valuesNative, false);
        TestGL valueGl = (TestGL) values.wrapped();
        values.setEnabled(true); values.setValuesEnabled(true); values.beginFrame();
        valueGl.glUseProgram(7); valueGl.glGetError();
        valueGl.glUniform1f(3, 0.5f); valueGl.glGetError();
        valueGl.glUniform1f(3, 0.5f);
        check(valuesNative.uniformWrites == 1, "duplicate value write omitted after error confirmation");
        valueGl.glUniform1f(3, 0.6f); valueGl.glGetError();
        check(valuesNative.uniformWrites == 2 && valuesNative.uniformValue == 0.6f, "changed uniform writes through");
        valueGl.glLinkProgram(7);
        valueGl.glUniform1f(3, 0.6f); valueGl.glGetError();
        check(valuesNative.uniformWrites == 3, "relinked program requires fresh value");
        values.endFrame(); values.beginFrame();
        valueGl.glUseProgram(7); valueGl.glGetError();
        valueGl.glUniform1f(3, 0.6f); valueGl.glGetError();
        check(valuesNative.uniformWrites == 4, "new frame never reuses values");
        values.setValuesEnabled(false);
        valueGl.glUniform1f(3, 0.6f);
        check(valuesNative.uniformWrites == 5, "disabled value experiment forwards original write");
        values.close();
        System.out.println("UniformLocationTrialTest PASS (location/value omission, native errors, invalidation, shadow)");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
