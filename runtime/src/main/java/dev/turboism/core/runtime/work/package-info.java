/**
 * Bounded, per-plugin execution of framework-scheduled work.
 *
 * <p>This package owns admission, backpressure, timeout, circuit-breaker,
 * completion, and per-plugin executor lifecycle. It is deliberately separate
 * from Cubism lifecycle hooks and from semantic event delivery.</p>
 */
package dev.turboism.core.runtime.work;
