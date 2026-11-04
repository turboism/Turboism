package dev.turboism.core.reflect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class ReflectAccessTest {

    @SuppressWarnings("unused")
    private final String secret = "value";

    private String mutable = "before";

    @SuppressWarnings("unused")
    private static final String STATIC_SECRET = "static-value";

    /** Superclass carrying a private field and a private method the hierarchy walk must reach. */
    static class Base {
        @SuppressWarnings("unused")
        private String inherited = "base-value";

        private String inheritedMethod() {
            return "base-method";
        }

        String overridden() {
            return "base";
        }
    }

    /** Subclass used to prove that lookups start at the receiver class, not at its parent. */
    static final class Derived extends Base {
        @SuppressWarnings("unused")
        private String own = "own-value";

        private String ownMethod() {
            return "own-method";
        }

        @Override
        String overridden() {
            return "derived";
        }
    }

    interface Contract {
        default String contract() {
            return "contract-value";
        }
    }

    static final class Implementing implements Contract {}

    static final class Awkward {
        private String explode() {
            throw new IllegalStateException("boom");
        }

        private String withArgument(final String ignored) {
            return ignored;
        }
    }

    @Test
    void opensPrivateMemberAndReturnsIt() throws Exception {
        final Field field = ReflectAccessTest.class.getDeclaredField("secret");
        assertSame(field, ReflectAccess.open(field));
        assertEquals("value", field.get(this));
    }

    @Test
    void refusedAccessFailsWithInaccessibleObjectException() throws Exception {
        final Method method = String.class.getDeclaredMethod("isLatin1");
        final InaccessibleObjectException failure =
                assertThrows(InaccessibleObjectException.class, () -> ReflectAccess.open(method));
        assertEquals(true, failure.getMessage().contains("isLatin1"));
    }

    @Test
    void findFieldReturnsOpenedFieldDeclaredOnTheExactClass() throws Exception {
        final Field field = ReflectAccess.findField(Derived.class, "own");
        assertNotNull(field);
        assertEquals("own", field.getName());
        // open(...) was applied by findField: reading needs no further setAccessible.
        assertEquals("own-value", field.get(new Derived()));
    }

    @Test
    void findFieldWalksSuperclassHierarchy() {
        final Field field = ReflectAccess.findField(Derived.class, "inherited");
        assertNotNull(field);
        assertEquals(Base.class, field.getDeclaringClass());
    }

    @Test
    void findFieldIsCachedPerClassLoader() {
        final Field first = ReflectAccess.findField(Derived.class, "own");
        final Field second = ReflectAccess.findField(Derived.class, "own");
        assertSame(first, second);
    }

    @Test
    void findFieldReturnsNullForNullInputAndMissingNames() {
        assertNull(ReflectAccess.findField(null, "own"));
        assertNull(ReflectAccess.findField(Derived.class, null));
        assertNull(ReflectAccess.findField(Derived.class, "absent"));
    }

    @Test
    void findFieldFailsClosedOnAnUnopenableHostField() {
        // java.base is not opened to the unnamed module: the miss must stay an access error.
        assertThrows(InaccessibleObjectException.class, () -> ReflectAccess.findField(String.class, "value"));
    }

    @Test
    void readFieldReadsOwnInheritedAndStaticFields() {
        final Derived derived = new Derived();
        assertEquals("own-value", ReflectAccess.readField(derived, "own"));
        assertEquals("base-value", ReflectAccess.readField(derived, "inherited"));
        assertEquals("value", ReflectAccess.readField(this, "secret"));
        assertEquals("static-value", ReflectAccess.readField(this, "STATIC_SECRET"));
    }

    @Test
    void readFieldReturnsNullInsteadOfFailing() {
        final Derived derived = new Derived();
        assertNull(ReflectAccess.readField(null, "own"));
        assertNull(ReflectAccess.readField(derived, null));
        assertNull(ReflectAccess.readField(derived, "absent"));
        assertNull(ReflectAccess.readField("host text", "value"));
    }

    @Test
    void findMethodResolvesPrivateInheritedAndInterfaceMethods() throws Exception {
        final Method own = ReflectAccess.findMethod(Derived.class, "ownMethod");
        assertNotNull(own);
        assertEquals("own-method", own.invoke(new Derived()));

        final Method inherited = ReflectAccess.findMethod(Derived.class, "inheritedMethod");
        assertNotNull(inherited);
        assertEquals(Base.class, inherited.getDeclaringClass());

        final Method contract = ReflectAccess.findMethod(Implementing.class, "contract");
        assertNotNull(contract);
        assertEquals("contract-value", contract.invoke(new Implementing()));
    }

    @Test
    void findMethodMatchesTheExactSignature() {
        final Method method = ReflectAccess.findMethod(Awkward.class, "withArgument", String.class);
        assertNotNull(method);
        assertEquals(1, method.getParameterCount());
        assertNull(ReflectAccess.findMethod(Awkward.class, "withArgument"));
        assertNull(ReflectAccess.findMethod(Awkward.class, "withArgument", int.class));
    }

    @Test
    void findMethodReturnsNullForNullInputAndMissingNames() {
        assertNull(ReflectAccess.findMethod(null, "ownMethod"));
        assertNull(ReflectAccess.findMethod(Derived.class, null));
        assertNull(ReflectAccess.findMethod(Derived.class, "absent"));
        assertNull(ReflectAccess.findMethod(Derived.class, "ownMethod", String.class));
    }

    @Test
    void findMethodFailsClosedOnAnUnopenableHostMethod() {
        assertThrows(InaccessibleObjectException.class, () -> ReflectAccess.findMethod(String.class, "isLatin1"));
    }

    @Test
    void tryInvokeZeroArgInvokesOwnAndInheritedMethods() {
        final Derived derived = new Derived();
        assertEquals("own-method", ReflectAccess.tryInvokeZeroArg(derived, "ownMethod"));
        assertEquals("base-method", ReflectAccess.tryInvokeZeroArg(derived, "inheritedMethod"));
        assertEquals("derived", ReflectAccess.tryInvokeZeroArg(derived, "overridden"));
        assertEquals("contract-value", ReflectAccess.tryInvokeZeroArg(new Implementing(), "contract"));
    }

    @Test
    void tryInvokeZeroArgReturnsNullInsteadOfFailing() {
        final Awkward awkward = new Awkward();
        assertNull(ReflectAccess.tryInvokeZeroArg(null, "explode"));
        assertNull(ReflectAccess.tryInvokeZeroArg(awkward, null));
        assertNull(ReflectAccess.tryInvokeZeroArg(awkward, "absent"));
        // Wrong arity: the name exists but takes an argument.
        assertNull(ReflectAccess.tryInvokeZeroArg(awkward, "withArgument"));
        // The target itself throws; the failure is reported as "no value".
        assertNull(ReflectAccess.tryInvokeZeroArg(awkward, "explode"));
        // Host method the runtime may not open.
        assertNull(ReflectAccess.tryInvokeZeroArg("host text", "isLatin1"));
    }
}
