/**
 * The unified Cubism model object API — the single recommended plane for plugin reads and writes
 * of model state.
 *
 * <p>Recommended parameter path:
 *
 * <pre>{@code
 * CubismModel model = context.cubism().model().active();
 * Parameter angleX = model.parameters().find(new ParameterId("ParamAngleX"));
 *
 * float value = angleX.getValue();   // read
 * angleX.setValue(value + 1.0f);     // single write: validated, undoable
 * }</pre>
 *
 * <p>For a batch of writes that must commit together or share one Editor Undo entry, wrap the
 * work in {@link dev.turboism.sdk.cubism.CubismFacade#authoringTransactions()} and call {@code
 * setValue} inside its callback. Do not open an edit session
 * ({@link dev.turboism.sdk.cubism.edit.EditSessionService}, incubating) or assemble command
 * objects for ordinary value writes.
 *
 * <p>Several look-alike parameter types serve different semantics and are not interchangeable:
 * {@link dev.turboism.sdk.cubism.ParameterSnapshot} is an immutable read snapshot, {@link
 * ParameterDefinition} is the payload for definition-level authoring ({@link
 * Parameter#updateDefinition}, {@link Parameters#create}), {@link
 * dev.turboism.sdk.cubism.core.OwnedParameter} belongs to the detached {@link
 * dev.turboism.sdk.cubism.core.OwnedModel} plane, and {@link
 * dev.turboism.sdk.cubism.edit.EditParameterNode} is a node of the incubating edit-session
 * structure tree.
 */
package dev.turboism.sdk.cubism.model;
