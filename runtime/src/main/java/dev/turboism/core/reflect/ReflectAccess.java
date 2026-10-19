package dev.turboism.core.reflect;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.InaccessibleObjectException;

/**
 * Single entry point for opening host members that are not public.
 *
 * <p>Equivalent to {@code member.setAccessible(true)}: access that is refused fails with the same
 * {@link InaccessibleObjectException} (and {@link SecurityException} still propagates), but through
 * {@link AccessibleObject#trySetAccessible()} so the refusal carries a message naming the member
 * and call sites stay greppable.</p>
 */
public final class ReflectAccess {

    private ReflectAccess() {}

    /**
     * Makes {@code member} accessible and returns it.
     *
     * @throws InaccessibleObjectException when the member's module does not open it to the runtime
     */
    public static <T extends AccessibleObject> T open(final T member) {
        if (!member.trySetAccessible()) {
            throw new InaccessibleObjectException("Turboism cannot open host member: " + member);
        }
        return member;
    }
}
