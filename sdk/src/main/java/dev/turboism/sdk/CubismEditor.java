package dev.turboism.sdk;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the Cubism Editor versions on which an SDK type or method is
 * available, evaluated against the host's own declared version.
 *
 * <p>{@link #value()} selects exact versions, while {@link #from()} and
 * {@link #to()} define an inclusive numeric range over the host's declared
 * version. These positive forms are mutually exclusive. When none is supplied,
 * the declaration imposes no version floor. {@link #exclude()} always removes
 * the named version, whether reviewed or not.</p>
 *
 * <p>Declarations evaluate against the version the host itself declared: an
 * unreviewed (compatibility-admitted) release can satisfy {@link #from()}
 * ranges but never a {@code value} list or {@link #exclude()} entry it is not
 * named by. Type, inherited-type, and method declarations are intersected, so
 * a narrower method declaration cannot accidentally widen its owning type's
 * availability.</p>
 *
 * <p>Annotation satisfaction alone never grants availability: the runtime must
 * additionally have admitted the capability ids backing the API surface, so a
 * compatibility-bound host exposes a method only when the covering selector
 * contract — including any hook-dependent capability stripping — admitted it.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface CubismEditor {

    /**
     * Selects exact reviewed Cubism Editor versions.
     *
     * <p>This attribute cannot be combined with {@link #from()} or
     * {@link #to()}.</p>
     *
     * @return exact versions such as {@code "5.2.03"} and {@code "5.3.02"}, or
     *     an empty array when a range or the complete reviewed set is selected
     */
    String[] value() default {};

    /**
     * @return the inclusive lower bound, or an empty string when there is no
     *     lower bound
     */
    String from() default "";

    /**
     * @return the inclusive upper bound, or an empty string when there is no
     *     upper bound
     */
    String to() default "";

    /**
     * Removes exact versions from the positively selected set. Exclusions may
     * name a version that is not reviewed yet so it remains excluded if that
     * version is admitted later.
     *
     * @return exact Cubism Editor versions to exclude
     */
    String[] exclude() default {};
}
