package dev.turboism.adapter.cubism.editor;

/**
 * One generation-bound reference to the active Editor modeling document, its model source and its
 * current model instance.
 *
 * <p>A binding is minted once per generation by {@link EditorModelGeneration#mint()} and never
 * re-minted on read. The identity text embeds the session, the model-source guid and the minting
 * generation, so comparing identities together with the model reference is the sound cheap proof
 * that a view still points at the objects it was created for.</p>
 *
 * @param identity the session-scoped identity of this binding
 * @param generation the monotonic generation this binding was minted for
 * @param modelId the verified model-source guid text
 * @param document the verified active modeling document
 * @param source the verified model source of that document
 * @param model the verified current model instance of that source
 */
record BoundModel(String identity, long generation, String modelId, Object document, Object source, Object model) {}
