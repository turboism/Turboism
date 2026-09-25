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
 *
 * <p>A subscription takes effect only on plugin <em>entrypoint</em> instances —
 * the classes named by the plugin descriptor's {@code entrypoints} list. An
 * annotated method participates when it is a member of such an entrypoint
 * class, whether declared directly or inherited from a supertype. Annotating a
 * method on a class that can never be an entrypoint — for example a non-public
 * or abstract class with no public concrete subclass — is rejected at compile
 * time by the annotation processor, and annotating arbitrary helper objects
 * does not create a subscription.</p>
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
