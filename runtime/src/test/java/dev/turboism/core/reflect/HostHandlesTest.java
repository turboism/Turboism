package dev.turboism.core.reflect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.adapter.cubism.optimization.HostMemberVerifier;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
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
        private static final String POOL = "pool";
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

    /** Owner whose reviewed member is declared by its superclass, not by itself. */
    public static class Inheriting extends Sample {
        /** A field the reviewed contract pins by name and type. */
        public String note = "inherited";
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
    void aDiscoveredMethodOpensThroughTheSamePolicy() throws Throwable {
        // Discovery stays with the caller (shape-checked getters); the opening and the adaptation are
        // still the ones this policy owns.
        final Method secret = Sample.class.getDeclaredMethod("secret");
        assertEquals("secret", (String) HostHandles.virtual(secret, REFERENCE).invokeWithArguments(new Sample()));

        final Method pick = Sample.class.getMethod("pick", String.class);
        assertEquals("string:x", (String) HostHandles.virtual(pick, SINGLE).invokeWithArguments(new Sample(), "x"));

        // unreflect keeps the declared shape for a caller that reshapes the handle itself.
        final MethodHandle declared = HostHandles.unreflect(secret);
        assertEquals(MethodType.methodType(String.class, Sample.class), declared.type());
        assertEquals("secret", (String) declared.invokeWithArguments(new Sample()));
    }

    @Test
    void fieldResolvesIndependentAccessorsForOneDeclaredType() throws Throwable {
        final HostHandles.HostField note = HostHandles.field(Sample.class, "note");
        final Sample sample = new Sample();

        assertEquals(String.class, note.type());
        assertEquals("before", note.get(sample));
        note.set(sample, "after");
        assertEquals("after", note.get(sample));
    }

    @Test
    void aStaticFinalFieldKeepsItsGetterAndFailsItsSetterReflectively() throws Throwable {
        final HostHandles.HostField pool = HostHandles.field(Sample.class, "POOL");

        assertEquals(String.class, pool.type());
        assertEquals("pool", pool.get(null));
        // The JVM refuses a setter for a final field, so the field keeps the reflective write path,
        // which fails loudly instead of silently doing nothing.
        assertThrows(IllegalAccessException.class, () -> pool.set(null, "replaced"));
    }

    @Test
    void anAbsentFieldIsRefusedAsMemberAbsent() {
        final HostHandles.HostMemberRefused absent =
                assertRefusal(HostHandles.Refusal.MEMBER_ABSENT, () -> HostHandles.field(Sample.class, "absent"));
        assertEquals("dev.turboism.core.reflect.HostHandlesTest$Sample.absent", absent.declaration());
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

    /*
     * HostMemberVerifier is the presence proof every migrated installer runs before it arms a weave.
     * These cases pin the three properties an installer depends on: the descriptor is the only
     * acceptance rule, an inherited member resolves through the owner, and a field of another type is
     * refused instead of accepted by name alone.
     */

    @Test
    void requireMethodAcceptsOnlyTheExactDescriptorOfAnOverloadPair() throws ReflectiveOperationException {
        HostMemberVerifier.requireMethod(Sample.class, "pick", "(Ljava/lang/String;)Ljava/lang/String;");
        HostMemberVerifier.requireMethod(Sample.class, "pick", "(Ljava/lang/Object;)Ljava/lang/String;");

        // The name exists, but no overload carries this descriptor, so the weave must not arm.
        final NoSuchMethodException refused = assertThrows(
                NoSuchMethodException.class,
                () -> HostMemberVerifier.requireMethod(Sample.class, "pick", "(Ljava/lang/String;)Ljava/lang/Object;"));
        assertEquals(
                "dev.turboism.core.reflect.HostHandlesTest$Sample.pick(Ljava/lang/String;)Ljava/lang/Object;",
                refused.getMessage());
    }

    @Test
    void requireMethodResolvesAMemberItsOwnerInherits() throws ReflectiveOperationException {
        // The reviewed owner declares nothing itself; the member lives on its superclass.
        HostMemberVerifier.requireMethod(Inheriting.class, "label", "()Ljava/lang/String;");

        assertThrows(
                NoSuchMethodException.class, () -> HostMemberVerifier.requireMethod(Inheriting.class, "label", "()I"));
    }

    @Test
    void requireFieldRefusesAFieldDeclaredWithAnotherType() throws ReflectiveOperationException {
        HostMemberVerifier.requireField(Inheriting.class, "note", "Ljava/lang/String;");

        // The field exists by name; a contract that pins another type must fail closed.
        final NoSuchFieldException mismatch = assertThrows(
                NoSuchFieldException.class, () -> HostMemberVerifier.requireField(Inheriting.class, "note", "I"));
        assertEquals("dev.turboism.core.reflect.HostHandlesTest$Inheriting.note:I", mismatch.getMessage());

        assertRefusal(
                HostHandles.Refusal.MEMBER_ABSENT,
                () -> HostMemberVerifier.requireField(Inheriting.class, "absent", "Ljava/lang/String;"));
    }

    @Test
    void requireMethodKeepsPublicAccessOnANonOpenNamedModule() throws ReflectiveOperationException {
        // java.base opens nothing to the unnamed module, so privateLookupIn is refused there. A public
        // member must still verify, or every migrated dependency owner on a JDK class would regress.
        assertFalse(
                java.util.List.class.getModule().isOpen("java.util", getClass().getModule()));
        HostMemberVerifier.requireMethod(java.util.List.class, "size", "()I");
        HostMemberVerifier.requireMethod(java.util.Arrays.class, "asList", "([Ljava/lang/Object;)Ljava/util/List;");
    }
}
