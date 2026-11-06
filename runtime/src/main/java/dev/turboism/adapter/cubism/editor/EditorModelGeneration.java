package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * Single owner of Editor model identity, generation and staleness for one access instance.
 *
 * <p>{@link #mint()} walks the active app controller, its current document, that document's model
 * source and the source's current instance, and reads the model-source guid only when the
 * document, source or model reference changed. Unchanged references return the cached binding, so
 * the guid read and the identity text build stay off the hot path: a repeated
 * {@link #requireCurrent(String, Object)} costs the five-call walk and no guid read.</p>
 *
 * <p>The cached bound objects are held weakly so a closed document, its model source and its model
 * instance do not stay reachable for the whole life of this owner; a cleared referent reads as "no
 * previous binding", which is the same comparison result a different binding would produce
 * anyway.</p>
 *
 * <p>Reference liveness is a pull check: it detects a document switch and a model reload that
 * produces a new source or instance, but it cannot see an in-place reload that reuses the same
 * native wrappers. {@link #invalidate(String)} is the push side of the same contract for callers
 * that do receive a lifecycle signal.</p>
 */
final class EditorModelGeneration {

    private final VerifiedMemberResolver resolver;
    private final String sessionIdentity;
    private final Runnable onRebind;
    private final Object lock = new Object();
    private WeakReference<Object> boundDocument;
    private WeakReference<Object> boundSource;
    private WeakReference<Object> boundModel;
    private long generation;
    private String modelId;
    private String identity;

    /**
     * Creates an owner that mints bindings without notifying anyone when the binding moves.
     *
     * @param resolver the verified member resolver of the active host binding
     * @param sessionIdentity the non-blank session identity embedded in every binding identity
     * @throws NullPointerException when {@code resolver} or {@code sessionIdentity} is null
     * @throws IllegalArgumentException when {@code sessionIdentity} is blank
     */
    EditorModelGeneration(final VerifiedMemberResolver resolver, final String sessionIdentity) {
        this(resolver, sessionIdentity, null);
    }

    /**
     * Creates an owner that runs {@code onRebind} whenever an established binding is replaced.
     *
     * @param resolver the verified member resolver of the active host binding
     * @param sessionIdentity the non-blank session identity embedded in every binding identity
     * @param onRebind run after a binding other than the first one was replaced; may be null
     * @throws NullPointerException when {@code resolver} or {@code sessionIdentity} is null
     * @throws IllegalArgumentException when {@code sessionIdentity} is blank
     */
    EditorModelGeneration(
            final VerifiedMemberResolver resolver, final String sessionIdentity, final Runnable onRebind) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.sessionIdentity = requireText(sessionIdentity, "sessionIdentity");
        this.onRebind = onRebind == null ? () -> {} : onRebind;
    }

    /**
     * Returns the binding of the active Editor model, minting a new generation when the active
     * document, model source or model instance reference changed.
     *
     * @return the binding of the active model; the same binding while the references are unchanged
     * @throws IllegalStateException when no verified modeling document and model is active
     */
    BoundModel mint() {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document =
                app == null ? null : resolver.invoke("cubism.editor-model.app-controller.current-document", app);
        if (!resolver.isInstance("cubism.editor-model.modeling-document.class", document)) {
            throw new IllegalStateException("The active Cubism document is not a modeling document.");
        }
        final Object source = resolver.invoke("cubism.editor-model.modeling-document.model-source", document);
        final Object model =
                source == null ? null : resolver.invoke("cubism.editor-model.model-source.current-instance", source);
        if (!resolver.isInstance("cubism.editor-model.model.class", model)) {
            throw new IllegalStateException("No verified active model is available.");
        }
        synchronized (lock) {
            if (!changesBinding(document, source, model)) {
                return new BoundModel(identity, generation, modelId, document, source, model);
            }
        }
        // Only a real switch or reload reads the guid: it is the one identity member the hot path
        // does not need, and it must not be read while the lock is held.
        final String mintedModelId = readModelId(source);
        final boolean rebind;
        final BoundModel minted;
        synchronized (lock) {
            if (!changesBinding(document, source, model)) {
                // Another thread minted this very binding while the guid was read.
                return new BoundModel(identity, generation, modelId, document, source, model);
            }
            rebind = generation != 0;
            boundDocument = new WeakReference<>(document);
            boundSource = new WeakReference<>(source);
            boundModel = new WeakReference<>(model);
            generation = Math.incrementExact(generation);
            modelId = mintedModelId;
            identity = sessionIdentity + ":" + modelId + ":" + generation;
            minted = new BoundModel(identity, generation, modelId, document, source, model);
        }
        if (rebind) onRebind.run();
        return minted;
    }

    /**
     * Fails closed unless the given identity and model reference are still the active binding.
     *
     * <p>Uses the same cached walk as {@link #mint()}, so a repeated check on an unchanged Editor
     * model resolves the document, source and model references only.</p>
     *
     * @param expectedIdentity the binding identity captured when the operation started
     * @param expectedModel the model reference captured when the operation started
     * @return the verified binding of the active model
     * @throws IllegalStateException when the binding moved to another document, source or model
     */
    BoundModel requireCurrent(final String expectedIdentity, final Object expectedModel) {
        final BoundModel current = mint();
        if (!current.identity().equals(expectedIdentity) || current.model() != expectedModel) {
            throw new IllegalStateException("Cubism model reference is stale for the active Editor model generation.");
        }
        return current;
    }

    /**
     * Returns the current generation without walking the host.
     *
     * @return the generation of the last minted binding, or zero when nothing was minted yet
     */
    long generation() {
        synchronized (lock) {
            return generation;
        }
    }

    /**
     * Marks the cached binding stale so the next {@link #mint()} walks again and mints a new
     * generation, even when the host still reports the same document, source and model reference.
     *
     * <p>Push invalidation only removes the shortcut: it does not read the host, so a caller that
     * misses its lifecycle signal still keeps the pull check of {@link #requireCurrent(String,
     * Object)}.</p>
     *
     * @param reason the lifecycle signal that invalidated the binding; recorded nowhere, but must
     *     name one so an accidental blank call fails
     * @throws IllegalArgumentException when {@code reason} is null or blank
     */
    void invalidate(final String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        synchronized (lock) {
            boundDocument = null;
            boundSource = null;
            boundModel = null;
        }
    }

    /** Compares the walked references with the cached ones; the caller holds {@link #lock}. */
    private boolean changesBinding(final Object document, final Object source, final Object model) {
        return document != cached(boundDocument) || source != cached(boundSource) || model != cached(boundModel);
    }

    private String readModelId(final Object source) {
        final Object guid = resolver.invoke("cubism.editor-model.model-source.guid", source);
        final Object value = resolver.invoke("cubism.editor-model.guid.value", guid);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("Verified Editor identity is unavailable.");
        }
        return text;
    }

    /** @return the cached reference, or {@code null} when it was never bound or has been collected. */
    private static Object cached(final WeakReference<Object> reference) {
        return reference == null ? null : reference.get();
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
