package dev.turboism.sdk.failure;


import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares one method as advice for the selected exception types. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HandlesException {

    /** Returns the exception types the annotated method advises. */
    Class<? extends Throwable>[] value();
}
