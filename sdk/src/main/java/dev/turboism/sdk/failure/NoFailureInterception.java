package dev.turboism.sdk.failure;

import dev.turboism.sdk.PreviewApi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Opts a callback out of ordinary failure advice while retaining Runtime containment. */
@PreviewApi
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface NoFailureInterception {
}
