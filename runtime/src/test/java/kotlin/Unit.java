package kotlin;

/**
 * Test-only stand-in for the Kotlin runtime's {@code kotlin.Unit}, mirroring the
 * {@code FakeCallback} stand-in for {@code kotlin.jvm.functions.Function1}: the real
 * Cubism host loads the Kotlin runtime, while the fake host classloader must still
 * satisfy the runtime's {@code kotlinUnit()} lookup for Window-menu click callbacks.
 */
public final class Unit {

    public static final Unit INSTANCE = new Unit();

    private Unit() {
    }
}
