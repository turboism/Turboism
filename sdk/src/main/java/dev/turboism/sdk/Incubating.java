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
 * <p>The annotation type is retained so existing plugin sources and compiled
 * bytecode keep linking; the SDK no longer applies it. Every public SDK element
 * now belongs to the stable surface and follows the standard compatibility
 * rules. Do not add new usages: declare a real {@link CubismEditor} availability
 * contract instead of a maturity marker.</p>
 *
 * <p>A declaration on a type covered every member it contained; a member-level
 * declaration narrowed a stable type's incubating surface.</p>
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
