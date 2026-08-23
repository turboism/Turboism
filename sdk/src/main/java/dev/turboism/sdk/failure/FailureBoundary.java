package dev.turboism.sdk.failure;

import dev.turboism.sdk.PreviewApi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a stable failure boundary for an intercepted plugin callback. */
@PreviewApi
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface FailureBoundary {

    /** Stable operation identifier used by advice and structured diagnostics. */
    String value();
}
