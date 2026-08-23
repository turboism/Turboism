package dev.turboism.sdk.failure;

import dev.turboism.sdk.PreviewApi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks an entrypoint class whose handler methods advise intercepted failures. */
@PreviewApi
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExceptionAdvice {
}
