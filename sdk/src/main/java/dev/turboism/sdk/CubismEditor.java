package dev.turboism.sdk;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the exact reviewed Cubism Editor versions on which an SDK interface
 * or method is available.
 *
 * <p>Values are exact Editor versions, never ranges or broad release lines. A
 * method declaration replaces its declaring interface's default. Runtime
 * availability is separate from plugin permissions, active-model state, and
 * backend capability admission.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface CubismEditor {

    /**
     * @return exact reviewed Cubism Editor versions such as {@code "5.2.03"}
     *     and {@code "5.3.02"}
     */
    String[] value();
}
