package dev.turboism.sdk.permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares the permission a plugin must hold to use the annotated API element. */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresPermission {

    /** Returns the required permission identifier. */
    String value();
}
