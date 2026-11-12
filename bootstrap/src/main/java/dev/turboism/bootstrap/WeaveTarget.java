package dev.turboism.bootstrap;

import java.lang.instrument.ClassFileTransformer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * One transformer and the class it is registered against, i.e. everything
 * {@link VerifiedInstall} needs to register, verify and un-weave a target without knowing which hook
 * declared it.
 *
 * <p>A hook whose one transformer rewrites several classes declares one target per class with the
 * same transformer instance: the routine registers that transformer once and retransforms every
 * declared class in one call, so the transformer sees the whole rewrite set.</p>
 *
 * @param type the attested host class, or {@code null} for a transformer the routine registers without
 *     retransforming a class of its own — a guard that only observes the classes it is asked about
 * @param transformer the transformer, registered with {@code canRetransform = true}
 * @param expectedMatches the exact matched-site count this hook expects, {@code -1} to assert none
 * @param matches the transformer's matched-site count accessor
 * @param failure the transformer's own refusal detail, {@code null} when it recorded none
 */
record WeaveTarget(
        Class<?> type,
        ClassFileTransformer transformer,
        int expectedMatches,
        IntSupplier matches,
        Supplier<String> failure) {

    /**
     * The common single-owner shape: the only class this transformer rewrites is {@code type} itself.
     *
     * @param type the attested host class
     * @param transformer the transformer
     * @param expectedMatches the exact matched-site count
     * @param matches the transformer's matched-site count accessor
     * @param failure the transformer's own refusal detail accessor
     * @return a target scoped to {@code type}
     */
    static WeaveTarget single(
            final Class<?> type,
            final ClassFileTransformer transformer,
            final int expectedMatches,
            final IntSupplier matches,
            final Supplier<String> failure) {
        return new WeaveTarget(type, transformer, expectedMatches, matches, failure);
    }

    /**
     * A transformer the routine only registers: no class of its own is retransformed and no match
     * count is asserted. This is the shape of a dependency guard that inspects the classes a hook's
     * weave reads instead of rewriting one.
     *
     * @param transformer the guard transformer
     * @return a target that joins the registration but not the retransformation
     */
    static WeaveTarget registeredOnly(final ClassFileTransformer transformer) {
        return new WeaveTarget(null, transformer, -1, () -> 0, () -> null);
    }

    /**
     * @param type a loaded class
     * @return its internal name
     */
    static String internalName(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    /**
     * @param expected the transformer type the hook owns
     * @param <S> the transformer type
     * @return the registered transformer as {@code expected}, for the hook's own post-check and report
     *     line
     */
    <S extends ClassFileTransformer> S transformer(final Class<S> expected) {
        return expected.cast(transformer);
    }
}
