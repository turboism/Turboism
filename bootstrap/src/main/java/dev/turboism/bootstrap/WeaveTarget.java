package dev.turboism.bootstrap;

import java.lang.instrument.ClassFileTransformer;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * One transformer and the class it is registered against, i.e. everything
 * {@link VerifiedInstall} needs to register, verify and un-weave a target without knowing which hook
 * declared it.
 *
 * @param type the attested host class
 * @param transformer the transformer, registered with {@code canRetransform = true}
 * @param expectedMatches the exact matched-site count this hook expects, {@code -1} to assert none
 * @param matches the transformer's matched-site count accessor
 * @param failure the transformer's own refusal detail, {@code null} when it recorded none
 * @param touchedOwners internal names this transformer actually rewrote; they drive the close proof
 * @param beforeSha256 the pre-rewrite digest of {@code owner}, per internal name
 */
record WeaveTarget(
        Class<?> type,
        ClassFileTransformer transformer,
        int expectedMatches,
        IntSupplier matches,
        Supplier<String> failure,
        Set<String> touchedOwners,
        Function<String, String> beforeSha256) {

    /**
     * The common single-owner shape: the only class this transformer rewrites is {@code type} itself.
     *
     * @param type the attested host class
     * @param transformer the transformer
     * @param expectedMatches the exact matched-site count
     * @param matches the transformer's matched-site count accessor
     * @param failure the transformer's own refusal detail accessor
     * @param beforeSha256 the pre-rewrite digest accessor
     * @return a target whose touched owners are {@code type} alone
     */
    static WeaveTarget single(
            final Class<?> type,
            final ClassFileTransformer transformer,
            final int expectedMatches,
            final IntSupplier matches,
            final Supplier<String> failure,
            final Function<String, String> beforeSha256) {
        return new WeaveTarget(
                type, transformer, expectedMatches, matches, failure, Set.of(internalName(type)), beforeSha256);
    }

    /**
     * @param type a loaded class
     * @return its internal name, the form {@link #touchedOwners()} and {@link #beforeSha256()} use
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
