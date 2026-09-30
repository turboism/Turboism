package dev.turboism.sdk;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an SDK type or member as incubating: published for early adopters but
 * not yet covered by the compatibility promises of the stable API surface.
 *
 * <p>Incubating API may change or be removed between framework versions —
 * including breaking signature changes — and is excluded from the SDK's
 * exact-API compatibility baselines. A declaration on a type covers every
 * member it contains; a member-level declaration narrows a stable type's
 * incubating surface.</p>
 *
 * <p>Removal of {@code @Incubating} is a one-way promotion: once an element
 * ships without it, the standard additive/compatible evolution rules apply.
 * Plugin authors should treat incubating dependencies as version-pinned.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
    ElementType.TYPE,
    ElementType.METHOD,
    ElementType.CONSTRUCTOR,
    ElementType.FIELD,
    ElementType.RECORD_COMPONENT,
    ElementType.PARAMETER
})
public @interface Incubating {}
