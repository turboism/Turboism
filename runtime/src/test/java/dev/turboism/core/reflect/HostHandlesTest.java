package dev.turboism.core.reflect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class HostHandlesTest {

    /** Erased receiver-first shape of every reference-returning member. */
    private static final MethodType REFERENCE = MethodType.methodType(Object.class, Object.class);

    /** Erased receiver-first shape of a primitive-returning boolean member. */
    private static final MethodType FLAG = MethodType.methodType(boolean.class, Object.class);

    /** Erased receiver-first shape of every one-argument reference member. */
    private static final MethodType SINGLE = MethodType.methodType(Object.class, Object.class, Object.class);

    /** Erased receiver-first shape of a field write. */
    private static final MethodType ASSIGN = MethodType.methodType(void.class, Object.class, Object.class);

    /** Erased shape of a constructor taking one {@code int}. */
    private static final MethodType CREATE = MethodType.methodType(Object.class, int.class);

    /** Production-shaped owner: an overload pair, a private member, a static entry, a mutable field. */
    public static class Sample {
        private final String label;
        private String note = "before";

        public Sample() {
            this("default");
        }

        public Sample(final int size) {
            this("size-" + size);
        }

        private Sample(final String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean empty() {
            return label.isEmpty();
        }

        private String secret() {
            return "secret";
        }

        public String pick(final Object value) {
            return "object";
        }

        public String pick(final String value) {
            return "string:" + value;
        }

        public static String statically(final String value) {
            return "static:" + value;
        }
    }

    /** A module that opens nothing, standing in for a closed host module. */
    private static final HostHandles.LookupFactory CLOSED = declaring -> {
        throw new IllegalAccessException("module " + declaring.getName() + " does not open it");
    };

    @Test
    void virtualBindsTheExactDescriptorOfTheReviewedOverload() throws Throwable {
        // Both overloads exist; the requested descriptor is the only acceptance rule.
        final MethodHandle pick =
                HostHandles.virtual(Sample.class, "pick", "(Ljava/lang/String;)Ljava/lang/String;", SINGLE);
        assertEquals("string:x", (String) pick.invokeWithArguments(new Sample(), "x"));

        final MethodHandle other =
                HostHandles.virtual(Sample.class, "pick", "(Ljava/lang/Object;)Ljava/lang/String;", SINGLE);
        assertEquals("object", (String) other.invokeWithArguments(new Sample(), "x"));
    }

    @Test
    void virtualReachesANonPublicMemberAndAPrimitiveReturn() throws Throwable {
        final MethodHandle secret = HostHandles.virtual(Sample.class, "secret", "()Ljava/lang/String;", REFERENCE);
        assertEquals("secret", (String) secret.invokeWithArguments(new Sample()));

        final MethodHandle flag = HostHandles.virtual(Sample.class, "empty", "()Z", FLAG);
        assertEquals(Boolean.FALSE, flag.invokeWithArguments(new Sample()));
    }

    @Test
    void staticCallTakesNoReceiver() throws Throwable {
        final MethodHandle statically =
                HostHandles.staticCall(Sample.class, "statically", "(Ljava/lang/String;)Ljava/lang/String;", REFERENCE);
        assertEquals("static:x", (String) statically.invokeWithArguments("x"));
    }

    @Test
    void getterAndSetterReadAndWriteTheExactField() throws Throwable {
        final Sample sample = new Sample();
        final MethodHandle get = HostHandles.getter(Sample.class, "note", "Ljava/lang/String;", REFERENCE);
        final MethodHandle set = HostHandles.setter(Sample.class, "note", "Ljava/lang/String;", ASSIGN);

        assertEquals("before", (String) get.invokeWithArguments(sample));
        assertNull(set.invokeWithArguments(sample, "after"));
        assertEquals("after", (String) get.invokeWithArguments(sample));
    }

    @Test
    void constructBindsTheExactConstructorIncludingANonPublicOne() throws Throwable {
        final Sample byCount =
                (Sample) HostHandles.construct(Sample.class, "(I)V", CREATE).invokeWithArguments(3);
        assertEquals("size-3", byCount.label());

        final Sample hidden = (Sample) HostHandles.construct(Sample.class, "(Ljava/lang/String;)V", REFERENCE)
                .invokeWithArguments("hidden");
        assertEquals("hidden", hidden.label());
    }

    @Test
    void loadResolvesAnInternalNameWithoutInitialisingTheOwner() throws Throwable {
        assertSame(
                Sample.class,
                HostHandles.load(getClass().getClassLoader(), "dev/turboism/core/reflect/HostHandlesTest$Sample"));
    }

    @Test
    void anAbsentMemberIsRefusedAsMemberAbsent() {
        final HostHandles.HostMemberRefused absent = assertRefusal(
                HostHandles.Refusal.MEMBER_ABSENT,
                () -> HostHandles.virtual(Sample.class, "missing", "()V", REFERENCE));
        assertEquals("dev.turboism.core.reflect.HostHandlesTest$Sample.missing()V", absent.declaration());

        assertRefusal(
                HostHandles.Refusal.MEMBER_ABSENT, () -> HostHandles.virtual(Sample.class, "label", "()I", REFERENCE));
        assertRefusal(
                HostHandles.Refusal.MEMBER_ABSENT,
                () -> HostHandles.getter(Sample.class, "absent", "Ljava/lang/String;", REFERENCE));
        assertRefusal(
                HostHandles.Refusal.MEMBER_ABSENT, () -> HostHandles.getter(Sample.class, "note", "I", REFERENCE));
        assertRefusal(HostHandles.Refusal.MEMBER_ABSENT, () -> HostHandles.construct(Sample.class, "(J)V", CREATE));
    }

    @Test
    void aClosedDeclaringClassIsRefusedAsModuleClosed() {
        assertRefusal(
                HostHandles.Refusal.MODULE_CLOSED,
                () -> HostHandles.virtual(CLOSED, Sample.class, "secret", "()Ljava/lang/String;", REFERENCE));
        assertRefusal(
                HostHandles.Refusal.MODULE_CLOSED,
                () -> HostHandles.getter(CLOSED, Sample.class, "label", "Ljava/lang/String;", REFERENCE));
        assertRefusal(
                HostHandles.Refusal.MODULE_CLOSED,
                () -> HostHandles.construct(CLOSED, Sample.class, "(Ljava/lang/String;)V", REFERENCE));
    }

    @Test
    void aPublicMemberFallsBackToThePublicLookupWhenThePrivateOneIsRefused() throws Throwable {
        final MethodHandle label =
                HostHandles.virtual(CLOSED, Sample.class, "label", "()Ljava/lang/String;", REFERENCE);
        assertEquals("default", (String) label.invokeWithArguments(new Sample()));
    }

    @Test
    void anUnadaptableShapeIsRefusedAsShapeMismatch() {
        // A shape with one argument too many cannot be produced for a receiver-only method.
        final HostHandles.HostMemberRefused mismatch = assertRefusal(
                HostHandles.Refusal.SHAPE_MISMATCH,
                () -> HostHandles.virtual(Sample.class, "label", "()Ljava/lang/String;", SINGLE));
        assertEquals(
                "dev.turboism.core.reflect.HostHandlesTest$Sample.label()Ljava/lang/String;", mismatch.declaration());

        // A static entry takes no receiver, so the receiver-first shape cannot be produced either.
        assertRefusal(
                HostHandles.Refusal.SHAPE_MISMATCH,
                () -> HostHandles.staticCall(
                        Sample.class, "statically", "(Ljava/lang/String;)Ljava/lang/String;", SINGLE));
    }

    @Test
    void anUnloadableOwnerIsRefusedAsOwnerUnloadable() {
        final ClassLoader empty = new ClassLoader(null) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                throw new ClassNotFoundException(name);
            }
        };
        final HostHandles.HostMemberRefused refused = assertRefusal(
                HostHandles.Refusal.OWNER_UNLOADABLE, () -> HostHandles.load(empty, "com/live2d/absent/CAbsent"));
        assertEquals("com/live2d/absent/CAbsent", refused.declaration());
    }

    private static HostHandles.HostMemberRefused assertRefusal(
            final HostHandles.Refusal expected, final Executable call) {
        final HostHandles.HostMemberRefused refused = assertThrows(HostHandles.HostMemberRefused.class, call);
        assertEquals(expected, refused.reason(), refused.declaration());
        assertFalse(refused.declaration().isBlank());
        return refused;
    }
}
