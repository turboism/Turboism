package dev.turboism.sdk.failure;

import dev.turboism.sdk.PreviewApi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares one method as advice for the selected exception types. */
@PreviewApi
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HandlesException {

    Class<? extends Throwable>[] value();
}
