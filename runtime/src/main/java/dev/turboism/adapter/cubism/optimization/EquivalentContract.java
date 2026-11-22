package dev.turboism.adapter.cubism.optimization;

/**
 * Compares reviewed host contracts for structural equality independent of per-build metadata.
 *
 * @param <T> contract payload type
 */
public interface EquivalentContract<T> {

    /**
     * Returns whether another contract exposes the same behavioral host surface.
     *
     * @param other candidate contract of the same runtime type
     * @return {@code true} when behavioral selectors and gates match identically
     */
    boolean sameContract(T other);
}
