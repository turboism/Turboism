package dev.turboism.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an SDK declaration as preview-only.
 *
 * <p>Preview APIs have no backward-compatibility guarantee and may change or
 * be removed before a verified production host adapter supports them.</p>
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface PreviewApi {
}
