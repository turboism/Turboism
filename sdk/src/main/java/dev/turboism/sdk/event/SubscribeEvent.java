package dev.turboism.sdk.event;


import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an instance method as a typed Turboism event subscriber.
 *
 * <p>The method's event parameter selects the concrete event state or event
 * family. The annotation only declares callback participation; dispatch mode,
 * mutation, cancellation, and failure policy belong to the event contract.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SubscribeEvent {

    /**
     * Determines deterministic invocation order among subscribers that are
     * otherwise eligible for the same publication.
     *
     * @return the subscriber priority
     */
    EventPriority priority() default EventPriority.NORMAL;

}
