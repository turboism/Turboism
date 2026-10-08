/**
 * Typed editing-session surface for Cubism Editor model documents.
 *
 * <p>This package ports the official Cubism external-application editing API (protocol 1.1.0)
 * onto Turboism's supported editor versions, which only expose integration protocol 1.0.x. The
 * surface is session-scoped: {@link dev.turboism.sdk.cubism.edit.EditSessionService#open} admits
 * an {@link dev.turboism.sdk.cubism.edit.EditSession} (the official {@code EditBegin}..{@code
 * EditEnd} span), and the session exposes the 36 official operations through five typed families:
 * {@link dev.turboism.sdk.cubism.edit.ParameterKeyOps}, {@link
 * dev.turboism.sdk.cubism.edit.ParameterStructureOps}, {@link
 * dev.turboism.sdk.cubism.edit.SelectionOps}, {@link
 * dev.turboism.sdk.cubism.edit.PartObjectOps}, and {@link
 * dev.turboism.sdk.cubism.edit.DeformerOps}.
 *
 * <p>Identity mapping is Turboism-owned throughout; the SDK never exposes {@code com.live2d}
 * types. The official {@code ModelUID} is carried as {@link
 * dev.turboism.sdk.cubism.id.DocumentId} on {@link
 * dev.turboism.sdk.cubism.edit.EditSessionService#open}; the official {@code ObjectId} /
 * {@code Id} fields map to {@link dev.turboism.sdk.cubism.model.ModelObjectReference} where the
 * object kind is part of identification, to kinded ids ({@code ParameterId}, {@code PartId},
 * {@code ArtMeshId}, {@code DeformerId}, {@code GlueId}) where the kind is fixed, and to {@link
 * dev.turboism.sdk.cubism.id.ModelObjectId} where the host returns or accepts bare id lists.
 * The runtime is responsible for routing these identities to host internals.
 *
 * <p>Everything fails closed: operations that cannot run against the connected editor build —
 * including operations whose host bindings are not yet verified — raise {@link
 * dev.turboism.sdk.cubism.edit.EditUnavailableException} or another {@link
 * dev.turboism.sdk.cubism.edit.EditSessionException} subtype rather than guessing at editor
 * internals.
 *
 * <p>The whole package is {@link dev.turboism.sdk.Incubating}: it backs the external-application
 * editing protocol and is not yet covered by the stable API compatibility surface. Ordinary
 * parameter value writes do not need an edit session — use {@link
 * dev.turboism.sdk.cubism.model.Parameter#setValue} instead, and {@link
 * dev.turboism.sdk.cubism.CubismFacade#authoringTransactions()} to group several writes into one
 * Editor Undo unit.
 */
package dev.turboism.sdk.cubism.edit;
