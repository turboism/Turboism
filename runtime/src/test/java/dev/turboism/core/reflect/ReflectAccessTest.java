package dev.turboism.core.reflect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class ReflectAccessTest {

    @SuppressWarnings("unused")
    private final String secret = "value";

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
}
