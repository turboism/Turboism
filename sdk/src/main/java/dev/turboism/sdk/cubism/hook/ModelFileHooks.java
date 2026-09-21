package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperation;
import dev.turboism.sdk.cubism.ProjectFileOperationResult;

/** Override-based lifecycle hooks for Cubism model file content. */
public interface ModelFileHooks {

    /** Runs before the host creates a model file; {@code operation} describes the request. */
    default void beforeCreateModel(final ProjectFileOperation operation) {
    }

    /** Runs when a model file was actually created; {@code model} is its content entry. */
    default void onModelCreated(final ProjectContentSnapshot model) {
    }

    /** Runs after the create invocation completed; {@code result} reports how it finished. */
    default void afterCreateModel(final ProjectFileOperationResult result) {
    }

    /** Runs before the host opens a model file; {@code operation} describes the request. */
    @CubismEditor({"5.3.02", "5.3.03"})
    default void beforeOpenModel(final ProjectFileOperation operation) {
    }

    /** Runs when a model file was actually opened; {@code model} is its content entry. */
    @CubismEditor({"5.3.02", "5.3.03"})
    default void onModelOpened(final ProjectContentSnapshot model) {
    }

    /** Runs after the open invocation completed; {@code result} reports how it finished. */
    @CubismEditor({"5.3.02", "5.3.03"})
    default void afterOpenModel(final ProjectFileOperationResult result) {
    }

    /** Runs before the host saves a model file; {@code operation} describes the request. */
    @CubismEditor({"5.3.02", "5.3.03"})
    default void beforeSaveModel(final ProjectFileOperation operation) {
    }

    /** Runs when a model file was actually saved; {@code model} is its content entry. */
    @CubismEditor({"5.3.02", "5.3.03"})
    default void onModelSaved(final ProjectContentSnapshot model) {
    }

    /** Runs after the save invocation completed; {@code result} reports how it finished. */
    @CubismEditor({"5.3.02", "5.3.03"})
    default void afterSaveModel(final ProjectFileOperationResult result) {
    }

    /** Runs before the host closes a model file; {@code operation} describes the request. */
    @CubismEditor({"5.3.02", "5.3.03"})
    default void beforeCloseModel(final ProjectFileOperation operation) {
    }

    /** Runs when a model file was actually closed; {@code model} is its content entry. */
    @CubismEditor({"5.3.02", "5.3.03"})
    default void onModelClosed(final ProjectContentSnapshot model) {
    }

    /** Runs after the close invocation completed; {@code result} reports how it finished. */
    @CubismEditor({"5.3.02", "5.3.03"})
    default void afterCloseModel(final ProjectFileOperationResult result) {
    }
}
