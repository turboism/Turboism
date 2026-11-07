package dev.turboism.validation;

/** Offline regression for the two nested registry/contributor close wrappers. */
public final class NativeHookHandlesTest {
    private NativeHookHandlesTest() {}

    private static final class Installation implements AutoCloseable {
        @Override public void close() {}
    }

    private static AutoCloseable wrap(AutoCloseable value) {
        return () -> value.close();
    }

    public static void main(String[] args) throws Exception {
        var installation = new Installation();
        var wrapped = wrap(wrap(installation));
        String expected = Installation.class.getName();
        if (NativeHookHandles.unwrap(wrapped, expected, 4) != installation)
            throw new AssertionError("nested close wrapper did not reveal installer");
        if (NativeHookHandles.unwrap(wrapped, expected, 0) != null)
            throw new AssertionError("depth bound ignored");
        if (NativeHookHandles.unwrap(new Object(), expected, 4) != null)
            throw new AssertionError("foreign object traversed");
        if (NativeHookHandles.unwrap(wrapped, "not.present", 4) != null)
            throw new AssertionError("missing installation invented");
        System.out.println("NativeHookHandlesTest PASS");
    }
}
