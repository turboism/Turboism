package dev.turboism.sdk.cubism.model;

/**
 * Access to Cubism model objects.
 *
 * <p>This is the entry point of the recommended parameter read/write path:
 * {@code access.active().parameters().find(id)} yields a {@link Parameter}; read its value with
 * {@link Parameter#getValue()} and write single values with {@link Parameter#setValue(float)}.
 * To group several writes into one Editor Undo unit, run them inside {@link
 * dev.turboism.sdk.cubism.CubismFacade#authoringTransactions()}.
 */
public interface CubismModelAccess {

    /**
     * Returns the active model.
     *
     * @throws IllegalStateException when no model is active
     */
    CubismModel active();
}
